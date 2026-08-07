#!/usr/bin/env node

'use strict';

const fs = require('fs');
const path = require('path');

const ROOT = path.resolve(__dirname, '..');
const SOURCE_LOCK = path.join(ROOT, 'gradle/blue-sibling-lock.properties');
const sourceLockText = fs.readFileSync(SOURCE_LOCK, 'utf8');
function sourceLockValue(name) {
  const match = new RegExp(`^${name}=([^\\r\\n]+)$`, 'm').exec(
    sourceLockText
  );
  if (!match || match[1].trim().length === 0) {
    throw new Error(
      `Coordination external-blocker catalog: missing ${name} in ` +
        SOURCE_LOCK
    );
  }
  return match[1].trim();
}
const LOCKED_REPOSITORY_COMMIT = sourceLockValue('blueRepositoryCommit');
const LOCKED_REPOSITORY_VERSION = sourceLockValue(
  'blueRepositoryLocalVersion'
);
const DEFAULT_RESULTS = path.join(
  ROOT,
  'build/test-results/coordinationReleaseEvidenceTest'
);
const BEHAVIOR_FIXTURE_CLASS =
  'blue.coordination.processor.CoordinationBehaviorFixtureHarnessTest';

const BLOCKER_DEFINITIONS = [
  {
    id: 'repository-node-provider-abi',
    owner: 'blue-repository-java',
    status: 'open',
    firstObservedAgainst: {
      commit: LOCKED_REPOSITORY_COMMIT,
      version: LOCKED_REPOSITORY_VERSION,
    },
    category: 'immutable-dependency-binary-incompatibility',
    failureType: 'java.lang.NoClassDefFoundError',
    logicalMessagePrefix: 'blue/language/NodeProvider',
    reproductionCommand:
      './gradlew coordinationExternalBlockerProbeTest --offline --no-daemon -PtestJfr=false',
    notes:
      'The locked immutable Repository bytecode references the removed ' +
      'blue.language.NodeProvider ABI.',
    matches(logicalMessage) {
      return logicalMessage.startsWith(this.logicalMessagePrefix);
    },
  },
  {
    id: 'repository-historical-registry-blueid-mismatch',
    owner: 'blue-repository-java',
    status: 'open',
    firstObservedAgainst: {
      commit: LOCKED_REPOSITORY_COMMIT,
      version: LOCKED_REPOSITORY_VERSION,
    },
    category: 'immutable-dependency-evidence-incompatibility',
    failureType: 'java.lang.IllegalArgumentException',
    logicalMessagePrefix:
      'Historical registry source src/main/resources/registry/',
    reproductionCommand:
      './gradlew coordinationExternalBlockerProbeTest --offline --no-daemon -PtestJfr=false',
    notes:
      'The locked historical Repository registry content no longer ' +
      'calculates to its requested BlueIds under the current Language ' +
      'environment.',
    matches(logicalMessage) {
      return (
        logicalMessage.startsWith(this.logicalMessagePrefix) &&
        logicalMessage.includes('Provider returned content with BlueId') &&
        logicalMessage.includes('for requested BlueId')
      );
    },
  },
];

function fail(message) {
  throw new Error(`Coordination external-blocker catalog: ${message}`);
}

function compareText(left, right) {
  return left < right ? -1 : left > right ? 1 : 0;
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

function requiredCount(attributes, name, resultPath) {
  const value = xmlAttribute(attributes, name);
  if (value === null || !/^\d+$/.test(value)) {
    fail(`${resultPath} has no valid ${name} count`);
  }
  return Number(value);
}

function normalizeTestIdentity(className, testName) {
  let normalizedName = testName.endsWith('()')
    ? testName.slice(0, -2)
    : testName;
  const dynamic = /^\d+: (.+)$/.exec(normalizedName);
  if (dynamic && className === BEHAVIOR_FIXTURE_CLASS) {
    normalizedName = dynamic[1];
  }
  return `${className}#${normalizedName}`;
}

function logicalFailureMessage(failureType, message) {
  const wrapper = `${failureType}: `;
  return message.startsWith(wrapper)
    ? message.slice(wrapper.length)
    : message;
}

function classifyFailure(record) {
  const matches = BLOCKER_DEFINITIONS.filter(
    (definition) =>
      definition.failureType === record.failureType &&
      definition.matches(record.logicalMessage)
  );
  if (matches.length !== 1) {
    const observed =
      `${record.failureType}: ${record.logicalMessage}`.slice(0, 500);
    if (matches.length === 0) {
      fail(`unclassified failure ${record.id}: ${observed}`);
    }
    fail(
      `ambiguous failure ${record.id}: ` +
        matches.map((definition) => definition.id).join(', ')
    );
  }
  return matches[0].id;
}

function junitFiles(resultsDirectory) {
  if (
    !fs.existsSync(resultsDirectory) ||
    !fs.statSync(resultsDirectory).isDirectory()
  ) {
    fail(`JUnit result directory is missing: ${resultsDirectory}`);
  }
  const files = [];
  const visit = (directory) => {
    fs.readdirSync(directory, { withFileTypes: true })
      .sort((left, right) => compareText(left.name, right.name))
      .forEach((entry) => {
        const target = path.join(directory, entry.name);
        if (entry.isDirectory()) {
          visit(target);
        } else if (
          entry.isFile() &&
          entry.name.startsWith('TEST-') &&
          entry.name.endsWith('.xml')
        ) {
          files.push(target);
        }
      });
  };
  visit(resultsDirectory);
  files.sort((left, right) =>
    compareText(
      path.relative(resultsDirectory, left),
      path.relative(resultsDirectory, right)
    )
  );
  if (files.length === 0) {
    fail(`no Gradle TEST-*.xml files in ${resultsDirectory}`);
  }
  return files;
}

function recordsFromFile(target, resultsDirectory) {
  const resultPath = path
    .relative(resultsDirectory, target)
    .split(path.sep)
    .join('/');
  const xml = fs.readFileSync(target, 'utf8');
  const suites = Array.from(xml.matchAll(/<testsuite\b([^>]*)>/g));
  if (suites.length !== 1) {
    fail(`${resultPath} must contain exactly one testsuite`);
  }
  const suiteAttributes = suites[0][1];
  const declared = {
    tests: requiredCount(suiteAttributes, 'tests', resultPath),
    skipped: requiredCount(suiteAttributes, 'skipped', resultPath),
    failures: requiredCount(suiteAttributes, 'failures', resultPath),
    errors: requiredCount(suiteAttributes, 'errors', resultPath),
  };
  const records = [];
  const testCasePattern =
    /<testcase\b([^>]*?)(?:\/>|>([\s\S]*?)<\/testcase>)/g;
  for (const match of xml.matchAll(testCasePattern)) {
    const attributes = match[1];
    const body = match[2] || '';
    const className = xmlAttribute(attributes, 'classname');
    const testName = xmlAttribute(attributes, 'name');
    if (!className || !testName) {
      fail(`${resultPath} has a testcase without classname or name`);
    }
    const failures = Array.from(
      body.matchAll(
        /<(failure|error)\b([^>]*?)(?:\/>|>([\s\S]*?)<\/\1>)/g
      )
    );
    const skipped = /<skipped\b/.test(body);
    if (failures.length > 1 || (failures.length === 1 && skipped)) {
      fail(
        `${resultPath} has an ambiguous testcase outcome for ` +
          `${className}#${testName}`
      );
    }
    if (skipped) {
      records.push({
        id: normalizeTestIdentity(className, testName),
        status: 'skipped',
        failureElement: null,
      });
      continue;
    }
    if (failures.length === 0) {
      records.push({
        id: normalizeTestIdentity(className, testName),
        status: 'passed',
        failureElement: null,
      });
      continue;
    }
    const failure = failures[0];
    const failureType = xmlAttribute(failure[2], 'type');
    const failureBody = decodeXml(
      (failure[3] || '').replace(/<!\[CDATA\[|\]\]>/g, '')
    );
    const message =
      xmlAttribute(failure[2], 'message') ||
      failureBody.split(/\r?\n/)[0] ||
      '';
    if (!failureType || !message) {
      fail(
        `${resultPath} has a failure without exact type and message for ` +
          `${className}#${testName}`
      );
    }
    const record = {
      id: normalizeTestIdentity(className, testName),
      status: 'failed',
      failureElement: failure[1],
      failureType,
      logicalMessage: logicalFailureMessage(failureType, message),
    };
    record.blockerId = classifyFailure(record);
    records.push(record);
  }
  const observed = {
    tests: records.length,
    skipped: records.filter((record) => record.status === 'skipped').length,
    failures: records.filter(
      (record) =>
        record.status === 'failed' && record.failureElement === 'failure'
    ).length,
    errors: records.filter(
      (record) =>
        record.status === 'failed' && record.failureElement === 'error'
    ).length,
  };
  Object.keys(declared).forEach((name) => {
    if (declared[name] !== observed[name]) {
      fail(
        `${resultPath} declares ${name}=${declared[name]} but contains ` +
          `${observed[name]}`
      );
    }
  });
  return records;
}

function blockerFromDefinition(definition, probes) {
  return {
    id: definition.id,
    owner: definition.owner,
    status: definition.status,
    firstObservedAgainst: definition.firstObservedAgainst,
    category: definition.category,
    failureType: definition.failureType,
    logicalMessagePrefix: definition.logicalMessagePrefix,
    reproductionCommand: definition.reproductionCommand,
    notes: definition.notes,
    probes: probes.map((test) => ({ test })),
  };
}

function generateCatalog(resultsDirectory) {
  const absoluteResults = path.resolve(resultsDirectory);
  const records = junitFiles(absoluteResults).flatMap((target) =>
    recordsFromFile(target, absoluteResults)
  );
  if (records.length === 0) {
    fail('the full JUnit suite contains no testcases');
  }
  const byIdentity = new Map();
  records.forEach((record) => {
    if (byIdentity.has(record.id)) {
      fail(`duplicate normalized test identity: ${record.id}`);
    }
    byIdentity.set(record.id, record);
  });
  const skipped = records
    .filter((record) => record.status === 'skipped')
    .map((record) => record.id)
    .sort(compareText);
  if (skipped.length > 0) {
    fail(`skipped tests are forbidden: ${skipped.join(', ')}`);
  }
  const failed = records.filter((record) => record.status === 'failed');
  const blockers = BLOCKER_DEFINITIONS.map((definition) => {
    const probes = failed
      .filter((record) => record.blockerId === definition.id)
      .map((record) => record.id)
      .sort(compareText);
    return probes.length === 0
      ? null
      : blockerFromDefinition(definition, probes);
  }).filter(Boolean);
  return {
    schema: 'blue-coordination/external-blockers/1.2',
    expectedSuite: {
      full: records.length,
      working: records.length - failed.length,
      probes: failed.length,
    },
    blockers,
  };
}

function writeCatalog(catalog, outputPath) {
  const serialized = `${JSON.stringify(catalog, null, 2)}\n`;
  if (!outputPath) {
    process.stdout.write(serialized);
    return;
  }
  const absoluteOutput = path.resolve(outputPath);
  fs.mkdirSync(path.dirname(absoluteOutput), { recursive: true });
  fs.writeFileSync(absoluteOutput, serialized, 'utf8');
}

function main(argumentsList = process.argv.slice(2)) {
  if (argumentsList.includes('--help') || argumentsList.length > 2) {
    process.stdout.write(
      'Usage: node tools/generate-coordination-external-blockers.js ' +
        '[junit-results-directory] [output-json]\n'
    );
    return;
  }
  const resultsDirectory = argumentsList[0]
    ? path.resolve(argumentsList[0])
    : DEFAULT_RESULTS;
  const outputPath = argumentsList[1]
    ? path.resolve(argumentsList[1])
    : null;
  writeCatalog(generateCatalog(resultsDirectory), outputPath);
}

if (require.main === module) {
  try {
    main();
  } catch (error) {
    process.stderr.write(`${error.message}\n`);
    process.exitCode = 1;
  }
}

module.exports = {
  BLOCKER_DEFINITIONS,
  generateCatalog,
  normalizeTestIdentity,
  recordsFromFile,
  writeCatalog,
};
