#!/usr/bin/env node

/*
 * Generates the immutable fixed-Repository closure required by Coordination.
 *
 * The roots are discovered from exact Repository BlueIds, qualified names,
 * and generated model imports in production, API, fixture, conformance, gas,
 * quota, and benchmark sources.  The closure is then expanded only through
 * exact BlueId references in the immutable Repository manifest resources.
 * Repository-audit consumers are deliberately excluded from root discovery
 * so that the evidence cannot make itself required.
 */

'use strict';

const childProcess = require('child_process');
const crypto = require('crypto');
const fs = require('fs');
const path = require('path');

function argumentsByName(argv) {
  const result = new Map();
  for (let index = 2; index < argv.length; index += 1) {
    const argument = argv[index];
    if (!argument.startsWith('--')) {
      throw new Error(`Unexpected argument: ${argument}`);
    }
    const separator = argument.indexOf('=');
    if (separator >= 0) {
      result.set(argument.slice(2, separator), argument.slice(separator + 1));
    } else {
      if (index + 1 >= argv.length) {
        throw new Error(`Missing value for ${argument}`);
      }
      result.set(argument.slice(2), argv[index + 1]);
      index += 1;
    }
  }
  return result;
}

function required(argumentsMap, name) {
  const value = argumentsMap.get(name);
  if (!value) {
    throw new Error(`Missing --${name}`);
  }
  return path.resolve(value);
}

function sha256(value) {
  return crypto.createHash('sha256').update(value).digest('hex');
}

function framedIdentity(fields) {
  const digest = crypto.createHash('sha256');
  for (const field of fields) {
    const bytes = Buffer.from(String(field), 'utf8');
    digest.update(Buffer.from(String(bytes.length), 'ascii'));
    digest.update(Buffer.from(':', 'ascii'));
    digest.update(bytes);
  }
  return `sha256:${digest.digest('hex')}`;
}

function compareText(left, right) {
  return Buffer.compare(
    Buffer.from(String(left), 'utf8'),
    Buffer.from(String(right), 'utf8')
  );
}

function authoredName(source, resourcePath) {
  const text = Buffer.isBuffer(source)
    ? source.toString('utf8')
    : String(source);
  const match = text.match(/^name:\s*(.+?)\s*$/m);
  if (!match) {
    throw new Error(
      `Historical registry source has no top-level name: ${resourcePath}`
    );
  }
  let value = match[1].trim();
  if (
    value.length >= 2 &&
    value.startsWith('"') &&
    value.endsWith('"')
  ) {
    value = JSON.parse(value);
  } else if (
    value.length >= 2 &&
    value.startsWith("'") &&
    value.endsWith("'")
  ) {
    value = value.slice(1, -1).replace(/''/g, "'");
  }
  if (!value) {
    throw new Error(
      `Historical registry source has a blank top-level name: ${resourcePath}`
    );
  }
  return value;
}

function regularFiles(root) {
  if (!fs.existsSync(root)) {
    return [];
  }
  const result = [];
  for (const entry of fs.readdirSync(root, { withFileTypes: true })) {
    const absolute = path.join(root, entry.name);
    if (entry.isDirectory()) {
      result.push(...regularFiles(absolute));
    } else if (entry.isFile()) {
      result.push(absolute);
    }
  }
  return result;
}

function immutableRepositorySnapshot(repositoryRoot, expectedHead) {
  const git = (args, encoding) =>
    childProcess.execFileSync('git', ['-C', repositoryRoot, ...args], {
      encoding: encoding || null,
      maxBuffer: 64 * 1024 * 1024,
    });
  const observedHead = git(['rev-parse', 'HEAD'], 'utf8').trim();
  if (observedHead !== expectedHead) {
    throw new Error(
      `Local Repository HEAD ${observedHead} differs from locked ${expectedHead}`
    );
  }
  const tree = new Map();
  for (const entry of git(['ls-tree', '-r', '-z', expectedHead])
    .toString('utf8')
    .split('\u0000')
    .filter(Boolean)) {
    const match = entry.match(/^([0-7]{6})\s+\w+\s+[0-9a-f]+\t(.+)$/s);
    if (!match) {
      throw new Error(`Cannot parse immutable Repository tree entry: ${entry}`);
    }
    const fields = entry.slice(0, entry.indexOf('\t')).split(/\s+/);
    tree.set(match[2], {
      mode: match[1],
      object: fields[2],
    });
  }
  const snapshotPaths = Array.from(tree.keys())
    .filter(
      (repositoryPath) =>
        repositoryPath === 'build.gradle' ||
        repositoryPath === 'tools/generate-repository-sources.js' ||
        repositoryPath.startsWith('src/main/java/blue/repo/') ||
        repositoryPath.startsWith('src/main/resources/blue/repo/')
    )
    .sort((left, right) =>
      Buffer.compare(Buffer.from(left, 'utf8'), Buffer.from(right, 'utf8'))
    );
  const batch = childProcess.execFileSync(
    'git',
    ['-C', repositoryRoot, 'cat-file', '--batch'],
    {
      input:
        snapshotPaths.map(
          (repositoryPath) => tree.get(repositoryPath).object
        ).join('\n') + '\n',
      maxBuffer: 128 * 1024 * 1024,
    }
  );
  const blobs = new Map();
  let offset = 0;
  for (const repositoryPath of snapshotPaths) {
    const headerEnd = batch.indexOf(0x0a, offset);
    if (headerEnd < 0) {
      throw new Error('Truncated immutable Repository batch header');
    }
    const header = batch.subarray(offset, headerEnd).toString('ascii');
    const headerFields = header.split(' ');
    if (headerFields.length !== 3 || headerFields[1] !== 'blob') {
      throw new Error(`Unexpected immutable Repository object: ${header}`);
    }
    const size = Number(headerFields[2]);
    const start = headerEnd + 1;
    const end = start + size;
    if (!Number.isSafeInteger(size) || size < 0 || end >= batch.length) {
      throw new Error(`Invalid immutable Repository blob size: ${header}`);
    }
    blobs.set(repositoryPath, Buffer.from(batch.subarray(start, end)));
    offset = end + 1;
  }
  const consumed = new Map();
  const read = (repositoryPath) => {
    if (!blobs.has(repositoryPath)) {
      throw new Error(`Immutable Repository path is unavailable: ${repositoryPath}`);
    }
    const retained = consumed.get(repositoryPath);
    if (retained) {
      return Buffer.from(retained);
    }
    const bytes = blobs.get(repositoryPath);
    consumed.set(repositoryPath, Buffer.from(bytes));
    return Buffer.from(bytes);
  };
  const list = (prefix) =>
    Array.from(tree.keys())
      .filter(
        (repositoryPath) =>
          repositoryPath === prefix ||
          repositoryPath.startsWith(`${prefix}/`)
      )
      .sort((left, right) =>
        Buffer.compare(Buffer.from(left, 'utf8'), Buffer.from(right, 'utf8'))
      );
  const evidence = () => {
    const inputs = Array.from(consumed.keys()).sort((left, right) =>
      Buffer.compare(Buffer.from(left, 'utf8'), Buffer.from(right, 'utf8'))
    );
    const fields = [];
    for (const repositoryPath of inputs) {
      const bytes = consumed.get(repositoryPath);
      fields.push(
        repositoryPath,
        'git-blob',
        tree.get(repositoryPath).mode,
        String(bytes.length),
        sha256(bytes)
      );
    }
    return {
      provenance: 'exact-local-immutable-git-head',
      headCommit: observedHead,
      inputCount: inputs.length,
      identity: framedIdentity(fields),
      matchesHead: true,
    };
  };
  return { read, list, evidence };
}

function normalizedRelative(root, file) {
  return path.relative(root, file).split(path.sep).join('/');
}

function javaString(value) {
  return String(value)
    .replace(/\\/g, '\\\\')
    .replace(/"/g, '\\"')
    .replace(/\r/g, '\\r')
    .replace(/\n/g, '\\n');
}

function regularExpressionLiteral(value) {
  return String(value).replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
}

function containsExactQualifiedName(source, qualifiedName) {
  for (const quote of ['"', "'", '`']) {
    if (source.includes(`${quote}${qualifiedName}${quote}`)) {
      return true;
    }
  }
  const literal = regularExpressionLiteral(qualifiedName);
  if (
    new RegExp(
      `(?:^|[\\n"'\\x60])\\s*(?:[-*]\\s+)?[\\w.-]+\\s*:\\s*${literal}\\s*(?=$|[\\r\\n"'\\x60])`,
      'm'
    ).test(source)
  ) {
    return true;
  }
  return new RegExp(
    `^\\s*(?:[-*]\\s+)?(?:[\\w.-]+\\s*:\\s*)?${literal}\\s*(?:#.*)?$`,
    'm'
  ).test(source);
}

function runtimeRegistrationQualifiedNames(relativePath, source) {
  if (!relativePath.endsWith('runtime-registrations.yaml')) {
    return [];
  }
  const declaredLines = source
    .split(/\r?\n/)
    .filter((line) => /^\s*-\s+type\s*:/.test(line));
  const qualifiedNames = [];
  for (const line of declaredLines) {
    const match = line.match(
      /^\s*-\s+type\s*:\s*(?:"([^"]+)"|'([^']+)'|([^#\r\n]+?))\s*(?:#.*)?$/
    );
    if (!match) {
      throw new Error(
        `Runtime registration has an unsupported type declaration in ${relativePath}: ${line.trim()}`
      );
    }
    const qualifiedName = (match[1] || match[2] || match[3] || '').trim();
    if (!qualifiedName) {
      throw new Error(
        `Runtime registration has a blank type declaration in ${relativePath}`
      );
    }
    qualifiedNames.push(qualifiedName);
  }
  return qualifiedNames;
}

function generatedRepositoryClasses(repositorySnapshot, definitionsByBlueId) {
  const result = new Map();
  const sourceRoot = 'src/main/java/blue/repo';
  for (const repositoryPath of repositorySnapshot
    .list(sourceRoot)
    .filter((candidate) => candidate.endsWith('.java'))) {
    const source = repositorySnapshot.read(repositoryPath).toString('utf8');
    const packageMatch = source.match(/package\s+([\w.]+)\s*;/);
    const classMatch = source.match(/public\s+(?:final\s+)?class\s+(\w+)/);
    const blueIdMatch = source.match(
      /public\s+static\s+String\s+blueId\s*\(\s*\)\s*\{\s*return\s+"([^"]+)"\s*;/s
    );
    if (!packageMatch || !classMatch || !blueIdMatch) {
      continue;
    }
    const definition = definitionsByBlueId.get(blueIdMatch[1]);
    if (definition) {
      result.set(`${packageMatch[1]}.${classMatch[1]}`, definition);
    }
  }
  return result;
}

function usageFiles(projectRoot) {
  const accepted = /\.(?:java|json|ya?ml|md|properties|txt)$/;
  const roots = [
    path.join(projectRoot, 'src', 'main'),
    path.join(projectRoot, 'src', 'test'),
    path.join(projectRoot, 'src', 'jmh'),
  ];
  const included = [];
  const excluded = [];
  for (const file of roots.flatMap(regularFiles).filter((candidate) => accepted.test(candidate))) {
    const source = fs.readFileSync(file, 'utf8');
    const relative = normalizedRelative(projectRoot, file);
    const auditConsumer =
      relative.startsWith('src/test/java/') &&
      source.includes('@Test') &&
      (source.includes('FixedRepositoryBoundSourceProvider') ||
        source.includes('CoordinationRequiredRepositoryClosure'));
    if (auditConsumer) {
      excluded.push(relative);
    } else {
      included.push({ file, relative, source });
    }
  }
  included.sort((left, right) =>
    compareText(left.relative, right.relative)
  );
  excluded.sort();
  return { included, excluded };
}

function defaultBlueEvidence(source, resourcePath) {
  const text = source.toString('utf8');
  const header = /^  mappings:\r?$/m.exec(text);
  if (!header) {
    throw new Error(`Default Blue has no mappings block: ${resourcePath}`);
  }
  const nextItem = /^- type:\r?$/gm;
  nextItem.lastIndex = header.index + header[0].length;
  const next = nextItem.exec(text);
  if (!next) {
    throw new Error(
      `Default Blue mappings are not followed by another transform: ${resourcePath}`
    );
  }
  const mappingText = text.slice(
    header.index + header[0].length,
    next.index
  );
  const aliases = new Map();
  for (const line of mappingText.split(/\r?\n/).filter(Boolean)) {
    const match = line.match(
      /^    ([^:\r\n]+):\s*([1-9A-HJ-NP-Za-km-z]{40,60})\s*$/
    );
    if (!match) {
      throw new Error(
        `Default Blue mapping is not an exact alias-to-BlueId entry: ` +
          `${resourcePath}:${line}`
      );
    }
    const alias = match[1].trim();
    if (aliases.has(alias)) {
      throw new Error(
        `Default Blue declares duplicate alias ${alias}: ${resourcePath}`
      );
    }
    aliases.set(alias, match[2]);
  }
  if (aliases.size === 0) {
    throw new Error(`Default Blue alias table is empty: ${resourcePath}`);
  }
  const normalizedBody = Buffer.from(
    text.slice(0, header.index) +
      '  mappings:\n    <exact-alias-table>\n' +
      text.slice(next.index),
    'utf8'
  );
  const aliasIdentity = framedIdentity(
    Array.from(aliases.entries()).flatMap(([alias, blueId]) => [
      alias,
      blueId,
    ])
  );
  return {
    aliases,
    sourceSha256: sha256(source),
    sourceBase64: source.toString('base64'),
    normalizedBody,
    normalizedBodySha256: sha256(normalizedBody),
    aliasIdentity,
  };
}

function historicalLanguageEvidence(
  languageRoot,
  version,
  currentLanguageCommit
) {
  const tag = `v${version}`;
  const git = (args, encoding) =>
    childProcess.execFileSync('git', ['-C', languageRoot, ...args], {
      encoding: encoding || null,
      maxBuffer: 64 * 1024 * 1024,
    });
  const commit = git(['rev-list', '-n', '1', tag], 'utf8').trim();
  if (!/^[0-9a-f]{40}$/.test(commit)) {
    throw new Error(`Historical Language tag ${tag} did not resolve to an exact commit`);
  }
  const resolvedCurrentCommit = git(
    ['rev-parse', `${currentLanguageCommit}^{commit}`],
    'utf8'
  ).trim();
  if (resolvedCurrentCommit !== currentLanguageCommit) {
    throw new Error(
      `Current Language commit ${currentLanguageCommit} did not resolve exactly`
    );
  }
  const treeListing = (ref, prefix) =>
    git(['ls-tree', '-r', '--name-only', ref, '--', prefix], 'utf8')
      .split(/\r?\n/)
      .filter(Boolean)
      .sort(compareText);
  const treeIdentity = (ref, prefix) => {
    const listing = treeListing(ref, prefix);
    if (listing.length === 0) {
      throw new Error(`Language tree is empty: ${ref}:${prefix}`);
    }
    const fields = [];
    for (const repositoryPath of listing) {
      fields.push(repositoryPath);
      fields.push(sha256(git(['show', `${ref}:${repositoryPath}`])));
    }
    return framedIdentity(fields);
  };
  const registryEntries = new Map();
  const addRegistryEntry = (blueId, entry) => {
    if (registryEntries.has(blueId)) {
      const previous = registryEntries.get(blueId);
      throw new Error(
        `Historical Language BlueId ${blueId} is declared by both ` +
          `${previous.registry}/${previous.key} and ` +
          `${entry.registry}/${entry.key}`
      );
    }
    registryEntries.set(blueId, entry);
  };
  const coreManifestPath =
    'src/main/resources/registry/blue-language-1.0/manifest.yaml';
  const coreManifest = git(['show', `${tag}:${coreManifestPath}`], 'utf8');
  for (const match of coreManifest.matchAll(
    /^\s{2}([A-Za-z][A-Za-z0-9]*):\s*"?([1-9A-HJ-NP-Za-km-z]{40,60})"?\s*$/gm
  )) {
    const resourcePath =
      `src/main/resources/registry/blue-language-1.0/${match[1]}.blue`;
    const source = git(['show', `${tag}:${resourcePath}`]);
    addRegistryEntry(match[2], {
      registry: 'blue-language-1.0',
      key: match[1],
      alias: authoredName(source, resourcePath),
      path: resourcePath,
      sourceResourceSha256: sha256(source),
      sourceBase64: source.toString('base64'),
    });
  }
  const contractsManifestPath =
    'src/main/resources/registry/blue-contracts-1.0/manifest.yaml';
  const contractsManifest =
    git(['show', `${tag}:${contractsManifestPath}`], 'utf8');
  for (const match of contractsManifest.matchAll(
    /-\s+key:\s*([^\r\n]+)\r?\n\s+path:\s*([^\r\n]+)\r?\n\s+blueId:\s*"([1-9A-HJ-NP-Za-km-z]{40,60})"/g
  )) {
    const resourcePath =
      `src/main/resources/registry/blue-contracts-1.0/${match[2].trim()}`;
    const source = git(['show', `${tag}:${resourcePath}`]);
    addRegistryEntry(match[3], {
      registry: 'blue-contracts-1.0',
      key: match[1].trim(),
      alias: authoredName(source, resourcePath),
      path: resourcePath,
      sourceResourceSha256: sha256(source),
      sourceBase64: source.toString('base64'),
    });
  }
  const aliases = new Map();
  for (const [blueId, entry] of registryEntries.entries()) {
    if (aliases.has(entry.alias)
        && aliases.get(entry.alias) !== blueId) {
      throw new Error(
        `Historical Language alias ${entry.alias} has conflicting identities`
      );
    }
    aliases.set(entry.alias, blueId);
  }
  const unchangedCoreSourceEntries = [];
  for (const [blueId, entry] of registryEntries.entries()) {
    if (entry.registry !== 'blue-language-1.0') {
      continue;
    }
    const historicalSource =
      Buffer.from(entry.sourceBase64, 'base64');
    const currentSource =
      git(['show', `${currentLanguageCommit}:${entry.path}`]);
    if (!historicalSource.equals(currentSource)) {
      throw new Error(
        `Historical core type source differs from current Language: ${entry.path}`
      );
    }
    unchangedCoreSourceEntries.push({
      key: entry.key,
      alias: entry.alias,
      blueId,
      path: entry.path,
      sha256: entry.sourceResourceSha256,
    });
  }
  unchangedCoreSourceEntries.sort(
    (left, right) =>
      compareText(left.alias, right.alias) ||
      compareText(left.blueId, right.blueId)
  );
  const coreSourceEquivalenceIdentity = framedIdentity(
    unchangedCoreSourceEntries.flatMap((entry) => [
      entry.key,
      entry.alias,
      entry.blueId,
      entry.path,
      entry.sha256,
    ])
  );
  const transformationRoot =
    'src/main/resources/transformation';
  const defaultBluePath =
    `${transformationRoot}/DefaultBlue.blue`;
  const historicalTransformPaths =
    treeListing(tag, transformationRoot);
  const currentTransformPaths =
    treeListing(currentLanguageCommit, transformationRoot);
  if (
    JSON.stringify(historicalTransformPaths) !==
    JSON.stringify(currentTransformPaths)
  ) {
    throw new Error(
      'Historical and current Language transformation inventories differ'
    );
  }
  const unchangedTransformEntries = [];
  let historicalDefaultBlue;
  let currentDefaultBlue;
  for (const transformPath of historicalTransformPaths) {
    const historicalSource =
      git(['show', `${tag}:${transformPath}`]);
    const currentSource =
      git(['show', `${currentLanguageCommit}:${transformPath}`]);
    if (transformPath === defaultBluePath) {
      historicalDefaultBlue =
        defaultBlueEvidence(historicalSource, `${tag}:${transformPath}`);
      currentDefaultBlue =
        defaultBlueEvidence(
          currentSource,
          `${currentLanguageCommit}:${transformPath}`
        );
      continue;
    }
    if (!historicalSource.equals(currentSource)) {
      throw new Error(
        `Historical preprocessing transform differs outside the ` +
          `Default Blue alias table: ${transformPath}`
      );
    }
    unchangedTransformEntries.push({
      path: transformPath,
      sha256: sha256(historicalSource),
    });
  }
  if (!historicalDefaultBlue || !currentDefaultBlue) {
    throw new Error('Default Blue transformation evidence is unavailable');
  }
  if (
    !historicalDefaultBlue.normalizedBody.equals(
      currentDefaultBlue.normalizedBody
    )
  ) {
    throw new Error(
      'Historical Default Blue differs from current Language outside its ' +
        'exact alias table'
    );
  }
  if (historicalDefaultBlue.aliases.size !== aliases.size) {
    throw new Error(
      'Historical Default Blue aliases do not cover the exact historical registries'
    );
  }
  for (const [alias, blueId] of aliases.entries()) {
    if (historicalDefaultBlue.aliases.get(alias) !== blueId) {
      throw new Error(
        `Historical Default Blue alias ${alias} does not match registry evidence`
      );
    }
  }
  const transformEquivalenceStatus =
    'proved-alias-table-only-delta';
  const transformEquivalenceIdentity = framedIdentity([
    transformEquivalenceStatus,
    commit,
    currentLanguageCommit,
    historicalDefaultBlue.sourceSha256,
    currentDefaultBlue.sourceSha256,
    historicalDefaultBlue.aliasIdentity,
    currentDefaultBlue.aliasIdentity,
    historicalDefaultBlue.normalizedBodySha256,
    ...unchangedTransformEntries.flatMap((entry) => [
      entry.path,
      entry.sha256,
    ]),
  ]);
  return {
    coordinate: `blue.language:blue-language-java:${version}`,
    commit,
    currentCommit: currentLanguageCommit,
    coreRegistryIdentity: treeIdentity(
      tag,
      'src/main/resources/registry/blue-language-1.0'
    ),
    currentCoreRegistryIdentity: treeIdentity(
      currentLanguageCommit,
      'src/main/resources/registry/blue-language-1.0'
    ),
    coreSourceEquivalenceIdentity,
    unchangedCoreSourceEntries,
    runtimeRoleRegistryIdentity: treeIdentity(
      tag,
      'src/main/resources/registry/blue-contracts-1.0'
    ),
    preprocessingTransformsIdentity: treeIdentity(
      tag,
      'src/main/resources/transformation'
    ),
    currentPreprocessingTransformsIdentity: treeIdentity(
      currentLanguageCommit,
      'src/main/resources/transformation'
    ),
    historicalDefaultBlue,
    currentDefaultBlue,
    unchangedTransformEntries,
    transformEquivalenceStatus,
    transformEquivalenceIdentity,
    registryEntries,
    aliases,
  };
}

function collectReferencedIdentities(value, target) {
  if (typeof value === 'string') {
    target.add(value);
    return;
  }
  if (Array.isArray(value)) {
    for (const item of value) {
      collectReferencedIdentities(item, target);
    }
    return;
  }
  if (value && typeof value === 'object') {
    for (const item of Object.values(value)) {
      collectReferencedIdentities(item, target);
    }
  }
}

function expandDefinitionClosure(
  definitionsByBlueId,
  definitionsByMaster,
  rootBlueIds,
  readDefinitionResource
) {
  const closure = new Map();
  const directReferences = new Map();
  const sourceResourceSha256ByBlueId = new Map();
  const externalReferenceSources = new Map();
  const queue = Array.from(rootBlueIds).sort();
  const queued = new Set(queue);
  const enqueue = (definition) => {
    if (
      definition &&
      !closure.has(definition.blueId) &&
      !queued.has(definition.blueId)
    ) {
      queue.push(definition.blueId);
      queued.add(definition.blueId);
    }
  };

  while (queue.length > 0) {
    queue.sort();
    const requested = queue.shift();
    queued.delete(requested);
    const definition = definitionsByBlueId.get(requested);
    if (!definition || closure.has(definition.blueId)) {
      continue;
    }
    closure.set(definition.blueId, definition);
    const master = definition.blueId.split('#')[0];
    for (const member of definitionsByMaster.get(master) || []) {
      enqueue(member);
    }

    const resourceBytes = readDefinitionResource(definition);
    sourceResourceSha256ByBlueId.set(
      definition.blueId,
      sha256(resourceBytes)
    );
    const resourceValue = JSON.parse(resourceBytes.toString('utf8'));
    const referencedValues = new Set();
    collectReferencedIdentities(resourceValue, referencedValues);
    const references = new Set();
    for (const referencedValue of referencedValues) {
      if (referencedValue === 'this' || referencedValue.startsWith('this#')) {
        const members = definitionsByMaster.get(master) || [];
        if (referencedValue.startsWith('this#')) {
          const targetIndex = Number(referencedValue.slice('this#'.length));
          if (
            !Number.isSafeInteger(targetIndex) ||
            targetIndex < 0 ||
            targetIndex >= members.length
          ) {
            throw new Error(
              `Cyclic placeholder ${referencedValue} points outside ${master}`
            );
          }
        }
        for (const member of members) {
          references.add(member.blueId);
          enqueue(member);
        }
        continue;
      }
      const referencedDefinition = definitionsByBlueId.get(referencedValue);
      if (referencedDefinition) {
        references.add(referencedDefinition.blueId);
        enqueue(referencedDefinition);
      } else if (
        /^[1-9A-HJ-NP-Za-km-z]{40,60}(?:#\d+)?$/.test(referencedValue)
      ) {
        const sources =
          externalReferenceSources.get(referencedValue) || new Set();
        sources.add(definition.qualifiedName);
        externalReferenceSources.set(referencedValue, sources);
      }
    }
    directReferences.set(
      definition.blueId,
      Array.from(references).sort()
    );
  }

  return {
    closure,
    directReferences,
    sourceResourceSha256ByBlueId,
    externalReferenceSources,
  };
}

function main() {
  const argumentsMap = argumentsByName(process.argv);
  const projectRoot = required(argumentsMap, 'project-root');
  const repositoryRoot = required(argumentsMap, 'repository-root');
  const languageRoot = required(argumentsMap, 'language-root');
  const javaOutput = required(argumentsMap, 'java-output');
  const reportOutput = required(argumentsMap, 'report-output');
  const repositoryHeadCommit = argumentsMap.get('repository-commit');
  if (!repositoryHeadCommit || !/^[0-9a-f]{40}$/.test(repositoryHeadCommit)) {
    throw new Error('--repository-commit must be an exact Git SHA');
  }
  const currentLanguageCommit =
    argumentsMap.get('language-commit');
  if (!currentLanguageCommit
      || !/^[0-9a-f]{40}$/.test(currentLanguageCommit)) {
    throw new Error('--language-commit must be an exact Git SHA');
  }

  const manifestPath =
    'src/main/resources/blue/repo/manifest.json';
  const repositoryBuildPath = 'build.gradle';
  const repositoryGeneratorPath =
    'tools/generate-repository-sources.js';
  const repositorySourceBundlePath =
    'src/main/resources/blue/repo/BlueRepository.blue';
  const repositorySnapshot =
    immutableRepositorySnapshot(
      repositoryRoot,
      repositoryHeadCommit
    );
  const manifestBytes =
    repositorySnapshot.read(
      manifestPath);
  const manifest = JSON.parse(manifestBytes.toString('utf8'));
  repositorySnapshot.read(repositoryBuildPath);
  repositorySnapshot.read(repositoryGeneratorPath);
  repositorySnapshot.read(repositorySourceBundlePath);
  for (const repositoryPath of repositorySnapshot.list(
    'src/main/resources/blue/repo/definitions'
  )) {
    repositorySnapshot.read(repositoryPath);
  }
  const definitions = manifest.definitions.slice();
  const definitionsByBlueId = new Map();
  const definitionsByQualifiedName = new Map();
  const definitionsByMaster = new Map();
  for (const definition of definitions) {
    definitionsByBlueId.set(definition.blueId, definition);
    for (const version of definition.versions || []) {
      definitionsByBlueId.set(version.typeBlueId, definition);
    }
    definitionsByQualifiedName.set(definition.qualifiedName, definition);
    const master = definition.blueId.split('#')[0];
    const members = definitionsByMaster.get(master) || [];
    members.push(definition);
    definitionsByMaster.set(master, members);
  }
  for (const members of definitionsByMaster.values()) {
    members.sort((left, right) => {
      const leftSeparator = left.blueId.indexOf('#');
      const rightSeparator = right.blueId.indexOf('#');
      if (leftSeparator >= 0 && rightSeparator >= 0) {
        return (
          Number(left.blueId.slice(leftSeparator + 1)) -
          Number(right.blueId.slice(rightSeparator + 1))
        );
      }
      return compareText(left.blueId, right.blueId);
    });
    if (members.some((member) => member.blueId.includes('#'))) {
      const master = members[0].blueId.split('#')[0];
      members.forEach((member, index) => {
        if (member.blueId !== `${master}#${index}`) {
          throw new Error(
            `Immutable Repository cyclic set is incomplete at ${master}#${index}`
          );
        }
      });
    }
  }

  const classIndex = generatedRepositoryClasses(
    repositorySnapshot,
    definitionsByBlueId
  );
  const usage = usageFiles(projectRoot);
  const roots = new Map();
  const unmappedGeneratedImports = new Set();
  const runtimeRegistrations = [];
  const addRoot = (definition, kind, usagePath, evidence) => {
    if (!definition) {
      return;
    }
    const reasons = roots.get(definition.blueId) || [];
    const key = `${kind}\u0000${usagePath}\u0000${evidence}`;
    if (!reasons.some((reason) => reason.key === key)) {
      reasons.push({ key, kind, path: usagePath, evidence });
      reasons.sort((left, right) => compareText(left.key, right.key));
    }
    roots.set(definition.blueId, reasons);
  };

  for (const usageFile of usage.included) {
    for (const qualifiedName of runtimeRegistrationQualifiedNames(
      usageFile.relative,
      usageFile.source
    )) {
      const definition = definitionsByQualifiedName.get(qualifiedName);
      if (!definition) {
        throw new Error(
          `Runtime registration ${usageFile.relative} references an unknown immutable Repository type: ${qualifiedName}`
        );
      }
      addRoot(
        definition,
        'runtime-registration',
        usageFile.relative,
        qualifiedName
      );
      runtimeRegistrations.push({
        path: usageFile.relative,
        qualifiedName,
        blueId: definition.blueId,
      });
    }
    for (const match of usageFile.source.matchAll(
      /import\s+(blue\.repo\.[\w.]+)\s*;/g
    )) {
      if (
        match[1].split('.').length >= 4 &&
        !classIndex.has(match[1])
      ) {
        unmappedGeneratedImports.add(
          `${usageFile.relative}:${match[1]}`
        );
      }
      addRoot(
        classIndex.get(match[1]),
        'generated-model-import',
        usageFile.relative,
        match[1]
      );
    }
    for (const match of usageFile.source.matchAll(
      /import\s+(blue\.repo\.[\w.]+)\.\*\s*;/g
    )) {
      const prefix = `${match[1]}.`;
      for (const [className, definition] of classIndex.entries()) {
        if (className.startsWith(prefix)) {
          addRoot(
            definition,
            'generated-model-wildcard-import',
            usageFile.relative,
            match[1]
          );
        }
      }
    }
    for (const definition of definitions) {
      if (containsExactQualifiedName(
        usageFile.source,
        definition.qualifiedName
      )) {
        addRoot(
          definition,
          'manifest-qualified-name',
          usageFile.relative,
          definition.qualifiedName
        );
      }
      const identities = [
        definition.blueId,
        ...(definition.versions || []).map((version) => version.typeBlueId),
      ];
      for (const identity of identities) {
        if (usageFile.source.includes(identity)) {
          addRoot(
            definition,
            'manifest-blue-id',
            usageFile.relative,
            identity
          );
        }
      }
    }
  }

  if (unmappedGeneratedImports.size > 0) {
    throw new Error(
      'Generated Repository imports are absent from immutable HEAD: ' +
        Array.from(unmappedGeneratedImports).sort().join(', ')
    );
  }

  if (roots.size === 0) {
    throw new Error('No Coordination fixed-Repository roots were discovered');
  }

  const expanded = expandDefinitionClosure(
    definitionsByBlueId,
    definitionsByMaster,
    roots.keys(),
    (definition) =>
      repositorySnapshot.read(
        `src/main/resources/${definition.resourcePath}`
      )
  );
  const closure = expanded.closure;
  const directReferences = expanded.directReferences;
  const sourceResourceSha256ByBlueId =
    expanded.sourceResourceSha256ByBlueId;
  const externalReferenceSources =
    expanded.externalReferenceSources;

  const entries = Array.from(closure.values()).sort(
    (left, right) =>
      compareText(left.qualifiedName, right.qualifiedName) ||
      compareText(left.blueId, right.blueId)
  );
  const repositoryBuild =
    repositorySnapshot.read(repositoryBuildPath).toString('utf8');
  const historicalLanguageMatch = repositoryBuild.match(
    /api\s+['"]blue\.language:blue-language-java:([^'"]+)['"]/
  );
  if (!historicalLanguageMatch) {
    throw new Error(
      'Immutable Repository build metadata does not declare its Language coordinate'
    );
  }
  const historicalLanguage = historicalLanguageEvidence(
    languageRoot,
    historicalLanguageMatch[1],
    currentLanguageCommit
  );
  const externalReferences = Array.from(
    externalReferenceSources.entries()
  )
    .map(([blueId, sources]) => {
      const resolved =
        historicalLanguage.registryEntries.get(blueId);
      return {
        blueId,
        sources: Array.from(sources).sort(),
        status: resolved ? 'resolved' : 'unknown',
        registry: resolved ? resolved.registry : null,
        key: resolved ? resolved.key : null,
        alias: resolved ? resolved.alias : null,
        path: resolved ? resolved.path : null,
        sourceResourceSha256: resolved
          ? resolved.sourceResourceSha256
          : null,
      };
    })
    .sort((left, right) => compareText(left.blueId, right.blueId));
  const unresolvedExternalReferences =
    externalReferences.filter(
      (reference) => reference.status !== 'resolved'
    );
  if (unresolvedExternalReferences.length > 0) {
    throw new Error(
      'Immutable Repository closure has unresolved external references: ' +
        unresolvedExternalReferences
          .map((reference) => reference.blueId)
          .join(', ')
    );
  }
  const externalReferencesIdentity = framedIdentity(
    externalReferences.flatMap((reference) => [
      reference.blueId,
      reference.registry,
      reference.key,
      reference.alias,
      reference.path,
      reference.sourceResourceSha256,
      ...reference.sources,
    ])
  );
  const historicalRegistryEvidence = Array.from(
    historicalLanguage.registryEntries.entries()
  )
    .filter(([, entry]) =>
      entry.registry === 'blue-contracts-1.0'
    )
    .map(([blueId, entry]) => ({
      registry: entry.registry,
      key: entry.key,
      alias: entry.alias,
      blueId,
      path: entry.path,
      sourceResourceSha256: entry.sourceResourceSha256,
      sourceBase64: entry.sourceBase64,
    }))
    .sort(
      (left, right) =>
        compareText(left.registry, right.registry) ||
        compareText(left.key, right.key) ||
        compareText(left.blueId, right.blueId)
    );
  const historicalRegistryEvidenceIdentity = framedIdentity(
    historicalRegistryEvidence.flatMap((entry) => [
      entry.registry,
      entry.key,
      entry.alias,
      entry.blueId,
      entry.path,
      entry.sourceResourceSha256,
    ])
  );
  const environment = {
    profile:
      'blue.coordination/fixed-repository-extracted-content-replay/1.0',
    strategy:
      'exact-extracted-canonical-content/no-active-runtime-merge/1.0',
    authoringEnvironmentClaim:
      'not-inferred-generator-copies-preexisting-published-identities',
    repositoryBuildDeclaredLanguageCoordinate:
      historicalLanguage.coordinate,
    repositoryBuildDeclaredLanguageTagCommit:
      historicalLanguage.commit,
    currentLanguageCommit:
      historicalLanguage.currentCommit,
    contextualCoreRegistryIdentity:
      historicalLanguage.coreRegistryIdentity,
    currentCoreRegistryIdentity:
      historicalLanguage.currentCoreRegistryIdentity,
    coreSourceEquivalenceIdentity:
      historicalLanguage.coreSourceEquivalenceIdentity,
    contextualRuntimeRoleRegistryEvidenceIdentity:
      historicalLanguage.runtimeRoleRegistryIdentity,
    contextualGeneratorIdentity: `sha256:${sha256(
      repositorySnapshot.read(repositoryGeneratorPath)
    )}`,
    contextualPreprocessingTransformsIdentity:
      historicalLanguage.preprocessingTransformsIdentity,
    currentPreprocessingTransformsIdentity:
      historicalLanguage.currentPreprocessingTransformsIdentity,
    transformEquivalenceStatus:
      historicalLanguage.transformEquivalenceStatus,
    transformEquivalenceIdentity:
      historicalLanguage.transformEquivalenceIdentity,
    historicalDefaultBlueSha256:
      historicalLanguage.historicalDefaultBlue.sourceSha256,
    currentDefaultBlueSha256:
      historicalLanguage.currentDefaultBlue.sourceSha256,
    historicalDefaultBlueAliasIdentity:
      historicalLanguage.historicalDefaultBlue.aliasIdentity,
    currentDefaultBlueAliasIdentity:
      historicalLanguage.currentDefaultBlue.aliasIdentity,
    normalizedDefaultBlueBodySha256:
      historicalLanguage.historicalDefaultBlue.normalizedBodySha256,
    runtimeRoleRegistryUse: 'evidence-only-not-installed',
    canonicalReplayUse:
      'proved-historical-alias-replay/current-verifier/no-direct-hash-admission/no-root-blueId-trust',
    externalReferencesIdentity,
    externalReferenceCount:
      externalReferences.length,
    historicalRegistryEvidenceIdentity,
    historicalRegistryEvidenceCount:
      historicalRegistryEvidence.length,
    repositoryGeneratorSha256: sha256(
      repositorySnapshot.read(repositoryGeneratorPath)
    ),
    repositoryBuildMetadataSha256: sha256(
      repositorySnapshot.read(repositoryBuildPath)
    ),
    repositorySourceBundleSha256: sha256(
      repositorySnapshot.read(repositorySourceBundlePath)
    ),
  };
  const repositorySourceStateEvidence =
    repositorySnapshot.evidence();
  environment.repositorySourceProvenance =
    repositorySourceStateEvidence.provenance;
  environment.repositoryHeadCommit =
    repositorySourceStateEvidence.headCommit;
  environment.repositorySourceStateIdentity =
    repositorySourceStateEvidence.identity;
  environment.repositorySourceMatchesHead =
    repositorySourceStateEvidence.matchesHead;
  environment.identity = framedIdentity([
    environment.profile,
    environment.strategy,
    environment.authoringEnvironmentClaim,
    environment.repositoryBuildDeclaredLanguageCoordinate,
    environment.repositoryBuildDeclaredLanguageTagCommit,
    environment.currentLanguageCommit,
    environment.contextualCoreRegistryIdentity,
    environment.currentCoreRegistryIdentity,
    environment.coreSourceEquivalenceIdentity,
    environment.contextualRuntimeRoleRegistryEvidenceIdentity,
    environment.contextualGeneratorIdentity,
    environment.contextualPreprocessingTransformsIdentity,
    environment.currentPreprocessingTransformsIdentity,
    environment.transformEquivalenceStatus,
    environment.transformEquivalenceIdentity,
    environment.historicalDefaultBlueSha256,
    environment.currentDefaultBlueSha256,
    environment.historicalDefaultBlueAliasIdentity,
    environment.currentDefaultBlueAliasIdentity,
    environment.normalizedDefaultBlueBodySha256,
    environment.runtimeRoleRegistryUse,
    environment.canonicalReplayUse,
    environment.externalReferencesIdentity,
    String(environment.externalReferenceCount),
    environment.historicalRegistryEvidenceIdentity,
    String(environment.historicalRegistryEvidenceCount),
    environment.repositoryGeneratorSha256,
    environment.repositoryBuildMetadataSha256,
    environment.repositorySourceProvenance,
    environment.repositoryHeadCommit,
    environment.repositorySourceStateIdentity,
    String(environment.repositorySourceMatchesHead),
  ]);

  const usageInputsIdentity = framedIdentity(
    usage.included.flatMap((input) => [
      input.relative,
      sha256(Buffer.from(input.source, 'utf8')),
    ])
  );
  const runtimeRegistrationsIdentity = framedIdentity(
    runtimeRegistrations.flatMap((registration) => [
      registration.path,
      registration.qualifiedName,
      registration.blueId,
    ])
  );
  const closureIdentity = framedIdentity([
    manifest.repositoryVersion,
    manifest.repositoryVersionBlueId,
    sha256(manifestBytes),
    repositorySourceStateEvidence.provenance,
    repositorySourceStateEvidence.headCommit,
    repositorySourceStateEvidence.identity,
    String(repositorySourceStateEvidence.matchesHead),
    environment.identity,
    usageInputsIdentity,
    ...entries.flatMap((definition) => [
      definition.qualifiedName,
      definition.blueId,
      definition.resourcePath,
      roots.has(definition.blueId) ? 'root' : 'transitive',
      ...(directReferences.get(definition.blueId) || []),
    ]),
  ]);
  const cyclicMasters = Array.from(
    new Set(
      entries
        .filter((definition) => definition.blueId.includes('#'))
        .map((definition) => definition.blueId.split('#')[0])
    )
  ).sort();

  const java = [];
  java.push('package blue.coordination.processor;');
  java.push('');
  java.push('import java.util.ArrayList;');
  java.push('import java.util.Arrays;');
  java.push('import java.util.Collections;');
  java.push('import java.util.LinkedHashMap;');
  java.push('import java.util.LinkedHashSet;');
  java.push('import java.util.List;');
  java.push('import java.util.Map;');
  java.push('import java.util.Set;');
  java.push('');
  java.push('/**');
  java.push(' * Generated immutable transitive fixed-Repository closure used by Coordination.');
  java.push(' *');
  java.push(' * <p>Do not edit this class. Its roots come from exact source and fixture');
  java.push(' * usage, and its edges come only from immutable manifest resources.</p>');
  java.push(' */');
  java.push('public final class CoordinationRequiredRepositoryClosure {');
  const constants = {
    SCHEMA:
      'blue.coordination/required-repository-closure/1.0',
    REPOSITORY_VERSION: manifest.repositoryVersion,
    REPOSITORY_MANIFEST_BLUE_ID: manifest.repositoryVersionBlueId,
    REPOSITORY_MANIFEST_SHA256: sha256(manifestBytes),
    REPOSITORY_SOURCE_PROVENANCE:
      repositorySourceStateEvidence.provenance,
    REPOSITORY_HEAD_COMMIT:
      repositorySourceStateEvidence.headCommit,
    REPOSITORY_SOURCE_STATE_IDENTITY:
      repositorySourceStateEvidence.identity,
    REPOSITORY_SOURCE_MATCHES_HEAD:
      String(repositorySourceStateEvidence.matchesHead),
    USAGE_INPUTS_IDENTITY: usageInputsIdentity,
    RUNTIME_REGISTRATIONS_IDENTITY:
      runtimeRegistrationsIdentity,
    RUNTIME_REGISTRATION_COUNT:
      String(runtimeRegistrations.length),
    CLOSURE_IDENTITY: closureIdentity,
    HISTORICAL_ENVIRONMENT_PROFILE: environment.profile,
    HISTORICAL_CANONICALIZATION_STRATEGY: environment.strategy,
    AUTHORING_ENVIRONMENT_CLAIM:
      environment.authoringEnvironmentClaim,
    REPOSITORY_BUILD_DECLARED_LANGUAGE_COORDINATE:
      environment.repositoryBuildDeclaredLanguageCoordinate,
    REPOSITORY_BUILD_DECLARED_LANGUAGE_TAG_COMMIT:
      environment.repositoryBuildDeclaredLanguageTagCommit,
    CURRENT_LANGUAGE_COMMIT:
      environment.currentLanguageCommit,
    CONTEXTUAL_CORE_REGISTRY_IDENTITY:
      environment.contextualCoreRegistryIdentity,
    CURRENT_CORE_REGISTRY_IDENTITY:
      environment.currentCoreRegistryIdentity,
    CORE_SOURCE_EQUIVALENCE_IDENTITY:
      environment.coreSourceEquivalenceIdentity,
    CONTEXTUAL_RUNTIME_ROLE_REGISTRY_EVIDENCE_IDENTITY:
      environment.contextualRuntimeRoleRegistryEvidenceIdentity,
    CONTEXTUAL_GENERATOR_IDENTITY:
      environment.contextualGeneratorIdentity,
    CONTEXTUAL_PREPROCESSING_TRANSFORMS_IDENTITY:
      environment.contextualPreprocessingTransformsIdentity,
    CURRENT_PREPROCESSING_TRANSFORMS_IDENTITY:
      environment.currentPreprocessingTransformsIdentity,
    TRANSFORM_EQUIVALENCE_STATUS:
      environment.transformEquivalenceStatus,
    TRANSFORM_EQUIVALENCE_IDENTITY:
      environment.transformEquivalenceIdentity,
    HISTORICAL_DEFAULT_BLUE_SHA256:
      environment.historicalDefaultBlueSha256,
    CURRENT_DEFAULT_BLUE_SHA256:
      environment.currentDefaultBlueSha256,
    HISTORICAL_DEFAULT_BLUE_ALIAS_IDENTITY:
      environment.historicalDefaultBlueAliasIdentity,
    CURRENT_DEFAULT_BLUE_ALIAS_IDENTITY:
      environment.currentDefaultBlueAliasIdentity,
    NORMALIZED_DEFAULT_BLUE_BODY_SHA256:
      environment.normalizedDefaultBlueBodySha256,
    RUNTIME_ROLE_REGISTRY_USE:
      environment.runtimeRoleRegistryUse,
    CANONICAL_REPLAY_USE:
      environment.canonicalReplayUse,
    EXTERNAL_REFERENCES_IDENTITY:
      environment.externalReferencesIdentity,
    EXTERNAL_REFERENCE_COUNT:
      String(environment.externalReferenceCount),
    HISTORICAL_REGISTRY_EVIDENCE_IDENTITY:
      environment.historicalRegistryEvidenceIdentity,
    HISTORICAL_REGISTRY_EVIDENCE_COUNT:
      String(environment.historicalRegistryEvidenceCount),
    REPOSITORY_GENERATOR_SHA256:
      environment.repositoryGeneratorSha256,
    REPOSITORY_BUILD_METADATA_SHA256:
      environment.repositoryBuildMetadataSha256,
    REPOSITORY_SOURCE_BUNDLE_SHA256:
      environment.repositorySourceBundleSha256,
    HISTORICAL_ENVIRONMENT_IDENTITY: environment.identity,
  };
  for (const [name, value] of Object.entries(constants)) {
    java.push(
      `    public static final String ${name} = "${javaString(value)}";`
    );
  }
  java.push(
    '    private static final String HISTORICAL_DEFAULT_BLUE_SOURCE_BASE64 = "' +
      javaString(historicalLanguage.historicalDefaultBlue.sourceBase64) +
      '";'
  );
  java.push('');
  java.push('    private static final List<Entry> ENTRIES =');
  java.push('            Collections.unmodifiableList(Arrays.asList(');
  entries.forEach((definition, index) => {
    const suffix = index + 1 < entries.length ? ',' : '));';
    const references =
      directReferences.get(definition.blueId) || [];
    const referenceArray =
      references.length === 0
        ? 'new String[0]'
        : 'new String[] {' +
          references
            .map((reference) => `"${javaString(reference)}"`)
            .join(', ') +
          '}';
    java.push(
      '                    new Entry("' +
        javaString(definition.qualifiedName) +
        '", "' +
        javaString(definition.blueId) +
        '", "' +
        javaString(definition.resourcePath) +
        '", "' +
        sourceResourceSha256ByBlueId.get(definition.blueId) +
        '", ' +
        (roots.has(definition.blueId) ? 'true' : 'false') +
        ', ' +
        (definition.blueId.includes('#') ? 'true' : 'false') +
        ', ' +
        referenceArray +
        ')' +
        suffix
    );
  });
  java.push('    private static final Map<String, Entry> BY_BLUE_ID;');
  java.push('    private static final Set<String> BLUE_IDS;');
  java.push('    private static final List<HistoricalEvidenceEntry>');
  java.push('            HISTORICAL_EVIDENCE_ENTRIES =');
  java.push('            Collections.unmodifiableList(Arrays.asList(');
  historicalRegistryEvidence.forEach((entry, index) => {
    const suffix =
      index + 1 < historicalRegistryEvidence.length ? ',' : '));';
    java.push(
      '                    new HistoricalEvidenceEntry("' +
        javaString(entry.registry) +
        '", "' +
        javaString(entry.key) +
        '", "' +
        javaString(entry.alias) +
        '", "' +
        javaString(entry.blueId) +
        '", "' +
        javaString(entry.path) +
        '", "' +
        javaString(entry.sourceResourceSha256) +
        '", "' +
        javaString(entry.sourceBase64) +
        '")' +
        suffix
    );
  });
  java.push('    private static final Map<String, String>');
  java.push('            HISTORICAL_PREPROCESSING_ALIASES;');
  java.push('');
  java.push('    static {');
  java.push('        Map<String, Entry> entries = new LinkedHashMap<String, Entry>();');
  java.push('        Set<String> blueIds = new LinkedHashSet<String>();');
  java.push('        for (Entry entry : ENTRIES) {');
  java.push('            entries.put(entry.blueId(), entry);');
  java.push('            blueIds.add(entry.blueId());');
  java.push('        }');
  java.push('        BY_BLUE_ID = Collections.unmodifiableMap(entries);');
  java.push('        BLUE_IDS = Collections.unmodifiableSet(blueIds);');
  java.push('        Map<String, String> aliases =');
  java.push('                new LinkedHashMap<String, String>();');
  for (const [alias, blueId] of
    historicalLanguage.historicalDefaultBlue.aliases.entries()) {
    java.push(
      '        aliases.put("' +
        javaString(alias) +
        '", "' +
        javaString(blueId) +
        '");'
    );
  }
  java.push('        HISTORICAL_PREPROCESSING_ALIASES =');
  java.push('                Collections.unmodifiableMap(aliases);');
  java.push('    }');
  java.push('');
  java.push('    private CoordinationRequiredRepositoryClosure() {');
  java.push('    }');
  java.push('');
  java.push('    /** Returns the exact immutable closure in canonical order. */');
  java.push('    public static List<Entry> entries() {');
  java.push('        return ENTRIES;');
  java.push('    }');
  java.push('');
  java.push('    /** Returns every required current Repository BlueId. */');
  java.push('    public static Set<String> blueIds() {');
  java.push('        return BLUE_IDS;');
  java.push('    }');
  java.push('');
  java.push('    /** Returns whether an exact current Repository BlueId is required. */');
  java.push('    public static boolean containsBlueId(String blueId) {');
  java.push('        return BY_BLUE_ID.containsKey(blueId);');
  java.push('    }');
  java.push('');
  java.push('    /** Returns the generated entry for one exact current Repository BlueId. */');
  java.push('    public static Entry entry(String blueId) {');
  java.push('        return BY_BLUE_ID.get(blueId);');
  java.push('    }');
  java.push('');
  java.push('    /** Returns exact historical Language source evidence; never an active registry. */');
  java.push('    public static List<HistoricalEvidenceEntry> historicalEvidenceEntries() {');
  java.push('        return HISTORICAL_EVIDENCE_ENTRIES;');
  java.push('    }');
  java.push('');
  java.push('    /** Returns exact historical authoring aliases for the isolated verifier. */');
  java.push('    public static Map<String, String> historicalPreprocessingAliases() {');
  java.push('        return HISTORICAL_PREPROCESSING_ALIASES;');
  java.push('    }');
  java.push('');
  java.push('    /** Returns exact v3.0.0 Default Blue transform evidence bytes. */');
  java.push('    public static byte[] historicalDefaultBlueSourceBytes() {');
  java.push('        return java.util.Base64.getDecoder().decode(');
  java.push('                HISTORICAL_DEFAULT_BLUE_SOURCE_BASE64);');
  java.push('    }');
  java.push('');
  java.push('    /** One immutable generated closure member. */');
  java.push('    public static final class Entry {');
  java.push('        private final String qualifiedName;');
  java.push('        private final String blueId;');
  java.push('        private final String resourcePath;');
  java.push('        private final String sourceResourceSha256;');
  java.push('        private final boolean root;');
  java.push('        private final boolean cyclicMember;');
  java.push('        private final List<String> directReferences;');
  java.push('');
  java.push('        private Entry(String qualifiedName, String blueId,');
  java.push('                      String resourcePath,');
  java.push('                      String sourceResourceSha256,');
  java.push('                      boolean root, boolean cyclicMember,');
  java.push('                      String[] directReferences) {');
  java.push('            this.qualifiedName = qualifiedName;');
  java.push('            this.blueId = blueId;');
  java.push('            this.resourcePath = resourcePath;');
  java.push('            this.sourceResourceSha256 = sourceResourceSha256;');
  java.push('            this.root = root;');
  java.push('            this.cyclicMember = cyclicMember;');
  java.push('            this.directReferences =');
  java.push('                    Collections.unmodifiableList(');
  java.push('                            Arrays.asList(directReferences.clone()));');
  java.push('        }');
  java.push('');
  java.push('        public String qualifiedName() { return qualifiedName; }');
  java.push('        public String blueId() { return blueId; }');
  java.push('        public String resourcePath() { return resourcePath; }');
  java.push('        public String sourceResourceSha256() { return sourceResourceSha256; }');
  java.push('        public boolean root() { return root; }');
  java.push('        public boolean cyclicMember() { return cyclicMember; }');
  java.push('        public List<String> directReferences() { return directReferences; }');
  java.push('    }');
  java.push('');
  java.push('    /** One exact v3.0.0 Language source used only as verification evidence. */');
  java.push('    public static final class HistoricalEvidenceEntry {');
  java.push('        private final String registry;');
  java.push('        private final String key;');
  java.push('        private final String alias;');
  java.push('        private final String blueId;');
  java.push('        private final String path;');
  java.push('        private final String sourceResourceSha256;');
  java.push('        private final String sourceBase64;');
  java.push('');
  java.push('        private HistoricalEvidenceEntry(');
  java.push('                String registry, String key, String alias,');
  java.push('                String blueId,');
  java.push('                String path, String sourceResourceSha256,');
  java.push('                String sourceBase64) {');
  java.push('            this.registry = registry;');
  java.push('            this.key = key;');
  java.push('            this.alias = alias;');
  java.push('            this.blueId = blueId;');
  java.push('            this.path = path;');
  java.push('            this.sourceResourceSha256 = sourceResourceSha256;');
  java.push('            this.sourceBase64 = sourceBase64;');
  java.push('        }');
  java.push('');
  java.push('        public String registry() { return registry; }');
  java.push('        public String key() { return key; }');
  java.push('        public String alias() { return alias; }');
  java.push('        public String blueId() { return blueId; }');
  java.push('        public String path() { return path; }');
  java.push('        public String sourceResourceSha256() { return sourceResourceSha256; }');
  java.push('        public byte[] sourceBytes() {');
  java.push('            return java.util.Base64.getDecoder().decode(sourceBase64);');
  java.push('        }');
  java.push('    }');
  java.push('}');
  java.push('');

  fs.mkdirSync(path.dirname(javaOutput), { recursive: true });
  fs.writeFileSync(javaOutput, java.join('\n'), 'utf8');

  const report = {
    schema:
      'blue.coordination/required-repository-closure-generation/1.0',
    status: 'generated',
    repository: {
      version: manifest.repositoryVersion,
      manifestBlueId: manifest.repositoryVersionBlueId,
      manifestSha256: sha256(manifestBytes),
      sourceProvenance: repositorySourceStateEvidence.provenance,
      headCommit: repositorySourceStateEvidence.headCommit,
      sourceStateIdentity: repositorySourceStateEvidence.identity,
      sourceMatchesHead: repositorySourceStateEvidence.matchesHead,
      sourceInputCount: repositorySourceStateEvidence.inputCount,
      changedSourceInputCount: 0,
      generatorSha256: environment.repositoryGeneratorSha256,
      buildMetadataSha256:
        environment.repositoryBuildMetadataSha256,
      sourceBundleSha256:
        environment.repositorySourceBundleSha256,
    },
    historicalEnvironment: environment,
    historicalCoreReplay: {
      status: 'proved-source-byte-equivalent',
      identity:
        historicalLanguage.coreSourceEquivalenceIdentity,
      historicalRegistryIdentity:
        historicalLanguage.coreRegistryIdentity,
      currentRegistryIdentity:
        historicalLanguage.currentCoreRegistryIdentity,
      entries:
        historicalLanguage.unchangedCoreSourceEntries,
    },
    historicalTransformReplay: {
      status:
        historicalLanguage.transformEquivalenceStatus,
      identity:
        historicalLanguage.transformEquivalenceIdentity,
      historicalLanguageCommit:
        historicalLanguage.commit,
      currentLanguageCommit:
        historicalLanguage.currentCommit,
      historicalDefaultBlueSha256:
        historicalLanguage.historicalDefaultBlue.sourceSha256,
      currentDefaultBlueSha256:
        historicalLanguage.currentDefaultBlue.sourceSha256,
      historicalAliasIdentity:
        historicalLanguage.historicalDefaultBlue.aliasIdentity,
      currentAliasIdentity:
        historicalLanguage.currentDefaultBlue.aliasIdentity,
      normalizedDefaultBlueBodySha256:
        historicalLanguage.historicalDefaultBlue
          .normalizedBodySha256,
      unchangedTransformEntries:
        historicalLanguage.unchangedTransformEntries,
    },
    externalReferences: {
      identity: externalReferencesIdentity,
      total: externalReferences.length,
      resolved: externalReferences.filter(
        (reference) => reference.status === 'resolved'
      ).length,
      unresolved: unresolvedExternalReferences.length,
      entries: externalReferences,
    },
    historicalRegistryEvidence: {
      identity: historicalRegistryEvidenceIdentity,
      total: historicalRegistryEvidence.length,
      activeRuntimeUse: false,
      use: 'isolated-bound-source-content-verification-only',
      entries: historicalRegistryEvidence.map((entry) => ({
        registry: entry.registry,
        key: entry.key,
        alias: entry.alias,
        blueId: entry.blueId,
        path: entry.path,
        sourceResourceSha256: entry.sourceResourceSha256,
      })),
    },
    usage: {
      inputs: usage.included.length,
      inputsIdentity: usageInputsIdentity,
      excludedAuditConsumers: usage.excluded,
      roots: roots.size,
      runtimeRegistrations: {
        identity: runtimeRegistrationsIdentity,
        total: runtimeRegistrations.length,
        entries: runtimeRegistrations,
      },
    },
    closure: {
      identity: closureIdentity,
      total: entries.length,
      cyclicSetCount: cyclicMasters.length,
      cyclicMemberCount: entries.filter((entry) =>
        entry.blueId.includes('#')
      ).length,
    },
    entries: entries.map((definition) => ({
      qualifiedName: definition.qualifiedName,
      blueId: definition.blueId,
      resourcePath: definition.resourcePath,
      sourceResourceSha256:
        sourceResourceSha256ByBlueId.get(definition.blueId),
      root: roots.has(definition.blueId),
      rootReasons: (roots.get(definition.blueId) || []).map((reason) => ({
        kind: reason.kind,
        path: reason.path,
        evidence: reason.evidence,
      })),
      directReferences: directReferences.get(definition.blueId) || [],
      cyclicMember: definition.blueId.includes('#'),
    })),
  };
  fs.mkdirSync(path.dirname(reportOutput), { recursive: true });
  fs.writeFileSync(
    reportOutput,
    `${JSON.stringify(report, null, 2)}\n`,
    'utf8'
  );
}

if (require.main === module) {
  main();
}

module.exports = {
  containsExactQualifiedName,
  expandDefinitionClosure,
  runtimeRegistrationQualifiedNames,
};
