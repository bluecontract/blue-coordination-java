const assert = require('node:assert/strict');
const test = require('node:test');

const {
  assertAuthorityRelease,
  authorityRelease,
  nextVersionForCurrentRc,
  parseVersion,
} = require('./prepare-rc-release.js');

test('parses stable and RC versions', () => {
  assert.deepEqual(parseVersion('3.0.0'), {
    major: 3,
    minor: 0,
    patch: 0,
    rc: null,
  });
  assert.deepEqual(parseVersion('3.0.0-rc.12'), {
    major: 3,
    minor: 0,
    patch: 0,
    rc: 12,
  });
});

test('keeps an intentional RC when its tag does not exist yet', () => {
  assert.equal(nextVersionForCurrentRc('3.0.0-rc.1', 0), '3.0.0-rc.1');
});

test('advances an RC after the current tag exists', () => {
  assert.equal(nextVersionForCurrentRc('3.0.0-rc.1', 1), '3.0.0-rc.2');
  assert.equal(nextVersionForCurrentRc('3.0.0-rc.1', 8), '3.0.0-rc.9');
  assert.equal(nextVersionForCurrentRc('3.0.0-rc.6', 6), '3.0.0-rc.7');
  assert.equal(nextVersionForCurrentRc('3.0.0-rc.7', 7), '3.0.0-rc.8');
  assert.equal(nextVersionForCurrentRc('3.0.0-rc.8', 8), '3.0.0-rc.9');
  assert.equal(nextVersionForCurrentRc('3.0.0-rc.9', 9), '3.0.0-rc.10');
  assert.equal(nextVersionForCurrentRc('3.0.0-rc.10', 10), '3.0.0-rc.11');
  assert.equal(nextVersionForCurrentRc('3.0.0-rc.11', 11), '3.0.0-rc.12');
});

test('reads the release bound by the current authority', () => {
  assert.equal(authorityRelease('RC7_VERSION: 3.0.0-rc.7\n'), '3.0.0-rc.7');
  assert.equal(authorityRelease('RC8_VERSION: 3.0.0-rc.8\n'), '3.0.0-rc.8');
  assert.equal(authorityRelease('RC9_VERSION: 3.0.0-rc.9\n'), '3.0.0-rc.9');
  assert.equal(authorityRelease('RC10_VERSION: 3.0.0-rc.10\n'), '3.0.0-rc.10');
  assert.equal(authorityRelease('RC11_VERSION: 3.0.0-rc.11\n'), '3.0.0-rc.11');
  assert.equal(authorityRelease('RC12_VERSION: 3.0.0-rc.12\n'), '3.0.0-rc.12');
  assert.throws(
    () => authorityRelease('# missing marker\n'),
    /Release authority is missing RC version/,
  );
});

test('rejects a prepared RC that differs from its authority', () => {
  assert.doesNotThrow(() => assertAuthorityRelease(
    '3.0.0-rc.7',
    'RC7_VERSION: 3.0.0-rc.7\n',
  ));
  assert.throws(
    () => assertAuthorityRelease(
      '3.0.0-rc.8',
      'RC7_VERSION: 3.0.0-rc.7\n',
    ),
    /Prepared RC 3\.0\.0-rc\.8 does not match authorized release 3\.0\.0-rc\.7/,
  );
});

test('binds the next RC without authorizing a later candidate', () => {
  assert.doesNotThrow(() => assertAuthorityRelease(
    '3.0.0-rc.10',
    'RC10_VERSION: 3.0.0-rc.10\n',
  ));
  assert.throws(
    () => assertAuthorityRelease(
      '3.0.0-rc.11',
      'RC10_VERSION: 3.0.0-rc.10\n',
    ),
    /Prepared RC 3\.0\.0-rc\.11 does not match authorized release 3\.0\.0-rc\.10/,
  );
});

test('authorizes RC11 without authorizing RC12', () => {
  assert.doesNotThrow(() => assertAuthorityRelease(
    '3.0.0-rc.11',
    'RC11_VERSION: 3.0.0-rc.11\n',
  ));
  assert.throws(
    () => assertAuthorityRelease(
      '3.0.0-rc.12',
      'RC11_VERSION: 3.0.0-rc.11\n',
    ),
    /Prepared RC 3\.0\.0-rc\.12 does not match authorized release 3\.0\.0-rc\.11/,
  );
});

test('authorizes RC12 without authorizing RC13', () => {
  assert.doesNotThrow(() => assertAuthorityRelease(
    '3.0.0-rc.12',
    'RC12_VERSION: 3.0.0-rc.12\n',
  ));
  assert.throws(
    () => assertAuthorityRelease(
      '3.0.0-rc.13',
      'RC12_VERSION: 3.0.0-rc.12\n',
    ),
    /Prepared RC 3\.0\.0-rc\.13 does not match authorized release 3\.0\.0-rc\.12/,
  );
});

test('repository authority prepares RC15 after RC14 and rejects RC16', () => {
  const fs = require('node:fs');
  const os = require('node:os');
  const path = require('node:path');
  const { execFileSync, spawnSync } = require('node:child_process');
  const root = path.resolve(__dirname, '../..');
  const fixture = fs.mkdtempSync(path.join(os.tmpdir(), 'coordination-rc-authority-'));
  try {
    fs.cpSync(path.join(root, 'docs/releases'), path.join(fixture, 'docs/releases'), { recursive: true });
    fs.writeFileSync(path.join(fixture, '.cz.toml'), 'version = "3.0.0-rc.14"\n');
    const git = args => execFileSync('git', args, { cwd: fixture, stdio: 'pipe' });
    git(['init']);
    git(['config', 'tag.gpgSign', 'false']);
    git(['-c', 'user.name=CI Test', '-c', 'user.email=ci@example.invalid', 'commit', '--allow-empty', '-m', 'fixture']);
    git(['tag', 'v3.0.0-rc.14']);
    const run = () => spawnSync(process.execPath, [path.join(__dirname, 'prepare-rc-release.js')], {
      cwd: fixture, encoding: 'utf8', env: { ...process.env, GITHUB_OUTPUT: '' },
    });
    const prepared = run();
    assert.equal(prepared.status, 0, prepared.stderr);
    assert.equal(fs.readFileSync(path.join(fixture, '.cz.toml'), 'utf8'), 'version = "3.0.0-rc.15"\n');
    git(['tag', 'v3.0.0-rc.15']);
    const rejected = run();
    assert.notEqual(rejected.status, 0);
    assert.match(rejected.stderr, /Prepared RC 3\.0\.0-rc\.16 does not match authorized release 3\.0\.0-rc\.15/);
    assert.equal(fs.readFileSync(path.join(fixture, '.cz.toml'), 'utf8'), 'version = "3.0.0-rc.15"\n');
  } finally {
    fs.rmSync(fixture, { recursive: true, force: true });
  }
});
