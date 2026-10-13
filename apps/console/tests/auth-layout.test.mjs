import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import test from 'node:test';

const viewportCss = await readFile(new URL('../src/auth-login-viewport.css', import.meta.url), 'utf8');
const graphic = await readFile(new URL('../src/AuthControlPlaneGraphic.tsx', import.meta.url), 'utf8');
const signInGate = await readFile(new URL('../src/SignInGate.tsx', import.meta.url), 'utf8');
const main = await readFile(new URL('../src/main.tsx', import.meta.url), 'utf8');

test('enterprise login owns dynamic viewport containment', () => {
  assert.match(main, /auth-login-viewport\.css/);
  assert.match(viewportCss, /min-height:\s*100dvh/);
  assert.match(viewportCss, /grid-template-columns:\s*minmax\(0,/);
  assert.match(viewportCss, /overflow-x:\s*clip/);
  assert.match(viewportCss, /\.auth-shell-enterprise\s*>\s*\*/);
  assert.match(viewportCss, /min-width:\s*0/);
  assert.match(viewportCss, /\.auth-network[\s\S]*overflow:\s*hidden/);
});

test('desktop login gives the brand surface more weight than the sign-in surface', () => {
  assert.match(viewportCss, /grid-template-columns:\s*minmax\(0,\s*1\.45fr\)\s*minmax\(27rem,\s*\.85fr\)/);
  assert.match(viewportCss, /\.auth-form-wrap-enterprise[\s\S]*max-width:\s*24rem/);
  assert.match(viewportCss, /@media \(max-width: 1250px\) and \(min-width: 981px\)[\s\S]*1\.18fr[\s\S]*\.82fr/);
  assert.match(viewportCss, /\.auth-core-face[\s\S]*transform:\s*none/);
});

test('required sign-in fields are compact without exposing raw mandatory asterisks', () => {
  assert.match(signInGate, /label="Username"[\s\S]*required/);
  assert.match(signInGate, /label="Password"[\s\S]*required/);
  assert.match(viewportCss, /\.auth-input \.MuiFormLabel-asterisk[\s\S]*display:\s*none/);
  assert.match(viewportCss, /\.auth-input \.MuiOutlinedInput-root[\s\S]*min-height:\s*3\.05rem/);
  assert.match(viewportCss, /\.auth-input \.MuiInputLabel-root[\s\S]*font-size:\s*\.8rem/);
  assert.match(viewportCss, /\.auth-input-icon svg[\s\S]*width:\s*1\.05rem/);
});

test('control-plane core keeps the Wyrmgate mark and label readable', () => {
  assert.match(graphic, /className="auth-core-logo">W/);
  assert.match(graphic, /<strong>Wyrmgate IAM<\/strong>/);
  assert.match(graphic, /<small>Identity control plane<\/small>/);
  assert.match(viewportCss, /\.auth-core-face[\s\S]*display:\s*flex/);
  assert.match(viewportCss, /\.auth-core-logo[\s\S]*width:\s*2\.55rem/);
  assert.match(viewportCss, /\.auth-core-copy[\s\S]*text-align:\s*left/);
});

test('short desktop viewports compact instead of forcing the login taller', () => {
  assert.match(viewportCss, /@media \(min-width: 981px\) and \(max-height: 800px\)/);
  assert.match(viewportCss, /\.auth-form-panel-enterprise[\s\S]*padding:/);
  assert.match(viewportCss, /\.auth-network[\s\S]*34dvh/);
});

test('stacked tablet and mobile layouts do not inherit desktop dynamic viewport minimums', () => {
  assert.match(viewportCss, /@media \(max-width: 980px\)[\s\S]*\.auth-brand-panel-enterprise[\s\S]*min-height:\s*40rem/);
  assert.match(viewportCss, /@media \(max-width: 680px\)[\s\S]*\.auth-form-panel-enterprise[\s\S]*min-height:\s*auto/);
});

test('sign-in surface exposes only supported authentication controls', () => {
  assert.doesNotMatch(signInGate, /auth-language-control|auth-form-topbar/);
  assert.doesNotMatch(signInGate, /auth-security-card/);
  assert.match(signInGate, /Server resolved/);
  assert.match(signInGate, /Secure server-managed session/);
  assert.match(signInGate, /Tenant routing is resolved by Wyrmgate/);
  assert.doesNotMatch(signInGate, /Forgot password|Remember me|Sign in with/);
});
