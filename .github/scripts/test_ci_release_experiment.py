import importlib.util
from pathlib import Path
import unittest
import tempfile
import os
from unittest.mock import patch

spec = importlib.util.spec_from_file_location('experiment', Path(__file__).with_name('ci-release-experiment.py'))
experiment = importlib.util.module_from_spec(spec)
spec.loader.exec_module(experiment)

class ReceiptTests(unittest.TestCase):
    def valid(self):
        return {'schema': 1, 'sha': 'a'*40, 'tree': 'b'*40, 'run': '123', 'attempt': '1',
                'java': '17', 'lane': 'archive', 'success': True,
                'commands': [{'args': a, 'exit': 0} for a in experiment.commands('archive', '17')],
                'started': 100, 'finished': 200}

    def test_valid_receipt(self):
        experiment.validate(self.valid(), self.valid(), 'archive', '17')

    def test_wrong_binding_or_failed_receipt(self):
        for field, value in [('sha','c'*40),('tree','d'*40),('run','124'),('attempt','2'),
                             ('java','21'),('lane','baseline'),('success',False)]:
            with self.subTest(field=field), self.assertRaises(ValueError):
                experiment.validate(dict(self.valid(), **{field:value}), self.valid(), 'archive', '17')

    def test_incomplete_or_failed_commands(self):
        for commands in [[], [{'args':['jreleaserDeploy'], 'exit':0}],
                         [{'args':a, 'exit':1} for a in experiment.commands('archive','17')]]:
            with self.assertRaises(ValueError):
                experiment.validate(dict(self.valid(), commands=commands), self.valid(), 'archive','17')

    def test_invalid_time(self):
        for value in [99, float('nan'), float('inf')]:
            with self.assertRaises(ValueError):
                experiment.validate(dict(self.valid(),finished=value),self.valid(),'archive','17')

    def test_command_scope_preserves_full_rc_gates(self):
        self.assertIn('stageRelease', experiment.commands('baseline','17')[1])
        self.assertIn('releaseCheck', experiment.commands('baseline','21')[1])
        self.assertNotIn('-x',str(experiment.commands('core','17')))
        self.assertNotIn('jreleaser',str(experiment.commands('core','17')))

    def test_archive_proof_checks_all_statuses_and_exact_tests(self):
        proof={'schemaId':experiment.ARCHIVE_SCHEMA,'archiveSha256':'a'*64,'archiveName':'source.zip',
               'coordinationVersion':'3.0.0-rc.11','java':'17','dependencyMode':'published-artifact',
               'focusedTests':experiment.FOCUSED_TESTS,'focusedTasks':experiment.FOCUSED_TASKS,
               **{name:'PASS' for name in experiment.ARCHIVE_STATUSES}}
        experiment.validate_archive(proof,'17','a'*64,'source.zip','3.0.0-rc.11')
        for field,value in [('archiveSha256','b'*64),('java','21'),('focusedTests',[]),('compileStatus','FAIL')]:
            with self.assertRaises(ValueError):
                experiment.validate_archive(dict(proof,**{field:value}),'17','a'*64,'source.zip','3.0.0-rc.11')

    def test_report_comparison_rejects_missing_suite_or_duplicate_case(self):
        p={'status':'PASS','topologyEvidenceVerified':True,'suites':{s:{'passed':True,'fullTask':True,
            'maxParallelForks':4,'testCases':[{'className':'Class','name':s,'failed':False,'skipped':False}]}
            for s in experiment.SUITES}}
        self.assertEqual(len(experiment.inventory(p)),4)
        broken=dict(p,suites=dict(p['suites']))
        broken['suites'].pop('scenarioTest')
        with self.assertRaises(ValueError):experiment.inventory(broken)
        p['suites']['test']['testCases']*=2
        with self.assertRaises(ValueError):experiment.inventory(p)

class ConsumeTests(unittest.TestCase):
    def test_actual_archive_receipt_and_reject_stale_attempt(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            archive = root / 'source.zip'
            archive.write_bytes(b'actual archive bytes')
            binding = {'schema':1,'sha':'a'*40,'tree':'b'*40,'run':'123','attempt':'1'}
            target = root / 'coordination-archive-17-1'
            proof = {'schemaId':experiment.ARCHIVE_SCHEMA,'archiveSha256':experiment.sha(archive),
                'archiveName':archive.name,'coordinationVersion':'3.0.0-rc.11','java':'17',
                'dependencyMode':'published-artifact','focusedTests':experiment.FOCUSED_TESTS,
                'focusedTasks':experiment.FOCUSED_TASKS,
                **{name:'PASS' for name in experiment.ARCHIVE_STATUSES}}
            experiment.write(target/'archive.json',proof)
            receipt = dict(binding,lane='archive',java='17',success=True,started=100,finished=200,
                commands=[{'args':a,'exit':0} for a in experiment.commands('archive','17')],
                archiveProofSha256=experiment.sha(target/'archive.json'))
            experiment.write(target/'timing.json',receipt)
            installed = root/'installed.json'
            with patch.object(experiment,'identity',return_value=binding), \
                 patch.object(experiment,'ARCHIVE_REPORT',installed), \
                 patch.dict(os.environ,{'RUNNER_TEMP':temporary,'EXPERIMENT_LANE':'core'}):
                experiment.consume('17',str(archive),'3.0.0-rc.11')
                self.assertEqual(experiment.read(installed),proof)
                installed.unlink()
                experiment.write(target/'timing.json',dict(receipt,attempt='2'))
                with self.assertRaisesRegex(ValueError,'attempt'):
                    experiment.consume('17',str(archive),'3.0.0-rc.11')
                self.assertFalse(installed.exists())
                experiment.write(target/'timing.json',receipt)
                archive.write_bytes(b'changed archive bytes')
                with self.assertRaisesRegex(ValueError,'archiveSha256'):
                    experiment.consume('17',str(archive),'3.0.0-rc.11')
                self.assertFalse(installed.exists())

class CompareTests(unittest.TestCase):
    def test_complete_six_jobs_then_reject_changed_case_inventory(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            binding = {'schema':1,'sha':'a'*40,'tree':'b'*40,'run':'123','attempt':'1'}
            proof = {'status':'PASS','topologyEvidenceVerified':True,'suites':{
                suite:{'passed':True,'fullTask':True,'maxParallelForks':4,
                       'testCases':[{'className':'Example','name':suite,'failed':False,'skipped':False}]}
                for suite in experiment.SUITES}}
            for lane in ['baseline','core','archive']:
                for java in ['17','21']:
                    folder=root/('coordination-timing-1-'+lane+'-'+java)
                    archive={'java':java,'focusedTests':experiment.FOCUSED_TESTS}
                    experiment.write(folder/'archive.json',archive)
                    row=dict(binding,lane=lane,java=java,success=True,started=100,
                        finished=200 if lane=='baseline' else 180,
                        commands=[{'args':args,'exit':0} for args in experiment.commands(lane,java)],
                        archiveProofSha256=experiment.sha(folder/'archive.json'),
                        processTreeSampling={'peakPssKiB':1024,'averageSampledCores':2})
                    if lane!='archive':
                        experiment.write(folder/'scope.json',proof)
                        experiment.write(folder/'build.json',{'java':java,'artifacts':{'jar':'123'},
                            'testInventory':experiment.inventory(proof),'focusedTests':experiment.FOCUSED_TESTS})
                        row.update(scopeProofSha256=experiment.sha(folder/'scope.json'),
                                   buildProofSha256=experiment.sha(folder/'build.json'))
                    experiment.write(folder/'timing.json',row)
            with patch.object(experiment,'identity',return_value=binding),patch.dict(os.environ,{'GITHUB_STEP_SUMMARY':str(root/'summary')}):
                experiment.compare(root)
                self.assertIn('20.0%',(root/'summary').read_text())
                folder=root/'coordination-timing-1-core-17'
                changed=experiment.read(folder/'scope.json')
                changed['suites']['test']['testCases'][0]['name']='changed'
                experiment.write(folder/'scope.json',changed)
                row=experiment.read(folder/'timing.json')
                row['scopeProofSha256']=experiment.sha(folder/'scope.json')
                experiment.write(folder/'timing.json',row)
                with self.assertRaisesRegex(ValueError,'coverage differs'):
                    experiment.compare(root)

if __name__ == '__main__': unittest.main()
