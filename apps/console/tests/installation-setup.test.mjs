import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import test from 'node:test';

const gate = await readFile(new URL('../src/SignInGate.tsx', import.meta.url), 'utf8');
const wizard = await readFile(new URL('../src/SetupWizard.tsx', import.meta.url), 'utf8');
const api = await readFile(new URL('../src/installationApi.ts', import.meta.url), 'utf8');

test('fresh-install status is resolved before ordinary authentication', () => {
  assert.match(gate, /getInstallationStatus\(controller\.signal\)[\s\S]*installation\.status !== 'SEALED'/);
  assert.match(gate, /cause instanceof ApiError && cause\.status === 404/);
});

test('wizard never accepts browser-owned Console routing', () => {
  assert.match(wizard, /label="Console redirect URI"[\s\S]*readOnly/);
  assert.match(wizard, /trusted server configuration/);
  assert.doesNotMatch(wizard, /setConsoleRedirectUri/);
  assert.doesNotMatch(wizard, /public base URL|baseUrl|baseURL/);
});

test('setup mutations use a setup-specific csrf boundary', () => {
  assert.match(api, /__Host-wyrmgate_setup_csrf/);
  assert.match(api, /X-Wyrmgate-Setup-CSRF/);
  assert.match(api, /credentials:\s*'same-origin'/);
  assert.match(api, /\/api\/setup\/session/);
  assert.match(api, /\/api\/setup\/initialize/);
});

test('resume locks durable non-secret plan inputs and asks for the password again', () => {
  assert.match(wizard, /const frozenPlan = status\.status === 'IN_PROGRESS'/);
  assert.match(wizard, /disabled=\{frozenPlan\}/);
  assert.match(wizard, /re-enter the original password/i);
  assert.match(wizard, /setPassword\(''\)/);
  assert.match(wizard, /setConfirmPassword\(''\)/);
});
