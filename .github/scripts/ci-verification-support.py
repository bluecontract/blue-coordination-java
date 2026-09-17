#!/usr/bin/env python3
"""Production verification receipts, archive validation and resource measurements."""
import hashlib
import json
import math
import os
from pathlib import Path
import subprocess
import time

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
    require(lane in ['core', 'archive'] and java in ['25'], 'Invalid lane/JDK')
    common = ['--no-daemon', '--no-build-cache', '--max-workers=4', '--no-parallel',
              '-PtestMaxParallelForks=2', '-PtestMethodParallelism=2',
              '-PblueDependencyMode=published-artifact', '-PtestJavaVersion=' + java]
    preflight = common + ['dependencyPreflight']
    if lane == 'archive':
        return [preflight, common + ['clean', 'verifyExtractedSourceArchive']]
    init = ['--init-script', '.github/scripts/ci-archive-handoff.init.gradle'] if lane == 'core' else []
    return [preflight, common + init + ['clean', 'stageRelease']]


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
                'focusedTasks': FOCUSED_TASKS, 'testMaxParallelForks': 2,
                'testMethodParallelism': 2, 'testMaxWorkers': 4}
    for key, value in expected.items():
        require(proof.get(key) == value, 'Archive proof mismatch: ' + key)
    require(sorted(proof.get('focusedTests', [])) == sorted(FOCUSED_TESTS), 'Incomplete archive test inventory')
    require(all(proof.get(key) == 'PASS' for key in ARCHIVE_STATUSES), 'Archive gate not passed')


def inventory(proof):
    require(proof.get('status') == 'PASS' and proof.get('topologyEvidenceVerified') is True, 'Scope/topology gate failed')
    require(set(proof.get('suites', {})) == set(SUITES), 'Missing/extra test suite')
    result = []
    for suite, data in proof['suites'].items():
        require(data.get('passed') is True and data.get('fullTask') is True and data.get('maxParallelForks') == 2,
                'Incomplete suite or wrong forks')
        require(data.get('junitParallelism') == {
            'enabled': 'true', 'mode.default': 'concurrent', 'mode.classes.default': 'concurrent',
            'config.strategy': 'fixed', 'config.fixed.parallelism': '2',
            'config.fixed.max-pool-size': '2', 'config.fixed.saturate': 'true'},
            'Wrong or unbounded JUnit parallelism')
        cases = data.get('testCases', [])
        require(cases and all(c.get('failed') is False and c.get('skipped') is False for c in cases),
                'Failed/skipped/empty tests')
        require(data.get('executedTests') == len(cases), 'Executed test count mismatch')
        # JUnit parameterized methods can share class and display name. Keep every
        # occurrence so comparison detects missing/extra invocations, like inspectBuild.
        result.extend((suite, c['className'], c['name']) for c in cases)
    return sorted(result)


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


def measure(lane, java, output, binding_factory, command_factory=commands, channel="rc"):
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
