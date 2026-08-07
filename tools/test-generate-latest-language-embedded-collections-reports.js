#!/usr/bin/env node

'use strict';

const assert = require('assert');
const fs = require('fs');
const os = require('os');
const path = require('path');
const {
  generateReports,
  validateManifest,
} = require('./generate-latest-language-embedded-collections-reports');

const fixturePath = path.join(
  __dirname,
  '..',
  'src',
  'test',
  'resources',
  'coordination',
  'latest-language-embedded-collections-run.fixture.json'
);
const fixture = JSON.parse(fs.readFileSync(fixturePath, 'utf8'));
const outputDirectory = fs.mkdtempSync(
  path.join(os.tmpdir(), 'blue-coordination-report-')
);

const reports = generateReports(fixture, outputDirectory);
assert.strictEqual(
  reports['final.json'].releaseEligible,
  true,
  'a complete same-run fixture must be release eligible'
);
assert.strictEqual(
  reports['final.json'].ordinaryTestTotals.executed,
  10,
  'ordinary executed totals must be derived from the fixture run'
);
assert.strictEqual(
  reports['final.json'].collectionSpecificTestTotals.executed,
  6,
  'collection totals must be derived independently'
);
assert.strictEqual(
  reports['final.json'].run.id,
  fixture.run.id,
  'every report must retain the exact run identity'
);
[
  'dependency-lock.json',
  'final.json',
  'fragmentation.json',
  'migration.json',
  'performance.json',
  'subscriptions.json',
].forEach((name) => {
  assert.strictEqual(
    fs.existsSync(path.join(outputDirectory, name)),
    true,
    `${name} must be generated`
  );
});

const red = JSON.parse(JSON.stringify(fixture));
red.tests.status = 'failed';
red.tests.ordinary.failed = 1;
red.tests.ordinary.passed = 9;
red.tests.ordinary.unclassified = 1;
red.tests.failureClassifications = {
  failed: 1,
  classified: 0,
  unclassified: 1,
  external: 0,
  coordinationOwned: 0,
  categories: [],
  unknown: [
    {
      testId: 'fixture#shouldFail',
      type: 'AssertionError',
      messageExcerpt: 'fixture failure',
      messageSha256:
        'aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa',
    },
  ],
};
assert.strictEqual(
  generateReports(red, outputDirectory)['final.json'].releaseEligible,
  false,
  'a failed same-run test must make the final report ineligible'
);

const mixedRun = JSON.parse(JSON.stringify(fixture));
mixedRun.performance.runId = 'different-run';
assert.throws(
  () => validateManifest(mixedRun),
  /performance\.runId does not match run\.id/,
  'mixed-run evidence must fail before any release conclusion is derived'
);

const unexplained = JSON.parse(JSON.stringify(fixture));
unexplained.performance.status = 'notExecuted';
assert.throws(
  () => validateManifest(unexplained),
  /performance\.reason must be non-empty text/,
  'a non-executed gate must retain its exact reason'
);
