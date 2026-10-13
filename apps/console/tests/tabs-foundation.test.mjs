import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import test from 'node:test';

const tabs = await readFile(new URL('../src/ui/tabs.tsx', import.meta.url), 'utf8');
const index = await readFile(new URL('../src/ui/index.tsx', import.meta.url), 'utf8');

test('tabs require an accessible name and make controlled/uncontrolled modes explicit', () => {
  assert.match(tabs, /type TabsAccessibleName/);
  assert.match(tabs, /'aria-label': string/);
  assert.match(tabs, /'aria-labelledby': string/);
  assert.match(tabs, /export type ControlledTabsProps/);
  assert.match(tabs, /defaultValue\?: never/);
  assert.match(tabs, /export type UncontrolledTabsProps/);
  assert.match(tabs, /value\?: never/);
});

test('tabs preserve horizontal overflow and standard keyboard behavior through MUI', () => {
  assert.match(tabs, /variant="scrollable"/);
  assert.match(tabs, /orientation="horizontal"/);
  assert.match(tabs, /scrollButtons="auto"/);
  assert.match(tabs, /allowScrollButtonsMobile/);
  assert.match(tabs, /selectionFollowsFocus=\{false\}/);
  assert.match(tabs, /overscrollBehaviorInline: 'contain'/);
  assert.match(tabs, /prefers-reduced-motion: reduce/);
  assert.match(tabs, /minWidth: 'max-content'/);
  assert.match(tabs, /whiteSpace: 'nowrap'/);
});

test('tab and tabpanel relationship helpers are explicit and route-agnostic', () => {
  assert.match(tabs, /export function getTabA11yProps/);
  assert.match(tabs, /'aria-controls': tabPanelElementId/);
  assert.match(tabs, /export function TabPanel/);
  assert.match(tabs, /role="tabpanel"/);
  assert.match(tabs, /aria-labelledby=\{tabElementId/);
  assert.match(tabs, /hidden=\{!active\}/);
  assert.match(tabs, /active \|\| keepMounted \? children : null/);
  assert.doesNotMatch(tabs, /location|navigate|history|pathname|searchParams|router/i);
});

test('tabs accessibility contract remains available through the ui barrel', () => {
  for (const symbol of ['Tab', 'TabPanel', 'Tabs', 'getTabA11yProps']) {
    assert.match(index, new RegExp(`\\b${symbol}\\b`));
  }
  for (const symbol of ['ControlledTabsProps', 'UncontrolledTabsProps', 'WyrmgateTabsProps', 'TabPanelProps']) {
    assert.match(index, new RegExp(`\\b${symbol}\\b`));
  }
});
