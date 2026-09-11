const assert = require('node:assert/strict');
const { execFileSync, spawnSync } = require('node:child_process');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const test = require('node:test');

const workflow = fs.readFileSync(
  path.join(__dirname, '../workflows/release-rc.yml'), 'utf8',
);
const version = '3.0.0-rc.8';
const tag = `refs/tags/v${version}`;

function stepCommand(name) {
  const start = workflow.indexOf(`      - name: ${name}\n`);
  assert.notEqual(start, -1, `Missing workflow step: ${name}`);
  const end = workflow.indexOf('\n      - name:', start + 1);
  const step = workflow.slice(start, end < 0 ? undefined : end);
  const command = step.match(/^        run: (.+)$/m)?.[1];
  assert.ok(command, `Expected a single command for ${name}`);
  return command.replaceAll('${{ steps.version.outputs.version }}', version);
}

const pushCommit = stepCommand('Push verified release commit');
const pushTag = stepCommand('Push published release tag');

function fixture(t) {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'coordination-rc-refs-'));
  t.after(() => fs.rmSync(root, { recursive: true, force: true }));
  const env = {
    ...process.env,
    GIT_CONFIG_NOSYSTEM: '1',
    GIT_CONFIG_GLOBAL: os.devNull,
    GIT_AUTHOR_NAME: 'Release workflow test',
    GIT_AUTHOR_EMAIL: 'release-test@example.invalid',
    GIT_COMMITTER_NAME: 'Release workflow test',
    GIT_COMMITTER_EMAIL: 'release-test@example.invalid',
  };
  const remote = path.join(root, 'remote.git');
  const publisher = path.join(root, 'publisher');
  const contributor = path.join(root, 'contributor');
  const git = (cwd, ...args) => execFileSync('git', args, {
    cwd, env, encoding: 'utf8', stdio: ['ignore', 'pipe', 'pipe'],
  }).trim();
  const shell = (command) => spawnSync('bash', ['-euo', 'pipefail', '-c', command], {
    cwd: publisher, env, encoding: 'utf8',
  });
  const commit = (cwd, message) => {
    git(cwd, 'commit', '--quiet', '--allow-empty', '-m', message);
    return git(cwd, 'rev-parse', 'HEAD');
  };
  git(root, 'init', '--bare', '--quiet', '--initial-branch=next', remote);
  git(root, 'clone', '--quiet', remote, publisher);
  commit(publisher, 'Initial next');
  git(publisher, 'push', '--quiet', 'origin', 'HEAD:next');
  git(root, 'clone', '--quiet', remote, contributor);
  const releaseCommit = commit(publisher, `chore: release ${version}`);
  git(publisher, 'tag', '-a', `v${version}`, '-m', `Release ${version}`);
  git(publisher, 'tag', '-a', 'unrelated-local-tag', '-m', 'Not a release output');
  // Even a configured follow-tags default must not publish either tag early.
  git(publisher, 'config', 'push.followTags', 'true');
  return { root, remote, publisher, contributor, git, shell, commit, releaseCommit };
}

test('publishes the commit after gates and the tag after deployment', () => {
  const names = [
    'Verify the Java 21 release gate',
    'Build and stage from published dependencies',
    'Push verified release commit',
    'Publish to Maven Central',
    'Push published release tag',
  ];
  const positions = names.map((name) => workflow.indexOf(`      - name: ${name}\n`));
  assert.ok(positions.every((position, i) => position >= 0
    && (i === 0 || position > positions[i - 1])));
});

test('a competing next update prevents deployment even after a successful dry run', (t) => {
  const f = fixture(t);
  const dryRun = f.shell(`git push --dry-run --no-follow-tags origin HEAD:refs/heads/next ${tag}`);
  assert.equal(dryRun.status, 0, dryRun.stderr);
  const newerCommit = f.commit(f.contributor, 'Concurrent merge before deployment');
  f.git(f.contributor, 'push', '--quiet', 'origin', 'HEAD:next');
  const result = f.shell(`${pushCommit}\nprintf deployed > deployment-started`);
  assert.notEqual(result.status, 0);
  assert.equal(fs.existsSync(path.join(f.publisher, 'deployment-started')), false);
  assert.equal(f.git(f.remote, 'rev-parse', 'refs/heads/next'), newerCommit);
  assert.equal(f.git(f.remote, 'tag', '--list'), '');
});

test('a next update during deployment does not block the exact release tag', (t) => {
  const f = fixture(t);
  const reserve = f.shell(pushCommit);
  assert.equal(reserve.status, 0, reserve.stderr);
  assert.equal(f.git(f.remote, 'rev-parse', 'refs/heads/next'), f.releaseCommit);
  assert.equal(f.git(f.remote, 'tag', '--list'), '');
  f.git(f.contributor, 'pull', '--quiet', '--ff-only');
  const newerCommit = f.commit(f.contributor, 'Concurrent merge during deployment');
  f.git(f.contributor, 'push', '--quiet', 'origin', 'HEAD:next');
  const published = f.shell(pushTag);
  assert.equal(published.status, 0, published.stderr);
  assert.equal(f.git(f.remote, 'rev-parse', 'refs/heads/next'), newerCommit);
  assert.equal(f.git(f.remote, 'rev-parse', `${tag}^{commit}`), f.releaseCommit);
  assert.equal(f.git(f.remote, 'tag', '--list'), `v${version}`);
});
