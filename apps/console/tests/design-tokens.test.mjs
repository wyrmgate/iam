import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import test from 'node:test';

const tokens = await readFile(new URL('../src/ui/designTokens.ts', import.meta.url), 'utf8');
const provider = await readFile(new URL('../src/ui/WyrmgateUiProvider.tsx', import.meta.url), 'utf8');

test('design tokens keep product, semantic, lifecycle and neutral colors distinct', () => {
  for (const group of ['product', 'neutral', 'semantic', 'lifecycle']) {
    assert.match(tokens, new RegExp(`${group}: \\{`));
  }
  assert.match(tokens, /accent: '#235ea7'/);
  assert.match(tokens, /success: '#257149'/);
  assert.match(tokens, /warning: '#875f12'/);
  assert.match(tokens, /danger: '#a12b35'/);
  assert.match(tokens, /active: '#257149'/);
  assert.match(tokens, /inactive: '#66758a'/);
});

test('theme consumes named tokens instead of duplicating palette literals', () => {
  assert.match(provider, /wyrmgateDesignTokens as tokens/);
  assert.match(provider, /primary: \{[\s\S]*tokens\.color\.product\.accent/);
  assert.match(provider, /error: \{ main: tokens\.color\.semantic\.danger \}/);
  assert.match(provider, /background: \{[\s\S]*tokens\.color\.neutral\.canvas/);
  assert.doesNotMatch(provider, /#[0-9a-fA-F]{6}/);
});

test('enterprise density baseline is centralized in shared defaults', () => {
  for (const component of [
    'MuiButton',
    'MuiTextField',
    'MuiFormControl',
    'MuiOutlinedInput',
    'MuiTable',
    'MuiTableCell',
    'MuiChip',
    'MuiTabs',
    'MuiTab',
    'MuiDialog',
  ]) {
    assert.match(provider, new RegExp(`${component}: \\{`));
  }
  assert.match(provider, /controlHeight: 36/);
  assert.match(provider, /compactControlHeight: 32/);
  assert.match(provider, /tableRowHeight: 40/);
});

test('theme preserves reduced-motion behavior globally', () => {
  assert.match(provider, /prefers-reduced-motion: reduce/);
  assert.match(provider, /animationDuration: '0\.01ms !important'/);
  assert.match(provider, /transitionDuration: '0\.01ms !important'/);
});
