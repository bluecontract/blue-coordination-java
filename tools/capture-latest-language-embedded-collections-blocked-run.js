#!/usr/bin/env node

'use strict';

const crypto = require('crypto');
const fs = require('fs');
const path = require('path');
const childProcess = require('child_process');
const {
  INPUT_SCHEMA,
  generateReports,
} = require('./generate-latest-language-embedded-collections-reports');

const ROOT = path.resolve(__dirname, '..');
const OUTPUT = path.join(
  ROOT,
  'build/reports/latest-language-embedded-collections'
);
let testResultsDirectory = path.join(ROOT, 'build/test-results/test');

function fail(message) {
  throw new Error(`Latest embedded-collections capture: ${message}`);
}

function configureTestResultsDirectory(value) {
  if (typeof value !== 'string' || value.trim() === '') {
    fail('test results directory must be non-empty text');
  }
  testResultsDirectory = path.resolve(ROOT, value);
  if (
    !fs.existsSync(testResultsDirectory) ||
    !fs.statSync(testResultsDirectory).isDirectory()
  ) {
    fail(`missing test results directory ${testResultsDirectory}`);
  }
}

function parseArguments(values) {
  const options = {};
  for (let index = 0; index < values.length; index += 1) {
    if (values[index] === '--results-dir') {
      options.resultsDirectory = values[++index];
    } else {
      fail(`unknown argument ${values[index]}`);
    }
  }
  if (!options.resultsDirectory) {
    fail('usage: --results-dir <JUnit XML directory>');
  }
  return options;
}

function read(relativePath) {
  return fs.readFileSync(path.join(ROOT, relativePath), 'utf8');
}

function readJson(relativePath) {
  return JSON.parse(read(relativePath));
}

function properties(relativePath) {
  return read(relativePath)
    .split(/\r?\n/)
    .filter((line) => line && !line.startsWith('#'))
    .reduce((result, line) => {
      const separator = line.indexOf('=');
      if (separator > 0) {
        result[line.slice(0, separator)] = line.slice(separator + 1);
      }
      return result;
    }, {});
}

function git(...argumentsList) {
  return childProcess.execFileSync('git', argumentsList, {
    cwd: ROOT,
    encoding: 'utf8',
  }).trim();
}

function sha256File(target) {
  return crypto
    .createHash('sha256')
    .update(fs.readFileSync(target))
    .digest('hex');
}

function directoryDigest(relativeDirectory) {
  const root = path.join(ROOT, relativeDirectory);
  if (!fs.existsSync(root) || !fs.statSync(root).isDirectory()) {
    fail(`missing compiled evidence directory ${relativeDirectory}`);
  }
  const files = [];
  const visit = (directory) => {
    fs.readdirSync(directory, { withFileTypes: true })
      .sort((left, right) => left.name.localeCompare(right.name))
      .forEach((entry) => {
        const target = path.join(directory, entry.name);
        if (entry.isDirectory()) {
          visit(target);
        } else if (entry.isFile()) {
          files.push(target);
        }
      });
  };
  visit(root);
  if (files.length === 0) {
    fail(`compiled evidence directory is empty: ${relativeDirectory}`);
  }
  const digest = crypto.createHash('sha256');
  files.forEach((target) => {
    digest.update(path.relative(root, target).split(path.sep).join('/'));
    digest.update('\0');
    digest.update(fs.readFileSync(target));
    digest.update('\0');
  });
  return {
    path: relativeDirectory,
    files: files.length,
    sha256: digest.digest('hex'),
  };
}

function javaSources(relativeRoot) {
  const root = path.join(ROOT, relativeRoot);
  if (!fs.existsSync(root)) {
    return [];
  }
  const result = [];
  const visit = (directory) => {
    fs.readdirSync(directory, { withFileTypes: true })
      .sort((left, right) => left.name.localeCompare(right.name))
      .forEach((entry) => {
        const target = path.join(directory, entry.name);
        if (entry.isDirectory()) {
          visit(target);
        } else if (entry.isFile() && entry.name.endsWith('.java')) {
          result.push(target);
        }
      });
  };
  visit(root);
  return result;
}

function declaredPackage(source) {
  const match = /^\s*package\s+([^;]+);/m.exec(source);
  return match ? match[1] : null;
}

function splitPackageFiles() {
  return ['src/main/java', 'src/test/java', 'src/jmh/java']
    .flatMap(javaSources)
    .filter((source) => {
      const packageName = declaredPackage(fs.readFileSync(source, 'utf8'));
      return packageName && packageName.startsWith('blue.language');
    })
    .map((source) => path.relative(ROOT, source).split(path.sep).join('/'));
}

function productionPackageCycleCount() {
  const sources = javaSources('src/main/java');
  const classPackages = new Map();
  const sourcePackages = new Map();
  sources.forEach((source) => {
    const text = fs.readFileSync(source, 'utf8');
    const packageName = declaredPackage(text);
    if (!packageName) {
      return;
    }
    sourcePackages.set(source, packageName);
    classPackages.set(
      `${packageName}.${path.basename(source, '.java')}`,
      packageName
    );
  });
  const graph = new Map();
  sourcePackages.forEach((packageName) => graph.set(packageName, new Set()));
  sourcePackages.forEach((packageName, source) => {
    const text = fs.readFileSync(source, 'utf8');
    for (const match of text.matchAll(/^\s*import\s+([^;]+);/gm)) {
      const targetPackage = classPackages.get(match[1]);
      if (targetPackage && targetPackage !== packageName) {
        graph.get(packageName).add(targetPackage);
      }
    }
  });

  let index = 0;
  let cycles = 0;
  const indexes = new Map();
  const lowLinks = new Map();
  const stack = [];
  const active = new Set();
  const connect = (vertex) => {
    indexes.set(vertex, index);
    lowLinks.set(vertex, index);
    index += 1;
    stack.push(vertex);
    active.add(vertex);
    graph.get(vertex).forEach((next) => {
      if (!indexes.has(next)) {
        connect(next);
        lowLinks.set(
          vertex,
          Math.min(lowLinks.get(vertex), lowLinks.get(next))
        );
      } else if (active.has(next)) {
        lowLinks.set(
          vertex,
          Math.min(lowLinks.get(vertex), indexes.get(next))
        );
      }
    });
    if (lowLinks.get(vertex) === indexes.get(vertex)) {
      const component = [];
      let member;
      do {
        member = stack.pop();
        active.delete(member);
        component.push(member);
      } while (member !== vertex);
      if (component.length > 1) {
        cycles += 1;
      }
    }
  };
  graph.forEach((_edges, vertex) => {
    if (!indexes.has(vertex)) {
      connect(vertex);
    }
  });
  return cycles;
}

function decodeXml(value) {
  return value
    .replace(/&#x([0-9a-f]+);/gi, (_match, digits) =>
      String.fromCodePoint(Number.parseInt(digits, 16)))
    .replace(/&#([0-9]+);/g, (_match, digits) =>
      String.fromCodePoint(Number.parseInt(digits, 10)))
    .replace(/&quot;/g, '"')
    .replace(/&apos;/g, "'")
    .replace(/&lt;/g, '<')
    .replace(/&gt;/g, '>')
    .replace(/&amp;/g, '&');
}

function xmlAttribute(attributes, name) {
  const match = new RegExp(`${name}="([^"]*)"`).exec(attributes);
  return match ? decodeXml(match[1]) : null;
}

const FAILURE_CLASSIFIERS = [
  {
    id: 'repository-node-provider-abi',
    owner: 'blue-repository-java',
    external: true,
    matches: (record) => record.text.includes(
      'NoClassDefFoundError: blue/language/NodeProvider'
    ),
  },
  {
    id: 'repository-historical-registry-blueid-mismatch',
    owner: 'blue-repository-java',
    external: true,
    matches: (record) =>
      record.text.includes(
        'Historical registry source src/main/resources/registry/'
      ) &&
      record.text.includes('Provider returned content with BlueId') &&
      record.text.includes('for requested BlueId'),
  },
];

function classifyFailure(record) {
  const matches = FAILURE_CLASSIFIERS.filter((classifier) =>
    classifier.matches(record)
  );
  if (matches.length > 1) {
    fail(
      `ambiguous failure classification for ${record.testId}: ` +
        matches.map((match) => match.id).join(', ')
    );
  }
  return matches.length === 1
    ? {
        id: matches[0].id,
        owner: matches[0].owner,
        external: matches[0].external,
      }
    : null;
}

function failureRecordsFromFile(target, xml, expectedFailures) {
  const records = [];
  const testCasePattern = /<testcase\b([^>]*?)(?:\/>|>([\s\S]*?)<\/testcase>)/g;
  for (const testCase of xml.matchAll(testCasePattern)) {
    const attributes = testCase[1];
    const body = testCase[2] || '';
    const className = xmlAttribute(attributes, 'classname') || 'unknown';
    const testName = xmlAttribute(attributes, 'name') || 'unknown';
    const failurePattern = /<(failure|error)\b([^>]*)>([\s\S]*?)<\/\1>/g;
    for (const failureMatch of body.matchAll(failurePattern)) {
      const failureAttributes = failureMatch[2];
      const failureBody = decodeXml(
        failureMatch[3].replace(/<!\[CDATA\[|\]\]>/g, '')
      );
      const message =
        xmlAttribute(failureAttributes, 'message') ||
        failureBody.split(/\r?\n/)[0] ||
        'missing failure message';
      const type =
        xmlAttribute(failureAttributes, 'type') || failureMatch[1];
      const record = {
        testId: `${className}#${testName}`,
        className,
        testName,
        type,
        message,
        text: `${message}\n${failureBody}`,
        resultPath: path.relative(ROOT, target).split(path.sep).join('/'),
      };
      record.classification = classifyFailure(record);
      records.push(record);
    }
  }
  if (records.length !== expectedFailures) {
    fail(
      `parsed ${records.length} failed testcases from ${target}; ` +
        `JUnit declared ${expectedFailures}`
    );
  }
  return records;
}

function failureClassificationSummary(records) {
  const categoriesById = new Map();
  const unknown = [];
  records.forEach((record) => {
    if (!record.classification) {
      unknown.push({
        testId: record.testId,
        type: record.type,
        messageExcerpt: record.message.slice(0, 400),
        messageSha256: crypto
          .createHash('sha256')
          .update(record.text)
          .digest('hex'),
      });
      return;
    }
    const key = record.classification.id;
    let category = categoriesById.get(key);
    if (!category) {
      category = {
        id: key,
        owner: record.classification.owner,
        external: record.classification.external,
        count: 0,
        sampleTestIds: [],
      };
      categoriesById.set(key, category);
    }
    category.count += 1;
    if (category.sampleTestIds.length < 5) {
      category.sampleTestIds.push(record.testId);
    }
  });
  const categories = Array.from(categoriesById.values()).sort((left, right) =>
    left.id.localeCompare(right.id)
  );
  unknown.sort((left, right) => left.testId.localeCompare(right.testId));
  return {
    failed: records.length,
    classified: records.length - unknown.length,
    unclassified: unknown.length,
    external: categories
      .filter((category) => category.external)
      .reduce((total, category) => total + category.count, 0),
    coordinationOwned: categories
      .filter((category) => !category.external)
      .reduce((total, category) => total + category.count, 0),
    categories,
    unknown,
  };
}

function testTotalsFromFile(target) {
  if (!fs.existsSync(target) || !fs.statSync(target).isFile()) {
    fail(`missing same-run test result ${target}`);
  }
  const xml = fs.readFileSync(target, 'utf8');
  const opening = xml.match(/<testsuite\s+[^>]+>/);
  if (!opening) {
    fail(`invalid test result ${target}`);
  }
  const number = (name) => {
    const match = new RegExp(`${name}="(\\d+)"`).exec(opening[0]);
    return match ? Number(match[1]) : 0;
  };
  const executed = number('tests');
  const skipped = number('skipped');
  const failed = number('failures') + number('errors');
  const failureRecords = failureRecordsFromFile(target, xml, failed);
  const failureClassifications = failureClassificationSummary(
    failureRecords
  );
  return {
    executed,
    passed: executed - skipped - failed,
    failed,
    skipped,
    unclassified: failureClassifications.unclassified,
    failureRecords,
    failureClassifications,
    path: path.relative(ROOT, target).split(path.sep).join('/'),
    sha256: sha256File(target),
    mtimeMs: fs.statSync(target).mtimeMs,
  };
}

function testTotals(className) {
  return testTotalsFromFile(path.join(
    testResultsDirectory,
    `TEST-${className}.xml`
  ));
}

const PROVIDER_DEMAND_SCHEMA =
  'blue.coordination/provider-demands/1.0';

function providerDemandEvidenceFromFile(target) {
  if (!fs.existsSync(target) || !fs.statSync(target).isFile()) {
    fail(`missing same-run provider-demand test result ${target}`);
  }
  const xml = fs.readFileSync(target, 'utf8');
  const markers = Array.from(
    xml.matchAll(/coordination\.providerDemands=(\{[^\r\n]+\})/g)
  );
  if (markers.length !== 1) {
    fail(
      `expected exactly one provider-demand evidence marker in ${target}; ` +
        `found ${markers.length}`
    );
  }
  let evidence;
  try {
    evidence = JSON.parse(decodeXml(markers[0][1]));
  } catch (error) {
    fail(`invalid provider-demand evidence JSON in ${target}: ${error.message}`);
  }
  if (!evidence || evidence.schema !== PROVIDER_DEMAND_SCHEMA) {
    fail(`invalid provider-demand evidence schema in ${target}`);
  }
  [
    'total',
    'forbidden',
    'variants',
    'selectedBodyDemands',
    'forbiddenIdentities',
  ].forEach(
    (field) => {
      if (!Number.isSafeInteger(evidence[field]) || evidence[field] < 0) {
        fail(`provider-demand evidence ${field} must be non-negative`);
      }
    }
  );
  if (evidence.variants < 2) {
    fail('provider-demand evidence must cover multiple representations');
  }
  if (evidence.selectedBodyDemands === 0) {
    fail('provider-demand evidence must observe a selected body load');
  }
  if (evidence.forbiddenIdentities === 0) {
    fail('provider-demand evidence must check at least one cold identity');
  }
  if (evidence.forbidden !== 0) {
    fail('provider-demand evidence contains a forbidden demand');
  }
  return {
    status: 'passed',
    total: evidence.total,
    forbidden: evidence.forbidden,
    variants: evidence.variants,
    selectedBodyDemands: evidence.selectedBodyDemands,
    forbiddenIdentities: evidence.forbiddenIdentities,
    evidence: {
      testId:
        'blue.coordination.processor.' +
        'CoordinationDocumentSplitterProcessingMatrixTest#' +
        'shouldPreserveProcessSemanticsAcrossSplitRepresentations',
      path: path.relative(ROOT, target).split(path.sep).join('/'),
      sha256: sha256File(target),
    },
  };
}

function allTestTotals(predicate = () => true) {
  const results = fs.readdirSync(testResultsDirectory)
    .filter((name) => name.startsWith('TEST-') && name.endsWith('.xml'))
    .filter(predicate)
    .map((name) => testTotalsFromFile(path.join(testResultsDirectory, name)));
  if (results.length === 0) {
    fail('no same-run ordinary test results were found');
  }
  const totals = sumTotals(...results);
  totals.failureRecords = results.flatMap((result) => result.failureRecords);
  totals.failureClassifications = failureClassificationSummary(
    totals.failureRecords
  );
  totals.classFiles = results.length;
  totals.mtimeMs = Math.min(...results.map((result) => result.mtimeMs));
  return totals;
}

function zeroTotals() {
  return {
    executed: 0,
    passed: 0,
    failed: 0,
    skipped: 0,
    unclassified: 0,
  };
}

function sumTotals(...values) {
  return values.reduce(
    (result, value) => {
      ['executed', 'passed', 'failed', 'skipped', 'unclassified']
        .forEach((field) => {
          result[field] += value[field];
        });
      return result;
    },
    zeroTotals()
  );
}

function combinedTestTotals(...values) {
  const totals = sumTotals(...values);
  totals.failureRecords = values.flatMap(
    (value) => value.failureRecords || []
  );
  totals.failureClassifications = failureClassificationSummary(
    totals.failureRecords
  );
  return totals;
}

function classificationReason(summary) {
  return (
    `${summary.external} external, ` +
    `${summary.coordinationOwned} Coordination-owned, and ` +
    `${summary.unclassified} unclassified failures`
  );
}

function reportableTotals(value) {
  const result = {
    executed: value.executed,
    passed: value.passed,
    failed: value.failed,
    skipped: value.skipped,
    unclassified: value.unclassified,
  };
  ['path', 'sha256', 'classFiles', 'mtimeMs'].forEach((field) => {
    if (value[field] !== undefined) {
      result[field] = value[field];
    }
  });
  return result;
}

function mapHashes(values) {
  return Object.keys(values)
    .sort()
    .reduce((result, name) => {
      result[name] = values[name].sha256;
      return result;
    }, {});
}

function main() {
  const options = parseArguments(process.argv.slice(2));
  configureTestResultsDirectory(options.resultsDirectory);
  const lock = properties('gradle/blue-sibling-lock.properties');
  const dependencyLock = readJson(
    'build/reports/latest-language-embedded-collections/resolved-dependency-lock.json'
  );
  const siblingInputs = readJson(
    'build/reports/latest-language-embedded-collections/sibling-inputs.json'
  );
  if (dependencyLock.status !== 'verified') {
    fail('focused dependency lock is not verified');
  }
  if (siblingInputs.status !== 'verified') {
    fail('sibling input receipt is not verified');
  }

  const canonical = testTotals(
    'blue.coordination.processor.CoordinationCanonicalFragmentContractTest'
  );
  const matrix = testTotals(
    'blue.coordination.processor.CoordinationDocumentSplitterProcessingMatrixTest'
  );
  const structuralFlagship = testTotals(
    'blue.coordination.processor.CoordinationNestedEmbeddedCollectionFlagshipStructuralTest'
  );
  const matrixCategories = matrix.failureClassifications.categories;
  if (
    canonical.failed !== 0 ||
    canonical.skipped !== 0 ||
    structuralFlagship.failed !== 0 ||
    structuralFlagship.skipped !== 0 ||
    matrix.failed !== 0 ||
    matrix.skipped !== 0 ||
    matrixCategories.length !== 0
  ) {
    fail(
      'collection evidence does not prove a completely green structural and pure-reference boundary'
    );
  }
  const collectionTotals = combinedTestTotals(
    canonical,
    structuralFlagship,
    matrix,
    testTotals(
      'blue.coordination.processor.CoordinationCollectionSubscriptionLifecycleTest'
    ),
    testTotals(
      'blue.coordination.processor.CoordinationPublicCollectionPlatformLifecycleTest'
    ),
    testTotals(
      'blue.coordination.processor.CoordinationPublicIndexedDeliveryCandidatesTest'
    ),
    testTotals(
      'blue.coordination.processor.CoordinationNestedIndexedCurrentRootDeliveryEquivalenceTest'
    )
  );
  if (matrix.failed !== 0 || matrix.skipped !== 0) {
    fail('provider-demand evidence test did not complete cleanly');
  }
  const providerDemands = providerDemandEvidenceFromFile(
    path.join(
      testResultsDirectory,
      'TEST-blue.coordination.processor.' +
        'CoordinationDocumentSplitterProcessingMatrixTest.xml'
    )
  );
  const ordinaryTotals = allTestTotals();
  const conformanceTotals = allTestTotals((name) =>
    /Conformance|ExternalBlocker/.test(name)
  );
  const runtimeFlagship = testTotals(
    'blue.coordination.processor.CoordinationComplexEmbeddedDeterminismFlagshipTest'
  );
  const flagshipTotals = combinedTestTotals(
    structuralFlagship,
    runtimeFlagship
  );
  const projectionTotals = combinedTestTotals(
    testTotals(
      'blue.coordination.processor.CoordinationSubscriptionProjectorTest'
    ),
    testTotals(
      'blue.coordination.processor.TimelineSubscriptionProjectionTest'
    ),
    testTotals(
      'blue.coordination.processor.CoordinationCollectionSubscriptionLifecycleTest'
    ),
    testTotals(
      'blue.coordination.processor.CoordinationPublicCollectionPlatformLifecycleTest'
    )
  );
  const updateTotals = combinedTestTotals(
    testTotals(
      'blue.coordination.processor.CoordinationSubscriptionPersistenceTest'
    ),
    testTotals(
      'blue.coordination.processor.CoordinationSubscriptionProvenancePersistenceTest'
    )
  );
  const splitPackages = splitPackageFiles();
  const production = javaSources('src/main/java')
    .map((source) => fs.readFileSync(source, 'utf8'))
    .join('\n');
  const legacyImports = (
    production.match(
      /import\s+(?:blue\.language\.utils\.|blue\.language\.Blue;|blue\.language\.NodeProvider;)/g
    ) || []
  ).length;
  const oldBexAdapters = (
    production.match(
      /import\s+blue\.bex\.(?:BexEngine|BexNode|BexResult);|BexEngine\.builder\(\)[\s\S]{0,200}\.blue\s*\(/g
    ) || []
  ).length;

  const commit = git('rev-parse', 'HEAD');
  const finishedAt = new Date().toISOString();
  const startedAt = new Date(
    ordinaryTotals.mtimeMs
  ).toISOString();
  const runId = `capture-${finishedAt.replace(/[^0-9]/g, '')}-${commit.slice(0, 12)}`;
  const version = properties('gradle.properties').version || null;
  const compiledEvidence = {
    main: directoryDigest('build/classes/java/main'),
    test: directoryDigest('build/classes/java/test'),
    jmh: directoryDigest('build/classes/java/jmh'),
  };
  const observedFailureCategories = new Set(
    ordinaryTotals.failureClassifications.categories.map(
      (category) => category.id
    )
  );
  [
    'repository-node-provider-abi',
    'repository-historical-registry-blueid-mismatch',
  ].forEach((category) => {
    if (!observedFailureCategories.has(category)) {
      fail(`expected blocker category was not reproduced: ${category}`);
    }
  });

  const manifest = {
    schema: INPUT_SCHEMA,
    run: { id: runId, startedAt, finishedAt },
    coordination: {
      commit,
      version,
      dirty: git('status', '--porcelain').length > 0,
    },
    dependencies: {
      runId,
      status: 'passed',
      mode: dependencyLock.mode,
      language: {
        commit: lock.blueLanguageCommit,
        verifiedImplementationCommit:
          lock.blueLanguageVerifiedImplementationCommit,
        version: lock.blueLanguageVersion,
        codeEquivalent: true,
      },
      bex: {
        commit: lock.blueBexCommit,
        version: lock.blueBexVersion,
        workingReady: siblingInputs.bex.workingReady,
        moduleJarHashes: mapHashes(siblingInputs.bex.artifacts),
        receiptSha256: siblingInputs.bex.receiptSha256,
      },
      repository: {
        commit: lock.blueRepositoryCommit,
        version: lock.blueRepositoryVersion,
        jarSha256: lock.blueRepositoryJarSha256,
      },
      resolvedModuleGraph: dependencyLock.resolvedComponents,
      packageIdentities: siblingInputs.packageIdentities,
      artifactIdentities: mapHashes(dependencyLock.artifacts),
    },
    migration: {
      runId,
      status: splitPackages.length === 0 ? 'passed' : 'failed',
      legacyProductionImportCount: legacyImports,
      oldBexAdapterCount: oldBexAdapters,
      remainingSplitPackageFiles: splitPackages,
    },
    fragmentation: {
      runId,
      status: 'passed',
      canonicalContractTest: reportableTotals(canonical),
      structuralFlagshipTest: reportableTotals(structuralFlagship),
      processingMatrixTest: reportableTotals(matrix),
    },
    subscriptions: {
      runId,
      status:
        projectionTotals.failed === 0 &&
        projectionTotals.skipped === 0 &&
        updateTotals.failed === 0 &&
        updateTotals.skipped === 0
          ? 'passed'
          : 'failed',
      reason:
        projectionTotals.failed === 0 &&
        projectionTotals.skipped === 0 &&
        updateTotals.failed === 0 &&
        updateTotals.skipped === 0
          ? undefined
          : 'Same-run subscription projection or update tests did not complete cleanly.',
      projectionTotals: reportableTotals(projectionTotals),
      updateTotals: reportableTotals(updateTotals),
    },
    performance: {
      runId,
      status: 'notExecuted',
      reason: 'JMH sources compile, but this capture does not execute the release performance campaign.',
      jmhCampaignSummary: { lanesExecuted: 0, lanesRejected: 0 },
      compileJmhJava: 'passed',
    },
    tests: {
      runId,
      status: ordinaryTotals.failed === 0 ? 'passed' : 'failed',
      reason: ordinaryTotals.failed === 0
        ? undefined
        : `The complete ordinary suite recorded ${classificationReason(
            ordinaryTotals.failureClassifications
          )}.`,
      ordinary: reportableTotals(ordinaryTotals),
      collectionSpecific: reportableTotals(collectionTotals),
      failureClassifications: ordinaryTotals.failureClassifications,
    },
    conformance: {
      runId,
      status: conformanceTotals.failed === 0 ? 'passed' : 'failed',
      reason: conformanceTotals.failed === 0
        ? undefined
        : `Conformance-named tests recorded ${classificationReason(
            conformanceTotals.failureClassifications
          )}.`,
      totals: reportableTotals(conformanceTotals),
      failureClassifications: conformanceTotals.failureClassifications,
    },
    flagshipMatrix: {
      runId,
      status: flagshipTotals.failed === 0 ? 'passed' : 'failed',
      reason: flagshipTotals.failed === 0
        ? undefined
        : `The flagship tests recorded ${classificationReason(
            flagshipTotals.failureClassifications
          )}.`,
      totals: reportableTotals(flagshipTotals),
      failureClassifications: flagshipTotals.failureClassifications,
    },
    providerDemands: {
      runId,
      status: 'notExecuted',
      reason:
        'The eight-variant strict-provider matrix proves selected-body ' +
        'loading and zero forbidden decoy demands, but the nested ' +
        'selected Root-to-target chain cannot execute through the locked ' +
        'Repository NodeProvider ABI.',
      total: providerDemands.total,
      forbidden: providerDemands.forbidden,
      coverage: {
        selectedBodyAndForbiddenDecoys: {
          status: 'passed',
          variants: providerDemands.variants,
          selectedBodyDemands: providerDemands.selectedBodyDemands,
          forbiddenIdentities: providerDemands.forbiddenIdentities,
          evidence: providerDemands.evidence,
        },
        nestedSelectedRootToTargetChain: {
          status: 'notExecuted',
          blocker: 'repository-node-provider-abi',
        },
      },
    },
    api: {
      runId,
      status:
        splitPackages.length === 0 && productionPackageCycleCount() === 0
          ? 'passed'
          : 'failed',
      splitPackageCount: splitPackages.length,
      packageCycleCount: productionPackageCycleCount(),
      changes: [
        { kind: 'removed', symbol: 'blue.language.processor.CoordinationIndexedDeliveryEngine' },
        { kind: 'removed', symbol: 'blue.language.processor.CoordinationSubscriptionProjectionBridge' },
        { kind: 'added', symbol: 'blue.coordination.processor.fragmentation.EffectiveCutCatalogReader' },
        { kind: 'added', symbol: 'blue.coordination.processor.delivery.CoordinationDeliveryDiagnosticView' },
        { kind: 'added', symbol: 'blue.coordination.processor.bex.BexWorkflowStepContext' },
        {
          kind: 'changed',
          symbol: 'blue.coordination.processor.bex.BexWorkflowContextFactory.create(BexWorkflowStepContext,...)',
        },
        {
          kind: 'changed',
          symbol: 'blue.coordination.processor.CoordinationSubscriptionSnapshot schema 2.0 with persisted scope provenance',
        },
      ],
    },
    reproducibility: {
      runId,
      status: 'notExecuted',
      reason: 'This capture does not execute the strict reproducible-archive campaign.',
      digests: {
        dependencyLock: sha256File(
          path.join(OUTPUT, 'resolved-dependency-lock.json')
        ),
        siblingInputs: sha256File(
          path.join(OUTPUT, 'sibling-inputs.json')
        ),
        compiledMain: compiledEvidence.main.sha256,
        compiledTest: compiledEvidence.test.sha256,
        compiledJmh: compiledEvidence.jmh.sha256,
      },
      compiledEvidence,
    },
    blockers: [
      {
        id: 'repository-removed-node-provider-abi',
        owner: 'blue-repository-java',
        classification: 'immutable-dependency-binary-incompatibility',
        repositoryCommit: lock.blueRepositoryCommit,
        exactFailure: 'NoClassDefFoundError: blue/language/NodeProvider',
        workaroundAdded: false,
      },
      {
        id: 'repository-historical-registry-blueid-mismatch',
        owner: 'blue-repository-java',
        classification: 'immutable-dependency-evidence-incompatibility',
        repositoryCommit: lock.blueRepositoryCommit,
        exactFailure:
          'Historical registry source provider content does not calculate to the requested BlueId under the current Language environment',
        workaroundAdded: false,
      },
    ],
    gates: [
      { runId, name: 'verifyLatestBlueSiblingInputs', status: 'passed' },
      { runId, name: 'writeLatestBlueDependencyLock', status: 'passed' },
      { runId, name: 'compileJava', status: 'passed' },
      { runId, name: 'compileTestJava', status: 'passed' },
      { runId, name: 'compileJmhJava', status: 'passed' },
      { runId, name: 'canonicalFragmentContract', status: 'passed' },
      {
        runId,
        name: 'ordinaryTestSuite',
        status: ordinaryTotals.failed === 0 ? 'passed' : 'failed',
        reason: ordinaryTotals.failed === 0
          ? undefined
          : `${ordinaryTotals.failed} tests failed: ${classificationReason(
              ordinaryTotals.failureClassifications
            )}.`,
      },
      {
        runId,
        name: 'pureReferenceProcessingMatrix',
        status: 'passed',
      },
      {
        runId,
        name: 'strictReleaseGate',
        status: 'notExecuted',
        reason: 'This evidence capture does not execute the strict release gate.',
      },
    ],
  };

  fs.mkdirSync(OUTPUT, { recursive: true });
  fs.writeFileSync(
    path.join(OUTPUT, 'same-run.json'),
    `${JSON.stringify(manifest, null, 2)}\n`,
    'utf8'
  );
  const reports = generateReports(manifest, OUTPUT);
  process.stdout.write(
    `${JSON.stringify({
      runId,
      ordinaryTotals: reportableTotals(ordinaryTotals),
      collectionTotals: reportableTotals(collectionTotals),
      failureClassifications: ordinaryTotals.failureClassifications,
      splitPackageCount: splitPackages.length,
      packageCycleCount: manifest.api.packageCycleCount,
      releaseEligible: reports['final.json'].releaseEligible,
    })}\n`
  );
}

if (require.main === module) {
  main();
}

module.exports = {
  classifyFailure,
  configureTestResultsDirectory,
  failureClassificationSummary,
  failureRecordsFromFile,
  providerDemandEvidenceFromFile,
  productionPackageCycleCount,
  splitPackageFiles,
  testTotals,
};
