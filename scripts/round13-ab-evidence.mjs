#!/usr/bin/env node

import { execFileSync, spawnSync } from 'node:child_process';
import { createHash } from 'node:crypto';
import {
  appendFileSync, existsSync, lstatSync, mkdirSync, readFileSync, readlinkSync,
  readdirSync, realpathSync, statSync, writeFileSync
} from 'node:fs';
import {
  arch as osArch, cpus as osCpus, hostname as osHostname,
  platform as osPlatform, release as osRelease, totalmem as osTotalMemory
} from 'node:os';
import { basename, dirname, join, resolve, sep } from 'node:path';
import { fileURLToPath } from 'node:url';

const RUNTIME_SCHEMA = 'blue-coordination-round13-five-occurrence-runtime-v2';
const PROVENANCE_SCHEMA = 'blue-coordination-round13-runtime-provenance-v2';
const PREFLIGHT_SCHEMA = 'blue-coordination-round13-ab-preflight-v2';
const LEDGER_SCHEMA = 'blue-coordination-round13-ab-ledger-entry-v1';
const POSTFLIGHT_SCHEMA = 'blue-coordination-round13-ab-postflight-v2';
const SAMPLE_SCHEMA = 'round13-playground-sample-v3';
const HELPER_CAMPAIGN_SCHEMA = 'round13-playground-campaign-v3';
const NO_POSTFLIGHT_TOOLING_CHANGES_MARKER =
  'NO_POSTFLIGHT_TOOLING_CHANGES';
const NO_POSTFLIGHT_TOOLING_CHANGES_REASON =
  'The tracked importer and receipt generator are the same frozen file, and the '
  + 'runner, current Round12NbaEvidence, candidate execution and harness surfaces, '
  + 'fixtures, locks, wrapper, composites, baseline archive and project, Java '
  + 'runtime, and physical host must match their exact preflight bindings through '
  + 'postflight, receipt generation, and independent verification. No postflight '
  + 'tooling or test-lane substitution is permitted.';
const ROUND12_NBA_EVIDENCE_PATH =
  'src/scenarioTest/java/blue/coordination/integration/Round12NbaEvidence.java';
const BASELINE_HASH =
  '2f303afd9435937145ce7fe09eb655ff1a05ee6ed7484faea62d93544a3ccf3e';
const EXPECTED_PATHS = [
  '/children/alphaFirst',
  '/children/alphaSecond',
  '/children/betaFirst',
  '/children/betaSecond',
  '/children/gamma'
];
const COMMON_ZERO_COUNTERS = [
  'UNRELATED_DOCUMENT_READS',
  'REQUEST_FRAGMENTS',
  'TIMELINE_ENTRY_FRAGMENTS',
  'ORDINARY_NODE_FRAGMENTS',
  'FULL_ENVIRONMENT_SCANS',
  'SOURCE_REPLAYS_PER_PARENT',
  'POST_PROCESS_FULL_PROJECTIONS'
];
const CANDIDATE_ONLY_ZERO_COUNTERS = [
  'PARENT_PROCESS_RERUNS_ON_GRAPH_RETRY',
  'CHILD_PROCESS_RERUNS_ON_PARENT_RETRY'
];
const FIXTURE_FILES = [
  'src/integrationTest/java/blue/coordination/integration/PlaygroundFiveOccurrenceFixtures.java',
  'src/integrationTest/java/blue/coordination/integration/Round12NbaFixtures.java',
  'src/integrationTest/resources/examples/playground/five-occurrence-initialization-host.yaml',
  'src/integrationTest/resources/examples/round12/nba-lifecycle-game.yaml'
];
const HARNESS_HELPER =
  'src/scenarioTest/java/blue/coordination/integration/PlaygroundFiveOccurrenceEvidence.java';
const LOCK_FILES = [
  'gradle/bex-source.lock',
  'gradle/repository-source.lock',
  'gradle/published-artifact.lockfile',
  'gradle.lockfile'
];
const BASELINE_ALLOWED_DIFFERENCES = {
  '.cz.toml': 'ADDED',
  'build.gradle': 'MODIFIED',
  'src/integrationTest/java/blue/coordination/integration/PlaygroundFiveOccurrenceFixtures.java':
    'ADDED',
  'src/integrationTest/java/blue/coordination/integration/TestEngine.java': 'MODIFIED',
  'src/integrationTest/resources/examples/playground/five-occurrence-initialization-host.yaml':
    'ADDED',
  'src/scenarioTest/java/blue/coordination/integration/PlaygroundFiveOccurrenceEvidence.java':
    'ADDED'
};
const BASELINE_EXPECTED_SHIM_SHA256 = {
  '.cz.toml': '2a4dc5464760ba429e99012998be2068384aefbd1d926571f45e9f24c1dca417',
  'build.gradle': '3558f123a1c83cd50441f94af31682c6931c1d44074b20e691c9c74c21c32f1b',
  'src/integrationTest/java/blue/coordination/integration/PlaygroundFiveOccurrenceFixtures.java':
    'f12e892c213bbcf74d09e2b71ba1fe8f73327eead07ad3ffda3d3197a74b52f1',
  'src/integrationTest/java/blue/coordination/integration/TestEngine.java':
    'bf282e92b0f861d9352eb2ac7e05183c25337bb3462cd55151c1f045ebd9d4c0',
  'src/integrationTest/resources/examples/playground/five-occurrence-initialization-host.yaml':
    'b1e94c039d0fd0df70b0c803ebe921a290d1590bac69a9073fe4910ca8939b6b',
  'src/scenarioTest/java/blue/coordination/integration/PlaygroundFiveOccurrenceEvidence.java':
    'cbb936435d13c4bf5678fc6addedbf625801a4edc52c453c8a441caa1a2bf8d3'
};
const METRICS = [
  'requestPreparationNanos',
  'appendNanos',
  'routeNanos',
  'embeddedInputPreparationNanos',
  'hostBeforeFrozenNanos',
  'processFrozenNanos',
  'initializationFrozenNanos',
  'frozenNanos',
  'hostAfterFrozenNanos',
  'initializationHostBeforeFrozenNanos',
  'initializationHostAfterFrozenNanos',
  'graphPublicationNanos',
  'coordinationHostNanos',
  'totalNanos'
];
const FIXED_COUNTS = {
  wholeObjectsAdded: 4,
  wholeObjectInsertions: 42,
  wholeObjectDuplicates: 31,
  journalEntries: 1,
  documentCount: 4,
  routeRows: 13,
  embeddingBindings: 5,
  childSessionsCreated: 3,
  childSessionsReused: 2,
  childInitializationCalls: 3,
  childInitializationRevisions: 3,
  externalProcessCalls: 1,
  embeddedEpochProcessCalls: 5,
  initializationApplications: 5,
  initializationEventsForwarded: 5,
  parentApplications: 5,
  frozenProcessCalls: 6,
  hostInitializationEvents: 5,
  alphaInitializationEvents: 2,
  betaInitializationEvents: 2,
  gammaInitializationEvents: 1,
  hostRevisionApplications: 5
};
const GATE_SPECS = {
  appendP95: ['appendNanos', 250_000, 1_000_000],
  routeP95: ['routeNanos', 50_000, 200_000],
  coordinationHostP95: ['coordinationHostNanos', 120_000_000, 250_000_000],
  totalP95: ['totalNanos', 2_500_000_000, 4_000_000_000]
};
const RUNTIME_STRING_FIELDS = [
  'javaVersion', 'javaVendor', 'javaHome', 'osName', 'osVersion', 'osArch'
];
const IGNORED_TREE_SEGMENTS = new Set([
  '.git', '.gradle', 'build', 'out', 'target', 'node_modules'
]);
const ROOT_BUILD_FILES = new Set([
  '.cz.toml', 'build.gradle', 'build.gradle.kts', 'settings.gradle',
  'settings.gradle.kts', 'gradle.properties', 'gradlew', 'gradlew.bat'
]);

function fail(message) {
  throw new Error(message);
}

function parseArgs(argv) {
  const result = {};
  const modes = new Set([
    'verify', 'capture-preflight', 'append-ledger', 'capture-postflight'
  ]);
  for (let index = 0; index < argv.length; index += 1) {
    const value = argv[index];
    if (!value.startsWith('--')) fail(`Unexpected argument ${value}`);
    const key = value.slice(2);
    if (modes.has(key)) {
      result[key] = true;
      continue;
    }
    if (index + 1 >= argv.length) fail(`Missing value for ${value}`);
    result[key] = argv[index += 1];
  }
  const selected = [...modes].filter(mode => result[mode]);
  if (selected.length > 1) fail(`Choose only one mode, got ${selected.join(', ')}`);
  return result;
}

function required(args, key) {
  if (!args[key]) fail(`Missing --${key}`);
  return resolve(args[key]);
}

function requiredText(args, key) {
  if (!args[key] || !String(args[key]).trim()) fail(`Missing --${key}`);
  return String(args[key]);
}

function requiredInteger(args, key) {
  const value = Number.parseInt(requiredText(args, key), 10);
  if (!Number.isSafeInteger(value)) fail(`Invalid --${key}`);
  return value;
}

function sha256(value) {
  return createHash('sha256').update(value).digest('hex');
}

function canonicalValue(value) {
  if (Array.isArray(value)) return value.map(canonicalValue);
  if (value !== null && typeof value === 'object') {
    return Object.fromEntries(Object.keys(value).sort()
      .map(key => [key, canonicalValue(value[key])]));
  }
  return value;
}

function canonicalJson(value) {
  return JSON.stringify(canonicalValue(value));
}

function compare(label, actual, expected) {
  if (canonicalJson(actual) !== canonicalJson(expected)) fail(`${label} differs`);
}

function readJson(file) {
  try {
    return JSON.parse(readFileSync(file, 'utf8'));
  } catch (error) {
    fail(`${file}: invalid JSON (${error.message})`);
  }
}

function writeJson(file, value) {
  mkdirSync(dirname(file), { recursive: true });
  writeFileSync(file, `${JSON.stringify(value, null, 2)}\n`);
}

function slash(path) {
  return path.split(sep).join('/');
}

function isMetadataPath(path) {
  const parts = path.split('/');
  return parts.includes('__MACOSX') || parts.includes('.DS_Store')
    || parts.some(part => part.startsWith('._'));
}

function shouldIgnoreTreePath(path) {
  if (isMetadataPath(path)) return true;
  return path.split('/').some(part => IGNORED_TREE_SEGMENTS.has(part));
}

function walkFiles(root, current = '') {
  const directory = current ? join(root, current) : root;
  return readdirSync(directory).sort().flatMap(name => {
    const path = current ? `${slash(current)}/${name}` : name;
    if (shouldIgnoreTreePath(path)) return [];
    const file = join(root, path);
    const stat = lstatSync(file);
    if (stat.isDirectory()) return walkFiles(root, path);
    return [path];
  });
}

function fileEntry(root, path, includeMode) {
  const file = join(root, path);
  const stat = lstatSync(file);
  const bytes = stat.isSymbolicLink()
    ? Buffer.from(`SYMLINK\0${readlinkSync(file)}`)
    : readFileSync(file);
  const entry = {
    path,
    type: stat.isSymbolicLink() ? 'symlink' : 'file',
    size: bytes.length,
    sha256: sha256(bytes)
  };
  if (includeMode) entry.mode = stat.mode & 0o777;
  return entry;
}

function manifestDigest(entries, includeMode) {
  const rows = entries.map(entry => [
    entry.path, entry.type, entry.size, entry.sha256,
    includeMode ? entry.mode : ''
  ].join('\0'));
  return sha256(rows.join('\n'));
}

function manifestFromPaths(root, paths, includeMode = true) {
  const entries = [...new Set(paths)].sort().map(path => fileEntry(root, path, includeMode));
  return {
    algorithm: 'SHA-256',
    includeMode,
    files: entries.length,
    entries,
    sha256: manifestDigest(entries, includeMode)
  };
}

function manifestFromFilter(root, predicate, includeMode = true) {
  return manifestFromPaths(root, walkFiles(root).filter(predicate), includeMode);
}

function codePointCompare(left, right) {
  return left < right ? -1 : left > right ? 1 : 0;
}

function localePathCompare(left, right) {
  return left.localeCompare(right);
}

function validateStoredManifest(label, manifest, comparator = codePointCompare) {
  if (manifest?.algorithm !== 'SHA-256' || !Array.isArray(manifest.entries)
      || manifest.files !== manifest.entries.length
      || typeof manifest.includeMode !== 'boolean') fail(`${label}: malformed manifest`);
  let previous = '';
  for (const entry of manifest.entries) {
    if (typeof entry.path !== 'string' || !entry.path
        || (previous && comparator(previous, entry.path) >= 0)
        || !['file', 'symlink'].includes(entry.type)
        || !Number.isSafeInteger(entry.size) || entry.size < 0
        || !/^[0-9a-f]{64}$/.test(entry.sha256)
        || (manifest.includeMode
          && (!Number.isSafeInteger(entry.mode) || entry.mode < 0))) {
      fail(`${label}: invalid manifest entry`);
    }
    previous = entry.path;
  }
  if (manifest.sha256 !== manifestDigest(manifest.entries, manifest.includeMode)) {
    fail(`${label}: manifest digest differs`);
  }
}

function mainSourceManifest(project) {
  const root = join(project, 'src/main/java');
  const rows = walkFiles(root).filter(path => path.endsWith('.java')).sort()
    .map(path => `src/main/java/${path} ${sha256(readFileSync(join(root, path)))}`);
  return { files: rows.length, sha256: sha256(rows.join('\n')) };
}

function isBuildInput(path) {
  const name = basename(path);
  return ROOT_BUILD_FILES.has(name) || path.startsWith('gradle/')
    || path.includes('/gradle/') || name.endsWith('.lockfile')
    || name.endsWith('-source.lock');
}

function isCandidateExecutionPath(path) {
  return path.startsWith('src/main/') || path.startsWith('src/scenarioTest/')
    || path.startsWith('src/integrationTest/') || path.startsWith('src/consumerTest/')
    || path.startsWith('src/testFixtures/') || path.startsWith('scripts/')
    || isBuildInput(path);
}

function isHarnessPath(path) {
  return path.startsWith('src/scenarioTest/') || path.startsWith('src/integrationTest/')
    || path.startsWith('src/consumerTest/') || path.startsWith('src/testFixtures/')
    || path === 'scripts/round13-ab-evidence.mjs'
    || path === 'scripts/run-round13-same-machine-ab.sh' || isBuildInput(path);
}

function isCompositeExecutionPath(path) {
  return path.startsWith('src/') || path.includes('/src/')
    || path.startsWith('scripts/') || path.includes('/scripts/') || isBuildInput(path);
}

function fileBindings(project, paths) {
  return paths.map(path => {
    const file = join(project, path);
    if (!existsSync(file)) fail(`${project}: missing binding ${path}`);
    return { path, size: statSync(file).size, sha256: sha256(readFileSync(file)) };
  });
}

function bindingHash(rows) {
  return sha256(rows.map(row => `${row.path} ${row.size} ${row.sha256}`).join('\n'));
}

function gitOutput(project, ...args) {
  return execFileSync('git', ['-C', project, ...args], {
    encoding: 'utf8'
  }).trim();
}

function archiveFiles(archive) {
  const names = execFileSync('unzip', ['-Z1', archive], {
    encoding: 'utf8', maxBuffer: 32 * 1024 * 1024
  }).split(/\r?\n/).filter(Boolean).filter(name => !name.endsWith('/'));
  const meaningful = names.filter(name => !isMetadataPath(name));
  const hasRootBuild = meaningful.includes('build.gradle');
  let prefix = '';
  if (!hasRootBuild) {
    const firstParts = new Set(meaningful.map(name => name.split('/')[0]));
    if (firstParts.size !== 1) fail('Archive root layout is ambiguous');
    prefix = `${[...firstParts][0]}/`;
  }
  return meaningful.map(entry => ({
    entry,
    path: entry.startsWith(prefix) ? entry.slice(prefix.length) : entry
  })).filter(row => row.path && !shouldIgnoreTreePath(row.path));
}

function archiveManifest(archive) {
  const entries = archiveFiles(archive).map(({ entry, path }) => {
    const bytes = execFileSync('unzip', ['-p', archive, entry], {
      encoding: null, maxBuffer: 64 * 1024 * 1024
    });
    return { path, type: 'file', size: bytes.length, sha256: sha256(bytes) };
  }).sort((a, b) => a.path.localeCompare(b.path));
  return {
    algorithm: 'SHA-256',
    includeMode: false,
    files: entries.length,
    entries,
    sha256: manifestDigest(entries, false)
  };
}

function baselineTreeBinding(project, archive) {
  const archived = archiveManifest(archive);
  const local = manifestFromPaths(project, walkFiles(project), false);
  const archiveByPath = new Map(archived.entries.map(entry => [entry.path, entry]));
  const localByPath = new Map(local.entries.map(entry => [entry.path, entry]));
  const paths = [...new Set([...archiveByPath.keys(), ...localByPath.keys()])].sort();
  const differences = [];
  for (const path of paths) {
    const before = archiveByPath.get(path);
    const after = localByPath.get(path);
    if (before && after && before.sha256 === after.sha256 && before.size === after.size) {
      continue;
    }
    differences.push({
      path,
      kind: before && after ? 'MODIFIED' : before ? 'REMOVED' : 'ADDED',
      archiveSha256: before?.sha256 ?? null,
      projectSha256: after?.sha256 ?? null
    });
  }
  compare('baseline harness difference allowlist',
    Object.fromEntries(differences.map(row => [row.path, row.kind])),
    BASELINE_ALLOWED_DIFFERENCES);
  compare('baseline harness pinned post-shim hashes',
    Object.fromEntries(differences.map(row => [row.path, row.projectSha256])),
    BASELINE_EXPECTED_SHIM_SHA256);
  return { archiveManifest: archived, projectManifest: local, differences };
}

function sysctlInteger(name) {
  const result = spawnSync('sysctl', ['-n', name], { encoding: 'utf8' });
  if (result.status !== 0) return null;
  const value = Number.parseInt(result.stdout.trim(), 10);
  return Number.isSafeInteger(value) && value > 0 ? value : null;
}

function sysctlText(name) {
  const result = spawnSync('sysctl', ['-n', name], { encoding: 'utf8' });
  return result.status === 0 && result.stdout.trim() ? result.stdout.trim() : null;
}

function darwinHardwareProfile() {
  const result = spawnSync('system_profiler', ['SPHardwareDataType'], {
    encoding: 'utf8', maxBuffer: 4 * 1024 * 1024
  });
  if (result.status !== 0) return {};
  const cores = result.stdout.match(/Total Number of Cores:\s*(\d+)/);
  const chip = result.stdout.match(/^\s*Chip:\s*(.+)$/m);
  return {
    physicalCpuCount: cores ? Number.parseInt(cores[1], 10) : null,
    cpuModel: chip?.[1]?.trim() ?? null
  };
}

function hostIdentity() {
  const cpuRows = osCpus();
  const logicalFallback = cpuRows.length;
  const darwin = osPlatform() === 'darwin';
  const hardware = darwin ? darwinHardwareProfile() : {};
  const sysctlPhysical = darwin && !hardware.physicalCpuCount
    ? sysctlInteger('hw.physicalcpu') : null;
  const details = {
    hostname: osHostname(),
    platform: osPlatform(),
    kernelRelease: osRelease(),
    architecture: osArch(),
    cpuModel: darwin
      ? hardware.cpuModel ?? sysctlText('machdep.cpu.brand_string') ?? cpuRows[0]?.model
      : cpuRows[0]?.model,
    physicalCpuCount: hardware.physicalCpuCount ?? sysctlPhysical ?? logicalFallback,
    physicalCpuCountSource: hardware.physicalCpuCount
      ? 'DARWIN_SYSTEM_PROFILER'
      : sysctlPhysical ? 'DARWIN_SYSCTL' : 'LOGICAL_FALLBACK',
    logicalCpuCount: darwin
      ? sysctlInteger('hw.logicalcpu') ?? logicalFallback
      : logicalFallback,
    totalMemoryBytes: darwin
      ? sysctlInteger('hw.memsize') ?? osTotalMemory()
      : osTotalMemory()
  };
  if (!details.hostname || !details.cpuModel || !details.physicalCpuCount
      || !details.logicalCpuCount || !details.totalMemoryBytes) {
    fail('Physical host identity is incomplete');
  }
  return { ...details, sha256: sha256(canonicalJson(details)) };
}

function parseRunner(runner) {
  const body = readFileSync(runner, 'utf8');
  for (const marker of [
    '-PtestJavaVersion=17',
    '-PblueDependencyMode=local-composite',
    'warmups=3',
    'samples=30',
    'pair % 2 == 1',
    '--capture-preflight',
    '--append-ledger',
    '--capture-postflight'
  ]) {
    if (!body.includes(marker)) fail(`Runner lacks ${marker}`);
  }
  const javaHome = body.match(/export\s+JAVA_HOME=(?:"([^"]+)"|'([^']+)'|([^\s]+))/);
  const bex = body.match(/-PblueBexCompositePath=([^\s\\]+)/);
  const repository = body.match(/-PblueRepositoryCompositePath=([^\s\\]+)/);
  if (!javaHome || !bex || !repository) fail('Runner bindings are incomplete');
  return {
    body,
    javaHome: realpathSync(resolve(javaHome[1] || javaHome[2] || javaHome[3])),
    bexPath: realpathSync(resolve(bex[1])),
    repositoryPath: realpathSync(resolve(repository[1]))
  };
}

function runChecked(command, arguments_, options = {}) {
  const result = spawnSync(command, arguments_, {
    encoding: 'utf8', maxBuffer: 32 * 1024 * 1024, ...options
  });
  if (result.status !== 0) {
    fail(`${command} ${arguments_.join(' ')} failed: ${result.stderr || result.stdout}`);
  }
  return `${result.stdout ?? ''}${result.stderr ?? ''}`.replace(/\r\n/g, '\n').trim();
}

function javaPreflight(javaHome) {
  const output = runChecked(join(javaHome, 'bin/java'), [
    '-XshowSettings:properties', '-version'
  ]);
  const property = name => {
    const match = output.match(new RegExp(
      `^\\s*${name.replaceAll('.', '\\.')}\\s*=\\s*(.+)$`, 'm'));
    if (!match) fail(`Java preflight lacks ${name}`);
    return match[1].trim();
  };
  const logicalProcessors = osCpus().length;
  if (logicalProcessors <= 0) fail('Java preflight lacks CPU count');
  const identity = {
    javaVersion: property('java.version'),
    javaVendor: property('java.vendor'),
    javaHome: realpathSync(property('java.home')),
    osName: property('os.name'),
    osVersion: property('os.version'),
    osArch: property('os.arch'),
    availableProcessors: logicalProcessors
  };
  if (!/^17(?:\.|$)/.test(identity.javaVersion)) fail('Runner JDK is not Java 17');
  return { identity, settingsSha256: sha256(output) };
}

function gradlePreflight(project, javaHome) {
  const output = runChecked(join(project, 'gradlew'), ['--version'], {
    cwd: project,
    env: { ...process.env, JAVA_HOME: javaHome }
  });
  if (!/^Gradle\s+\d+/m.test(output) || !/JVM:\s+17(?:\.|\s)/.test(output)) {
    fail(`${project}: Gradle wrapper is not running on Java 17`);
  }
  return { output, sha256: sha256(output) };
}

function captureBindings({
  rawRoot, baselineProject, candidateProject, archive, runner, samples, warmups
}) {
  if (samples !== 30 || warmups !== 3) fail('Round 13 requires 30 samples and 3 warmups');
  if (sha256(readFileSync(archive)) !== BASELINE_HASH) fail('Archive.zip hash differs');
  const runnerBinding = parseRunner(runner);
  const importerBinding = currentGeneratorMetadata(candidateProject);
  const importer = importerBinding.path;
  if (realpathSync(join(candidateProject, 'scripts/round13-ab-evidence.mjs'))
      !== importer) fail('Campaign must use the candidate importer');
  if (realpathSync(join(candidateProject, 'scripts/run-round13-same-machine-ab.sh'))
      !== realpathSync(runner)) fail('Campaign must use the tracked candidate runner');
  const baselineTree = baselineTreeBinding(baselineProject, archive);
  const czLines = readFileSync(join(baselineProject, '.cz.toml'), 'utf8')
    .split(/\r?\n/).filter(Boolean);
  if (czLines.length !== 4 || !czLines.includes('version = "3.0.0-rc.1"')) {
    fail('Baseline four-line .cz.toml shim differs');
  }
  const baselineMain = mainSourceManifest(baselineProject);
  const candidateMain = mainSourceManifest(candidateProject);
  const baselineLocks = fileBindings(baselineProject, LOCK_FILES);
  const candidateLocks = fileBindings(candidateProject, LOCK_FILES);
  compare('Gradle/source locks', baselineLocks, candidateLocks);
  const baselineFixture = fileBindings(baselineProject, FIXTURE_FILES);
  const candidateFixture = fileBindings(candidateProject, FIXTURE_FILES);
  compare('consumed fixtures', baselineFixture, candidateFixture);
  const wrapperPath = 'gradle/wrapper/gradle-wrapper.properties';
  const baselineWrapper = fileBindings(baselineProject, [wrapperPath]);
  const candidateWrapper = fileBindings(candidateProject, [wrapperPath]);
  compare('Gradle wrapper properties', baselineWrapper, candidateWrapper);
  const java = javaPreflight(runnerBinding.javaHome);
  const baselineGradle = gradlePreflight(baselineProject, runnerBinding.javaHome);
  const candidateGradle = gradlePreflight(candidateProject, runnerBinding.javaHome);
  compare('Gradle --version identity', baselineGradle, candidateGradle);
  const dependencyPaths = [
    ['blue-bex-java', runnerBinding.bexPath],
    ['blue-repository-java', runnerBinding.repositoryPath],
    ['blue-language-java', realpathSync(resolve(
      runnerBinding.repositoryPath, '../blue-language-java'))]
  ];
  const compositeDependencies = dependencyPaths.map(([name, path]) => ({
    name,
    path,
    commit: gitOutput(path, 'rev-parse', 'HEAD'),
    executionSurfaceManifest: manifestFromFilter(path, isCompositeExecutionPath)
  }));
  return {
    integrityMode: 'FULL_CONTENT_MANIFESTS_AND_OBSERVED_COMPLETION_LEDGER',
    rawRoot: realpathSync(rawRoot),
    samplesPerVariant: samples,
    warmups,
    order: 'ODD_BASELINE_CANDIDATE_EVEN_CANDIDATE_BASELINE',
    runner: {
      path: realpathSync(runner),
      sha256: sha256(readFileSync(runner)),
      dependencyMode: 'local-composite',
      testJavaVersion: 17
    },
    importer: importerBinding,
    runtimePreflight: java,
    hostIdentity: hostIdentity(),
    gradlePreflight: {
      baseline: baselineGradle,
      candidate: candidateGradle,
      sameWrapper: true
    },
    wrapper: {
      baseline: baselineWrapper,
      candidate: candidateWrapper,
      bindingSha256: bindingHash(candidateWrapper)
    },
    locks: {
      baseline: baselineLocks,
      candidate: candidateLocks,
      bindingSha256: bindingHash(candidateLocks)
    },
    fixture: {
      baseline: baselineFixture,
      candidate: candidateFixture,
      bindingSha256: bindingHash(candidateFixture)
    },
    baseline: {
      projectPath: realpathSync(baselineProject),
      archivePath: realpathSync(archive),
      archiveSha256: BASELINE_HASH,
      mainSourceManifest: baselineMain,
      ...baselineTree
    },
    candidate: {
      projectPath: realpathSync(candidateProject),
      implementationHead: gitOutput(candidateProject, 'rev-parse', 'HEAD'),
      mainSourceManifest: candidateMain,
      executionSurfaceManifest: manifestFromFilter(
        candidateProject, isCandidateExecutionPath),
      harnessSurfaceManifest: manifestFromFilter(candidateProject, isHarnessPath),
      round12NbaEvidence: fileEntry(
        candidateProject, ROUND12_NBA_EVIDENCE_PATH, true)
    },
    compositeDependencies
  };
}

function bindingEnvelope(schemaId, campaignId, capturedAt, bindings) {
  return {
    schemaId,
    campaignId,
    capturedAt,
    bindings,
    bindingsSha256: sha256(canonicalJson(bindings))
  };
}

function validateEnvelope(envelope, schema, label) {
  if (envelope?.schemaId !== schema || typeof envelope.campaignId !== 'string'
      || !envelope.campaignId || !validTimestamp(envelope.capturedAt)
      || envelope.bindingsSha256 !== sha256(canonicalJson(envelope.bindings))) {
    fail(`${label}: invalid binding envelope`);
  }
}

function validTimestamp(value) {
  return typeof value === 'string' && Number.isFinite(Date.parse(value));
}

function capturePreflight(args) {
  const rawRoot = required(args, 'raw');
  const baselineProject = required(args, 'baseline');
  const candidateProject = required(args, 'candidate');
  const archive = required(args, 'archive');
  const runner = required(args, 'runner');
  const output = required(args, 'output');
  const campaignId = requiredText(args, 'campaign-id');
  const samples = requiredInteger(args, 'samples');
  const warmups = requiredInteger(args, 'warmups');
  if (existsSync(output)) fail(`Preflight output already exists: ${output}`);
  if (output !== join(rawRoot, 'preflight-bindings.json')) {
    fail('Preflight receipt must use the canonical raw-root path');
  }
  const bindings = captureBindings({
    rawRoot, baselineProject, candidateProject, archive, runner, samples, warmups
  });
  writeJson(output, bindingEnvelope(
    PREFLIGHT_SCHEMA, campaignId, new Date().toISOString(), bindings));
  console.log(`ROUND13_AB_PREFLIGHT=${output}`);
}

function expectedVariant(ordinal) {
  const pair = Math.ceil(ordinal / 2);
  const first = pair % 2 === 1 ? 'baseline' : 'candidate';
  return ordinal % 2 === 1 ? first : first === 'baseline' ? 'candidate' : 'baseline';
}

function validateRuntimeIdentity(runtime, label) {
  if (runtime === null || typeof runtime !== 'object' || Array.isArray(runtime)) {
    fail(`${label}: runtime identity missing`);
  }
  for (const field of RUNTIME_STRING_FIELDS) {
    if (typeof runtime[field] !== 'string' || !runtime[field].trim()) {
      fail(`${label}: runtime.${field} missing`);
    }
  }
  if (!/^17(?:\.|$)/.test(runtime.javaVersion)
      || !Array.isArray(runtime.jvmInputArguments)
      || runtime.jvmInputArguments.some(argument =>
        typeof argument !== 'string' || !argument.trim())
      || runtime.jvmInputArguments.some(argument =>
        argument.startsWith('-Dblue.coordination.round13.sample='))
      || !runtime.jvmInputArguments.includes('-Dblue.coordination.round13.warmups=3')
      || !Number.isSafeInteger(runtime.availableProcessors)
      || runtime.availableProcessors <= 0) fail(`${label}: invalid runtime identity`);
}

function validateSample(sample, variant, label) {
  if (sample.schemaVersion !== SAMPLE_SCHEMA) fail(`${label}: bad sample schema`);
  if (typeof sample.entryBlueId !== 'string' || !sample.entryBlueId.trim()) {
    fail(`${label}: entryBlueId missing`);
  }
  validateRuntimeIdentity(sample.runtime, label);
  compare(`${label}: application paths`, sample.applicationPaths, EXPECTED_PATHS);
  for (const [name, expected] of Object.entries(FIXED_COUNTS)) {
    if (sample[name] !== expected) fail(`${label}: ${name}=${sample[name]}`);
  }
  for (const name of METRICS) {
    if (!Number.isSafeInteger(sample[name]) || sample[name] < 0) {
      fail(`${label}: invalid ${name}`);
    }
  }
  if (sample.frozenNanos !== sample.processFrozenNanos
      + sample.initializationFrozenNanos) fail(`${label}: frozen total differs`);
  if (sample.totalNanos < sample.frozenNanos) {
    fail(`${label}: total time is smaller than frozen time`);
  }
  if (sample.coordinationHostNanos !== sample.totalNanos - sample.frozenNanos) {
    fail(`${label}: host remainder differs`);
  }
  const counters = sample.forbiddenCounters;
  if (counters === null || typeof counters !== 'object' || Array.isArray(counters)) {
    fail(`${label}: counters missing`);
  }
  const expected = variant === 'baseline'
    ? COMMON_ZERO_COUNTERS
    : [...COMMON_ZERO_COUNTERS, ...CANDIDATE_ONLY_ZERO_COUNTERS];
  compare(`${label}: counter vocabulary`, Object.keys(counters).sort(), [...expected].sort());
  for (const name of expected) {
    if (counters[name] !== 0) fail(`${label}: ${name} must be zero`);
  }
  if (variant === 'baseline' && CANDIDATE_ONLY_ZERO_COUNTERS.some(
    name => Object.hasOwn(counters, name))) {
    fail(`${label}: baseline must not fabricate retry counters`);
  }
}

function ledgerRecordDigest(record) {
  const copy = { ...record };
  delete copy.entrySha256;
  return sha256(canonicalJson(copy));
}

function parseLedgerBytes(bytes, label, requireComplete = false) {
  const body = bytes.toString('utf8');
  const lines = body ? body.split(/\r?\n/).filter(Boolean) : [];
  const records = [];
  for (let index = 0; index < lines.length; index += 1) {
    const line = lines[index];
    let record;
    try {
      record = JSON.parse(line);
    } catch (error) {
      fail(`${label}: ledger line ${index + 1} is invalid JSON`);
    }
    if (record.schemaId !== LEDGER_SCHEMA || record.ordinal !== index + 1
        || typeof record.campaignId !== 'string' || !record.campaignId
        || record.pair !== Math.ceil(record.ordinal / 2)
        || record.variant !== expectedVariant(record.ordinal)
        || record.rawPath !== `${record.variant}/${record.pair}.json`
        || record.sampleSchema !== SAMPLE_SCHEMA
        || typeof record.entryBlueId !== 'string' || !record.entryBlueId.trim()
        || !validTimestamp(record.startedAt) || !validTimestamp(record.endedAt)
        || Date.parse(record.endedAt) < Date.parse(record.startedAt)
        || !/^[0-9a-f]{64}$/.test(record.rawSha256)
        || !/^[0-9a-f]{64}$/.test(record.canonicalSha256)
        || !/^[0-9a-f]{64}$/.test(record.runnerSha256)
        || !/^[0-9a-f]{64}$/.test(record.importerSha256)
        || !/^[0-9a-f]{64}$/.test(record.preflightSha256)
        || record.previousEntrySha256 !== (index === 0 ? null : records[index - 1].entrySha256)
        || (index > 0
          && Date.parse(record.startedAt) < Date.parse(records[index - 1].endedAt))
        || record.entrySha256 !== ledgerRecordDigest(record)) {
      fail(`${label}: invalid ledger record ${index + 1}`);
    }
    records.push(record);
  }
  if (records.length > 60 || (requireComplete && records.length !== 60)) {
    fail(`${label}: expected ${requireComplete ? 'exactly 60' : 'at most 60'} records`);
  }
  return records;
}

function assertRawInventory(rawRoot) {
  const expected = Array.from({ length: 30 }, (_, index) => `${index + 1}.json`);
  for (const variant of ['baseline', 'candidate']) {
    const actual = readdirSync(join(rawRoot, variant))
      .filter(name => name.endsWith('.json'))
      .sort((left, right) => Number.parseInt(left, 10) - Number.parseInt(right, 10));
    compare(`${variant} raw sample inventory`, actual, expected);
  }
}

function assertRuntimeMatchesPreflight(sampleRuntime, bindings, label) {
  const java = bindings.runtimePreflight.identity;
  for (const field of RUNTIME_STRING_FIELDS) {
    const actual = field === 'javaHome' ? realpathSync(sampleRuntime[field]) : sampleRuntime[field];
    if (actual !== java[field]) fail(`${label}: runtime.${field} differs from preflight`);
  }
  if (sampleRuntime.availableProcessors !== java.availableProcessors) {
    fail(`${label}: availableProcessors differs from preflight`);
  }
}

function appendLedger(args) {
  const preflightPath = required(args, 'preflight');
  const ledgerPath = required(args, 'ledger');
  const rawPath = required(args, 'raw-sample');
  const runner = required(args, 'runner');
  const campaignId = requiredText(args, 'campaign-id');
  const ordinal = requiredInteger(args, 'ordinal');
  const pair = requiredInteger(args, 'pair');
  const variant = requiredText(args, 'variant');
  const startedAt = requiredText(args, 'started-at');
  const endedAt = requiredText(args, 'ended-at');
  const preflightBytes = readFileSync(preflightPath);
  const preflight = JSON.parse(preflightBytes);
  validateEnvelope(preflight, PREFLIGHT_SCHEMA, 'preflight');
  if (preflightPath !== join(preflight.bindings.rawRoot, 'preflight-bindings.json')
      || ledgerPath !== join(preflight.bindings.rawRoot, 'completion-ledger.jsonl')) {
    fail('Ledger receipts must use canonical raw-root paths');
  }
  if (campaignId !== preflight.campaignId || pair !== Math.ceil(ordinal / 2)
      || variant !== expectedVariant(ordinal) || !validTimestamp(startedAt)
      || !validTimestamp(endedAt) || Date.parse(endedAt) < Date.parse(startedAt)) {
    fail('Ledger invocation does not match campaign order');
  }
  const expectedRaw = resolve(preflight.bindings.rawRoot, variant, `${pair}.json`);
  if (rawPath !== expectedRaw) fail(`Raw path differs: ${rawPath}`);
  if (sha256(readFileSync(runner)) !== preflight.bindings.runner.sha256
      || sha256(readFileSync(fileURLToPath(import.meta.url)))
      !== preflight.bindings.importer.sha256
      || process.version !== preflight.bindings.importer.nodeVersion
      || realpathSync(process.execPath) !== preflight.bindings.importer.nodeExecutable) {
    fail('Runner/importer changed during campaign');
  }
  const existingBytes = existsSync(ledgerPath) ? readFileSync(ledgerPath) : Buffer.alloc(0);
  const existing = parseLedgerBytes(existingBytes, 'completion ledger');
  if (ordinal !== existing.length + 1) fail('Ledger ordinal is not the next completion');
  const raw = readFileSync(rawPath);
  const sample = JSON.parse(raw);
  validateSample(sample, variant, `${variant}/${pair}`);
  assertRuntimeMatchesPreflight(sample.runtime, preflight.bindings, `${variant}/${pair}`);
  if (existing.length > 0 && sample.entryBlueId !== existing[0].entryBlueId) {
    fail('entryBlueId differs across campaign rows');
  }
  const record = {
    schemaId: LEDGER_SCHEMA,
    campaignId,
    ordinal,
    pair,
    variant,
    startedAt,
    endedAt,
    rawPath: `${variant}/${pair}.json`,
    rawSha256: sha256(raw),
    canonicalSha256: sha256(canonicalJson(sample)),
    sampleSchema: SAMPLE_SCHEMA,
    entryBlueId: sample.entryBlueId,
    runnerSha256: preflight.bindings.runner.sha256,
    importerSha256: preflight.bindings.importer.sha256,
    preflightSha256: sha256(preflightBytes),
    previousEntrySha256: existing.at(-1)?.entrySha256 ?? null
  };
  record.entrySha256 = ledgerRecordDigest(record);
  mkdirSync(dirname(ledgerPath), { recursive: true });
  appendFileSync(ledgerPath, `${JSON.stringify(record)}\n`);
  console.log(`ROUND13_AB_LEDGER_ORDINAL=${ordinal}`);
}

function validateLedgerAgainstRaw(records, rawRoot, preflightBytes, bindings) {
  const entryBlueIds = new Set();
  let runtime;
  for (const record of records) {
    if (record.campaignId !== JSON.parse(preflightBytes).campaignId
        || record.preflightSha256 !== sha256(preflightBytes)
        || record.runnerSha256 !== bindings.runner.sha256
        || record.importerSha256 !== bindings.importer.sha256) {
      fail(`ledger/${record.ordinal}: binding differs`);
    }
    const raw = readFileSync(join(rawRoot, record.rawPath));
    if (record.rawSha256 !== sha256(raw)) fail(`ledger/${record.ordinal}: raw hash differs`);
    const sample = JSON.parse(raw);
    validateSample(sample, record.variant, record.rawPath);
    if (record.canonicalSha256 !== sha256(canonicalJson(sample))) {
      fail(`ledger/${record.ordinal}: canonical hash differs`);
    }
    if (record.entryBlueId !== sample.entryBlueId) {
      fail(`ledger/${record.ordinal}: entryBlueId differs`);
    }
    assertRuntimeMatchesPreflight(sample.runtime, bindings, record.rawPath);
    entryBlueIds.add(sample.entryBlueId);
    if (runtime === undefined) runtime = sample.runtime;
    else compare(`${record.rawPath}: runtime identity`, sample.runtime, runtime);
  }
  if (entryBlueIds.size !== 1) fail('All 60 rows must share one entryBlueId');
  return runtime;
}

function capturePostflight(args) {
  const rawRoot = required(args, 'raw');
  const baselineProject = required(args, 'baseline');
  const candidateProject = required(args, 'candidate');
  const archive = required(args, 'archive');
  const runner = required(args, 'runner');
  const preflightPath = required(args, 'preflight');
  const ledgerPath = required(args, 'ledger');
  const output = required(args, 'output');
  const campaignId = requiredText(args, 'campaign-id');
  if (existsSync(output)) fail(`Postflight output already exists: ${output}`);
  const preflightBytes = readFileSync(preflightPath);
  const preflight = JSON.parse(preflightBytes);
  validateEnvelope(preflight, PREFLIGHT_SCHEMA, 'preflight');
  if (preflightPath !== join(rawRoot, 'preflight-bindings.json')
      || ledgerPath !== join(rawRoot, 'completion-ledger.jsonl')
      || output !== join(rawRoot, 'postflight-bindings.json')) {
    fail('Postflight receipts must use canonical raw-root paths');
  }
  if (preflight.campaignId !== campaignId) fail('Postflight campaign ID differs');
  const ledgerBytes = readFileSync(ledgerPath);
  const records = parseLedgerBytes(ledgerBytes, 'completion ledger', true);
  const bindings = captureBindings({
    rawRoot,
    baselineProject,
    candidateProject,
    archive,
    runner,
    samples: preflight.bindings.samplesPerVariant,
    warmups: preflight.bindings.warmups
  });
  compare('postflight bindings', bindings, preflight.bindings);
  validateLedgerAgainstRaw(records, rawRoot, preflightBytes, bindings);
  const postflight = {
    ...bindingEnvelope(POSTFLIGHT_SCHEMA, campaignId, new Date().toISOString(), bindings),
    preflightSha256: sha256(preflightBytes),
    completionLedgerSha256: sha256(ledgerBytes),
    completedRows: records.length,
    finalLedgerEntrySha256: records.at(-1).entrySha256
  };
  writeJson(output, postflight);
  console.log(`ROUND13_AB_POSTFLIGHT=${output}`);
}

function distribution(rows, metric) {
  const values = rows.map(row => row.sample[metric]).sort((a, b) => a - b);
  const at = fraction => values[Math.max(0, Math.ceil(values.length * fraction) - 1)];
  return { p50Nanos: at(0.5), p95Nanos: at(0.95), maximumNanos: values.at(-1) };
}

function distributions(rows) {
  return Object.fromEntries(METRICS.map(metric => [metric, distribution(rows, metric)]));
}

function decision(candidateDistributions) {
  const gates = Object.fromEntries(Object.entries(GATE_SPECS).map(
    ([name, [metric, preferred, hard]]) => {
      const observed = candidateDistributions[metric].p95Nanos;
      return [name, {
        metric,
        statistic: 'p95Nanos',
        observedCandidateNanos: observed,
        preferredMaxNanos: preferred,
        hardMaxNanos: hard,
        preferredStatus: observed <= preferred ? 'PASS' : 'MISS',
        hardStatus: observed <= hard ? 'PASS' : 'FAIL'
      }];
    }));
  const hardPass = Object.values(gates).every(gate => gate.hardStatus === 'PASS');
  const preferredPass = Object.values(gates).every(
    gate => gate.preferredStatus === 'PASS');
  return {
    overallStatus: !hardPass ? 'FAIL' : preferredPass ? 'PASS' : 'PASS_HARD_GATE',
    gates
  };
}

function validateCampaignBundle(preflightBytes, ledgerBytes, postflightBytes, rawRoot) {
  const preflight = JSON.parse(preflightBytes);
  const postflight = JSON.parse(postflightBytes);
  validateEnvelope(preflight, PREFLIGHT_SCHEMA, 'preflight');
  validateEnvelope(postflight, POSTFLIGHT_SCHEMA, 'postflight');
  if (preflight.campaignId !== postflight.campaignId
      || postflight.preflightSha256 !== sha256(preflightBytes)
      || postflight.completionLedgerSha256 !== sha256(ledgerBytes)
      || postflight.completedRows !== 60
      || preflight.bindingsSha256 !== postflight.bindingsSha256) {
    fail('Preflight/postflight campaign chain differs');
  }
  compare('preflight/postflight bindings', preflight.bindings, postflight.bindings);
  assertRawInventory(rawRoot);
  const records = parseLedgerBytes(ledgerBytes, 'completion ledger', true);
  if (postflight.finalLedgerEntrySha256 !== records.at(-1).entrySha256) {
    fail('Postflight final ledger hash differs');
  }
  const runtime = validateLedgerAgainstRaw(
    records, rawRoot, preflightBytes, preflight.bindings);
  return { preflight, postflight, records, runtime };
}

function currentGeneratorMetadata(candidateProject) {
  const path = realpathSync(fileURLToPath(import.meta.url));
  const expectedPath = realpathSync(
    join(candidateProject, 'scripts/round13-ab-evidence.mjs'));
  if (path !== expectedPath) fail('Receipt generation must use the candidate generator');
  const bytes = readFileSync(path);
  const stat = lstatSync(path);
  return {
    path,
    sha256: sha256(bytes),
    size: bytes.length,
    mode: stat.mode & 0o777,
    nodeVersion: process.version,
    nodeExecutable: realpathSync(process.execPath)
  };
}

function exactToolingDisclosure(bindings, candidateProject) {
  const importerAndReceiptGenerator = currentGeneratorMetadata(candidateProject);
  compare('current importer and receipt generator',
    importerAndReceiptGenerator, bindings.importer);
  const round12NbaEvidence = fileEntry(
    candidateProject, ROUND12_NBA_EVIDENCE_PATH, true);
  compare('current Round12NbaEvidence',
    round12NbaEvidence, bindings.candidate.round12NbaEvidence);
  return {
    marker: NO_POSTFLIGHT_TOOLING_CHANGES_MARKER,
    reason: NO_POSTFLIGHT_TOOLING_CHANGES_REASON,
    postflightToolingChanges: false,
    importerAndReceiptGenerator,
    round12NbaEvidence
  };
}

function validateToolingDisclosure(disclosure, bindings, candidateProject) {
  if (disclosure?.marker !== NO_POSTFLIGHT_TOOLING_CHANGES_MARKER
      || disclosure.reason !== NO_POSTFLIGHT_TOOLING_CHANGES_REASON
      || disclosure.postflightToolingChanges !== false) {
    fail('No-postflight-tooling-changes disclosure differs');
  }
  const current = exactToolingDisclosure(bindings, candidateProject);
  compare('frozen tooling disclosure', disclosure, current);
  return current;
}

function toolingDisclosureSummary(disclosure) {
  return {
    marker: disclosure.marker,
    reason: disclosure.reason,
    postflightToolingChanges: disclosure.postflightToolingChanges,
    importerSha256: disclosure.importerAndReceiptGenerator.sha256,
    receiptGeneratorPath: disclosure.importerAndReceiptGenerator.path,
    receiptGeneratorSha256: disclosure.importerAndReceiptGenerator.sha256,
    round12NbaEvidence: disclosure.round12NbaEvidence
  };
}

function verifyCurrentManifest(label, root, predicate, expected) {
  validateStoredManifest(label, expected);
  compare(label, manifestFromFilter(root, predicate, expected.includeMode), expected);
}

function isAncestor(project, commit) {
  const result = spawnSync('git', ['-C', project, 'merge-base', '--is-ancestor', commit, 'HEAD']);
  return result.status === 0;
}

function verifyCurrentBindings(bindings, candidateOverride, toolingDisclosure) {
  if (bindings.integrityMode !== 'FULL_CONTENT_MANIFESTS_AND_OBSERVED_COMPLETION_LEDGER'
      || bindings.samplesPerVariant !== 30 || bindings.warmups !== 3
      || bindings.baseline.archiveSha256 !== BASELINE_HASH) fail('Binding profile differs');
  const candidate = realpathSync(candidateOverride);
  if (candidate !== bindings.candidate.projectPath) fail('Candidate project path differs');
  validateToolingDisclosure(toolingDisclosure, bindings, candidate);
  if (sha256(readFileSync(bindings.runner.path)) !== bindings.runner.sha256
      || sha256(readFileSync(bindings.importer.path)) !== bindings.importer.sha256
      || process.version !== bindings.importer.nodeVersion
      || realpathSync(process.execPath) !== bindings.importer.nodeExecutable) {
    fail('Current runner/importer/Node runtime differs from campaign');
  }
  if (sha256(readFileSync(bindings.baseline.archivePath)) !== BASELINE_HASH) {
    fail('Current Archive.zip differs');
  }
  validateStoredManifest(
    'archive tree', bindings.baseline.archiveManifest, localePathCompare);
  compare('current archive tree', archiveManifest(bindings.baseline.archivePath),
    bindings.baseline.archiveManifest);
  validateStoredManifest('baseline project tree', bindings.baseline.projectManifest);
  compare('baseline difference allowlist',
    Object.fromEntries(bindings.baseline.differences.map(row => [row.path, row.kind])),
    BASELINE_ALLOWED_DIFFERENCES);
  compare('baseline pinned post-shim hashes',
    Object.fromEntries(bindings.baseline.differences.map(
      row => [row.path, row.projectSha256])),
    BASELINE_EXPECTED_SHIM_SHA256);
  const currentMain = mainSourceManifest(candidate);
  compare('candidate main-source manifest', currentMain, bindings.candidate.mainSourceManifest);
  verifyCurrentManifest('candidate execution surface', candidate,
    isCandidateExecutionPath, bindings.candidate.executionSurfaceManifest);
  verifyCurrentManifest('candidate harness surface', candidate,
    isHarnessPath, bindings.candidate.harnessSurfaceManifest);
  if (!isAncestor(candidate, bindings.candidate.implementationHead)) {
    fail('Tested implementation commit is not an ancestor of candidate HEAD');
  }
  if (existsSync(bindings.baseline.projectPath)) {
    compare('current baseline tree',
      manifestFromPaths(bindings.baseline.projectPath,
        walkFiles(bindings.baseline.projectPath), false),
      bindings.baseline.projectManifest);
  }
  for (const dependency of bindings.compositeDependencies) {
    verifyCurrentManifest(`${dependency.name} execution surface`, dependency.path,
      isCompositeExecutionPath, dependency.executionSurfaceManifest);
    if (!isAncestor(dependency.path, dependency.commit)) {
      fail(`${dependency.name}: campaign commit is not an ancestor of HEAD`);
    }
  }
  const java = javaPreflight(bindings.runner.path.includes('/')
    ? parseRunner(bindings.runner.path).javaHome
    : bindings.runtimePreflight.identity.javaHome);
  compare('current Java preflight identity',
    java.identity, bindings.runtimePreflight.identity);
  compare('current host identity', hostIdentity(), bindings.hostIdentity);
  for (const manifest of [
    bindings.candidate.executionSurfaceManifest,
    bindings.candidate.harnessSurfaceManifest,
    ...bindings.compositeDependencies.map(value => value.executionSurfaceManifest)
  ]) validateStoredManifest('execution surface', manifest);
  if (bindingHash(bindings.fixture.candidate) !== bindings.fixture.bindingSha256
      || bindingHash(bindings.locks.candidate) !== bindings.locks.bindingSha256
      || bindingHash(bindings.wrapper.candidate) !== bindings.wrapper.bindingSha256) {
    fail('Stored fixture/lock/wrapper binding differs');
  }
}

function rowsFromCampaign(records, rawRoot) {
  const variants = { baseline: [], candidate: [] };
  for (const record of records) {
    const raw = readFileSync(join(rawRoot, record.rawPath));
    const sample = JSON.parse(raw);
    variants[record.variant].push({
      pair: record.pair,
      ordinal: record.ordinal,
      startedAt: record.startedAt,
      endedAt: record.endedAt,
      rawSha256: record.rawSha256,
      canonicalSha256: record.canonicalSha256,
      rawBase64: raw.toString('base64'),
      sample
    });
  }
  for (const variant of Object.keys(variants)) {
    variants[variant].sort((a, b) => a.pair - b.pair);
  }
  return variants;
}

function decodeIntegrity(provenance) {
  const integrity = provenance.integrity;
  if (integrity?.mode !== 'FULL_CONTENT_MANIFESTS_AND_OBSERVED_COMPLETION_LEDGER') {
    fail('Campaign integrity mode differs');
  }
  for (const name of ['preflightBase64', 'completionLedgerBase64', 'postflightBase64']) {
    if (typeof integrity[name] !== 'string' || !integrity[name]) {
      fail(`Campaign integrity lacks ${name}`);
    }
  }
  const bytes = {
    preflightBytes: Buffer.from(integrity.preflightBase64, 'base64'),
    ledgerBytes: Buffer.from(integrity.completionLedgerBase64, 'base64'),
    postflightBytes: Buffer.from(integrity.postflightBase64, 'base64')
  };
  if (integrity.preflightSha256 !== sha256(bytes.preflightBytes)
      || integrity.completionLedgerSha256 !== sha256(bytes.ledgerBytes)
      || integrity.postflightSha256 !== sha256(bytes.postflightBytes)) {
    fail('Embedded campaign byte hashes differ');
  }
  return bytes;
}

function validateCanonical(runtime, provenance, candidateProject, verifyBindings = true) {
  if (runtime.schemaId !== RUNTIME_SCHEMA || provenance.schemaId !== PROVENANCE_SCHEMA) {
    fail('Canonical schema ID differs');
  }
  if (runtime.comparison !== 'SAME_MACHINE_INTERLEAVED_AB'
      || provenance.comparison !== 'SAME_MACHINE_INTERLEAVED_AB'
      || runtime.warmups !== 3 || provenance.warmups !== 3
      || runtime.samplesPerVariant !== 30 || provenance.samplesPerVariant !== 30) {
    fail('Campaign shape differs');
  }
  const bytes = decodeIntegrity(provenance);
  const preflight = JSON.parse(bytes.preflightBytes);
  const postflight = JSON.parse(bytes.postflightBytes);
  validateEnvelope(preflight, PREFLIGHT_SCHEMA, 'embedded preflight');
  validateEnvelope(postflight, POSTFLIGHT_SCHEMA, 'embedded postflight');
  const ledger = parseLedgerBytes(bytes.ledgerBytes, 'embedded completion ledger', true);
  if (preflight.campaignId !== runtime.campaignId
      || runtime.campaignId !== provenance.campaignId
      || postflight.campaignId !== runtime.campaignId
      || postflight.preflightSha256 !== sha256(bytes.preflightBytes)
      || postflight.completionLedgerSha256 !== sha256(bytes.ledgerBytes)
      || postflight.completedRows !== 60
      || preflight.bindingsSha256 !== postflight.bindingsSha256
      || provenance.integrity.bindingsSha256 !== preflight.bindingsSha256
      || provenance.integrity.importerSha256 !== preflight.bindings.importer.sha256
      || provenance.integrity.receiptGeneratorSha256
      !== provenance.toolingDisclosure?.importerAndReceiptGenerator?.sha256
      || postflight.finalLedgerEntrySha256 !== ledger.at(-1).entrySha256) {
    fail('Embedded campaign chain differs');
  }
  compare('embedded preflight/postflight bindings', preflight.bindings, postflight.bindings);
  if (verifyBindings) verifyCurrentBindings(
    preflight.bindings, candidateProject, provenance.toolingDisclosure);
  compare('runtime tooling disclosure', runtime.toolingDisclosure,
    toolingDisclosureSummary(provenance.toolingDisclosure));
  if (provenance.baseline.archiveSha256 !== BASELINE_HASH) {
    fail('Baseline archive binding differs');
  }
  compare('baseline unavailable counters', provenance.baselineUnavailableCounters,
    CANDIDATE_ONLY_ZERO_COUNTERS);
  if (provenance.runtime.javaMajor !== 17 || provenance.runtime.sameRuntime !== true
      || provenance.gradle.sameWrapper !== true || provenance.locks.sameLocks !== true
      || provenance.fixture.sameFixture !== true
      || provenance.harness.sampleSchema !== SAMPLE_SCHEMA
      || provenance.harness.helperCampaignSchema !== HELPER_CAMPAIGN_SCHEMA
      || provenance.baseline.productionSourceFilesChanged !== false
      || provenance.baseline.archiveOmittedCzToml !== true
      || provenance.baseline.czTomlShimLines !== 4
      || provenance.baseline.applicationPathEvidence
      !== 'READ_ONLY_COMPLETED_CATCH_UP_PROJECTION'
      || provenance.candidate.applicationPathEvidence
      !== 'COMMITTED_OCCURRENCE_CAUSE') fail('Runtime/fixture baseline provenance differs');
  validateRuntimeIdentity(provenance.runtime.identity, 'provenance runtime');
  const allEntryBlueIds = new Set();
  let commonRuntime;
  const runtimeRowsByOrdinal = new Map();
  for (const variant of ['baseline', 'candidate']) {
    const rows = runtime.variants?.[variant]?.rawSamples;
    if (!Array.isArray(rows) || rows.length !== 30) fail(`${variant}: need 30 rows`);
    rows.forEach((row, index) => {
      if (row.pair !== index + 1 || row.ordinal < 1 || row.ordinal > 60
          || typeof row.rawBase64 !== 'string') fail(`${variant}: row shape differs`);
      const raw = Buffer.from(row.rawBase64, 'base64');
      if (sha256(raw) !== row.rawSha256) fail(`${variant}/${row.pair}: raw hash differs`);
      const decoded = JSON.parse(raw);
      compare(`${variant}/${row.pair}: raw sample`, decoded, row.sample);
      validateSample(row.sample, variant, `${variant}/${row.pair}`);
      if (row.canonicalSha256 !== sha256(canonicalJson(row.sample))) {
        fail(`${variant}/${row.pair}: canonical hash differs`);
      }
      allEntryBlueIds.add(row.sample.entryBlueId);
      if (commonRuntime === undefined) commonRuntime = row.sample.runtime;
      else compare(`${variant}/${row.pair}: runtime identity`, row.sample.runtime, commonRuntime);
      runtimeRowsByOrdinal.set(row.ordinal, { variant, row });
    });
    compare(`${variant} distributions`, runtime.variants[variant].distributions,
      distributions(rows));
  }
  if (allEntryBlueIds.size !== 1 || runtimeRowsByOrdinal.size !== 60) {
    fail('Campaign rows must share one entryBlueId and 60 unique ordinals');
  }
  compare('runtime/provenance identity', commonRuntime, provenance.runtime.identity);
  for (const record of ledger) {
    const bound = runtimeRowsByOrdinal.get(record.ordinal);
    if (record.campaignId !== preflight.campaignId
        || record.preflightSha256 !== sha256(bytes.preflightBytes)
        || record.runnerSha256 !== preflight.bindings.runner.sha256
        || record.importerSha256 !== preflight.bindings.importer.sha256
        || !bound || bound.variant !== record.variant || bound.row.pair !== record.pair
        || bound.row.rawSha256 !== record.rawSha256
        || bound.row.canonicalSha256 !== record.canonicalSha256
        || bound.row.startedAt !== record.startedAt || bound.row.endedAt !== record.endedAt
        || bound.row.sample.entryBlueId !== record.entryBlueId) {
      fail(`ledger/runtime row ${record.ordinal} differs`);
    }
  }
  compare('runtime top-level identity', runtime.runtimeIdentity, commonRuntime);
  compare('observed sample sequence', provenance.rawSampleSequence,
    ledger.map(record => ({
      ordinal: record.ordinal,
      pair: record.pair,
      variant: record.variant,
      startedAt: record.startedAt,
      endedAt: record.endedAt,
      rawPath: record.rawPath,
      rawSha256: record.rawSha256,
      canonicalSha256: record.canonicalSha256,
      entrySha256: record.entrySha256
    })));
  compare('candidate decision', runtime.candidateDecision,
    decision(runtime.variants.candidate.distributions));
  const main = mainSourceManifest(candidateProject);
  if (provenance.candidate.mainSourceManifestSha256 !== main.sha256
      || provenance.candidate.productionFiles !== main.files) {
    fail('Candidate main-source manifest is stale');
  }
  return runtime.candidateDecision.overallStatus;
}

function renderMarkdown(runtime) {
  const tooling = runtime.toolingDisclosure;
  const lines = [
    '# Round 13 five-occurrence same-machine A/B', '',
    `> **${tooling.marker}:** ${tooling.reason}`, '>',
    '> Postflight tooling changes: **no**. The campaign importer and receipt '
      + `generator are the same frozen file: \`${tooling.importerSha256}\`. `
      + `Current Round12NbaEvidence: \`${tooling.round12NbaEvidence.sha256}\` `
      + `(${tooling.round12NbaEvidence.size} bytes, mode `
      + `0${tooling.round12NbaEvidence.mode.toString(8)}).`, '',
    `Schema: \`${RUNTIME_SCHEMA}\`.`, '',
    `Campaign: \`${runtime.campaignId}\`. Entry Blue ID: \`${runtime.entryBlueId}\`.`, '',
    'Warmups: 3. Samples: 30 baseline + 30 candidate. Observed order: odd A/B, even B/A.', '',
    '| Variant | Span | p50 ms | p95 ms | max ms |',
    '| --- | --- | ---: | ---: | ---: |'
  ];
  for (const variant of ['baseline', 'candidate']) {
    for (const metric of METRICS) {
      const value = runtime.variants[variant].distributions[metric];
      lines.push(`| ${variant} | ${metric} | ${(value.p50Nanos / 1e6).toFixed(6)} | ${(value.p95Nanos / 1e6).toFixed(6)} | ${(value.maximumNanos / 1e6).toFixed(6)} |`);
    }
  }
  lines.push('', '## Structural counts', '',
    '| Count | Baseline (every row) | Candidate (every row) |',
    '| --- | ---: | ---: |');
  for (const [name, value] of Object.entries(FIXED_COUNTS)) {
    lines.push(`| ${name} | ${value} | ${value} |`);
  }
  lines.push('', '## Forbidden-work counters', '',
    '| Counter | Baseline (every row) | Candidate (every row) |',
    '| --- | ---: | ---: |');
  for (const name of COMMON_ZERO_COUNTERS) lines.push(`| ${name} | 0 | 0 |`);
  for (const name of CANDIDATE_ONLY_ZERO_COUNTERS) {
    lines.push(`| ${name} | unavailable in Archive.zip | 0 |`);
  }
  lines.push('', '## Candidate gates', '',
    '| Candidate gate | Observed p95 ms | Preferred | Hard |',
    '| --- | ---: | --- | --- |');
  for (const [name, gate] of Object.entries(runtime.candidateDecision.gates)) {
    lines.push(`| ${name} | ${(gate.observedCandidateNanos / 1e6).toFixed(6)} | ${gate.preferredStatus} | ${gate.hardStatus} |`);
  }
  lines.push('', `Overall: \`${runtime.candidateDecision.overallStatus}\`.`, '');
  return `${lines.join('\n')}\n`;
}

function generate(args) {
  const rawRoot = required(args, 'raw');
  const baselineProject = required(args, 'baseline');
  const candidateProject = required(args, 'candidate');
  const archive = required(args, 'archive');
  const runner = required(args, 'runner');
  const output = required(args, 'output');
  const preflightPath = join(rawRoot, 'preflight-bindings.json');
  const ledgerPath = join(rawRoot, 'completion-ledger.jsonl');
  const postflightPath = join(rawRoot, 'postflight-bindings.json');
  for (const file of [preflightPath, ledgerPath, postflightPath]) {
    if (!existsSync(file)) fail(`Campaign receipt is missing: ${file}`);
  }
  const preflightBytes = readFileSync(preflightPath);
  const ledgerBytes = readFileSync(ledgerPath);
  const postflightBytes = readFileSync(postflightPath);
  const campaign = validateCampaignBundle(
    preflightBytes, ledgerBytes, postflightBytes, rawRoot);
  const bindings = campaign.preflight.bindings;
  if (realpathSync(baselineProject) !== bindings.baseline.projectPath
      || realpathSync(candidateProject) !== bindings.candidate.projectPath
      || realpathSync(archive) !== bindings.baseline.archivePath
      || realpathSync(runner) !== bindings.runner.path) fail('Importer paths differ from preflight');
  const toolingDisclosure = exactToolingDisclosure(bindings, candidateProject);
  verifyCurrentBindings(bindings, candidateProject, toolingDisclosure);
  const variantRows = rowsFromCampaign(campaign.records, rawRoot);
  const variants = Object.fromEntries(['baseline', 'candidate'].map(variant => [
    variant,
    {
      rawSamples: variantRows[variant],
      distributions: distributions(variantRows[variant])
    }
  ]));
  const generatedAt = new Date().toISOString();
  const runtime = {
    schemaId: RUNTIME_SCHEMA,
    generatedAt,
    campaignId: campaign.preflight.campaignId,
    comparison: 'SAME_MACHINE_INTERLEAVED_AB',
    warmups: 3,
    samplesPerVariant: 30,
    entryBlueId: campaign.records[0].entryBlueId,
    runtimeIdentity: campaign.runtime,
    toolingDisclosure: toolingDisclosureSummary(toolingDisclosure),
    variants,
    candidateDecision: decision(variants.candidate.distributions)
  };
  const wrapperPath = 'gradle/wrapper/gradle-wrapper.properties';
  const candidateStatus = gitOutput(
    candidateProject, 'status', '--porcelain', '--untracked-files=normal');
  const provenance = {
    schemaId: PROVENANCE_SCHEMA,
    generatedAt,
    campaignId: campaign.preflight.campaignId,
    comparison: 'SAME_MACHINE_INTERLEAVED_AB',
    warmups: 3,
    samplesPerVariant: 30,
    baselineUnavailableCounters: CANDIDATE_ONLY_ZERO_COUNTERS,
    toolingDisclosure,
    integrity: {
      mode: 'FULL_CONTENT_MANIFESTS_AND_OBSERVED_COMPLETION_LEDGER',
      preflightBase64: preflightBytes.toString('base64'),
      completionLedgerBase64: ledgerBytes.toString('base64'),
      postflightBase64: postflightBytes.toString('base64'),
      preflightSha256: sha256(preflightBytes),
      completionLedgerSha256: sha256(ledgerBytes),
      postflightSha256: sha256(postflightBytes),
      bindingsSha256: campaign.preflight.bindingsSha256,
      importerSha256: bindings.importer.sha256,
      receiptGeneratorSha256:
        toolingDisclosure.importerAndReceiptGenerator.sha256
    },
    runner: bindings.runner,
    runtime: {
      javaMajor: 17,
      javaHome: bindings.runtimePreflight.identity.javaHome,
      identity: campaign.runtime,
      preflight: bindings.runtimePreflight,
      bindingSha256: sha256(canonicalJson(campaign.runtime)),
      sameRuntime: true
    },
    gradle: {
      distributionUrl: readFileSync(join(candidateProject, wrapperPath), 'utf8')
        .split(/\r?\n/).find(line => line.startsWith('distributionUrl='))?.slice(16),
      preflight: bindings.gradlePreflight,
      baselineWrapperSha256: bindings.wrapper.baseline[0].sha256,
      candidateWrapperSha256: bindings.wrapper.candidate[0].sha256,
      sameWrapper: true
    },
    harness: {
      sampleSchema: SAMPLE_SCHEMA,
      helperCampaignSchema: HELPER_CAMPAIGN_SCHEMA,
      baselineHelperSha256: sha256(readFileSync(join(baselineProject, HARNESS_HELPER))),
      candidateHelperSha256: sha256(readFileSync(join(candidateProject, HARNESS_HELPER))),
      candidateSurfaceManifest: bindings.candidate.harnessSurfaceManifest,
      baselineDifferences: [
        'RETRY_COUNTERS_UNAVAILABLE_NOT_ZERO',
        'APPLICATION_PATH_FROM_READ_ONLY_CATCH_UP_PROJECTION'
      ]
    },
    locks: { ...bindings.locks, sameLocks: true },
    fixture: { ...bindings.fixture, sameFixture: true },
    compositeDependencies: bindings.compositeDependencies,
    baseline: {
      projectPath: bindings.baseline.projectPath,
      archivePath: bindings.baseline.archivePath,
      archiveSha256: BASELINE_HASH,
      productionFiles: bindings.baseline.mainSourceManifest.files,
      mainSourceManifestSha256: bindings.baseline.mainSourceManifest.sha256,
      archiveTreeManifest: bindings.baseline.archiveManifest,
      projectTreeManifest: bindings.baseline.projectManifest,
      allowedHarnessDifferences: bindings.baseline.differences,
      productionSourceFilesChanged: false,
      archiveOmittedCzToml: true,
      czTomlShimLines: 4,
      buildShimLines: 4,
      applicationPathEvidence: 'READ_ONLY_COMPLETED_CATCH_UP_PROJECTION'
    },
    candidate: {
      projectPath: bindings.candidate.projectPath,
      implementationHead: bindings.candidate.implementationHead,
      // Compatibility with the existing FINAL gate. Integrity uses manifests above.
      worktreeStatusSha256: sha256(candidateStatus),
      productionFiles: bindings.candidate.mainSourceManifest.files,
      mainSourceManifestSha256: bindings.candidate.mainSourceManifest.sha256,
      executionSurfaceManifest: bindings.candidate.executionSurfaceManifest,
      applicationPathEvidence: 'COMMITTED_OCCURRENCE_CAUSE'
    },
    rawSampleSequence: campaign.records.map(record => ({
      ordinal: record.ordinal,
      pair: record.pair,
      variant: record.variant,
      startedAt: record.startedAt,
      endedAt: record.endedAt,
      rawPath: record.rawPath,
      rawSha256: record.rawSha256,
      canonicalSha256: record.canonicalSha256,
      entrySha256: record.entrySha256
    }))
  };
  validateCanonical(runtime, provenance, candidateProject);
  mkdirSync(output, { recursive: true });
  const runtimePath = join(output, 'five-occurrence-runtime.json');
  const provenancePath = join(output, 'runtime-provenance.json');
  const markdownPath = join(output, 'five-occurrence-runtime.md');
  writeJson(runtimePath, runtime);
  writeJson(provenancePath, provenance);
  writeFileSync(markdownPath, renderMarkdown(runtime));
  console.log(`ROUND13_RUNTIME_JSON=${runtimePath}`);
  console.log(`ROUND13_RUNTIME_MARKDOWN=${markdownPath}`);
  console.log(`ROUND13_RUNTIME_PROVENANCE=${provenancePath}`);
}

function verify(args) {
  const candidateProject = required(args, 'candidate');
  const runtimePath = required(args, 'runtime');
  const provenancePath = required(args, 'provenance');
  const markdownPath = required(args, 'markdown');
  const runtime = readJson(runtimePath);
  const provenance = readJson(provenancePath);
  const status = validateCanonical(runtime, provenance, candidateProject);
  const markdown = readFileSync(markdownPath, 'utf8');
  if (markdown !== renderMarkdown(runtime)) fail('Runtime Markdown differs from JSON');
  console.log(`ROUND13_AB_VERIFIED=${status}`);
}

try {
  const args = parseArgs(process.argv.slice(2));
  if (args.verify) verify(args);
  else if (args['capture-preflight']) capturePreflight(args);
  else if (args['append-ledger']) appendLedger(args);
  else if (args['capture-postflight']) capturePostflight(args);
  else generate(args);
} catch (error) {
  console.error(`Round 13 A/B evidence error: ${error.message}`);
  process.exitCode = 1;
}
