#!/usr/bin/env node

'use strict';

const assert = require('assert');
const fs = require('fs');
const os = require('os');
const path = require('path');
const {
  failureClassificationSummary,
  failureRecordsFromFile,
  providerDemandEvidenceFromFile,
} = require('./capture-latest-language-embedded-collections-blocked-run');

const directory = fs.mkdtempSync(
  path.join(os.tmpdir(), 'blue-coordination-classifier-')
);
const result = path.join(directory, 'TEST-fixture.xml');
fs.writeFileSync(
  result,
  `<?xml version="1.0" encoding="UTF-8"?>
<testsuite name="fixture" tests="2" skipped="0" failures="2" errors="0">
  <testcase name="shouldReproduceRepositoryAbi()" classname="fixture.RepositoryTest">
    <failure message="java.lang.NoClassDefFoundError: blue/language/NodeProvider" type="java.lang.NoClassDefFoundError">java.lang.NoClassDefFoundError: blue/language/NodeProvider</failure>
  </testcase>
  <testcase name="shouldRemainUnknown()" classname="fixture.UnknownTest">
    <failure message="fixture &amp; unknown" type="java.lang.AssertionError">fixture &amp; unknown</failure>
  </testcase>
</testsuite>
`,
  'utf8'
);

const records = failureRecordsFromFile(result, fs.readFileSync(result, 'utf8'), 2);
const summary = failureClassificationSummary(records);
assert.strictEqual(summary.failed, 2);
assert.strictEqual(summary.classified, 1);
assert.strictEqual(summary.unclassified, 1);
assert.strictEqual(summary.external, 1);
assert.strictEqual(summary.coordinationOwned, 0);
assert.strictEqual(summary.categories[0].id, 'repository-node-provider-abi');
assert.strictEqual(summary.unknown[0].messageExcerpt, 'fixture & unknown');
assert.throws(
  () => failureRecordsFromFile(result, fs.readFileSync(result, 'utf8'), 3),
  /JUnit declared 3/,
  'capture must reject a parser/count mismatch'
);

const providerResult = path.join(directory, 'TEST-provider.xml');
fs.writeFileSync(
  providerResult,
  `<?xml version="1.0" encoding="UTF-8"?>
<testsuite name="fixture" tests="1" skipped="0" failures="0" errors="0">
  <testcase name="shouldProveDemands()" classname="fixture.ProviderTest"/>
  <system-out><![CDATA[coordination.providerDemands={"schema":"blue.coordination/provider-demands/1.0","total":7,"forbidden":0,"variants":8,"selectedBodyDemands":6,"forbiddenIdentities":4}
]]></system-out>
</testsuite>
`,
  'utf8'
);
const providerEvidence = providerDemandEvidenceFromFile(providerResult);
assert.strictEqual(providerEvidence.status, 'passed');
assert.strictEqual(providerEvidence.total, 7);
assert.strictEqual(providerEvidence.forbidden, 0);
assert.strictEqual(providerEvidence.variants, 8);
assert.strictEqual(providerEvidence.selectedBodyDemands, 6);
assert.strictEqual(providerEvidence.forbiddenIdentities, 4);

fs.appendFileSync(
  providerResult,
  'coordination.providerDemands={"schema":"blue.coordination/provider-demands/1.0","total":1,"forbidden":0,"variants":2,"selectedBodyDemands":1,"forbiddenIdentities":1}\n',
  'utf8'
);
assert.throws(
  () => providerDemandEvidenceFromFile(providerResult),
  /exactly one provider-demand evidence marker/,
  'capture must reject ambiguous provider-demand evidence'
);
