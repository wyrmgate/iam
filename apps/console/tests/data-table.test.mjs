import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import test from 'node:test';

const table = await readFile(new URL('../src/ui/table.tsx', import.meta.url), 'utf8');
const barrel = await readFile(new URL('../src/ui/index.tsx', import.meta.url), 'utf8');

test('DataTable keeps dense administrative overflow and accessible naming hooks', () => {
  assert.match(table, /overflowX: 'auto'/);
  assert.match(table, /maxWidth: '100%'/);
  assert.match(table, /aria-label=\{ariaLabel\}/);
  assert.match(table, /aria-labelledby=\{ariaLabelledBy\}/);
  assert.match(table, /component="caption"/);
  assert.match(table, /captionHidden/);
  assert.match(table, /MuiTableHead-root/);
  assert.match(table, /MuiTableBody-root/);
});

test('DataTable exposes domain-neutral row, action and state placement helpers', () => {
  assert.match(table, /export function DataTableRow/);
  assert.match(table, /interactive = false/);
  assert.match(table, /hover=\{hover \?\? interactive\}/);
  assert.match(table, /Mui-selected/);
  assert.match(table, /export function DataTableActionCell/);
  assert.match(table, /whiteSpace: 'nowrap'/);
  assert.match(table, /export function DataTableStateRow/);
  assert.match(table, /colSpan=\{colSpan\}/);
  assert.match(table, /role=\{loading \? 'status' : undefined\}/);
});

test('DataTable helpers remain available through the stable ui barrel', () => {
  for (const name of ['DataTable', 'DataTableActionCell', 'DataTableRow', 'DataTableStateRow']) {
    assert.match(barrel, new RegExp(`\\b${name}\\b`));
  }
  for (const name of ['Table', 'TableBody', 'TableCell', 'TableHead', 'TableRow']) {
    assert.match(barrel, new RegExp(`\\b${name}\\b`));
  }
});
