import importlib.util
from pathlib import Path
import unittest

spec = importlib.util.spec_from_file_location('support', Path(__file__).with_name('ci-verification-support.py'))
support = importlib.util.module_from_spec(spec)
spec.loader.exec_module(support)

class ReceiptTests(unittest.TestCase):
    def valid(self):
        return {'schema': 1, 'sha': 'a'*40, 'tree': 'b'*40, 'run': '123', 'attempt': '1',
                'java': '25', 'lane': 'archive', 'success': True,
                'commands': [{'args': a, 'exit': 0} for a in support.commands('archive', '25')],
                'started': 100, 'finished': 200}

    def test_valid_receipt(self):
        support.validate(self.valid(), self.valid(), 'archive', '25')

    def test_wrong_binding_or_failed_receipt(self):
        for field, value in [('sha','c'*40),('tree','d'*40),('run','124'),('attempt','2'),
                             ('java','21'),('lane','baseline'),('success',False)]:
            with self.subTest(field=field), self.assertRaises(ValueError):
                support.validate(dict(self.valid(), **{field:value}), self.valid(), 'archive', '25')

    def test_incomplete_or_failed_commands(self):
        for commands in [[], [{'args':['jreleaserDeploy'], 'exit':0}],
                         [{'args':a, 'exit':1} for a in support.commands('archive','25')]]:
            with self.assertRaises(ValueError):
                support.validate(dict(self.valid(), commands=commands), self.valid(), 'archive','25')

    def test_invalid_time(self):
        for value in [99, float('nan'), float('inf')]:
            with self.assertRaises(ValueError):
                support.validate(dict(self.valid(),finished=value),self.valid(),'archive','25')

    def test_command_scope_preserves_full_rc_gates(self):
        self.assertIn('stageRelease', support.commands('core','25')[1])
        self.assertIn('stageRelease', support.commands('core','25')[1])
        self.assertNotIn('-x',str(support.commands('core','25')))
        self.assertNotIn('jreleaser',str(support.commands('core','25')))

    def test_commands_use_bounded_two_by_two_concurrency(self):
        for lane in ['core', 'archive']:
            for command in support.commands(lane, '25'):
                self.assertIn('-PtestMaxParallelForks=2', command)
                self.assertIn('-PtestMethodParallelism=2', command)
                self.assertIn('--max-workers=4', command)
                self.assertIn('--no-parallel', command)

    def test_inventory_rejects_disabled_or_unbounded_parallelism(self):
        valid = {'enabled':'true','mode.default':'concurrent','mode.classes.default':'concurrent',
                 'config.strategy':'fixed','config.fixed.parallelism':'2',
                 'config.fixed.max-pool-size':'2','config.fixed.saturate':'true'}
        for field, value in [('enabled','false'), ('config.fixed.max-pool-size','256'),
                             ('config.fixed.parallelism','4'), ('mode.classes.default','same_thread'),
                             ('config.strategy','dynamic'), ('config.fixed.saturate','false')]:
            suites = {s: {'passed':True,'fullTask':True,'maxParallelForks':2,
                'junitParallelism':dict(valid),'executedTests':1,
                'testCases':[{'className':'Class','name':s,'failed':False,'skipped':False}]}
                for s in support.SUITES}
            proof = {'status':'PASS','topologyEvidenceVerified':True,'suites':suites}
            support.inventory(proof)
            suites['test']['junitParallelism'][field] = value
            with self.subTest(field=field), self.assertRaises(ValueError):
                support.inventory(proof)

    def test_archive_proof_checks_all_statuses_and_exact_tests(self):
        proof={'schemaId':support.ARCHIVE_SCHEMA,'archiveSha256':'a'*64,'archiveName':'source.zip',
               'coordinationVersion':'3.0.0-rc.11','java':'25','dependencyMode':'published-artifact',
               'focusedTests':support.FOCUSED_TESTS,'focusedTasks':support.FOCUSED_TASKS,
               'testMaxParallelForks':2,'testMethodParallelism':2,'testMaxWorkers':4,
               **{name:'PASS' for name in support.ARCHIVE_STATUSES}}
        support.validate_archive(proof,'25','a'*64,'source.zip','3.0.0-rc.11')
        for field,value in [('archiveSha256','b'*64),('java','21'),('focusedTests',[]),('compileStatus','FAIL'),
                            ('testMaxParallelForks',4),('testMethodParallelism',1),('testMaxWorkers',2)]:
            with self.assertRaises(ValueError):
                support.validate_archive(dict(proof,**{field:value}),'25','a'*64,'source.zip','3.0.0-rc.11')

    def test_report_comparison_preserves_parameterized_display_name_multiplicity(self):
        p={'status':'PASS','topologyEvidenceVerified':True,'suites':{s:{'passed':True,'fullTask':True,
            'maxParallelForks':2,'junitParallelism':{
                'enabled':'true','mode.default':'concurrent','mode.classes.default':'concurrent',
                'config.strategy':'fixed','config.fixed.parallelism':'2',
                'config.fixed.max-pool-size':'2','config.fixed.saturate':'true'},'executedTests':1,'testCases':[{'className':'Class','name':s,'failed':False,'skipped':False}]}
            for s in support.SUITES}}
        self.assertEqual(len(support.inventory(p)),4)
        broken=dict(p,suites=dict(p['suites']))
        broken['suites'].pop('scenarioTest')
        with self.assertRaises(ValueError):support.inventory(broken)
        p['suites']['test']['testCases']*=2
        p['suites']['test']['executedTests']=2
        inventory=support.inventory(p)
        self.assertEqual(len(inventory),5)
        self.assertEqual(inventory.count(('test','Class','test')),2)
        p['suites']['test']['executedTests']=1
        with self.assertRaisesRegex(ValueError,'count'):support.inventory(p)

if __name__ == "__main__": unittest.main()
