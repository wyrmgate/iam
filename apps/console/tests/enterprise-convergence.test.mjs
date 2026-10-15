import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import test from 'node:test';

const main = await readFile(new URL('../src/main.tsx', import.meta.url), 'utf8');
const enterprise = await readFile(new URL('../src/enterprise-console.css', import.meta.url), 'utf8');
const accessWrapper = await readFile(new URL('../src/workspaces/AccessWorkspace.tsx', import.meta.url), 'utf8');
const access = await readFile(new URL('../src/access-golden.css', import.meta.url), 'utf8');
const accessPolicy = await readFile(new URL('../src/access-policy-editor.css', import.meta.url), 'utf8');
const operations = await readFile(new URL('../src/iam-operations.css', import.meta.url), 'utf8');
const requests = await readFile(new URL('../src/requests-approvals.css', import.meta.url), 'utf8');
const shellTheme = await readFile(new URL('../src/shell-theme.css', import.meta.url), 'utf8');

test('authenticated Console activates one final enterprise presentation contract', () => {
  assert.match(main, /import '\.\/enterprise-console\.css';/);
  assert.ok(main.lastIndexOf("import './enterprise-console.css';") > main.lastIndexOf("import './shell-session-golden.css';"));
  for (const selector of ['.access-golden-page', '.iam-operations', '.requests-approvals']) {
    assert.match(enterprise, new RegExp(selector.replace('.', '\\.') + '[,\\s\\{]'));
  }
  for (const token of ['--wg-canvas', '--wg-surface', '--wg-border', '--wg-text', '--wg-accent', '--wg-success', '--wg-warning', '--wg-danger']) {
    assert.match(enterprise, new RegExp(token));
  }
});

test('purpose-built workbench CSS owns geometry, not a competing product palette', () => {
  assert.doesNotMatch(operations, /--iam-/);
  assert.doesNotMatch(requests, /--ra-/);
  for (const css of [access, accessPolicy, operations, requests]) {
    assert.doesNotMatch(css, /#[0-9a-fA-F]{3,8}\b/);
    assert.doesNotMatch(css, /(?:linear|radial)-gradient\(/);
  }
  assert.match(access, /var\(--wg-border\)/);
  assert.match(operations, /var\(--wg-surface\)/);
  assert.match(requests, /var\(--wg-text-secondary\)/);
});

test('Access no longer stacks a temporary enterprise alignment stylesheet', async () => {
  assert.doesNotMatch(accessWrapper, /access-enterprise-alignment\.css/);
  await assert.rejects(
    readFile(new URL('../src/access-enterprise-alignment.css', import.meta.url), 'utf8'),
    (error) => error?.code === 'ENOENT',
  );
});

test('shell presentation resolves its shared visual roles through enterprise tokens', () => {
  for (const [shellRole, sharedRole] of [
    ['--shell-canvas', '--wg-canvas'],
    ['--shell-surface', '--wg-surface'],
    ['--shell-border', '--wg-border'],
    ['--shell-border-strong', '--wg-border-strong'],
    ['--shell-text', '--wg-text'],
    ['--shell-muted', '--wg-text-secondary'],
    ['--shell-accent', '--wg-accent'],
  ]) {
    assert.match(shellTheme, new RegExp(`${shellRole}: var\\(${sharedRole}\\)`));
  }
  assert.match(shellTheme, /outline: 3px solid var\(--wg-focus\)/);
});
