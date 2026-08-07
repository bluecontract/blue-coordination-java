#!/usr/bin/env node

'use strict';

const crypto = require('crypto');
const fs = require('fs');
const path = require('path');

const TRACE_SCHEMA =
  'blue-coordination/nested-agreement-flagship-trace/1.0';
const EVIDENCE_STATUSES = new Set([
  'passed',
  'failed',
  'notExecuted',
]);
const RUNTIME_RESULT_FIELDS = [
  'events',
  'matrixTotals',
  'subscriptionTransitions',
  'maximumGasTrace',
  'providerDemands',
  'causalTrace',
];

function fail(message) {
  throw new Error(`Nested agreement trace: ${message}`);
}

function object(value, label) {
  if (value === null || typeof value !== 'object' || Array.isArray(value)) {
    fail(`${label} must be an object`);
  }
  return value;
}

function array(value, label) {
  if (!Array.isArray(value)) {
    fail(`${label} must be an array`);
  }
  return value;
}

function text(value, label) {
  if (typeof value !== 'string' || value.trim() === '') {
    fail(`${label} must be non-empty text`);
  }
  return value;
}

function nonNegativeInteger(value, label) {
  if (!Number.isInteger(value) || value < 0) {
    fail(`${label} must be a non-negative integer`);
  }
  return value;
}

function status(value, label) {
  if (!EVIDENCE_STATUSES.has(value)) {
    fail(`${label} has unsupported status ${value}`);
  }
  return value;
}

function textArray(value, label) {
  const values = array(value, label);
  if (values.length === 0) {
    fail(`${label} must not be empty`);
  }
  values.forEach((entry, index) => text(entry, `${label}[${index}]`));
  return values;
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

function prettyJson(value) {
  return JSON.stringify(deepSort(value), null, 2);
}

function sha256(bytes) {
  return crypto.createHash('sha256').update(bytes).digest('hex');
}

function validateStructuralEvidence(value) {
  const structural = object(value, 'structuralEvidence');
  status(structural.status, 'structuralEvidence.status');
  textArray(structural.sourceTests, 'structuralEvidence.sourceTests');
  if (structural.status === 'passed') {
    if (structural.scopePlan === undefined) {
      fail('structuralEvidence.scopePlan is required when structural evidence passed');
    }
    if (structural.fragmentInventory === undefined) {
      fail(
        'structuralEvidence.fragmentInventory is required when structural evidence passed'
      );
    }
    if (structural.reconstruction === undefined) {
      fail(
        'structuralEvidence.reconstruction is required when structural evidence passed'
      );
    }
  } else {
    text(structural.diagnostic, 'structuralEvidence.diagnostic');
  }
  return structural;
}

function validateRuntimeLane(value, index) {
  const label = `runtimeLanes[${index}]`;
  const lane = object(value, label);
  text(lane.id, `${label}.id`);
  status(lane.status, `${label}.status`);
  nonNegativeInteger(lane.declaredScenarios, `${label}.declaredScenarios`);
  nonNegativeInteger(lane.attemptedScenarios, `${label}.attemptedScenarios`);
  nonNegativeInteger(lane.completedScenarios, `${label}.completedScenarios`);
  if (lane.attemptedScenarios > lane.declaredScenarios) {
    fail(`${label}.attemptedScenarios exceeds declaredScenarios`);
  }
  if (lane.completedScenarios > lane.attemptedScenarios) {
    fail(`${label}.completedScenarios exceeds attemptedScenarios`);
  }
  if (lane.status === 'passed') {
    if (
      lane.attemptedScenarios !== lane.declaredScenarios ||
      lane.completedScenarios !== lane.declaredScenarios
    ) {
      fail(`${label} cannot pass without completing every declared scenario`);
    }
  } else if (lane.status === 'failed') {
    text(lane.diagnostic, `${label}.diagnostic`);
  } else if (
    lane.attemptedScenarios !== 0 ||
    lane.completedScenarios !== 0
  ) {
    fail(`${label} marked notExecuted must have zero attempted and completed scenarios`);
  }
  return lane;
}

function validateRuntimeStatus(trace, lanes) {
  const statuses = new Set(lanes.map((lane) => lane.status));
  if (trace.status === 'passed') {
    if (statuses.size !== 1 || !statuses.has('passed')) {
      fail('a passing trace requires every runtime lane to pass');
    }
    return;
  }
  if (trace.status === 'failed') {
    if (!statuses.has('failed')) {
      fail('a failed trace requires at least one failed runtime lane');
    }
    return;
  }
  if (statuses.size !== 1 || !statuses.has('notExecuted')) {
    fail('a notExecuted trace requires every runtime lane to be notExecuted');
  }
}

function validatePassingRuntimeResults(trace) {
  RUNTIME_RESULT_FIELDS.slice(0, 5).forEach((field) => {
    if (trace[field] === undefined || trace[field] === null) {
      fail(`${field} is required for a passing runtime trace`);
    }
  });
  const events = array(trace.events, 'events');
  if (events.length === 0) {
    fail('events must contain observed PROCESS invocations');
  }
  events.forEach((event, index) => {
    object(event, `events[${index}]`);
    text(event.id, `events[${index}].id`);
    text(event.target, `events[${index}].target`);
    if (event.status !== 'SUCCESS') {
      fail(`events[${index}].status must be SUCCESS in a passing trace`);
    }
  });
}

function validateTrace(value) {
  const trace = object(value, 'trace');
  if (trace.schema !== TRACE_SCHEMA) {
    fail(`unsupported schema ${trace.schema}`);
  }
  status(trace.status, 'status');
  const run = object(trace.run, 'run');
  text(run.id, 'run.id');
  textArray(run.sourceTests, 'run.sourceTests');
  if (run.finishedAt !== undefined) {
    text(run.finishedAt, 'run.finishedAt');
  }
  validateStructuralEvidence(trace.structuralEvidence);
  const lanes = array(trace.runtimeLanes, 'runtimeLanes');
  if (lanes.length === 0) {
    fail('runtimeLanes must not be empty');
  }
  const validatedLanes = lanes.map(validateRuntimeLane);
  validateRuntimeStatus(trace, validatedLanes);
  if (trace.status === 'passed') {
    validatePassingRuntimeResults(trace);
  } else {
    const claimed = RUNTIME_RESULT_FIELDS.filter(
      (field) => trace[field] !== undefined
    );
    if (claimed.length > 0) {
      fail(
        `non-passing trace must not publish runtime result fields: ${claimed.join(', ')}`
      );
    }
  }
  return trace;
}

function markdownCell(value) {
  return String(value).replace(/\|/g, '\\|').replace(/\n/g, '<br>');
}

function section(title, value) {
  return [
    `## ${title}`,
    '',
    '```json',
    prettyJson(value),
    '```',
    '',
  ].join('\n');
}

function laneDiagnostic(lane) {
  return lane.diagnostic || '';
}

function renderTrace(traceValue, sourceBytes) {
  const trace = validateTrace(traceValue);
  const lines = [
    '<!-- GENERATED FILE: tools/publish-nested-agreement-trace.js -->',
    '',
    '# Nested agreement flagship evidence',
    '',
    `Evidence status: \`${trace.status}\``,
    '',
    `Run: \`${trace.run.id}\``,
    '',
  ];
  if (trace.run.finishedAt !== undefined) {
    lines.push(`Finished: \`${trace.run.finishedAt}\``, '');
  }
  lines.push(
    `Source trace SHA-256: \`${sha256(sourceBytes)}\``,
    '',
    'This file is generated from the structured trace named above.',
    'Structural results and PROCESS runtime results are separate evidence lanes.',
    'structural lane does not imply that any PROCESS scenario executed.',
    '',
    '## Evidence lanes',
    '',
    '| Lane | Status | Declared | Attempted | Completed | Diagnostic |',
    '|---|---|---:|---:|---:|---|',
    `| structural | ${markdownCell(trace.structuralEvidence.status)} | 1 | ` +
      `${trace.structuralEvidence.status === 'notExecuted' ? 0 : 1} | ` +
      `${trace.structuralEvidence.status === 'passed' ? 1 : 0} | ` +
      `${markdownCell(trace.structuralEvidence.diagnostic || '')} |`
  );
  trace.runtimeLanes.forEach((lane) => {
    lines.push(
      `| ${markdownCell(lane.id)} | ${markdownCell(lane.status)} | ` +
        `${lane.declaredScenarios} | ${lane.attemptedScenarios} | ` +
        `${lane.completedScenarios} | ${markdownCell(laneDiagnostic(lane))} |`
    );
  });
  lines.push('');

  if (trace.structuralEvidence.status === 'passed') {
    lines.push(
      section('Observed structural scope plan', trace.structuralEvidence.scopePlan)
    );
    lines.push(
      section(
        'Observed structural fragment inventory',
        trace.structuralEvidence.fragmentInventory
      )
    );
    lines.push(
      section(
        'Observed structural reconstruction',
        trace.structuralEvidence.reconstruction
      )
    );
  }

  if (trace.status !== 'passed') {
    lines.push(
      '## PROCESS runtime result boundary',
      '',
      'No PROCESS event sequence, resulting Root, public event, subscription',
      'transition, gas trace, or provider-demand result is published for this',
      `\`${trace.status}\` trace. Scenarios with zero attempts were not executed.`,
      'The structural sections above, when present, are representation evidence',
      'only and are not runtime-semantic evidence.',
      ''
    );
    return `${lines.join('\n').replace(/\n{3,}/g, '\n\n')}\n`;
  }

  lines.push(
    '## Observed PROCESS sequence',
    '',
    '| Event | Target | Status | Resulting Root | Public events | Gas |',
    '|---|---|---|---|---|---:|'
  );
  trace.events.forEach((event) => {
    lines.push(
      `| ${markdownCell(event.id)} | ${markdownCell(event.target)} | ` +
        `${markdownCell(event.status)} | ` +
        `${markdownCell(event.resultingRootBlueId || '')} | ` +
        `${markdownCell(JSON.stringify(event.publicEvents || []))} | ` +
        `${markdownCell(event.gas === undefined ? '' : event.gas)} |`
    );
  });
  lines.push('');
  lines.push(section('Representation/provider matrix', trace.matrixTotals));
  lines.push(
    section('Subscription transitions', trace.subscriptionTransitions)
  );
  lines.push(section('Maximum gas trace', trace.maximumGasTrace));
  lines.push(section('Provider demands', trace.providerDemands));
  if (trace.causalTrace !== undefined) {
    lines.push(section('Causal trace', trace.causalTrace));
  }
  return `${lines.join('\n').replace(/\n{3,}/g, '\n\n')}\n`;
}

function parseArguments(values) {
  const result = {};
  for (let index = 0; index < values.length; index += 1) {
    if (values[index] === '--input') {
      result.input = values[++index];
    } else if (values[index] === '--output') {
      result.output = values[++index];
    } else {
      fail(`unknown argument ${values[index]}`);
    }
  }
  if (!result.input || !result.output) {
    fail('usage: --input <trace.json> --output <walkthrough.md>');
  }
  return result;
}

function publish(input, output) {
  const bytes = fs.readFileSync(input);
  const trace = JSON.parse(bytes.toString('utf8'));
  const markdown = renderTrace(trace, bytes);
  fs.mkdirSync(path.dirname(output), { recursive: true });
  fs.writeFileSync(output, markdown, 'utf8');
  return markdown;
}

function main() {
  const options = parseArguments(process.argv.slice(2));
  publish(options.input, options.output);
}

if (require.main === module) {
  main();
}

module.exports = {
  TRACE_SCHEMA,
  canonicalJson,
  publish,
  renderTrace,
  validateTrace,
};
