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
  shell: 'shell-foundation.css',
  tabs: 'ui/tabs.tsx',
});

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
    sources.shell,
    /button:focus-visible/,
    /prefers-reduced-motion: reduce/,
  );
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
