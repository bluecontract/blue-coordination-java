import importlib.util
from pathlib import Path
import unittest

spec = importlib.util.spec_from_file_location('verification', Path(__file__).with_name('ci-verification.py'))
verification = importlib.util.module_from_spec(spec)
spec.loader.exec_module(verification)

class SourceBindingTests(unittest.TestCase):
    def test_archive_receipt_cannot_cross_workflow_scopes(self):
        binding = {'schema':1, 'sha':'a'*40, 'tree':'b'*40, 'run':'123', 'attempt':'1', 'scope':'rc'}
        receipt = dict(binding, scope='build', lane='archive', java='17', success=True,
            started=100, finished=200, commands=[{'args':args, 'exit':0}
                for args in verification.timing.commands('archive','17')])
        with self.assertRaisesRegex(ValueError, 'scope'):
            verification.validate_receipt(receipt, binding, 'archive', '17')


class ProductionContractTests(unittest.TestCase):
    def binding(self):
        return {'schema':1, 'sha':'a'*40, 'tree':'b'*40, 'run':'123', 'attempt':'1', 'scope':'rc'}

    def test_requires_exact_prepared_source_without_requiring_event_sha(self):
        import os
        from unittest.mock import patch
        env = {'GITHUB_REPOSITORY':'bluecontract/blue-coordination-java', 'VERIFICATION_SCOPE':'rc',
            'VERIFICATION_COMMIT':'a'*40, 'GITHUB_SHA':'c'*40, 'GITHUB_RUN_ID':'123', 'GITHUB_RUN_ATTEMPT':'1'}
        with patch.dict(os.environ,env), patch.object(verification.subprocess,'check_output',
                side_effect=['a'*40, 'b'*40, '']):
            self.assertEqual(verification.identity(), self.binding())
        with patch.dict(os.environ,env), patch.object(verification.subprocess,'check_output',
                side_effect=['c'*40, 'b'*40]):
            with self.assertRaisesRegex(ValueError,'prepared source'):
                verification.identity()
        with patch.dict(os.environ,env), patch.object(verification.subprocess,'check_output',
                side_effect=['a'*40, 'b'*40, ' M build.gradle']):
            with self.assertRaisesRegex(ValueError,'not clean'):
                verification.identity()

    def test_exact_owner_commands_and_run_binding(self):
        receipt = dict(self.binding(), lane='archive', java='17', success=True,
            started=100, finished=200, commands=[{'args':a,'exit':0}
                for a in verification.commands('archive','17')])
        verification.validate_receipt(receipt, self.binding(),'archive','17')
        for field,value in [('sha','c'*40),('tree','d'*40),('run','124'),('attempt','2'),
                            ('scope','stable'),('success',False),('commands',[])]:
            with self.subTest(field=field), self.assertRaises(ValueError):
                verification.validate_receipt(dict(receipt,**{field:value}), self.binding(),'archive','17')
        core = str(verification.commands('core','17'))
        self.assertIn('stageRelease',core)
        self.assertIn('ci-archive-handoff.init.gradle',core)
        self.assertNotIn('jreleaser',core)
        self.assertNotIn("'-x'",core)

    def test_stable_gate_does_not_claim_rc_readiness(self):
        import os
        from unittest.mock import patch
        with patch.dict(os.environ,{'RELEASE_CHANNEL':'stable'}), patch.object(verification.timing,'measure') as measure:
            verification.run('core','output')
            self.assertEqual(measure.call_args.args[-1], 'stable')

class ConsumeProductionProofTests(unittest.TestCase):
    def test_consumes_exact_archive_and_rejects_corruption(self):
        import os
        import tempfile
        from unittest.mock import patch
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            archive = root/'source.zip'
            archive.write_bytes(b'verified source bytes')
            binding = {'schema':1,'sha':'a'*40,'tree':'b'*40,'run':'123','attempt':'1','scope':'rc'}
            folder = root/verification.artifact(binding)
            timing = verification.timing
            proof = {'schemaId':timing.ARCHIVE_SCHEMA,'archiveSha256':timing.sha(archive),
                'archiveName':archive.name,'coordinationVersion':'3.0.0-rc.11','java':'17',
                'dependencyMode':'published-artifact','focusedTests':timing.FOCUSED_TESTS,
                'focusedTasks':timing.FOCUSED_TASKS,
                'testMaxParallelForks':2,'testMethodParallelism':2,'testMaxWorkers':4, **{key:'PASS' for key in timing.ARCHIVE_STATUSES}}
            timing.write(folder/'archive.json',proof)
            receipt = dict(binding,lane='archive',java='17',success=True,started=100,finished=200,
                commands=[{'args':args,'exit':0} for args in verification.commands('archive','17')],
                archiveProofSha256=timing.sha(folder/'archive.json'))
            timing.write(folder/'timing.json',receipt)
            installed = root/'installed.json'
            with patch.object(verification,'identity',return_value=binding), \
                 patch.object(timing,'ARCHIVE_REPORT',installed), patch.dict(os.environ,{'RUNNER_TEMP':temporary}):
                verification.consume('17',str(archive),'3.0.0-rc.11')
                self.assertEqual(timing.read(installed),proof)
                installed.unlink()
                timing.write(folder/'timing.json',dict(receipt,attempt='2'))
                with self.assertRaisesRegex(ValueError,'attempt'):
                    verification.consume('17',str(archive),'3.0.0-rc.11')
                self.assertFalse(installed.exists())
                timing.write(folder/'timing.json',receipt)
                archive.write_bytes(b'changed archive bytes')
                with self.assertRaisesRegex(ValueError,'archiveSha256'):
                    verification.consume('17',str(archive),'3.0.0-rc.11')
                self.assertFalse(installed.exists())
                archive.write_bytes(b'verified source bytes')
                timing.write(folder/'archive.json',dict(proof,java='25'))
                with self.assertRaisesRegex(ValueError,'corrupted'):
                    verification.consume('17',str(archive),'3.0.0-rc.11')
                self.assertFalse(installed.exists())

if __name__ == '__main__': unittest.main()
