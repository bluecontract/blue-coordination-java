#!/usr/bin/env node

'use strict';

const assert = require('assert');
const {
  expandDefinitionClosure,
  runtimeRegistrationQualifiedNames,
} = require('./generate-coordination-required-repository-closure');

const rootId = 'Root111111111111111111111111111111111111111';
const bridgeId = 'Bridge111111111111111111111111111111111111';
const cyclicMaster = 'Cycle111111111111111111111111111111111111';
const cyclicZero = `${cyclicMaster}#0`;
const cyclicOne = `${cyclicMaster}#1`;

const definitions = [
  {
    qualifiedName: 'Fixture/Registered Root',
    blueId: rootId,
    resourcePath: 'fixture/root.json',
  },
  {
    qualifiedName: 'Fixture/Transitive Bridge',
    blueId: bridgeId,
    resourcePath: 'fixture/bridge.json',
  },
  {
    qualifiedName: 'Fixture/Cyclic Zero',
    blueId: cyclicZero,
    resourcePath: 'fixture/cyclic-zero.json',
  },
  {
    qualifiedName: 'Fixture/Cyclic One',
    blueId: cyclicOne,
    resourcePath: 'fixture/cyclic-one.json',
  },
];
const definitionsByBlueId = new Map(
  definitions.map((definition) => [definition.blueId, definition])
);
const definitionsByMaster = new Map([
  [rootId, [definitions[0]]],
  [bridgeId, [definitions[1]]],
  [cyclicMaster, [definitions[2], definitions[3]]],
]);
const resources = new Map([
  [rootId, { type: bridgeId }],
  [bridgeId, { type: cyclicZero }],
  [cyclicZero, { peer: 'this#1' }],
  [cyclicOne, { peer: 'this#0' }],
]);

const registrations = runtimeRegistrationQualifiedNames(
  'src/test/resources/coordination/conformance/runtime-registrations.yaml',
  [
    'schema: fixture',
    '- type: Fixture/Registered Root',
    '  handler: fixture',
  ].join('\n')
);
assert.deepStrictEqual(
  registrations,
  ['Fixture/Registered Root'],
  'the runtime-registration fixture must produce one explicit direct root'
);

const expanded = expandDefinitionClosure(
  definitionsByBlueId,
  definitionsByMaster,
  [definitions[0].blueId],
  (definition) =>
    Buffer.from(
      JSON.stringify(resources.get(definition.blueId)),
      'utf8'
    )
);
assert.deepStrictEqual(
  Array.from(expanded.closure.keys()).sort(),
  [rootId, bridgeId, cyclicZero, cyclicOne].sort(),
  'a direct runtime root must expand through the bridge to the complete cyclic set'
);
assert.deepStrictEqual(
  expanded.directReferences.get(rootId),
  [bridgeId],
  'the direct root edge must be retained'
);
assert.deepStrictEqual(
  expanded.directReferences.get(bridgeId),
  [cyclicZero],
  'the transitive edge into the cyclic set must be retained'
);
assert.deepStrictEqual(
  expanded.directReferences.get(cyclicZero),
  [cyclicZero, cyclicOne],
  'every cyclic member must be retained from a this# reference'
);
assert.deepStrictEqual(
  expanded.directReferences.get(cyclicOne),
  [cyclicZero, cyclicOne],
  'the complete cyclic set must remain closed'
);
assert.strictEqual(
  expanded.sourceResourceSha256ByBlueId.size,
  4,
  'every required source resource must be hashed'
);
