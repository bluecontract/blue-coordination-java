#!/usr/bin/env python3
"""Explicitly refresh scheduling hints from one successful Build; never commit."""
import argparse
import importlib.util
import json
import math
from pathlib import Path
import subprocess
import sys
import tempfile
import xml.etree.ElementTree as ET

sys.dont_write_bytecode = True
spec = importlib.util.spec_from_file_location('shards', Path(__file__).with_name('ci-test-shards.py'))
shards = importlib.util.module_from_spec(spec)
spec.loader.exec_module(shards)
support = shards.support
REPO = 'bluecontract/blue-coordination-java'


def weights_from(packages, run, attempt):
    support.require(len(packages) == 3, 'Expected three shard artifacts')
    plans = [support.read(p / 'plan.json') for p in packages]
    support.require(all(p == plans[0] for p in plans), 'Shard plans differ')
    plan = plans[0]
    support.require(plan['run'] == run and plan['attempt'] == attempt
                    and plan['scope'] == 'build' and plan['java'] == '17', 'Unexpected run or attempt')
    binding = {k: plan[k] for k in ['schema', 'sha', 'tree', 'run', 'attempt', 'scope', 'java', 'version']}
    binding['planSha256'] = support.sha(packages[0] / 'plan.json')
    receipts = [support.read(p / 'receipt.json') for p in packages]
    support.validate_test_shards(receipts, binding, plan['groups'])
    weights = {}
    for package, receipt in zip(packages, receipts):
        support.require(shards.checked_tree(package / 'evidence') == receipt['files'], 'Evidence hash mismatch')
        for suite in support.SUITES:
            directory = package / 'evidence/test-results' / suite
            support.require(shards.xml_cases(directory) == receipt['suites'][suite]['testCases'], 'XML receipt mismatch')
            for path in directory.glob('TEST-*.xml'):
                root = ET.parse(path).getroot()
                key = suite + ':' + root.attrib['name']
                elapsed = float(root.attrib['time'])
                support.require(key not in weights and math.isfinite(elapsed) and elapsed >= 0,
                                'Duplicate class or invalid elapsed time')
                weights[key] = elapsed
    expected = {suite + ':' + name for suite, names in plan['discovered'].items() for name in names}
    support.require(set(weights) == expected, 'Measured classes differ from discovered inventory')
    return weights


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--run', required=True, type=int, help='Successful sharded Build run ID')
    parser.add_argument('--output', type=Path, default=Path(__file__).with_name('ci-test-weights.json'))
    args = parser.parse_args()
    run = str(args.run)
    metadata = json.loads(subprocess.check_output(['gh', 'api', f'repos/{REPO}/actions/runs/{run}'], text=True))
    support.require(metadata['status'] == 'completed' and metadata['conclusion'] == 'success'
                    and metadata['path'] == '.github/workflows/build.yml', 'Expected successful Build workflow')
    attempt = str(metadata['run_attempt'])
    with tempfile.TemporaryDirectory(prefix='coordination-weights-') as tmp:
        subprocess.run(['gh', 'run', 'download', run, '--repo', REPO, '--pattern',
                        f'coordination-tests-*-of-3-build-{attempt}', '--dir', tmp], check=True)
        weights = weights_from(sorted(Path(tmp).glob('coordination-tests-*-of-3-*')), run, attempt)
    # Validate everything before replacing the scheduling data. No git operations.
    args.output.write_text(json.dumps(weights, sort_keys=True, indent=2) + '\n')
    print(f'Updated {len(weights)} class weights from run {run}, attempt {attempt}: {args.output}')
    print('Review the diff and commit explicitly if the new distribution is useful.')


if __name__ == '__main__':
    main()
