"""Branch-only matched full-test experiment; never stages or publishes a release."""
import importlib.util
import json
import os
from pathlib import Path
import subprocess
import time

spec = importlib.util.spec_from_file_location('support', Path(__file__).with_name('ci-verification-support.py'))
support = importlib.util.module_from_spec(spec)
spec.loader.exec_module(support)


def main():
    java = os.environ['BENCHMARK_JAVA']
    assert java in ['17', '25']
    assert os.cpu_count() == 4, 'Comparison requires four logical runner CPUs'
    output = Path('benchmark-output')
    output.mkdir(exist_ok=False)
    command = ['./gradlew', '--no-daemon', '--no-build-cache', '--max-workers=4', '--no-parallel',
               '-PtestMaxParallelForks=2', '-PtestMethodParallelism=2',
               '-PblueDependencyMode=published-artifact', '-PtestJavaVersion=' + java,
               '--init-script', '.github/scripts/test-jdk-experiment.init.gradle',
               'clean', 'assertBenchmarkRuntime', 'verifyReleaseTestExecutionScope']
    receipt = dict(sha=subprocess.check_output(['git', 'rev-parse', 'HEAD'], text=True).strip(),
                   java=java, command=command, started=time.time(), success=False,
                   run=os.environ.get('GITHUB_RUN_ID'), attempt=os.environ.get('GITHUB_RUN_ATTEMPT'),
                   logicalCpus=os.cpu_count(), meminfo=Path('/proc/meminfo').read_text(),
                   lscpu=subprocess.check_output(['lscpu', '-J'], text=True))
    measurements = dict(peakRssKiB=0, peakPssKiB=0, peakProcesses=0, memoryReadMisses=0, cpuTicks={})
    known = {}
    try:
        print('Running:', *command, flush=True)
        process = subprocess.Popen(command)
        while process.poll() is None:
            support.sample_processes(process.pid, known, measurements)
            time.sleep(1)
        receipt['exit'] = process.returncode
        assert process.returncode == 0, 'Full test command failed'
        scope = json.loads(support.SCOPE_REPORT.read_text())
        assert scope['javaVersion'] == java
        cases = support.inventory(scope)
        assert len(cases) == 1186, 'Full expected inventory changed'
        (output / 'scope.json').write_text(json.dumps(scope, indent=2))
        receipt.update(testCount=len(cases), scopeSha256=support.sha(output / 'scope.json'), success=True)
    finally:
        receipt['finished'] = time.time()
        measurements['sampledCpuSeconds'] = sum(measurements.pop('cpuTicks').values()) / os.sysconf('SC_CLK_TCK')
        measurements['averageSampledCores'] = measurements['sampledCpuSeconds'] / (receipt['finished'] - receipt['started'])
        receipt['processTreeSampling'] = measurements
        (output / 'timing.json').write_text(json.dumps(receipt, indent=2))


if __name__ == '__main__':
    main()
