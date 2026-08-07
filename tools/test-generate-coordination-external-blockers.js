#!/usr/bin/env node

'use strict';

const assert = require('assert');
const fs = require('fs');
const os = require('os');
const path = require('path');
const {
  generateCatalog,
  normalizeTestIdentity,
  writeCatalog,
} = require('./generate-coordination-external-blockers');

const BEHAVIOR_FIXTURE_CLASS =
  'blue.coordination.processor.CoordinationBehaviorFixtureHarnessTest';
const NODE_PROVIDER_MESSAGE =
  'java.lang.NoClassDefFoundError: blue/language/NodeProvider';
const NODE_PROVIDER_FAILURE =
  `\n    <failure message="${NODE_PROVIDER_MESSAGE}" ` +
  `type="java.lang.NoClassDefFoundError">${NODE_PROVIDER_MESSAGE}</failure>`;
const HISTORICAL_MESSAGE =
  'java.lang.IllegalArgumentException: Historical registry source ' +
  'src/main/resources/registry/blue-contracts-1.0/Handler.blue failed: ' +
  'Provider returned content with BlueId calculated-id for requested ' +
  'BlueId requested-id.';
const HISTORICAL_FAILURE =
  `\n    <failure message="${HISTORICAL_MESSAGE}" ` +
  'type="java.lang.IllegalArgumentException">' +
  'java.lang.IllegalArgumentException: historical mismatch</failure>';

function suite(testCases, counts = {}) {
  const skipped = counts.skipped || 0;
  const failures = counts.failures === undefined
    ? testCases.filter((testCase) => testCase.includes('<failure')).length
    : counts.failures;
  const errors = counts.errors || 0;
  return `<?xml version="1.0" encoding="UTF-8"?>
<testsuite name="fixture" tests="${testCases.length}" ` +
    `skipped="${skipped}" failures="${failures}" errors="${errors}">
${testCases.join('\n')}
</testsuite>
`;
}

function testcase(className, name, outcome = '') {
  return outcome
    ? `  <testcase name="${name}" classname="${className}">${outcome}
  </testcase>`
    : `  <testcase name="${name}" classname="${className}"/>`;
}

function fixtureDirectory() {
  return fs.mkdtempSync(
    path.join(os.tmpdir(), 'blue-coordination-blocker-catalog-')
  );
}

function writeResult(directory, name, xml) {
  fs.writeFileSync(path.join(directory, `TEST-${name}.xml`), xml, 'utf8');
}

assert.strictEqual(
  normalizeTestIdentity('fixture.ExampleTest', 'shouldWork()'),
  'fixture.ExampleTest#shouldWork'
);
assert.strictEqual(
  normalizeTestIdentity(BEHAVIOR_FIXTURE_CLASS, '19: coord-case@references'),
  `${BEHAVIOR_FIXTURE_CLASS}#coord-case@references`
);
assert.strictEqual(
  normalizeTestIdentity('fixture.ExampleTest', '19: shouldRemainDisplayed()'),
  'fixture.ExampleTest#19: shouldRemainDisplayed'
);

const valid = fixtureDirectory();
writeResult(
  valid,
  'z-last',
  suite([
    testcase('fixture.ZTest', 'shouldPass()'),
    testcase('fixture.ZTest', 'shouldFindRemovedAbi()', NODE_PROVIDER_FAILURE),
  ])
);
writeResult(
  valid,
  'a-first',
  suite([
    testcase(
      'fixture.ATest',
      'shouldAlsoFindRemovedAbi()',
      NODE_PROVIDER_FAILURE
    ),
    testcase(
      BEHAVIOR_FIXTURE_CLASS,
      '7: coord-historical@references',
      HISTORICAL_FAILURE
    ),
  ])
);

const catalog = generateCatalog(valid);
assert.strictEqual(
  catalog.schema,
  'blue-coordination/external-blockers/1.2'
);
assert.deepStrictEqual(catalog.expectedSuite, {
  full: 4,
  working: 1,
  probes: 3,
});
assert.deepStrictEqual(
  catalog.blockers.map((blocker) => blocker.id),
  [
    'repository-node-provider-abi',
    'repository-historical-registry-blueid-mismatch',
  ]
);
assert.strictEqual(
  catalog.blockers[0].failureType,
  'java.lang.NoClassDefFoundError'
);
assert.strictEqual(
  catalog.blockers[0].logicalMessagePrefix,
  'blue/language/NodeProvider'
);
assert.deepStrictEqual(catalog.blockers[0].probes, [
  { test: 'fixture.ATest#shouldAlsoFindRemovedAbi' },
  { test: 'fixture.ZTest#shouldFindRemovedAbi' },
]);
assert.deepStrictEqual(catalog.blockers[1].probes, [
  {
    test:
      `${BEHAVIOR_FIXTURE_CLASS}#coord-historical@references`,
  },
]);

const allGreen = fixtureDirectory();
writeResult(
  allGreen,
  'all-green',
  suite([
    testcase('fixture.GreenTest', 'shouldPassFirst()'),
    testcase('fixture.GreenTest', 'shouldPassSecond()'),
  ])
);
assert.deepStrictEqual(generateCatalog(allGreen), {
  schema: 'blue-coordination/external-blockers/1.2',
  expectedSuite: {
    full: 2,
    working: 2,
    probes: 0,
  },
  blockers: [],
});

const firstOutput = path.join(valid, 'first.json');
const secondOutput = path.join(valid, 'second.json');
writeCatalog(catalog, firstOutput);
writeCatalog(generateCatalog(valid), secondOutput);
assert.strictEqual(
  fs.readFileSync(firstOutput, 'utf8'),
  fs.readFileSync(secondOutput, 'utf8'),
  'catalog output must be deterministic'
);

const skipped = fixtureDirectory();
writeResult(
  skipped,
  'skipped',
  suite(
    [testcase('fixture.SkipTest', 'shouldNeverSkip()', '\n    <skipped/>')],
    { skipped: 1 }
  )
);
assert.throws(
  () => generateCatalog(skipped),
  /skipped tests are forbidden: fixture\.SkipTest#shouldNeverSkip/
);

const duplicate = fixtureDirectory();
writeResult(
  duplicate,
  'duplicate',
  suite([
    testcase('fixture.DuplicateTest', 'shouldBeUnique'),
    testcase('fixture.DuplicateTest', 'shouldBeUnique()'),
    testcase(
      'fixture.DuplicateTest',
      'shouldExposeFailure()',
      NODE_PROVIDER_FAILURE
    ),
  ])
);
assert.throws(
  () => generateCatalog(duplicate),
  /duplicate normalized test identity: fixture\.DuplicateTest#shouldBeUnique/
);

const unknown = fixtureDirectory();
writeResult(
  unknown,
  'unknown',
  suite([
    testcase(
      'fixture.UnknownTest',
      'shouldRejectUnknown()',
      '\n    <failure message="unexpected Coordination defect" ' +
        'type="java.lang.AssertionError">unexpected</failure>'
    ),
  ])
);
assert.throws(
  () => generateCatalog(unknown),
  /unclassified failure fixture\.UnknownTest#shouldRejectUnknown/
);

const lookalike = fixtureDirectory();
writeResult(
  lookalike,
  'lookalike',
  suite([
    testcase(
      'fixture.LookalikeTest',
      'shouldRequireExactFailureType()',
      '\n    <failure message="java.lang.AssertionError: ' +
        'blue/language/NodeProvider" type="java.lang.AssertionError">' +
        'lookalike</failure>'
    ),
  ])
);
assert.throws(
  () => generateCatalog(lookalike),
  /unclassified failure fixture\.LookalikeTest#shouldRequireExactFailureType/
);

const malformed = fixtureDirectory();
writeResult(
  malformed,
  'malformed',
  suite(
    [
      testcase(
        'fixture.MalformedTest',
        'shouldRejectBadCounts()',
        NODE_PROVIDER_FAILURE
      ),
    ],
    { failures: 0 }
  )
);
assert.throws(
  () => generateCatalog(malformed),
  /declares failures=0 but contains 1/
);
