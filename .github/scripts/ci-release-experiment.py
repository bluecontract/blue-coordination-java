#!/usr/bin/env python3
"""Branch-only, non-publishing comparison of the complete RC verification gates."""
import hashlib
import json
import math
import os
from pathlib import Path
import shutil
import subprocess
import sys
import time

BRANCH = 'refs/heads/codex/ci/coordination-release-experiment'
SUITES = ['test', 'integrationTest', 'consumerTest', 'scenarioTest']
ARCHIVE_SCHEMA = 'blue-coordination-contracts10-source-archive-verification-v3'
ARCHIVE_STATUSES = ['extractedConfiguration', 'compileStatus', 'dependencyIsolationStatus', 'focusedTestsStatus']
FOCUSED_TASKS = ['clean', 'test', 'verifyActiveDependencyLane', 'dependencyPreflight', 'verifyDependencyModeIsolation']
FOCUSED_TESTS = ['blue.coordination.' + name for name in [
    'sdk.SdkCanonicalRetainedManagedEpochScenarioTest', 'internal.ManagedSameStateEpochPublicationTest',
    'sdk.SdkManagedDraftAcceptanceTest', 'sdk.SdkManagedEpochCycleAcceptanceTest',
    'sdk.RootedAdmissionBasisTest', 'sdk.RootedSourceIsolationTest', 'sdk.RootedLocalHistoryRecoveryTest']]
ARCHIVE_REPORT = Path('build/reports/contracts10/source-archive-verification.json')
SCOPE_REPORT = Path('build/reports/test-execution-scope/verifyReleaseTestExecutionScope.json')


def require(condition, message):
    if not condition:
        raise ValueError(message)


def read(path):
    return json.loads(Path(path).read_text())


def write(path, value):
    Path(path).parent.mkdir(parents=True, exist_ok=True)
    Path(path).write_text(json.dumps(value, indent=2, allow_nan=False) + '\n')


def sha(path):
    return hashlib.sha256(Path(path).read_bytes()).hexdigest()


def commands(lane, java):
    require(lane in ['baseline', 'core', 'archive'] and java in ['25'], 'Invalid lane/JDK')
    common = ['--no-daemon', '--no-build-cache', '--max-workers=4', '-PtestMaxParallelForks=4',
              '-PblueDependencyMode=published-artifact', '-PtestJavaVersion=' + java]
    preflight = common + ['dependencyPreflight']
    if lane == 'archive':
        return [preflight, common + ['clean', 'verifyExtractedSourceArchive']]
    init = ['--init-script', '.github/scripts/ci-archive-receipt.init.gradle'] if lane == 'core' else []
    return [preflight, common + init + ['clean', 'stageRelease']]


def identity():
    require(os.environ.get('GITHUB_REF') == BRANCH, 'Experiment branch required')
    require(os.environ.get('GITHUB_REPOSITORY') == 'bluecontract/blue-coordination-java', 'Unexpected repository')
    result = {'schema': 1}
    for key, arg in [('sha', 'HEAD'), ('tree', 'HEAD^{tree}')]:
        result[key] = subprocess.check_output(['git', 'rev-parse', arg], text=True).strip()
    require(result['sha'] == os.environ.get('GITHUB_SHA'), 'Checkout differs from requested source')
    subprocess.run(['git', 'diff', '--quiet', 'HEAD', '--'], check=True)
    for key, env in [('run', 'GITHUB_RUN_ID'), ('attempt', 'GITHUB_RUN_ATTEMPT')]:
        result[key] = os.environ.get(env, '')
        require(result[key].isdigit() and int(result[key]) > 0, 'Missing run binding')
    return result


def validate(receipt, binding, lane, java, command_factory=commands):
    for key in ['schema', 'sha', 'tree', 'run', 'attempt']:
        require(receipt.get(key) == binding.get(key), 'Receipt mismatch: ' + key)
    require(receipt.get('lane') == lane and receipt.get('java') == java, 'Wrong receipt owner')
    require(receipt.get('success') is True, 'Receipt did not succeed')
    actual = receipt.get('commands', [])
    require([item.get('args') for item in actual] == command_factory(lane, java), 'Incomplete/changed command scope')
    require(all(item.get('exit') == 0 for item in actual), 'Failed command')
    start, end = receipt.get('started'), receipt.get('finished')
    require(all(isinstance(t, (int, float)) and math.isfinite(t) for t in [start, end]) and end > start,
            'Invalid timing window')


def validate_archive(proof, java, digest, name, version):
    expected = {'schemaId': ARCHIVE_SCHEMA, 'archiveSha256': digest, 'archiveName': name,
                'coordinationVersion': version, 'java': java, 'dependencyMode': 'published-artifact',
                'focusedTasks': FOCUSED_TASKS}
    for key, value in expected.items():
        require(proof.get(key) == value, 'Archive proof mismatch: ' + key)
    require(sorted(proof.get('focusedTests', [])) == sorted(FOCUSED_TESTS), 'Incomplete archive test inventory')
    require(all(proof.get(key) == 'PASS' for key in ARCHIVE_STATUSES), 'Archive gate not passed')


def inventory(proof):
    require(proof.get('status') == 'PASS' and proof.get('topologyEvidenceVerified') is True, 'Scope/topology gate failed')
    require(set(proof.get('suites', {})) == set(SUITES), 'Missing/extra test suite')
    result = []
    for suite, data in proof['suites'].items():
        require(data.get('passed') is True and data.get('fullTask') is True and data.get('maxParallelForks') == 4,
                'Incomplete suite or wrong forks')
        cases = data.get('testCases', [])
        require(cases and all(c.get('failed') is False and c.get('skipped') is False for c in cases),
                'Failed/skipped/empty tests')
        require(data.get('executedTests') == len(cases), 'Executed test count mismatch')
        # JUnit parameterized methods can share class and display name. Keep every
        # occurrence so comparison detects missing/extra invocations, like inspectBuild.
        result.extend((suite, c['className'], c['name']) for c in cases)
    return sorted(result)


def consume(java, archive_path, version):
    binding = identity()
    require(os.environ.get('EXPERIMENT_LANE') == 'core', 'Only core consumes receipts')
    target = Path(os.environ['RUNNER_TEMP']) / ('coordination-archive-' + java + '-' + binding['attempt'])
    deadline = time.monotonic() + 1800
    while not (target / 'timing.json').is_file():
        shutil.rmtree(target, ignore_errors=True)
        downloaded = subprocess.run(['gh', 'run', 'download', binding['run'], '--repo',
            os.environ['GITHUB_REPOSITORY'], '--name', 'coordination-timing-' + binding['attempt'] + '-archive-' + java,
            '--dir', str(target)], stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True)
        if downloaded.returncode == 0:
            break
        require(time.monotonic() < deadline, 'Archive receipt not available within30min')
        print('Waiting for verified extracted-source archive job', flush=True)
        time.sleep(15)
    receipt = read(target / 'timing.json')
    validate(receipt, binding, 'archive', java)
    proof = read(target / 'archive.json')
    require(sha(target / 'archive.json') == receipt['archiveProofSha256'], 'Archive proof corrupted')
    validate_archive(proof, java, sha(archive_path), Path(archive_path).name, version)
    write(ARCHIVE_REPORT, proof)
    print('Verified actual extracted-source archive execution for Java' + java, flush=True)


def sample_processes(root_pid, known, measurements):
    """Sample this command's process tree, retaining already observed detached daemons."""
    processes = {}
    for directory in Path('/proc').iterdir():
        if not directory.name.isdigit():
            continue
        try:
            fields = (directory / 'stat').read_text().rsplit(')', 1)[1].split()
            processes[int(directory.name)] = (int(fields[1]), int(fields[19]), int(fields[11]) + int(fields[12]))
        except (OSError, ValueError, IndexError):
            continue
    selected = {pid for pid, (_, born, _) in processes.items() if known.get(pid) == born or pid == root_pid}
    while True:
        children = {pid for pid, (parent, _, _) in processes.items() if parent in selected}
        if children <= selected:
            break
        selected |= children
    rss = pss = 0
    for pid in selected:
        _, born, ticks = processes[pid]
        known[pid] = born
        measurements['cpuTicks'][str(pid) + ':' + str(born)] = ticks
        try:
            mem = dict(line.split(':', 1) for line in (Path('/proc') / str(pid) / 'smaps_rollup').read_text().splitlines()[1:])
            rss += int(mem['Rss'].split()[0]); pss += int(mem['Pss'].split()[0])
        except (OSError, ValueError, KeyError):
            measurements['memoryReadMisses'] += 1
    measurements['peakRssKiB'] = max(measurements['peakRssKiB'], rss)
    measurements['peakPssKiB'] = max(measurements['peakPssKiB'], pss)
    measurements['peakProcesses'] = max(measurements['peakProcesses'], len(selected))


def measure(lane, java, output, binding_factory=identity, command_factory=commands, channel="rc"):
    out = Path(output)
    require(not out.exists(), 'Refuse reused output directory')
    out.mkdir(parents=True)
    receipt = dict(binding_factory(), lane=lane, java=java, started=time.time(), success=False, commands=[])
    measurements = {'peakRssKiB': 0, 'peakPssKiB': 0, 'peakProcesses': 0, 'memoryReadMisses': 0, 'cpuTicks': {}}
    known = {}
    receipt['hardware'] = {'logicalCpus': os.cpu_count(), 'meminfo': Path('/proc/meminfo').read_text()}
    try:
        for args in command_factory(lane, java):
            start = time.time()
            print('Running:', './gradlew', *args, flush=True)
            process = subprocess.Popen(['./gradlew', *args])
            while process.poll() is None:
                sample_processes(process.pid, known, measurements)
                time.sleep(1)
            receipt['commands'].append({'args': args, 'exit': process.returncode, 'elapsedSeconds': time.time() - start})
            require(process.returncode == 0, 'Gradle command failed')
        binding_factory()
        proof = read(ARCHIVE_REPORT)
        archive = Path('build/distributions') / proof['archiveName']
        validate_archive(proof, java, sha(archive), archive.name, proof['coordinationVersion'])
        write(out / 'archive.json', proof)
        receipt['archiveProofSha256'] = sha(out / 'archive.json')
        if lane != 'archive':
            scope = read(SCOPE_REPORT)
            receipt['testCount'] = len(inventory(scope))
            write(out / 'scope.json', scope)
            receipt['scopeProofSha256'] = sha(out / 'scope.json')
            # Reuse the production handoff validator, without candidate/tag/publish operations.
            inspection = subprocess.check_output(['node', '-e',
                "const h=require('./.github/scripts/release-handoff.js');"
                "console.log(JSON.stringify(h.inspectBuild('build',"
                "{version:process.argv[1],channel:process.argv[3]},process.argv[2])))",
                proof['coordinationVersion'], java, channel], text=True)
            write(out / 'build.json', json.loads(inspection))
            receipt['buildProofSha256'] = sha(out / 'build.json')
        receipt['success'] = True
    finally:
        receipt['finished'] = time.time()
        measurements['sampledCpuSeconds'] = sum(measurements.pop('cpuTicks').values()) / os.sysconf('SC_CLK_TCK')
        measurements['averageSampledCores'] = measurements['sampledCpuSeconds'] / (receipt['finished'] - receipt['started'])
        receipt['processTreeSampling'] = measurements
        write(out / 'timing.json', receipt)


def compare(directory):
    binding = identity()
    receipts = {}
    root = Path(directory)
    for lane in ['baseline', 'core', 'archive']:
        for java in ['25']:
            matches = list(root.glob('coordination-timing-' + binding['attempt'] + '-' + lane + '-' + java + '/timing.json'))
            require(len(matches) == 1, 'Missing/duplicate job receipt: ' + lane + java)
            receipt = read(matches[0]); validate(receipt, binding, lane, java)
            receipt['_path'] = matches[0].parent
            receipts[(lane, java)] = receipt
    for java in ['25']:
        baseline, core, archive = [receipts[(lane, java)] for lane in ['baseline', 'core', 'archive']]
        for row in [baseline, core, archive]:
            proof_path = row['_path'] / 'archive.json'
            require(sha(proof_path) == row['archiveProofSha256'], 'Changed archive receipt')
        require(read(baseline['_path'] / 'archive.json') == read(core['_path'] / 'archive.json')
                == read(archive['_path'] / 'archive.json'), 'Archive verification differs between variants')
        for row in [baseline, core]:
            require(sha(row['_path'] / 'scope.json') == row['scopeProofSha256'], 'Changed scope proof')
        require(inventory(read(baseline['_path'] / 'scope.json')) == inventory(read(core['_path'] / 'scope.json')),
                'Test coverage differs between variants')
        for row in [baseline, core]:
            require(sha(row['_path'] / 'build.json') == row['buildProofSha256'], 'Changed build handoff')
        require(read(baseline['_path'] / 'build.json') == read(core['_path'] / 'build.json'),
                'Production release handoff differs between baseline and core')
    baseline = [receipts[('baseline', java)] for java in ['25']]
    parallel = [receipts[(lane, java)] for lane in ['core','archive'] for java in ['25']]
    window = lambda rows: max(r['finished'] for r in rows) - min(r['started'] for r in rows)
    before, after = window(baseline), window(parallel)
    lines = ['## Complete RC verification, without publication', '',
             f'Baseline Java 25 window: **{before:.1f}s**. Parallel archive window: **{after:.1f}s**.',
             f'Observed change: **{(before-after)/before*100:.1f}% faster** (negative means slower).', '',
             '| Lane | JDK | Command window(s) | Peak PSS(MiB) | Sampled cores |', '|---|---|---:|---:|---:|']
    for (lane, java), row in receipts.items():
        metrics = row['processTreeSampling']
        lines.append(f"| {lane} | {java} | {row['finished']-row['started']:.1f} | {metrics['peakPssKiB']/1024:.1f} | {metrics['averageSampledCores']:.2f} |")
    lines += ['', 'All full test inventories, topology gates, archive proofs and Java 25 staged bytes match.',
              'Windows include receipt waits and staggered job starts; exclude setup/upload/final comparison.',
              'Process-tree CPU/PSS sampling is approximate (short-lived processes can be missed).',
              'No Maven publication or release executed; historical publication latency is not included.']
    summary = '\n'.join(lines) + '\n'
    print(summary)
    with open(os.environ['GITHUB_STEP_SUMMARY'], 'a') as handle:
        handle.write(summary)


if __name__ == '__main__':
    action, *arguments = sys.argv[1:]
    {'measure': measure, 'consume': consume, 'compare': compare}[action](*arguments)
