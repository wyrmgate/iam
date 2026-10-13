import assert from 'node:assert/strict';
import test from 'node:test';
import {
  assertSourceContains,
  assertSourceExcludes,
  readConsoleSources,
} from './helpers/source-contract.mjs';

const sources = await readConsoleSources({
  dialogs: 'ui/dialogs.tsx',
  forms: 'ui/forms.tsx',
  provider: 'ui/WyrmgateUiProvider.tsx',
  semanticWorkspace: 'SemanticWorkspace.tsx',
  shellFoundation: 'shell-foundation.css',
  shellTheme: 'shell-theme.css',
  tabs: 'ui/tabs.tsx',
  tokens: 'ui/designTokens.ts',
});

function tokenHex(name) {
  const match = sources.tokens.match(new RegExp(`${name}: '(#[0-9a-f]{6})'`, 'i'));
  assert.ok(match, `expected ${name} color token`);
  return match[1];
}

function relativeLuminance(hex) {
  const channels = hex.slice(1).match(/.{2}/g).map((value) => Number.parseInt(value, 16) / 255);
  const [red, green, blue] = channels.map((value) => (
    value <= 0.04045 ? value / 12.92 : ((value + 0.055) / 1.055) ** 2.4
  ));
  return (0.2126 * red) + (0.7152 * green) + (0.0722 * blue);
}

function contrastRatio(foreground, background) {
  const lighter = Math.max(relativeLuminance(foreground), relativeLuminance(background));
  const darker = Math.min(relativeLuminance(foreground), relativeLuminance(background));
  return (lighter + 0.05) / (darker + 0.05);
}

test('keyboard focus remains visible and reduced motion remains respected', () => {
  assertSourceContains(
    sources.provider,
    /MuiButtonBase/,
    /'&:focus-visible'/,
    /accentFocus/,
    /prefers-reduced-motion: reduce/,
    /scrollBehavior: 'auto !important'/,
  );
  assertSourceContains(
    sources.shellTheme,
    /button:focus-visible/,
    /command-input-row:focus-within/,
    /prefers-reduced-motion: reduce/,
  );
  assertSourceContains(sources.shellFoundation, /prefers-reduced-motion: reduce/);
});

test('form help, validation, and read-only state are exposed to assistive technology', () => {
  assertSourceContains(
    sources.forms,
    /'aria-describedby': descriptionId/,
    /'aria-invalid': Boolean\(error\) \|\| undefined/,
    /'aria-readonly': readOnly \|\| undefined/,
    /<FormHelperText id=\{descriptionId\}/,
  );
});

test('dialogs preserve deliberate initial focus and framework return-focus behavior', () => {
  assertSourceContains(
    sources.dialogs,
    /initialFocusRef\.current\?\.focus\(\)/,
    /cancelButtonRef\.current\?\.focus\(\)/,
    /aria-labelledby/,
    /aria-describedby/,
  );
  assertSourceExcludes(sources.dialogs, /disableRestoreFocus/);
});

test('semantic workspace tables have names and explicit column headers', () => {
  assertSourceContains(
    sources.semanticWorkspace,
    /<DataTable minWidth=\{820\} ariaLabel=\{`\$\{spec\.title\} resources`\}>/,
    /component="th" scope="col"/,
  );
});

test('semantic workspace tabs expose tab-to-panel relationships without routing changes', () => {
  assertSourceContains(
    sources.semanticWorkspace,
    /getTabA11yProps\(groupId, tab\.id\)/,
    /<TabPanel[\s\S]*groupId=\{groupId\}[\s\S]*tabId=\{tab\.id\}[\s\S]*active=\{tab\.id === active\}/,
  );
  assertSourceContains(
    sources.tabs,
    /role="tabpanel"/,
    /aria-labelledby=\{tabElementId/,
    /prefers-reduced-motion: reduce/,
  );
  assertSourceExcludes(sources.semanticWorkspace, /navigateConsole|history\.pushState|location\.pathname/);
});

test('shared foreground tokens retain WCAG AA contrast on the primary surface', () => {
  const surface = tokenHex('surface');
  for (const foregroundName of ['text', 'textSecondary', 'accent', 'danger', 'warning', 'success', 'info']) {
    const ratio = contrastRatio(tokenHex(foregroundName), surface);
    assert.ok(ratio >= 4.5, `${foregroundName} contrast ${ratio.toFixed(2)} must remain at least 4.5:1`);
  }
});
