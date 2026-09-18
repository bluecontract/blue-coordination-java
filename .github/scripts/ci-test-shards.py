#!/usr/bin/env python3
"""Bound CI class shards and validated import; never performs external publication."""
import importlib.util
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import time
import xml.etree.ElementTree as ET

spec = importlib.util.spec_from_file_location('verification', Path(__file__).with_name('ci-verification.py'))
verification = importlib.util.module_from_spec(spec)
spec.loader.exec_module(verification)
support = verification.timing
INIT = '.github/scripts/ci-test-shards.init.gradle'
COMMON = ['--no-daemon', '--no-build-cache', '--no-parallel', '--max-workers=4',
          '-PtestMaxParallelForks=2', '-PtestMethodParallelism=2',
          '-PtestJavaVersion=17', '-PblueDependencyMode=published-artifact']


def binding():
    value = verification.identity()
    version = os.environ.get('RELEASE_VERSION', '')
    support.require(version, 'Missing version')
    return dict(value, java='17', version=version)


def plan_for(discovered, weights):
    support.require(set(discovered) == set(support.SUITES), 'Missing discovered suite')
    groups = {str(i): {suite: [] for suite in support.SUITES} for i in range(3)}
    loads = [0., 0., 0.]
    rows = []
    for suite, classes in discovered.items():
        support.require(len(classes) == len(set(classes)) and classes, 'Invalid class discovery')
        for name in classes:
            weight = weights.get(suite + ':' + name, 1.)
            support.require(isinstance(weight, (int, float)) and 0 <= weight < 1e9, 'Invalid class weight')
            rows.append((weight, suite, name))
    for weight, suite, name in sorted(rows, key=lambda row: (-row[0], row[1], row[2])):
        shard = min(range(3), key=lambda i: (loads[i], i))
        groups[str(shard)][suite].append(name)
        loads[shard] += weight
    for group in groups.values():
        for names in group.values():
            names.sort()
    return groups


def checked_tree(root):
    result = {}
    support.require(root.is_dir() and not root.is_symlink(), 'Missing evidence tree')
    for path in root.rglob('*'):
        support.require(not path.is_symlink(), 'Symlink evidence forbidden')
        if path.is_file():
            result[path.relative_to(root).as_posix()] = support.sha(path)
        else:
            support.require(path.is_dir(), 'Nonregular evidence forbidden')
    return result


def xml_cases(directory):
    cases = []
    for path in sorted(directory.glob('TEST-*.xml')):
        for case in ET.parse(path).getroot().iter('testcase'):
            cases.append(dict(className=case.attrib['classname'], name=case.attrib['name'],
                              failed=case.find('failure') is not None or case.find('error') is not None,
                              skipped=case.find('skipped') is not None))
    return sorted(cases, key=lambda row: (row['className'], row['name']))


def run(shard, output):
    shard = int(shard)
    support.require(shard in [0, 1, 2], 'Invalid shard')
    identity = binding()
    out = Path(output)
    support.require(not out.exists(), 'Refuse stale shard output')
    out.mkdir(parents=True)
    receipt = dict(identity, shard=shard, success=False, started=time.time(), commands=[])
    measurements = dict(peakRssKiB=0, peakPssKiB=0, peakProcesses=0, memoryReadMisses=0, cpuTicks={})
    known = {}
    def gradle(args, env=None):
        command = ['./gradlew', *COMMON, *args]
        print('Running:', *command, flush=True)
        process = subprocess.Popen(command, env=env)
        while process.poll() is None:
            support.sample_processes(process.pid, known, measurements)
            time.sleep(1)
        receipt['commands'].append(dict(args=command[1:], exit=process.returncode))
        support.require(process.returncode == 0, 'Shard Gradle command failed')
    try:
        gradle(['clean', *['discover' + s[0].upper() + s[1:] + 'Classes' for s in support.SUITES]])
        discovered = {s: Path('build/reports/test-execution-scope/' + s + '-discovered-classes.txt').read_text().splitlines()
                      for s in support.SUITES}
        plan = dict(identity, groups=plan_for(discovered, support.read('.github/scripts/ci-test-weights.json')),
                    discovered=discovered)
        support.write(out / 'plan.json', plan)
        env = dict(os.environ, CI_TEST_MODE='shard', CI_TEST_SHARD=str(shard), CI_TEST_PLAN=str((out / 'plan.json').resolve()))
        gradle(['--init-script', INIT, 'ciShardProof'], env)
        runtime = support.read(Path('build/reports/ci-test-shards/runtime.json'))
        suites = {}
        for suite in support.SUITES:
            cases = xml_cases(Path('build/test-results') / suite)
            suites[suite] = dict(runtime[suite], testCases=cases,
                                  executedClasses=sorted({c['className'] for c in cases}))
        receipt.update(assigned=plan['groups'][str(shard)], suites=suites,
                       planSha256=support.sha(out / 'plan.json'))
        evidence = out / 'evidence'
        evidence.mkdir()
        for name in ['reports', 'test-results', 'rooted-evidence']:
            source = Path('build') / name
            if source.exists():
                shutil.copytree(source, evidence / name)
        # Runtime proof and full class discovery are bound separately; these per-shard
        # control files are not merged into production reports.
        shutil.rmtree(evidence / 'reports/ci-test-shards', ignore_errors=True)
        shutil.rmtree(evidence / 'reports/test-execution-scope', ignore_errors=True)
        for generated in ['tests', 'problems']:
            shutil.rmtree(evidence / 'reports' / generated, ignore_errors=True)
        for binary in evidence.glob('test-results/*/binary'):
            shutil.rmtree(binary)
        receipt['files'] = checked_tree(evidence)
        support.write(out / 'runtime.json', runtime)
        receipt['runtimeSha256'] = support.sha(out / 'runtime.json')
        binding()
        receipt['success'] = True
    finally:
        receipt['finished'] = time.time()
        measurements['sampledCpuSeconds'] = sum(measurements.pop('cpuTicks').values()) / os.sysconf('SC_CLK_TCK')
        measurements['averageSampledCores'] = measurements['sampledCpuSeconds'] / (receipt['finished'] - receipt['started'])
        receipt['processTreeSampling'] = measurements
        support.write(out / 'receipt.json', receipt)


def verify_packages(packages, expected):
    support.require(len(packages) == 3, 'Missing shard packages')
    plans = [support.read(p / 'plan.json') for p in packages]
    support.require(all(plan == plans[0] for plan in plans), 'Shard plans differ')
    plan = plans[0]
    for key, value in expected.items():
        support.require(plan.get(key) == value, 'Plan binding mismatch: ' + key)
    groups = plan_for(plan['discovered'], support.read('.github/scripts/ci-test-weights.json'))
    support.require(plan['groups'] == groups, 'Noncanonical partition')
    receipts = [support.read(p / 'receipt.json') for p in packages]
    for p, receipt in zip(packages, receipts):
        expected_commands = [COMMON + ['clean', *['discover' + s[0].upper() + s[1:] + 'Classes' for s in support.SUITES]],
                             COMMON + ['--init-script', INIT, 'ciShardProof']]
        support.require(receipt.get('commands') == [dict(args=args, exit=0) for args in expected_commands],
                        'Incomplete shard command scope')
        support.require(checked_tree(p / 'evidence') == receipt['files'], 'Evidence hash inventory mismatch')
        support.require(support.sha(p / 'runtime.json') == receipt['runtimeSha256'], 'Runtime proof corrupted')
        runtime = support.read(p / 'runtime.json')
        for suite in support.SUITES:
            rows = xml_cases(p / 'evidence/test-results' / suite)
            support.require(rows == receipt['suites'][suite]['testCases'], 'XML receipt mismatch')
            actual = runtime[suite]
            support.require(actual['java'] == 17 and actual['maxParallelForks'] == 2
                            and actual['methodThreads'] == 2 and actual['maxWorkers'] == 4,
                            'Wrong test runtime/concurrency')
            support.require(actual['executed'] is True or not groups[str(receipt['shard'])][suite],
                            'Assigned test task did not execute')
            for key, value in actual.items():
                support.require(receipt['suites'][suite].get(key) == value, 'Runtime receipt mismatch')
    digest = support.sha(packages[0] / 'plan.json')
    support.validate_test_shards(receipts, dict(expected, planSha256=digest), groups)
    return plan, receipts, digest


def consume(directory):
    expected = binding()
    packages = sorted(Path(directory).glob('coordination-tests-*-of-3-*'))
    plan, receipts, digest = verify_packages(packages, expected)
    report = Path('build/reports/ci-test-shards')
    support.require(not report.exists(), 'Refuse stale imported test evidence')
    # All validation precedes writes. Never overwrite an independently generated report.
    for package, receipt in zip(packages, receipts):
        for name, hashed in receipt['files'].items():
            target = Path('build') / name
            if target.exists():
                support.require(support.sha(target) == hashed, 'Existing evidence collision')
            else:
                target.parent.mkdir(parents=True, exist_ok=True)
                shutil.copyfile(package / 'evidence' / name, target)
    for package, receipt in zip(packages, receipts):
        shutil.copytree(package, report / str(receipt['shard']))
    evidence = dict(expected, schema=1, status='PASS', shardIds=[0, 1, 2], planSha256=digest,
                    receiptSha256={str(r['shard']):support.sha(p / 'receipt.json') for p, r in zip(packages, receipts)})
    support.write(report / 'delegated.json', evidence)


def attest(build):
    build = Path(build)
    folder = build / 'reports/ci-test-shards'
    evidence = support.read(folder / 'delegated.json')
    expected = {key:evidence[key] for key in ['schema', 'sha', 'tree', 'run', 'attempt', 'scope', 'java', 'version']}
    packages = [folder / str(i) for i in range(3)]
    plan, receipts, digest = verify_packages(packages, expected)
    support.require(evidence['planSha256'] == digest, 'Delegated plan corrupted')
    scope = support.read(build / 'reports/test-execution-scope/verifyReleaseTestExecutionScope.json')
    actual_cases = support.inventory(scope)
    receipt_cases = support.validate_test_shards(receipts, dict(expected, planSha256=digest), plan['groups'])
    support.require(actual_cases == receipt_cases, 'Delegated scope testcase multiset differs')
    for package, receipt in zip(packages, receipts):
        support.require(evidence['receiptSha256'][str(receipt['shard'])] == support.sha(package / 'receipt.json'),
                        'Delegated receipt corrupted')
        for name, digest in receipt['files'].items():
            support.require(support.sha(build / name) == digest, 'Merged evidence changed: ' + name)
    return evidence


if __name__ == '__main__':
    action, *args = sys.argv[1:]
    {'run':run, 'consume':consume, 'attest':attest}[action](*args)
