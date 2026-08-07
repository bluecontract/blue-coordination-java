#!/usr/bin/env node

'use strict';

const crypto = require('crypto');
const fs = require('fs');
const path = require('path');

const INPUT_SCHEMA =
  'blue-coordination/latest-language-embedded-collections-run/1.0';
const OUTPUT_DIRECTORY =
  'build/reports/latest-language-embedded-collections';
const EVIDENCE_SECTIONS = [
  'dependencies',
  'migration',
  'fragmentation',
  'subscriptions',
  'performance',
  'tests',
  'conformance',
  'flagshipMatrix',
  'providerDemands',
  'api',
  'reproducibility',
];
const VALID_STATUSES = new Set(['passed', 'failed', 'notExecuted']);

function fail(message) {
  throw new Error(`Latest embedded-collections report: ${message}`);
}

function requireObject(value, label) {
  if (value === null || typeof value !== 'object' || Array.isArray(value)) {
    fail(`${label} must be an object`);
  }
  return value;
}

function requireText(value, label) {
  if (typeof value !== 'string' || value.trim() === '') {
    fail(`${label} must be non-empty text`);
  }
  return value;
}

function requireStatus(value, label) {
  if (!VALID_STATUSES.has(value)) {
    fail(`${label} must be passed, failed, or notExecuted`);
  }
  return value;
}

function requireNonNegativeInteger(value, label) {
  if (!Number.isSafeInteger(value) || value < 0) {
    fail(`${label} must be a non-negative safe integer`);
  }
  return value;
}

function requireTotals(value, label) {
  const totals = requireObject(value, label);
  ['passed', 'failed', 'skipped', 'unclassified'].forEach((field) =>
    requireNonNegativeInteger(totals[field], `${label}.${field}`)
  );
  const executed = totals.passed + totals.failed + totals.skipped;
  if (totals.executed !== undefined) {
    requireNonNegativeInteger(totals.executed, `${label}.executed`);
    if (totals.executed !== executed) {
      fail(`${label}.executed does not equal passed + failed + skipped`);
    }
  }
  if (totals.unclassified > totals.failed) {
    fail(`${label}.unclassified cannot exceed failed`);
  }
  return {
    executed,
    passed: totals.passed,
    failed: totals.failed,
    skipped: totals.skipped,
    unclassified: totals.unclassified,
  };
}

function requireFailureClassifications(value, label, expectedFailed) {
  const summary = requireObject(value, label);
  ['failed', 'classified', 'unclassified', 'external', 'coordinationOwned']
    .forEach((field) =>
      requireNonNegativeInteger(summary[field], `${label}.${field}`)
    );
  if (summary.failed !== expectedFailed) {
    fail(`${label}.failed does not match the associated test totals`);
  }
  if (summary.classified + summary.unclassified !== summary.failed) {
    fail(`${label} does not partition failed tests`);
  }
  if (summary.external + summary.coordinationOwned !== summary.classified) {
    fail(`${label} does not partition classified ownership`);
  }
  if (!Array.isArray(summary.categories)) {
    fail(`${label}.categories must be an array`);
  }
  const categoryCount = summary.categories.reduce((total, category, index) => {
    const exact = requireObject(category, `${label}.categories[${index}]`);
    requireText(exact.id, `${label}.categories[${index}].id`);
    requireText(exact.owner, `${label}.categories[${index}].owner`);
    if (typeof exact.external !== 'boolean') {
      fail(`${label}.categories[${index}].external must be boolean`);
    }
    return total + requireNonNegativeInteger(
      exact.count,
      `${label}.categories[${index}].count`
    );
  }, 0);
  if (categoryCount !== summary.classified) {
    fail(`${label}.categories do not sum to classified`);
  }
  if (!Array.isArray(summary.unknown)) {
    fail(`${label}.unknown must be an array`);
  }
  if (summary.unknown.length !== summary.unclassified) {
    fail(`${label}.unknown does not match unclassified`);
  }
  return summary;
}

function requireTestReceiptStatus(receipt, totals, label) {
  if (receipt.status === 'passed') {
    if (
      totals.failed !== 0 ||
      totals.skipped !== 0 ||
      totals.unclassified !== 0
    ) {
      fail(`${label}.status cannot be passed with non-green totals`);
    }
  } else if (receipt.status === 'failed' && totals.failed === 0) {
    fail(`${label}.status cannot be failed when no test failed`);
  }
}

function deepSort(value) {
  if (Array.isArray(value)) {
    return value.map(deepSort);
  }
  if (value !== null && typeof value === 'object') {
    return Object.keys(value)
      .sort()
      .reduce((result, key) => {
        result[key] = deepSort(value[key]);
        return result;
      }, {});
  }
  return value;
}

function canonicalJson(value) {
  return JSON.stringify(deepSort(value));
}

function sha256(value) {
  return crypto.createHash('sha256').update(value, 'utf8').digest('hex');
}

function evidenceDigest(manifest) {
  const evidence = {};
  EVIDENCE_SECTIONS.forEach((section) => {
    evidence[section] = manifest[section];
  });
  evidence.run = manifest.run;
  evidence.coordination = manifest.coordination;
  evidence.blockers = manifest.blockers;
  evidence.gates = manifest.gates;
  return sha256(canonicalJson(evidence));
}

function validateManifest(input) {
  const manifest = requireObject(input, 'manifest');
  if (manifest.schema !== INPUT_SCHEMA) {
    fail(`unsupported manifest schema ${manifest.schema}`);
  }
  const run = requireObject(manifest.run, 'run');
  const runId = requireText(run.id, 'run.id');
  requireText(run.startedAt, 'run.startedAt');
  requireText(run.finishedAt, 'run.finishedAt');
  if (run.startedAt > run.finishedAt) {
    fail('run.startedAt must not be after run.finishedAt');
  }
  const coordination = requireObject(
    manifest.coordination,
    'coordination'
  );
  requireText(coordination.commit, 'coordination.commit');
  if (typeof coordination.dirty !== 'boolean') {
    fail('coordination.dirty must be boolean');
  }
  EVIDENCE_SECTIONS.forEach((section) => {
    const receipt = requireObject(manifest[section], section);
    if (receipt.runId !== runId) {
      fail(`${section}.runId does not match run.id`);
    }
    const status = requireStatus(receipt.status, `${section}.status`);
    if (status === 'notExecuted') {
      requireText(receipt.reason, `${section}.reason`);
    }
  });
  const dependencies = manifest.dependencies;
  ['language', 'bex', 'repository'].forEach((name) => {
    const selected = requireObject(
      dependencies[name],
      `dependencies.${name}`
    );
    requireText(selected.commit, `dependencies.${name}.commit`);
    requireText(selected.version, `dependencies.${name}.version`);
  });
  if (typeof dependencies.bex.workingReady !== 'boolean') {
    fail('dependencies.bex.workingReady must be boolean');
  }
  requireObject(
    dependencies.bex.moduleJarHashes,
    'dependencies.bex.moduleJarHashes'
  );
  const graph = dependencies.resolvedModuleGraph;
  if (
    graph === null ||
    typeof graph !== 'object' ||
    (!Array.isArray(graph) && Object.getPrototypeOf(graph) !== Object.prototype)
  ) {
    fail('dependencies.resolvedModuleGraph must be an object or array');
  }
  requireObject(
    dependencies.packageIdentities,
    'dependencies.packageIdentities'
  );
  requireObject(
    dependencies.artifactIdentities,
    'dependencies.artifactIdentities'
  );
  const ordinaryTotals = requireTotals(
    manifest.tests.ordinary,
    'tests.ordinary'
  );
  const collectionTotals = requireTotals(
    manifest.tests.collectionSpecific,
    'tests.collectionSpecific'
  );
  const conformanceTotals = requireTotals(
    manifest.conformance.totals,
    'conformance.totals'
  );
  const flagshipTotals = requireTotals(
    manifest.flagshipMatrix.totals,
    'flagshipMatrix.totals'
  );
  requireFailureClassifications(
    manifest.tests.failureClassifications,
    'tests.failureClassifications',
    ordinaryTotals.failed
  );
  requireFailureClassifications(
    manifest.conformance.failureClassifications,
    'conformance.failureClassifications',
    conformanceTotals.failed
  );
  requireFailureClassifications(
    manifest.flagshipMatrix.failureClassifications,
    'flagshipMatrix.failureClassifications',
    flagshipTotals.failed
  );
  requireTestReceiptStatus(manifest.tests, ordinaryTotals, 'tests');
  requireTestReceiptStatus(
    manifest.conformance,
    conformanceTotals,
    'conformance'
  );
  requireTestReceiptStatus(
    manifest.flagshipMatrix,
    flagshipTotals,
    'flagshipMatrix'
  );
  if (collectionTotals.unclassified > collectionTotals.failed) {
    fail('tests.collectionSpecific has invalid unclassified totals');
  }
  requireTotals(
    manifest.subscriptions.projectionTotals,
    'subscriptions.projectionTotals'
  );
  requireTotals(
    manifest.subscriptions.updateTotals,
    'subscriptions.updateTotals'
  );
  requireNonNegativeInteger(
    manifest.providerDemands.total,
    'providerDemands.total'
  );
  requireNonNegativeInteger(
    manifest.providerDemands.forbidden,
    'providerDemands.forbidden'
  );
  requireNonNegativeInteger(
    manifest.api.splitPackageCount,
    'api.splitPackageCount'
  );
  requireNonNegativeInteger(
    manifest.api.packageCycleCount,
    'api.packageCycleCount'
  );
  if (!Array.isArray(manifest.blockers)) {
    fail('blockers must be an array');
  }
  if (!Array.isArray(manifest.gates) || manifest.gates.length === 0) {
    fail('gates must be a non-empty array');
  }
  manifest.gates.forEach((gate, index) => {
    requireObject(gate, `gates[${index}]`);
    requireText(gate.name, `gates[${index}].name`);
    const status = requireStatus(gate.status, `gates[${index}].status`);
    if (status === 'notExecuted') {
      requireText(gate.reason, `gates[${index}].reason`);
    }
    if (gate.runId !== runId) {
      fail(`gates[${index}].runId does not match run.id`);
    }
  });
  return manifest;
}

function reportEnvelope(schema, manifest, body) {
  return Object.assign(
    {
      schema,
      run: manifest.run,
      generatedAt: manifest.run.finishedAt,
      sourceEvidenceSha256: evidenceDigest(manifest),
    },
    body
  );
}

function migrationReport(manifest) {
  return reportEnvelope(
    'blue-coordination/latest-language-embedded-collections-migration/1.0',
    manifest,
    {
      status: manifest.migration.status,
      coordination: manifest.coordination,
      dependencies: manifest.dependencies,
      api: manifest.api,
      migration: manifest.migration,
    }
  );
}

function fragmentationReport(manifest) {
  return reportEnvelope(
    'blue-coordination/latest-language-embedded-collections-fragmentation/1.0',
    manifest,
    {
      status: manifest.fragmentation.status,
      fragmentation: manifest.fragmentation,
      collectionTests: requireTotals(
        manifest.tests.collectionSpecific,
        'tests.collectionSpecific'
      ),
      providerDemands: manifest.providerDemands,
      flagshipMatrix: manifest.flagshipMatrix,
    }
  );
}

function subscriptionsReport(manifest) {
  return reportEnvelope(
    'blue-coordination/latest-language-embedded-collections-subscriptions/1.0',
    manifest,
    {
      status: manifest.subscriptions.status,
      subscriptions: manifest.subscriptions,
    }
  );
}

function performanceReport(manifest) {
  return reportEnvelope(
    'blue-coordination/latest-language-embedded-collections-performance/1.0',
    manifest,
    {
      status: manifest.performance.status,
      performance: manifest.performance,
    }
  );
}

function dependencyLockReport(manifest) {
  return reportEnvelope(
    'blue-coordination/latest-language-embedded-collections-dependency-lock/1.0',
    manifest,
    {
      status: manifest.dependencies.status,
      dependencies: manifest.dependencies,
    }
  );
}

function totalsAreGreen(totals) {
  const exact = requireTotals(totals, 'release totals');
  return exact.failed === 0 && exact.skipped === 0 && exact.unclassified === 0;
}

function deriveReleaseEligible(manifest) {
  const receiptsPassed = EVIDENCE_SECTIONS.every(
    (section) => manifest[section].status === 'passed'
  );
  const gatesPassed = manifest.gates.every(
    (gate) => gate.status === 'passed'
  );
  return (
    receiptsPassed &&
    gatesPassed &&
    totalsAreGreen(manifest.tests.ordinary) &&
    totalsAreGreen(manifest.tests.collectionSpecific) &&
    totalsAreGreen(manifest.conformance.totals) &&
    totalsAreGreen(manifest.flagshipMatrix.totals) &&
    manifest.providerDemands.forbidden === 0 &&
    manifest.api.splitPackageCount === 0 &&
    manifest.api.packageCycleCount === 0 &&
    manifest.blockers.length === 0
  );
}

function finalReport(manifest, componentReports) {
  const dependencies = manifest.dependencies;
  return reportEnvelope(
    'blue-coordination/latest-language-embedded-collections-final/2.0',
    manifest,
    {
      coordinationCommit: manifest.coordination.commit,
      coordinationVersion: manifest.coordination.version || null,
      coordinationDirty: manifest.coordination.dirty === true,
      language: dependencies.language,
      bex: dependencies.bex,
      repository: dependencies.repository,
      resolvedModuleGraph: dependencies.resolvedModuleGraph,
      packageIdentities: dependencies.packageIdentities,
      artifactIdentities: dependencies.artifactIdentities,
      ordinaryTestTotals: requireTotals(
        manifest.tests.ordinary,
        'tests.ordinary'
      ),
      collectionSpecificTestTotals: requireTotals(
        manifest.tests.collectionSpecific,
        'tests.collectionSpecific'
      ),
      conformanceTotals: requireTotals(
        manifest.conformance.totals,
        'conformance.totals'
      ),
      flagshipMatrixTotals: requireTotals(
        manifest.flagshipMatrix.totals,
        'flagshipMatrix.totals'
      ),
      failureClassifications: {
        ordinary: manifest.tests.failureClassifications,
        conformance: manifest.conformance.failureClassifications,
        flagshipMatrix: manifest.flagshipMatrix.failureClassifications,
      },
      providerDemandTotals: manifest.providerDemands,
      forbiddenDemands: manifest.providerDemands.forbidden,
      subscriptionProjectionTotals: requireTotals(
        manifest.subscriptions.projectionTotals,
        'subscriptions.projectionTotals'
      ),
      subscriptionUpdateTotals: requireTotals(
        manifest.subscriptions.updateTotals,
        'subscriptions.updateTotals'
      ),
      maximumGasTrace: manifest.fragmentation.maximumGasTrace || null,
      jmhCampaignSummary: manifest.performance.jmhCampaignSummary || null,
      apiChanges: manifest.api.changes || [],
      splitPackageCount: manifest.api.splitPackageCount,
      packageCycleCount: manifest.api.packageCycleCount,
      reproducibilityDigests: manifest.reproducibility.digests || {},
      remainingExternalBlockers: manifest.blockers,
      gates: manifest.gates,
      componentReportDigests: Object.keys(componentReports)
        .sort()
        .reduce((digests, name) => {
          digests[name] = sha256(canonicalJson(componentReports[name]));
          return digests;
        }, {}),
      releaseEligible: deriveReleaseEligible(manifest),
    }
  );
}

function writeJson(target, value) {
  fs.mkdirSync(path.dirname(target), { recursive: true });
  fs.writeFileSync(
    target,
    `${JSON.stringify(deepSort(value), null, 2)}\n`,
    'utf8'
  );
}

function generateReports(manifestValue, outputDirectory) {
  const manifest = validateManifest(manifestValue);
  const components = {
    'dependency-lock.json': dependencyLockReport(manifest),
    'fragmentation.json': fragmentationReport(manifest),
    'migration.json': migrationReport(manifest),
    'performance.json': performanceReport(manifest),
    'subscriptions.json': subscriptionsReport(manifest),
  };
  Object.keys(components).forEach((name) =>
    writeJson(path.join(outputDirectory, name), components[name])
  );
  const final = finalReport(manifest, components);
  writeJson(path.join(outputDirectory, 'final.json'), final);
  return Object.assign({ 'final.json': final }, components);
}

function parseArguments(argumentsList) {
  const result = { outputDirectory: OUTPUT_DIRECTORY };
  for (let index = 0; index < argumentsList.length; index += 1) {
    const value = argumentsList[index];
    if (value === '--manifest') {
      result.manifest = argumentsList[++index];
    } else if (value === '--output-dir') {
      result.outputDirectory = argumentsList[++index];
    } else {
      fail(`unknown argument ${value}`);
    }
  }
  if (!result.manifest) {
    fail('usage: --manifest <same-run.json> [--output-dir <directory>]');
  }
  return result;
}

function main() {
  const options = parseArguments(process.argv.slice(2));
  const manifest = JSON.parse(fs.readFileSync(options.manifest, 'utf8'));
  generateReports(manifest, options.outputDirectory);
}

if (require.main === module) {
  main();
}

module.exports = {
  INPUT_SCHEMA,
  deriveReleaseEligible,
  generateReports,
  validateManifest,
};
