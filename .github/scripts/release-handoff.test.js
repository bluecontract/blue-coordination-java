const assert = require('node:assert/strict');
const { execFileSync, spawnSync } = require('node:child_process');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const test = require('node:test');
const { candidate, restore, seal, verify, sha } = require('./release-handoff');

function write(root, file, value) {
  const target = path.join(root, file);
  fs.mkdirSync(path.dirname(target), { recursive: true });
  fs.writeFileSync(target, typeof value === 'string' ? value : JSON.stringify(value));
}

function fixture(t, channel = 'rc') {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'coordination-release-handoff-'));
  t.after(() => fs.rmSync(root, { recursive: true, force: true }));
  const version = channel === 'rc' ? '3.0.0-rc.8' : '3.0.0';
  const gitEnv = { ...process.env, GIT_CONFIG_NOSYSTEM: '1', GIT_CONFIG_GLOBAL: os.devNull,
    GIT_AUTHOR_NAME: 'Release test', GIT_AUTHOR_EMAIL: 'test@example.invalid',
    GIT_COMMITTER_NAME: 'Release test', GIT_COMMITTER_EMAIL: 'test@example.invalid' };
  const git = (cwd, ...args) => execFileSync('git', args, { cwd, env: gitEnv,
    encoding: 'utf8', stdio: ['ignore', 'pipe', 'pipe'] }).trim();
  const source = path.join(root, 'source');
  git(root, 'init', '--quiet', '--initial-branch=next', source);
  write(source, '.gitignore', 'build/\n');
  write(source, '.cz.toml', `version = "${version}"\n`);
  write(source, 'gradle/published-artifact.lockfile', 'exact dependency lock\n');
  git(source, 'add', '.');
  git(source, 'commit', '--quiet', '-m', 'Release source');
  // Downstream checkouts initially lack this unpushed release commit and tag.
  const checkouts = {};
  for (const lane of ['25', 'publish']) {
    const checkout = path.join(root, lane);
    git(root, 'clone', '--quiet', source, checkout);
    checkouts[lane] = checkout;
  }
  git(source, 'commit', '--quiet', '--allow-empty', '-m', 'Prepared release candidate');
  if (channel === 'rc') git(source, 'tag', '-a', `v${version}`, '-m', 'Release');
  const directory = path.join(root, 'candidate');
  const info = candidate(source, directory, channel);
  const expected = { commit: info.commit, version, channel, bundleSha: info['bundle-sha'] };
  for (const checkout of Object.values(checkouts)) restore(checkout, directory, expected);
  const env = { RELEASE_COMMIT: info.commit, RELEASE_VERSION: version,
    RELEASE_CHANNEL: channel, GITHUB_RUN_ID: '12345' };
  const handoffs = path.join(root, 'handoffs');
  fs.mkdirSync(handoffs);

  function build(java) {
    const output = path.join(checkouts[java], 'build');
    const suites = {};
    for (const suite of ['test', 'integrationTest', 'consumerTest', 'scenarioTest']) {
      const className = `${suite}.ExampleTest`;
      suites[suite] = { passed: true, fullTask: true, failures: 0, skipped: 0,
        executedTests: 1, expectedClasses: [className], executedClasses: [className],
        testCases: [{ className, name: 'evidence()', failed: false, skipped: false }] };
    }
    write(output, 'reports/test-execution-scope/verifyReleaseTestExecutionScope.json', {
      status: 'PASS', coordinationVersion: version, javaVersion: java,
      topologyEvidenceVerified: true, suites,
    });
    const archiveName = `blue-coordination-java-${version}-source.zip`;
    write(output, `distributions/${archiveName}`, 'verified source archive bytes');
    write(output, 'reports/contracts10/source-archive-verification.json', {
      coordinationVersion: version, dependencyMode: 'published-artifact', java,
      extractedConfiguration: 'PASS', compileStatus: 'PASS', dependencyIsolationStatus: 'PASS',
      focusedTestsStatus: 'PASS', focusedTests: ['FocusedExampleTest'], archiveName,
      archiveSha256: sha(path.join(output, 'distributions', archiveName)),
    });
    const artifacts = {};
    const stem = `blue-coordination-java-${version}`;
    for (const suffix of ['', '-sources', '-javadoc', '-test-fixtures']) {
      const file = `${stem}${suffix}.jar`;
      write(output, `libs/${file}`, `verified ${suffix} bytes`);
      artifacts[suffix || 'main'] = { file, sha256: sha(path.join(output, 'libs', file)) };
      if (java === '25') write(output, `staging-deploy/blue/coordination/blue-coordination-java/${version}/${file}`, `verified ${suffix} bytes`);
    }
    write(output, 'publications/mavenJava/pom-default.xml', 'verified publication pom');
    if (java === '25') {
      write(output, 'publications/mavenJava/module.json', 'generated module metadata');
      write(output, `staging-deploy/blue/coordination/blue-coordination-java/${version}/${stem}.pom`, 'verified publication pom');
      write(output, `staging-deploy/blue/coordination/blue-coordination-java/${version}/${stem}.module`, 'generated module metadata');
      if (channel === 'rc') write(output, `reports/release/${version}-readiness.json`, {
        release: version, status: 'PASS_FOR_BOUNDED_EXTERNAL_PILOT',
        dependencyMode: 'published-artifact', artifacts,
      });
    }
    write(output, 'test-results/test/example.xml', '<testcase/>');
    write(output, 'rooted-evidence/gas/example.json', { gas: 42 });
    return output;
  }
  const builds = { 25: build('25') };
  const hashes = {};
  function sealGate() {
    for (const java of ['25']) {
      hashes[java] = seal(checkouts[java], path.join(handoffs, `java${java}.tar.gz`), java, env)['handoff-sha'];
    }
    return hashes;
  }
  function mutate(java, file, change) {
    const data = JSON.parse(fs.readFileSync(path.join(builds[java], file), 'utf8'));
    change(data);
    write(builds[java], file, data);
  }
  return { root, git, source, directory, expected, checkouts, env, builds, version,
    handoffs, hashes, sealGate, mutate, publish: () => verify(checkouts.publish, handoffs, hashes, env) };
}

for (const channel of ['rc', 'stable']) {
  test(`${channel} candidate survives separate checkouts and publishes the exact staged bytes`, (t) => {
    const f = fixture(t, channel);
    f.sealGate();
    assert.equal(f.publish().status, 'PASS');
    const relative = `staging-deploy/blue/coordination/blue-coordination-java/${f.version}/blue-coordination-java-${f.version}.jar`;
    assert.equal(sha(path.join(f.builds['25'], relative)), sha(path.join(f.checkouts.publish, 'build', relative)));
    assert.equal(f.git(f.checkouts.publish, 'rev-parse', 'HEAD'), f.env.RELEASE_COMMIT);
    assert.equal(f.git(f.checkouts.publish, 'status', '--porcelain'), '');
    const report = JSON.parse(fs.readFileSync(path.join(f.checkouts.publish,
      'build/reports/release/parallel-gates.json'), 'utf8'));
    assert.deepEqual(report.handoffSha256, f.hashes);
    assert.equal(report.binding.commit, f.env.RELEASE_COMMIT);
  });
}

test('rejects a modified candidate bundle before checkout', (t) => {
  const f = fixture(t);
  fs.appendFileSync(path.join(f.directory, 'candidate.bundle'), 'modified');
  assert.throws(() => restore(f.checkouts.publish, f.directory, f.expected), /bundle SHA mismatch/);
});

test('rejects a different candidate commit even with a valid bundle digest', (t) => {
  const f = fixture(t);
  assert.throws(() => restore(f.checkouts.publish, f.directory,
    { ...f.expected, commit: '0'.repeat(40) }), /Wrong candidate commit/);
});

for (const [label, mutate] of [
  ['missing suite', (scope) => { delete scope.suites.scenarioTest; }],
  ['skipped tests', (scope) => { scope.suites.test.skipped = 1; }],
  ['failed tests', (scope) => { scope.suites.test.failures = 1; }],
  ['filtered suite', (scope) => { scope.suites.test.fullTask = false; }],
  ['missing topology comparison', (scope) => { scope.topologyEvidenceVerified = false; }],
  ['wrong JDK', (scope) => { scope.javaVersion = '17'; }],
]) {
  test(`cannot seal ${label} as a passing gate`, (t) => {
    const f = fixture(t);
    f.mutate('25', 'reports/test-execution-scope/verifyReleaseTestExecutionScope.json', mutate);
    assert.throws(() => f.sealGate());
  });
}

test('rejects failed extracted-source verification', (t) => {
  const f = fixture(t);
  f.mutate('25', 'reports/contracts10/source-archive-verification.json', (data) => { data.focusedTestsStatus = 'FAIL'; });
  assert.throws(() => f.sealGate(), /focusedTestsStatus did not pass/);
});

test('rejects artifacts changed after staging', (t) => {
  const f = fixture(t);
  fs.appendFileSync(path.join(f.builds['25'], `staging-deploy/blue/coordination/blue-coordination-java/${f.version}/blue-coordination-java-${f.version}.jar`), 'changed');
  assert.throws(() => f.sealGate(), /Staged artifact differs/);
});

test('rejects a source archive changed after extracted-source verification', (t) => {
  const f = fixture(t);
  fs.appendFileSync(path.join(f.builds['25'], `distributions/blue-coordination-java-${f.version}-source.zip`), 'changed');
  assert.throws(() => f.sealGate(), /Source archive changed after verification/);
});

test('rejects stale RC readiness artifact hashes', (t) => {
  const f = fixture(t);
  f.mutate('25', `reports/release/${f.version}-readiness.json`, (data) => {
    data.artifacts.main.sha256 = '0'.repeat(64);
  });
  assert.throws(() => f.sealGate(), /Readiness artifact mismatch/);
});

test('rejects a corrupt handoff before restoring any publication files', (t) => {
  const f = fixture(t);
  f.sealGate();
  fs.appendFileSync(path.join(f.handoffs, 'java25.tar.gz'), 'changed');
  assert.throws(() => f.publish(), /Java 25 handoff SHA mismatch/);
  assert.equal(fs.existsSync(path.join(f.checkouts.publish, 'build')), false);
});

test('requires the successful Java 25 job output', (t) => {
  const f = fixture(t);
  f.sealGate();
  delete f.hashes['25'];
  assert.throws(() => f.publish(), /Missing Java 25 job SHA/);
  assert.equal(fs.existsSync(path.join(f.checkouts.publish, 'build')), false);
});

test('rejects evidence from a different workflow run', (t) => {
  const f = fixture(t);
  f.sealGate();
  f.env.GITHUB_RUN_ID = '54321';
  assert.throws(() => f.publish(), /candidate\/run mismatch/);
});

test('rejects evidence from a different source commit with the same version', (t) => {
  const f = fixture(t);
  f.sealGate();
  f.git(f.checkouts.publish, 'commit', '--quiet', '--allow-empty', '-m', 'Another candidate');
  f.env.RELEASE_COMMIT = f.git(f.checkouts.publish, 'rev-parse', 'HEAD');
  assert.throws(() => f.publish(), /candidate\/run mismatch/);
});

test('rejects an obsolete Java 17 handoff', (t) => {
  const f = fixture(t);
  assert.throws(() => seal(f.checkouts['25'], path.join(f.handoffs, 'old.tar.gz'), '17', f.env), /Invalid verification JDK/);
});

test('requires Java 25 staged module metadata', (t) => {
  const f = fixture(t);
  fs.unlinkSync(path.join(f.builds['25'], 'publications/mavenJava/module.json'));
  assert.throws(() => f.sealGate());
});

test('Java 25 verification depends on preparation and publication waits for its handoff', () => {
  const workflow = fs.readFileSync(path.join(__dirname, '../workflows/release-candidate.yml'), 'utf8');
  const job = (name) => workflow.match(new RegExp(`^  ${name}:\\n([\\s\\S]*?)(?=^  \\w+:|$(?![\\s\\S]))`, 'm'))?.[1];
  for (const java of ['25']) {
    const body = job(`java${java}`);
    assert.match(body, /    needs: prepare\n/);
    assert.doesNotMatch(body, /continue-on-error|--tests|\s-x\s/);
    assert.match(body, /uses: .\/.github\/workflows\/verification.yml/);
    assert.match(body, /release-source: true/);
  }
  assert.equal(job('java17'), undefined);
  assert.equal(job('java21'), undefined);
  const publish = job('publish');
  assert.match(publish, /needs:\n      - prepare\n      - java25/);
  assert.doesNotMatch(publish.slice(0, publish.indexOf('    steps:')), /if:|continue-on-error/);
  assert.ok(publish.indexOf('release-handoff.js verify') < publish.indexOf('jreleaserDeploy -x stageRelease'));
  const rc = fs.readFileSync(path.join(__dirname, '../workflows/release-rc.yml'), 'utf8');
  const stable = fs.readFileSync(path.join(__dirname, '../workflows/release.yml'), 'utf8');
  assert.match(rc, /github.ref == 'refs\/heads\/next'/);
  assert.match(stable, /github.ref == 'refs\/heads\/main'/);
  for (const wrapper of [rc, stable]) assert.match(wrapper, /uses: .\/.github\/workflows\/release-candidate.yml/);
});

test('the shared preparation refuses feature branches and RC versions on stable', (t) => {
  const f = fixture(t);
  const workflow = fs.readFileSync(path.join(__dirname, '../workflows/release-candidate.yml'), 'utf8');
  const step = workflow.split('      - name: Require the release branch\n')[1].split('      - name:')[0];
  const command = step.split('        run: |-\n')[1].split('\n')
    .filter((line) => line.startsWith('          ')).map((line) => line.slice(10)).join('\n');
  assert.ok(command.includes('case "$RELEASE_CHANNEL"'), 'Missing executable branch guard');
  const run = (channel, ref) => spawnSync('bash', ['-euo', 'pipefail', '-c', command], {
    cwd: f.source, env: { ...process.env, RELEASE_CHANNEL: channel, GITHUB_REF: ref }, encoding: 'utf8',
  }).status;
  assert.equal(run('rc', 'refs/heads/next'), 0);
  assert.notEqual(run('rc', 'refs/heads/codex/feature'), 0);
  assert.notEqual(run('stable', 'refs/heads/codex/feature'), 0);
  assert.notEqual(run('stable', 'refs/heads/main'), 0); // RC version in .cz.toml
  write(f.source, '.cz.toml', 'version = "3.0.0"\n');
  assert.equal(run('stable', 'refs/heads/main'), 0);
  assert.notEqual(run('unknown', 'refs/heads/next'), 0);
});


test('CI uses only Java 25 while published bytecode remains Java 17', () => {
  const build = fs.readFileSync(path.join(__dirname, '../../build.gradle'), 'utf8');
  assert.match(build, /toolchain \{ languageVersion = JavaLanguageVersion.of\(25\)/);
  assert.match(build, /sourceCompatibility = JavaVersion.VERSION_17/);
  assert.match(build, /targetCompatibility = JavaVersion.VERSION_17/);
  assert.match(build, /options.release = 17/);
  assert.doesNotMatch(build, /JavaLanguageVersion.of\(17\)|JavaLanguageVersion.of\(21\)/);
  for (const file of ['../actions/setup-release/action.yml', '../workflows/ci-release-experiment.yml']) {
    const text = fs.readFileSync(path.join(__dirname, file), 'utf8');
    assert.match(text, /java-version: '25'/);
    assert.doesNotMatch(text, /JAVA_HOME_(17|21)_X64|java-version: '(17|21)/);
  }
});


test('production verification runs full core and archive independently and checks the sealed handoff', () => {
  const shared = fs.readFileSync(path.join(__dirname, '../workflows/verification.yml'), 'utf8');
  assert.match(shared, /  archive:\n/);
  assert.match(shared, /  core:\n/);
  assert.doesNotMatch(shared, /needs:|continue-on-error|--tests|\s-x\s/);
  assert.match(shared, /ci-verification.py run core/);
  assert.match(shared, /ci-verification.py run archive/);
  assert.match(shared, /release-handoff.js seal/);
  assert.match(shared, /coordination-archive-\$\{\{ inputs.scope \}\}-\$\{\{ github.run_attempt \}\}/);
  const topology = fs.readFileSync(path.join(__dirname, '../workflows/verify-production-topology.yml'), 'utf8');
  assert.match(topology, /needs: \[prepare, verify\]/);
  assert.match(topology, /release-handoff.js verify/);
  assert.doesNotMatch(topology, /secrets\.|git push|git tag|jreleaserDeploy/);
  const build = fs.readFileSync(path.join(__dirname, '../workflows/build.yml'), 'utf8');
  assert.match(build, /prepare-verification-source.yml/);
  assert.match(build, /uses: .\/.github\/workflows\/verification.yml/);
});

test('prepared verification source restores without creating or requiring a release tag', (t) => {
  const f = fixture(t, 'stable');
  write(f.source, '.cz.toml', 'version = "3.0.0-rc.11"\n');
  f.git(f.source, 'add', '.cz.toml');
  f.git(f.source, 'commit', '--quiet', '-m', 'Prepared verification source');
  const directory = path.join(f.root, 'verification-source');
  const script = path.join(__dirname, 'release-handoff.js');
  const source = JSON.parse(execFileSync(process.execPath, [script, 'source', directory], {
    cwd:f.source, env:{...process.env, ...f.env}, encoding:'utf8',
  }));
  assert.equal(source.version, '3.0.0-rc.11');
  assert.notEqual(source.commit, f.expected.commit);
  execFileSync(process.execPath, [script, 'restore-source', directory], {
    cwd:f.checkouts.publish, env:{...process.env, ...f.env, RELEASE_COMMIT:source.commit,
      RELEASE_VERSION:source.version, RELEASE_BUNDLE_SHA:source['bundle-sha']}, encoding:'utf8',
  });
  assert.equal(f.git(f.checkouts.publish, 'rev-parse', 'HEAD'), source.commit);
  assert.equal(f.git(f.checkouts.publish, 'tag', '--list'), '');
});
