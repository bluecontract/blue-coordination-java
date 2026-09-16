#!/usr/bin/env python3
"""Shared non-publishing source/archive verification helpers."""
import importlib.util
from pathlib import Path

spec = importlib.util.spec_from_file_location('timing', Path(__file__).with_name('ci-release-experiment.py'))
timing = importlib.util.module_from_spec(spec)
spec.loader.exec_module(timing)

import os
import re
import shutil
import subprocess
import sys
import time


def identity():
    timing.require(os.environ.get('GITHUB_REPOSITORY') == 'bluecontract/blue-coordination-java', 'Unexpected repository')
    scope = os.environ.get('VERIFICATION_SCOPE')
    timing.require(scope in ['build', 'rc', 'stable', 'topology'], 'Invalid verification scope')
    expected = os.environ.get('VERIFICATION_COMMIT', '')
    timing.require(re.fullmatch('[0-9a-f]{40}', expected), 'Missing prepared source commit')
    sha = subprocess.check_output(['git', 'rev-parse', 'HEAD'], text=True).strip()
    tree = subprocess.check_output(['git', 'rev-parse', 'HEAD^{tree}'], text=True).strip()
    timing.require(sha == expected, 'Checkout differs from prepared source')
    timing.require(not subprocess.check_output(['git', 'status', '--porcelain'], text=True).strip(), 'Prepared source is not clean')
    run, attempt = os.environ.get('GITHUB_RUN_ID', ''), os.environ.get('GITHUB_RUN_ATTEMPT', '')
    timing.require(run.isdigit() and int(run) > 0 and attempt.isdigit() and int(attempt) > 0, 'Missing run binding')
    return {'schema':1, 'sha':sha, 'tree':tree, 'run':run, 'attempt':attempt, 'scope':scope}


def commands(lane, java):
    result = timing.commands(lane, java)
    return [[arg.replace('ci-archive-receipt.init.gradle', 'ci-archive-handoff.init.gradle')
             for arg in command] for command in result]


def validate_receipt(receipt, binding, lane, java):
    timing.require(receipt.get('scope') == binding.get('scope'), 'Receipt mismatch: scope')
    timing.validate(receipt, binding, lane, java, commands)


def artifact(binding):
    return 'coordination-archive-' + binding['scope'] + '-' + binding['attempt']


def consume(java, archive_path, version):
    binding = identity()
    target = Path(os.environ['RUNNER_TEMP']) / artifact(binding)
    deadline = time.monotonic() + 1800
    while not (target / 'timing.json').is_file():
        shutil.rmtree(target, ignore_errors=True)
        result = subprocess.run(['gh', 'run', 'download', binding['run'], '--repo',
            os.environ['GITHUB_REPOSITORY'], '--name', artifact(binding), '--dir', str(target)],
            stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True)
        if result.returncode == 0:
            break
        timing.require(time.monotonic() < deadline, 'Archive receipt unavailable after 30 minutes')
        print('Waiting for actual extracted-source verification', flush=True)
        time.sleep(15)
    receipt = timing.read(target / 'timing.json')
    validate_receipt(receipt, binding, 'archive', java)
    proof_file = target / 'archive.json'
    timing.require(timing.sha(proof_file) == receipt['archiveProofSha256'], 'Archive proof corrupted')
    proof = timing.read(proof_file)
    timing.validate_archive(proof, java, timing.sha(archive_path), Path(archive_path).name, version)
    timing.write(timing.ARCHIVE_REPORT, proof)
    print('Verified actual archive execution: scope=' + binding['scope'] + ' Java=' + java, flush=True)


def run(lane, output):
    timing.require(lane in ['core', 'archive'], 'Invalid production verification lane')
    channel = os.environ.get('RELEASE_CHANNEL', 'rc')
    timing.require(channel in ['rc', 'stable'], 'Invalid release channel')
    timing.measure(lane, '25', output, identity, commands, channel)


if __name__ == '__main__':
    action, *arguments = sys.argv[1:]
    {'run':run, 'consume':consume}[action](*arguments)
