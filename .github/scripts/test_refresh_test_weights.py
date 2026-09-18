import importlib.util
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch
import xml.etree.ElementTree as ET

import test_ci_test_shards as fixtures

shards, support = fixtures.shards, fixtures.support

spec = importlib.util.spec_from_file_location('refresh', Path(__file__).with_name('refresh-test-weights.py'))
refresh = importlib.util.module_from_spec(spec)
spec.loader.exec_module(refresh)


class RefreshWeightsTests(unittest.TestCase):
    def test_cli_rejects_failed_run_before_download_or_write(self):
        with tempfile.TemporaryDirectory() as tmp:
            output = Path(tmp) / 'weights.json'
            output.write_text('unchanged')
            metadata = dict(status='completed', conclusion='failure', path='.github/workflows/build.yml')
            with patch('sys.argv', ['refresh', '--run', '123', '--output', str(output)]), \
                    patch.object(refresh.subprocess, 'check_output', return_value=json.dumps(metadata)), \
                    patch.object(refresh.subprocess, 'run') as download:
                with self.assertRaises(ValueError):
                    refresh.main()
                download.assert_not_called()
            self.assertEqual(output.read_text(), 'unchanged')

    def fixtures(self, root):
        packages, binding = fixtures.FileProofTests().packages(root)
        for package in packages:
            for path in (package / 'evidence/test-results').rglob('TEST-*.xml'):
                tree = ET.parse(path)
                tree.getroot().set('name', path.stem.removeprefix('TEST-'))
                tree.getroot().set('time', '12.5')
                tree.write(path)
            receipt = support.read(package / 'receipt.json')
            receipt['files'] = shards.checked_tree(package / 'evidence')
            support.write(package / 'receipt.json', receipt)
        return packages

    def test_refresh_uses_measured_class_time(self):
        with tempfile.TemporaryDirectory() as tmp:
            result = refresh.weights_from(self.fixtures(Path(tmp)), '123', '1')
            self.assertEqual(len(result), 12)
            self.assertEqual(set(result.values()), {12.5})

    def test_incomplete_wrong_attempt_and_corrupt_reports_are_rejected(self):
        with tempfile.TemporaryDirectory() as tmp:
            packages = self.fixtures(Path(tmp))
            for selected, attempt in [(packages[:2], '1'), (packages, '2')]:
                with self.assertRaises(ValueError):
                    refresh.weights_from(selected, '123', attempt)
            next((packages[0] / 'evidence/test-results').rglob('TEST-*.xml')).write_text('broken')
            with self.assertRaises(ValueError):
                refresh.weights_from(packages, '123', '1')

    def test_negative_and_nonfinite_elapsed_are_rejected(self):
        for value in ['-1', 'nan', 'inf']:
            with self.subTest(value=value), tempfile.TemporaryDirectory() as tmp:
                packages = self.fixtures(Path(tmp))
                path = next((packages[0] / 'evidence/test-results').rglob('TEST-*.xml'))
                tree = ET.parse(path); tree.getroot().set('time', value); tree.write(path)
                receipt = support.read(packages[0] / 'receipt.json')
                receipt['files'] = shards.checked_tree(packages[0] / 'evidence')
                support.write(packages[0] / 'receipt.json', receipt)
                with self.assertRaises(ValueError):
                    refresh.weights_from(packages, '123', '1')


if __name__ == '__main__':
    unittest.main()
