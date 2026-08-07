#!/usr/bin/env node

'use strict';

const assert = require('assert');
const fs = require('fs');
const path = require('path');
const {
  canonicalJson,
  renderTrace,
  validateTrace,
} = require('./publish-nested-agreement-trace');

function structuralEvidence() {
  return {
    status: 'passed',
    sourceTests: [
      'blue.coordination.processor.CoordinationNestedEmbeddedCollectionFlagshipStructuralTest#shouldExposeExactAgreementPortfolioCollectionScopePlans',
    ],
    scopePlan: {
      '/': ['/agreements/agreement-a', '/agreements/agreement-b'],
    },
    fragmentInventory: [
      { kind: 'EMBEDDED_ROOT', path: '/agreements/agreement-a' },
    ],
    reconstruction: {
      wireValueEqual: true,
      blueIdEqual: true,
    },
  };
}

const observed = {
  schema: 'blue-coordination/nested-agreement-flagship-trace/1.0',
  status: 'passed',
  run: {
    id: 'fixture-observed-run',
    sourceTests: [
      'blue.coordination.processor.CoordinationComplexEmbeddedDeterminismFlagshipTest',
    ],
  },
  structuralEvidence: structuralEvidence(),
  runtimeLanes: [
    {
      id: 'deep-cancellation-descendants-only',
      status: 'passed',
      declaredScenarios: 1,
      attemptedScenarios: 1,
      completedScenarios: 1,
    },
  ],
  events: [
    {
      id: 'C-descendants-only',
      target: '/agreements/agreement-a/lessons/lesson-a/cancellations/cancel-a',
      status: 'SUCCESS',
      resultingRootBlueId: 'FixtureRootBlueId',
      publicEvents: [],
      gas: 3,
    },
  ],
  matrixTotals: { declared: 1, passed: 1, failed: 0 },
  subscriptionTransitions: [],
  maximumGasTrace: { total: 3 },
  providerDemands: { total: 2, forbidden: 0 },
};
const bytes = Buffer.from(JSON.stringify(observed), 'utf8');
const markdown = renderTrace(observed, bytes);

assert.match(
  markdown,
  /Evidence status: `passed`/,
  'the generated walkthrough must identify passing observed evidence'
);
assert.match(
  markdown,
  /\| C-descendants-only \| \/agreements\/agreement-a\/lessons\/lesson-a\/cancellations\/cancel-a \| SUCCESS \| FixtureRootBlueId \| \[\] \| 3 \|/,
  'the event row must come from the structured trace'
);
assert.match(
  markdown,
  /Source trace SHA-256: `[0-9a-f]{64}`/,
  'the generated walkthrough must bind the exact source bytes'
);
assert.match(
  markdown,
  /Structural\s+results and PROCESS runtime results are separate evidence lanes/,
  'the generated walkthrough must state the evidence boundary'
);

const failed = {
  schema: observed.schema,
  status: 'failed',
  run: {
    id: 'fixture-failed-run',
    sourceTests: [
      'blue.coordination.processor.CoordinationNestedEmbeddedCollectionFlagshipStructuralTest',
      'blue.coordination.processor.CoordinationComplexEmbeddedDeterminismFlagshipTest',
    ],
  },
  structuralEvidence: structuralEvidence(),
  runtimeLanes: [
    {
      id: 'nested-collection-process-matrix',
      status: 'failed',
      declaredScenarios: 9,
      attemptedScenarios: 1,
      completedScenarios: 0,
      diagnostic: 'A PROCESS assertion failed.',
    },
    {
      id: 'membership-lifecycle',
      status: 'notExecuted',
      declaredScenarios: 4,
      attemptedScenarios: 0,
      completedScenarios: 0,
    },
  ],
};
const failedMarkdown = renderTrace(
  failed,
  Buffer.from(JSON.stringify(failed), 'utf8')
);

assert.match(
  failedMarkdown,
  /Evidence status: `failed`/,
  'the generated walkthrough must identify failed evidence'
);
assert.match(
  failedMarkdown,
  /\| structural \| passed \|/,
  'passing structural evidence must remain visible'
);
assert.match(
  failedMarkdown,
  /\| membership-lifecycle \| notExecuted \| 4 \| 0 \| 0 \|/,
  'unexecuted scenarios must retain exact zero attempt counts'
);
assert.match(
  failedMarkdown,
  /No PROCESS event sequence, resulting Root, public event, subscription/,
  'failed evidence must not be rendered as a runtime result'
);
assert.doesNotMatch(
  failedMarkdown,
  /## Observed PROCESS sequence/,
  'failed evidence must not contain a PROCESS result table'
);

const failedWithRuntimeClaim = JSON.parse(JSON.stringify(failed));
failedWithRuntimeClaim.events = observed.events;
assert.throws(
  () => validateTrace(failedWithRuntimeClaim),
  /non-passing trace must not publish runtime result fields: events/,
  'a failed trace must reject unexecuted runtime claims'
);

const falsePass = JSON.parse(JSON.stringify(observed));
falsePass.runtimeLanes[0].completedScenarios = 0;
assert.throws(
  () => validateTrace(falsePass),
  /cannot pass without completing every declared scenario/,
  'a lane must not pass without completing its declared scenario set'
);

const unexplainedFailure = JSON.parse(JSON.stringify(failed));
delete unexplainedFailure.runtimeLanes[0].diagnostic;
assert.throws(
  () => validateTrace(unexplainedFailure),
  /runtimeLanes\[0\]\.diagnostic must be non-empty text/,
  'a failed lane must explain the failed assertion'
);

const obsoletePublicApiBlocker = JSON.parse(JSON.stringify(failed));
obsoletePublicApiBlocker.status = 'blocked';
obsoletePublicApiBlocker.runtimeLanes[0].status = 'blocked';
assert.throws(
  () => validateTrace(obsoletePublicApiBlocker),
  /status has unsupported status blocked/,
  'the trace must reject obsolete public-API blocker states'
);

const differentlyOrdered = {
  b: { d: 4, c: 3 },
  a: 1,
};
assert.strictEqual(
  canonicalJson(differentlyOrdered),
  '{"a":1,"b":{"c":3,"d":4}}',
  'canonical trace JSON must be independent of object insertion order'
);

const checkedInSource = path.join(
  __dirname,
  '..',
  'docs',
  'examples',
  'nested-agreement-lesson-cancellation-trace.json'
);
const checkedInMarkdown = path.join(
  __dirname,
  '..',
  'docs',
  'examples',
  'nested-agreement-lesson-cancellation-trace.md'
);
const checkedInBytes = fs.readFileSync(checkedInSource);
assert.strictEqual(
  fs.readFileSync(checkedInMarkdown, 'utf8'),
  renderTrace(JSON.parse(checkedInBytes.toString('utf8')), checkedInBytes),
  'the checked-in walkthrough must exactly match its structured source trace'
);
