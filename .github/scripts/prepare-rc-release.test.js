const assert = require('node:assert/strict');
const test = require('node:test');

const {
  assertEvidenceRelease,
  evidenceRelease,
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
});

test('reads the release bound by canonical evidence', () => {
  assert.equal(evidenceRelease('{"release":"3.0.0-rc.1"}'), '3.0.0-rc.1');
  assert.throws(
    () => evidenceRelease('{}'),
    /Canonical evidence is missing a release/,
  );
});

test('rejects a prepared RC that differs from canonical evidence', () => {
  assert.doesNotThrow(() => assertEvidenceRelease(
    '3.0.0-rc.1',
    '{"release":"3.0.0-rc.1"}',
  ));
  assert.throws(
    () => assertEvidenceRelease(
      '2.0.0-rc.9',
      '{"release":"3.0.0-rc.1"}',
    ),
    /Prepared RC 2\.0\.0-rc\.9 does not match canonical evidence release 3\.0\.0-rc\.1/,
  );
});
