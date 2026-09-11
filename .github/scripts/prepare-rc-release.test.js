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
});

test('reads the release bound by the current authority', () => {
  assert.equal(authorityRelease('RC7_VERSION: 3.0.0-rc.7\n'), '3.0.0-rc.7');
  assert.equal(authorityRelease('RC8_VERSION: 3.0.0-rc.8\n'), '3.0.0-rc.8');
  assert.equal(authorityRelease('RC9_VERSION: 3.0.0-rc.9\n'), '3.0.0-rc.9');
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
    '3.0.0-rc.9',
    'RC9_VERSION: 3.0.0-rc.9\n',
  ));
  assert.throws(
    () => assertAuthorityRelease(
      '3.0.0-rc.10',
      'RC9_VERSION: 3.0.0-rc.9\n',
    ),
    /Prepared RC 3\.0\.0-rc\.10 does not match authorized release 3\.0\.0-rc\.9/,
  );
});
