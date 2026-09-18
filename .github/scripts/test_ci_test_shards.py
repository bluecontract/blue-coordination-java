import importlib.util
import json
from pathlib import Path
import tempfile
import unittest

spec = importlib.util.spec_from_file_location('support', Path(__file__).with_name('ci-verification-support.py'))
support = importlib.util.module_from_spec(spec)
spec.loader.exec_module(support)
spec = importlib.util.spec_from_file_location('shards', Path(__file__).with_name('ci-test-shards.py'))
shards = importlib.util.module_from_spec(spec)
spec.loader.exec_module(shards)


class DelegatedScopeContract(unittest.TestCase):
    def test_current_local_filter_rejection_remains(self):
        proof = self.proof()
        proof.pop('delegatedTestEvidence')
        with self.assertRaises(ValueError):
            support.inventory(proof)

    def proof(self):
        parallel = {'enabled': 'true', 'mode.default': 'concurrent', 'mode.classes.default': 'concurrent',
                    'config.strategy': 'fixed', 'config.fixed.parallelism': '2',
                    'config.fixed.max-pool-size': '2', 'config.fixed.saturate': 'true'}
        suites = {suite: dict(passed=True, fullTask=False, executionMode='delegated', maxParallelForks=2,
                            junitParallelism=parallel, executedTests=2,
                            testCases=[dict(className=suite+'Test', name='parameter()', failed=False, skipped=False)]*2)
                  for suite in support.SUITES}
        return dict(status='PASS', topologyEvidenceVerified=True, suites=suites,
                    delegatedTestEvidence=dict(schema=1, status='PASS',
                        shardIds=[0, 1, 2], planSha256='a'*64,
                        receiptSha256={'0':'b'*64, '1':'c'*64, '2':'d'*64}))

    def test_verified_delegation_is_honest_about_nonlocal_execution(self):
        # All eight executions count, including legitimate repeated parameter display names.
        self.assertEqual(len(support.inventory(self.proof())), 8)


class ShardReceiptContract(unittest.TestCase):
    def validate(self, receipts=None, **changes):
        # New CI-only validator belongs beside the existing strict inventory validator.
        binding = dict(sha='a'*40, tree='b'*40, run='123', attempt='1', scope='build', java='17')
        plan = {'0': {'test':['A']}, '1': {'test':['B']}, '2': {'test':['C']}}
        rows = []
        for shard in range(3):
            name = chr(ord('A')+shard)
            rows.append(dict(binding, shard=shard, success=True, planSha256='c'*64,
                assigned=plan[str(shard)], suites={'test':dict(executedClasses=[name],
                    testCases=[dict(className=name,name='case()',failed=False,skipped=False)],
                    selectors={'classes':[name], 'methods':[], 'excludeClasses':[], 'tags':[], 'engines':[]})},
                files={'test-results/test/TEST-'+name+'.xml':str(shard)*64}))
        if receipts is not None:
            rows = receipts(rows)
        expected = dict(binding, planSha256='c'*64)
        expected.update(changes)
        return support.validate_test_shards(rows, expected, plan)

    def test_accepts_complete_partition(self):
        self.validate()

    def test_rejects_missing_duplicate_wrong_binding_and_missing_class(self):
        mutations = [lambda rows: rows[:2], lambda rows: [rows[0],rows[0],rows[2]],
                     lambda rows: [dict(rows[0],attempt='2'),*rows[1:]],
                     lambda rows: [dict(rows[0],sha='f'*40),*rows[1:]],
                     lambda rows: [dict(rows[0],assigned={'test':[]}),*rows[1:]],
                     lambda rows: [dict(rows[0],success=False),*rows[1:]]]
        for mutate in mutations:
            with self.subTest(mutation=mutate), self.assertRaises(ValueError):
                self.validate(mutate)

    def test_rejects_skips_failures_method_filters_and_conflicting_files(self):
        for field in ['skipped', 'failed']:
            def mutate(rows):
                rows[0]['suites']['test']['testCases'][0][field] = True
                return rows
            with self.subTest(field=field), self.assertRaises(ValueError):
                self.validate(mutate)
        def filtered(rows):
            rows[0]['suites']['test']['selectors']['methods'] = ['case']
            return rows
        with self.assertRaises(ValueError):
            self.validate(filtered)
        def collision(rows):
            rows[1]['files'].update(rows[0]['files'])
            rows[1]['files']['test-results/test/TEST-A.xml'] = 'f'*64
            return rows
        with self.assertRaises(ValueError):
            self.validate(collision)


class FileProofTests(unittest.TestCase):
    def packages(self, root):
        binding = dict(schema=1, sha='a'*40, tree='b'*40, run='123', attempt='1', scope='build', java='17', version='1.0')
        discovered = {suite:[suite + letter for letter in 'ABC'] for suite in support.SUITES}
        groups = shards.plan_for(discovered, {})
        plan = dict(binding, discovered=discovered, groups=groups)
        packages = []
        for i in range(3):
            p = root / ('shard'+str(i)); p.mkdir()
            support.write(p/'plan.json', plan)
            runtime = {}; suites = {}
            for suite, names in groups[str(i)].items():
                directory=p/'evidence/test-results'/suite;directory.mkdir(parents=True)
                for name in names:
                    # Repeated display names are legitimate and must remain duplicated.
                    (directory/('TEST-'+name+'.xml')).write_text('<testsuite>'+''.join(
                        '<testcase classname="'+name+'" name="parameter()"/>' for _ in range(2))+'</testsuite>')
                runtime[suite] = dict(java=17,maxParallelForks=2,methodThreads=2,maxWorkers=4,executed=True,
                    selectors=dict(classes=names,methods=[],excludeClasses=[],tags=[],engines=[]))
                cases=shards.xml_cases(directory)
                suites[suite]=dict(runtime[suite],testCases=cases,executedClasses=names)
            support.write(p/'runtime.json',runtime)
            commands=[shards.COMMON+['clean', *['discover'+s[0].upper()+s[1:]+'Classes' for s in support.SUITES]],
                      shards.COMMON+['--init-script',shards.INIT,'ciShardProof']]
            receipt=dict(binding,shard=i,success=True,assigned=groups[str(i)],suites=suites,
                files=shards.checked_tree(p/'evidence'),planSha256=support.sha(p/'plan.json'),
                runtimeSha256=support.sha(p/'runtime.json'),commands=[dict(args=a,exit=0) for a in commands])
            support.write(p/'receipt.json',receipt);packages.append(p)
        return packages,binding

    def test_real_files_validate_then_tamper_fails(self):
        with tempfile.TemporaryDirectory() as tmp:
            packages,binding=self.packages(Path(tmp))
            plan,receipts,_=shards.verify_packages(packages,binding)
            self.assertEqual(sum(len(s['testCases']) for r in receipts for s in r['suites'].values()),24)
            xml=next((packages[0]/'evidence').rglob('*.xml'))
            xml.write_text(xml.read_text().replace('parameter()', 'changed()'))
            with self.assertRaisesRegex(ValueError,'hash'):
                shards.verify_packages(packages,binding)

    def test_runtime_proof_and_missing_xml_are_not_trusted(self):
        with tempfile.TemporaryDirectory() as tmp:
            packages,binding=self.packages(Path(tmp))
            (packages[0]/'runtime.json').write_text('{}')
            with self.assertRaisesRegex(ValueError,'Runtime proof'):
                shards.verify_packages(packages,binding)
        with tempfile.TemporaryDirectory() as tmp:
            packages,binding=self.packages(Path(tmp))
            next((packages[0]/'evidence').rglob('*.xml')).unlink()
            with self.assertRaisesRegex(ValueError,'hash'):
                shards.verify_packages(packages,binding)

    def test_new_classes_are_assigned_and_overlap_rejected(self):
        discovered={s:[s+'New'] for s in support.SUITES}
        groups=shards.plan_for(discovered,{})
        self.assertEqual(sum(len(c) for group in groups.values() for c in group.values()),4)
        discovered['test'].append('testNew')
        with self.assertRaises(ValueError):shards.plan_for(discovered,{})

    def test_rejects_wrong_provenance_commands_and_nonexecuted_tasks(self):
        for field,value in [('sha','f'*40),('tree','f'*40),('run','124'),('attempt','2'),
                            ('scope','rc'),('version','2.0'),('java','25')]:
            with self.subTest(field=field), tempfile.TemporaryDirectory() as tmp:
                packages,binding=self.packages(Path(tmp))
                with self.assertRaises(ValueError):shards.verify_packages(packages,dict(binding,**{field:value}))
        with tempfile.TemporaryDirectory() as tmp:
            packages,binding=self.packages(Path(tmp));p=packages[0]
            receipt=support.read(p/'receipt.json');receipt['commands'][1]['args'].append('-x')
            support.write(p/'receipt.json',receipt)
            with self.assertRaisesRegex(ValueError,'command scope'):shards.verify_packages(packages,binding)
        with tempfile.TemporaryDirectory() as tmp:
            packages,binding=self.packages(Path(tmp));p=packages[0]
            runtime=support.read(p/'runtime.json');runtime['test']['executed']=False
            support.write(p/'runtime.json',runtime)
            receipt=support.read(p/'receipt.json');receipt['runtimeSha256']=support.sha(p/'runtime.json')
            receipt['suites']['test']['executed']=False;support.write(p/'receipt.json',receipt)
            with self.assertRaisesRegex(ValueError,'did not execute'):shards.verify_packages(packages,binding)

    def test_duplicate_topology_owner_rejected_even_with_identical_bytes(self):
        with tempfile.TemporaryDirectory() as tmp:
            packages,binding=self.packages(Path(tmp))
            for p in packages[:2]:
                f=p/'evidence/rooted-evidence/topology-fragments/Owner__method.json'
                f.parent.mkdir(parents=True);f.write_text('{}')
                receipt=support.read(p/'receipt.json');receipt['files']=shards.checked_tree(p/'evidence')
                support.write(p/'receipt.json',receipt)
            with self.assertRaisesRegex(ValueError,'Duplicate XML or topology'):
                shards.verify_packages(packages,binding)

    def test_symlinks_rejected(self):
        with tempfile.TemporaryDirectory() as tmp:
            root=Path(tmp);(root/'target').write_text('data');(root/'link').symlink_to(root/'target')
            with self.assertRaises(ValueError):shards.checked_tree(root)


if __name__ == '__main__':
    unittest.main()
