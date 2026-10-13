import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import test from 'node:test';

const index = await readFile(new URL('../src/ui/index.tsx', import.meta.url), 'utf8');
const modules = {
  layout: await readFile(new URL('../src/ui/layout.tsx', import.meta.url), 'utf8'),
  forms: await readFile(new URL('../src/ui/forms.tsx', import.meta.url), 'utf8'),
  page: await readFile(new URL('../src/ui/page.tsx', import.meta.url), 'utf8'),
  state: await readFile(new URL('../src/ui/state.tsx', import.meta.url), 'utf8'),
  table: await readFile(new URL('../src/ui/table.tsx', import.meta.url), 'utf8'),
  dialogs: await readFile(new URL('../src/ui/dialogs.tsx', import.meta.url), 'utf8'),
  tabs: await readFile(new URL('../src/ui/tabs.tsx', import.meta.url), 'utf8'),
};

const expectedExports = [
  'Box', 'CircularProgress', 'Stack', 'Typography',
  'Button', 'CheckboxInput', 'SelectInput', 'TextInput',
  'Page', 'PageHeader', 'PageToolbar', 'Surface',
  'Alert', 'StatePanel', 'StatusChip',
  'DataTable', 'Table', 'TableBody', 'TableCell', 'TableHead', 'TableRow',
  'Modal', 'SidePanel', 'ConfirmDialog',
  'Tab', 'Tabs',
];

test('ui index remains a re-export-only compatibility boundary', () => {
  assert.doesNotMatch(index, /function\s+\w+|@mui\/material/);
  for (const moduleName of Object.keys(modules)) {
    assert.match(index, new RegExp(`from '\\.\\/${moduleName}'`));
  }
  for (const symbol of expectedExports) {
    assert.match(index, new RegExp(`\\b${symbol}\\b`));
  }
});

test('shared primitive implementations have focused ownership modules', () => {
  assert.match(modules.forms, /export function TextInput/);
  assert.match(modules.forms, /export function SelectInput/);
  assert.match(modules.page, /export function PageHeader/);
  assert.match(modules.state, /export function StatePanel/);
  assert.match(modules.table, /export function DataTable/);
  assert.match(modules.dialogs, /export function ConfirmDialog/);
  assert.match(modules.tabs, /export function Tabs/);
});

test('decomposition does not duplicate implementations across ownership modules', () => {
  const combined = Object.values(modules).join('\n');
  for (const symbol of ['TextInput', 'PageHeader', 'StatePanel', 'DataTable', 'ConfirmDialog', 'Tabs']) {
    assert.equal(combined.match(new RegExp(`export function ${symbol}\\b`, 'g'))?.length, 1);
  }
});
