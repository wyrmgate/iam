import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import test from 'node:test';

const tokens = await readFile(new URL('../src/ui/designTokens.ts', import.meta.url), 'utf8');
const variables = await readFile(new URL('../src/ui/cssVariables.ts', import.meta.url), 'utf8');
const provider = await readFile(new URL('../src/ui/WyrmgateUiProvider.tsx', import.meta.url), 'utf8');
const shellTheme = await readFile(new URL('../src/shell-theme.css', import.meta.url), 'utf8');

test('design tokens keep product, neutral, navigation, semantic, lifecycle and visualization roles distinct', () => {
  for (const group of ['product', 'neutral', 'navigation', 'semantic', 'lifecycle', 'visualization', 'overlay']) {
    assert.match(tokens, new RegExp(`${group}: \\{`));
  }
  assert.match(tokens, /accentPressed:/);
  assert.match(tokens, /surfaceHover:/);
  assert.match(tokens, /activeIndicator:/);
  assert.match(tokens, /successSubtle:/);
  assert.match(tokens, /categorical:/);
  assert.match(tokens, /scrimLight:/);
});

test('theme consumes named tokens instead of duplicating palette literals', () => {
  assert.match(provider, /wyrmgateDesignTokens as tokens/);
  assert.match(provider, /primary: \{[\s\S]*tokens\.color\.product\.accent/);
  assert.match(provider, /error: \{ main: tokens\.color\.semantic\.danger \}/);
  assert.match(provider, /background: \{[\s\S]*tokens\.color\.neutral\.canvas/);
  assert.doesNotMatch(provider, /#[0-9a-fA-F]{6}/);
});

test('css variables and shell presentation consume the same semantic source', () => {
  assert.match(variables, /wyrmgateDesignTokens as tokens/);
  for (const role of [
    "'--wg-nav': tokens.color.navigation.background",
    "'--wg-nav-active': tokens.color.navigation.active",
    "'--wg-nav-active-indicator': tokens.color.navigation.activeIndicator",
    "'--wg-surface-hover': tokens.color.neutral.surfaceHover",
    "'--wg-shadow-popover': tokens.elevation.popover",
  ]) {
    assert.ok(variables.includes(role));
  }
  assert.match(shellTheme, /--shell-nav: var\(--wg-nav\)/);
  assert.match(shellTheme, /--shell-nav-active: var\(--wg-nav-active\)/);
  assert.match(shellTheme, /--shell-nav-indicator: var\(--wg-nav-active-indicator\)/);
  assert.match(shellTheme, /--shell-shadow: var\(--wg-shadow-popover\)/);
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
  assert.match(tokens, /controlHeight: 36/);
  assert.match(tokens, /compactControlHeight: 32/);
  assert.match(tokens, /mobileTargetHeight: 44/);
  assert.match(tokens, /tableRowHeight: 40/);
  assert.match(provider, /tokens\.density\.controlHeight/);
  assert.match(provider, /tokens\.density\.compactControlHeight/);
  assert.match(provider, /tokens\.density\.mobileTargetHeight/);
  assert.match(provider, /tokens\.density\.tableRowHeight/);
});

test('normal surfaces stay flat while overlays have named elevation roles', () => {
  assert.match(provider, /MuiCard:[\s\S]*boxShadow: 'none'/);
  assert.match(provider, /MuiDialog:[\s\S]*tokens\.elevation\.dialog/);
  assert.match(provider, /MuiMenu:[\s\S]*tokens\.elevation\.popover/);
  assert.match(tokens, /drawer: '18px 0 55px/);
});

test('theme preserves reduced-motion behavior globally', () => {
  assert.match(provider, /prefers-reduced-motion: reduce/);
  assert.match(provider, /animationDuration: '0\.01ms !important'/);
  assert.match(provider, /transitionDuration: '0\.01ms !important'/);
});
