#!/usr/bin/env node

// Only successful release jobs seal a handoff. The downstream job verifies the
// archive SHA from that job's output before reading or restoring any of it.
const assert = require('node:assert/strict');
const { execFileSync } = require('node:child_process');
const { createHash } = require('node:crypto');
const fs = require('node:fs');
const path = require('node:path');

const SUITES = ['test', 'integrationTest', 'consumerTest', 'scenarioTest'];
const EVIDENCE_DIRS = ['libs', 'distributions', 'publications', 'reports',
  'test-results', 'rooted-evidence'];
const MANIFEST = 'release-handoff.json';

function git(root, ...args) {
  return execFileSync('git', args, {
    cwd: root, encoding: 'utf8', stdio: ['ignore', 'pipe', 'pipe'],
  }).trim();
}

function sha(file) {
  assert.ok(fs.lstatSync(file).isFile(), `Expected a regular file: ${file}`);
  return createHash('sha256').update(fs.readFileSync(file)).digest('hex');
}

function json(file) {
  return JSON.parse(fs.readFileSync(file, 'utf8'));
}

function version(root) {
  const value = fs.readFileSync(path.join(root, '.cz.toml'), 'utf8')
    .match(/^version\s*=\s*"([^"]+)"/m)?.[1];
  assert.match(value || '', /^\d+\.\d+\.\d+(?:-rc\.\d+)?$/, 'Invalid release version');
  return value;
}

function clean(root) {
  assert.equal(git(root, 'status', '--porcelain'), '', 'Release worktree must be clean');
}

function candidate(root, directory, channel) {
  clean(root);
  assert.ok(['rc', 'stable'].includes(channel), 'Invalid release channel');
  const release = version(root);
  const commit = git(root, 'rev-parse', 'HEAD');
  const refs = ['HEAD'];
  if (channel === 'rc') {
    const tag = `refs/tags/v${release}`;
    assert.equal(git(root, 'rev-parse', `${tag}^{commit}`), commit);
    refs.push(tag);
  }
  fs.mkdirSync(directory, { recursive: true });
  const bundle = path.join(directory, 'candidate.bundle');
  git(root, 'bundle', 'create', bundle, ...refs);
  return { commit, version: release, 'bundle-sha': sha(bundle) };
}

function restore(root, directory, expected) {
  const bundle = path.join(directory, 'candidate.bundle');
  assert.equal(sha(bundle), expected.bundleSha, 'Candidate bundle SHA mismatch');
  assert.match(expected.commit, /^[0-9a-f]{40}$/, 'Invalid candidate commit');
  git(root, 'bundle', 'verify', bundle);
  git(root, 'fetch', '--no-tags', bundle, 'HEAD');
  assert.equal(git(root, 'rev-parse', 'FETCH_HEAD'), expected.commit, 'Wrong candidate commit');
  git(root, 'checkout', '--detach', expected.commit);
  assert.equal(version(root), expected.version, 'Wrong candidate version');
  if (expected.channel === 'rc') {
    const tag = `refs/tags/v${expected.version}`;
    git(root, 'fetch', '--no-tags', bundle, `${tag}:${tag}`);
    assert.equal(git(root, 'rev-parse', `${tag}^{commit}`), expected.commit, 'Wrong release tag');
  }
  clean(root);
}

function identity(root, env) {
  clean(root);
  assert.ok(['rc', 'stable'].includes(env.RELEASE_CHANNEL), 'Invalid release channel');
  assert.match(env.GITHUB_RUN_ID || '', /^\d+$/, 'Missing workflow run identity');
  const commit = git(root, 'rev-parse', 'HEAD');
  assert.equal(commit, env.RELEASE_COMMIT, 'Wrong source commit');
  const release = version(root);
  assert.equal(release, env.RELEASE_VERSION, 'Wrong release version');
  return {
    commit, tree: git(root, 'rev-parse', 'HEAD^{tree}'), version: release,
    channel: env.RELEASE_CHANNEL, runId: env.GITHUB_RUN_ID,
    dependencyLockSha256: sha(path.join(root, 'gradle/published-artifact.lockfile')),
  };
}

function inspectBuild(build, binding, java) {
  assert.ok(['17', '21'].includes(java), 'Invalid verification JDK');
  const scope = json(path.join(build, 'reports/test-execution-scope/verifyReleaseTestExecutionScope.json'));
  assert.equal(scope.status, 'PASS', 'Release execution scope did not pass');
  assert.equal(scope.coordinationVersion, binding.version, 'Scope version mismatch');
  assert.equal(scope.javaVersion, java, 'Scope JDK mismatch');
  assert.equal(scope.topologyEvidenceVerified, true, 'Topology comparison did not pass');
  assert.deepEqual(Object.keys(scope.suites).sort(), [...SUITES].sort(), 'Missing release suite');
  const testInventory = {};
  for (const suite of SUITES) {
    const result = scope.suites[suite];
    assert.equal(result.passed, true, `${suite} did not pass`);
    assert.equal(result.fullTask, true, `${suite} was filtered or excluded`);
    assert.equal(result.failures, 0, `${suite} failed`);
    assert.equal(result.skipped, 0, `${suite} skipped tests`);
    assert.ok(result.testCases.length > 0, `${suite} has no tests`);
    assert.equal(result.executedTests, result.testCases.length);
    assert.deepEqual(result.executedClasses, result.expectedClasses, `${suite} class inventory changed`);
    testInventory[suite] = result.testCases.map((testCase) => {
      assert.equal(testCase.failed, false);
      assert.equal(testCase.skipped, false);
      return JSON.stringify([testCase.className, testCase.name]);
    }).sort();
  }

  const archive = json(path.join(build, 'reports/contracts10/source-archive-verification.json'));
  assert.equal(archive.coordinationVersion, binding.version);
  assert.equal(archive.dependencyMode, 'published-artifact');
  assert.equal(archive.java, java, 'Extracted-source JDK mismatch');
  for (const field of ['extractedConfiguration', 'compileStatus',
    'dependencyIsolationStatus', 'focusedTestsStatus']) {
    assert.equal(archive[field], 'PASS', `Extracted-source ${field} did not pass`);
  }
  assert.ok(archive.focusedTests.length > 0, 'Missing extracted-source test inventory');
  const archiveName = `blue-coordination-java-${binding.version}-source.zip`;
  assert.equal(archive.archiveName, archiveName);
  assert.equal(sha(path.join(build, 'distributions', archiveName)), archive.archiveSha256,
    'Source archive changed after verification');

  const stem = `blue-coordination-java-${binding.version}`;
  const artifacts = {};
  for (const suffix of ['', '-sources', '-javadoc', '-test-fixtures']) {
    const file = `libs/${stem}${suffix}.jar`;
    artifacts[file] = sha(path.join(build, file));
  }
  for (const file of ['publications/mavenJava/pom-default.xml', `distributions/${archiveName}`]) {
    artifacts[file] = sha(path.join(build, file));
  }

  let stagedModuleSha256;
  if (java === '17') {
    const staged = path.join(build, 'staging-deploy/blue/coordination/blue-coordination-java', binding.version);
    for (const [file, hash] of Object.entries(artifacts)) {
      if (file.startsWith('distributions/')) continue;
      const name = file.endsWith('pom-default.xml') ? `${stem}.pom`
        : path.basename(file);
      assert.equal(sha(path.join(staged, name)), hash, `Staged artifact differs: ${name}`);
    }
    // Gradle generates module metadata during staging, not in releaseCheck.
    stagedModuleSha256 = sha(path.join(build, 'publications/mavenJava/module.json'));
    assert.equal(sha(path.join(staged, `${stem}.module`)), stagedModuleSha256,
      'Staged Gradle module metadata differs');
    if (binding.channel === 'rc') {
      const readiness = json(path.join(build, `reports/release/${binding.version}-readiness.json`));
      assert.equal(readiness.release, binding.version);
      assert.equal(readiness.status, 'PASS_FOR_BOUNDED_EXTERNAL_PILOT');
      assert.equal(readiness.dependencyMode, 'published-artifact');
      assert.deepEqual(Object.values(readiness.artifacts).map((item) => [item.file, item.sha256]).sort(),
        Object.entries(artifacts).filter(([file]) => file.startsWith('libs/'))
          .map(([file, hash]) => [path.basename(file), hash]).sort(), 'Readiness artifact mismatch');
    }
  }
  return { schema: 'coordination-release-handoff/1', binding, java, artifacts,
    testInventory, focusedTests: [...archive.focusedTests].sort(),
    ...(java === '17' ? { stagedModuleSha256 } : {}) };
}

function regularTree(directory) {
  assert.ok(fs.lstatSync(directory).isDirectory(), `Missing evidence directory: ${directory}`);
  for (const item of fs.readdirSync(directory)) {
    const file = path.join(directory, item);
    const stat = fs.lstatSync(file);
    if (stat.isDirectory()) regularTree(file);
    else assert.ok(stat.isFile(), `Non-regular handoff entry: ${file}`);
  }
}

function seal(root, output, java, env) {
  const build = path.join(root, 'build');
  const binding = identity(root, env);
  const receipt = inspectBuild(build, binding, java);
  fs.writeFileSync(path.join(build, MANIFEST), `${JSON.stringify(receipt, null, 2)}\n`);
  const directories = [...EVIDENCE_DIRS, ...(java === '17' ? ['staging-deploy'] : [])];
  for (const directory of directories) regularTree(path.join(build, directory));
  execFileSync('tar', ['-czf', output, '-C', root, `build/${MANIFEST}`,
    ...directories.map((directory) => `build/${directory}`)]);
  return { 'handoff-sha': sha(output) };
}

function verify(root, directory, expectedHashes, env) {
  const binding = identity(root, env);
  const receipts = {};
  for (const java of ['17', '21']) {
    const archive = path.join(directory, `java${java}.tar.gz`);
    assert.match(expectedHashes[java] || '', /^[0-9a-f]{64}$/, `Missing Java ${java} job SHA`);
    assert.equal(sha(archive), expectedHashes[java], `Java ${java} handoff SHA mismatch`);
    const extracted = path.join(directory, `java${java}`);
    fs.mkdirSync(extracted); // Refuse reuse of a previously extracted handoff.
    execFileSync('tar', ['-xzf', archive, '-C', extracted]);
    const build = path.join(extracted, 'build');
    regularTree(build);
    const receipt = json(path.join(build, MANIFEST));
    assert.deepEqual(receipt.binding, binding, `Java ${java} candidate/run mismatch`);
    assert.deepEqual(receipt, inspectBuild(build, binding, java), `Java ${java} evidence mismatch`);
    receipts[java] = receipt;
  }
  for (const field of ['artifacts', 'testInventory', 'focusedTests']) {
    assert.deepEqual(receipts['17'][field], receipts['21'][field], `JDK gates differ: ${field}`);
  }
  assert.ok(!fs.existsSync(path.join(root, 'build')), 'Refusing to overwrite local build outputs');
  fs.cpSync(path.join(directory, 'java17/build'), path.join(root, 'build'), { recursive: true });
  const report = { schema: 'coordination-release-gates/1', status: 'PASS', binding,
    handoffSha256: expectedHashes, artifacts: receipts['17'].artifacts,
    testCounts: Object.fromEntries(Object.entries(receipts['17'].testInventory)
      .map(([suite, tests]) => [suite, tests.length])) };
  const reportDirectory = path.join(root, 'build/reports/release');
  fs.mkdirSync(reportDirectory, { recursive: true });
  fs.writeFileSync(path.join(reportDirectory, 'parallel-gates.json'), `${JSON.stringify(report, null, 2)}\n`);
  clean(root);
  return { commit: binding.commit, version: binding.version, status: 'PASS' };
}

if (require.main === module) {
  const [command, location, java] = process.argv.slice(2);
  const root = process.cwd();
  const env = process.env;
  let output;
  if (command === 'candidate') output = candidate(root, location, env.RELEASE_CHANNEL);
  else if (command === 'restore') restore(root, location, {
    bundleSha: env.RELEASE_BUNDLE_SHA, commit: env.RELEASE_COMMIT,
    version: env.RELEASE_VERSION, channel: env.RELEASE_CHANNEL,
  });
  else if (command === 'seal') output = seal(root, location, java, env);
  else if (command === 'verify') output = verify(root, location, {
    17: env.JAVA17_HANDOFF_SHA, 21: env.JAVA21_HANDOFF_SHA,
  }, env);
  else throw new Error(`Unknown release handoff command: ${command}`);
  if (output) {
    console.log(JSON.stringify(output));
    if (env.GITHUB_OUTPUT) {
      for (const [key, value] of Object.entries(output)) {
        fs.appendFileSync(env.GITHUB_OUTPUT, `${key}=${value}\n`);
      }
    }
  }
}

module.exports = { candidate, restore, identity, inspectBuild, seal, verify, sha };
