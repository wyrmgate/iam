import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import test from 'node:test';

const stateSource = await readFile(new URL('../src/ui/state.tsx', import.meta.url), 'utf8');
const barrelSource = await readFile(new URL('../src/ui/index.tsx', import.meta.url), 'utf8');

const states = [
  'loading',
  'empty',
  'informational',
  'warning',
  'error',
  'forbidden',
  'not-found',
  'conflict',
  'stale',
];

test('StatePanel exposes the shared domain-neutral state vocabulary', () => {
  assert.match(stateSource, /export type StatePanelState =/);
  for (const state of states) {
    assert.match(stateSource, new RegExp(`'${state}'`));
  }
  assert.match(stateSource, /state\?: StatePanelState;/);
  assert.match(stateSource, /'data-state': resolvedState/);
  assert.match(barrelSource, /type StatePanelProps, type StatePanelState/);

  assert.doesNotMatch(stateSource, /\b(?:401|403|404|409|412|503)\b/);
  assert.doesNotMatch(stateSource, /ApiError|apiRequest|AdministrativePermission|ETag|If-Match/);
});

test('StatePanel separates passive announcements from urgent states', () => {
  for (const passive of ['loading', 'empty', 'informational']) {
    assert.match(stateSource, new RegExp(`${passive}: \\{[^}]*role: 'status'`));
  }
  assert.match(stateSource, /'not-found': \{[^}]*role: 'status'/);

  for (const urgent of ['warning', 'error', 'forbidden', 'conflict', 'stale']) {
    assert.match(stateSource, new RegExp(`${urgent}: \\{[^}]*role: 'alert'`));
  }

  assert.match(stateSource, /loading: \{[^}]*busy: true[^}]*loading: true/);
  assert.match(stateSource, /'aria-busy': presentation\.busy \|\| undefined/);
  assert.match(stateSource, /'aria-atomic': presentation\.role === 'status' \? true : undefined/);
});

test('StatePanel preserves meaningful visual distinctions and legacy severity callers', () => {
  assert.match(stateSource, /forbidden: \{ severity: 'error'/);
  assert.match(stateSource, /'not-found': \{ severity: 'info'/);
  assert.match(stateSource, /conflict: \{ severity: 'warning'/);
  assert.match(stateSource, /stale: \{ severity: 'warning'/);
  assert.match(stateSource, /function stateFromLegacySeverity/);
  assert.match(stateSource, /const resolvedState = state \?\? stateFromLegacySeverity\(severity\)/);
  assert.match(stateSource, /severity=\{state \? presentation\.severity : severity \?\? presentation\.severity\}/);
});

test('StatePanel keeps title, detail and action in one consistent content layout', () => {
  assert.match(stateSource, /function StatePanelContent/);
  assert.match(stateSource, /<Typography component="strong" variant="subtitle2"/);
  assert.match(stateSource, /<Typography component="div" variant="body2"/);
  assert.match(stateSource, /action \? <Box sx=\{\{ flexShrink: 0, ml: 'auto' \}\}>\{action\}<\/Box> : null/);
  assert.match(stateSource, /borderColor: 'divider'/);
  assert.match(stateSource, /bgcolor: 'background\.paper'/);
  assert.doesNotMatch(stateSource, /#[0-9a-fA-F]{3,8}/);
});
