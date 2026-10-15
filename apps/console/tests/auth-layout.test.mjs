import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import test from 'node:test';

const authCss = await readFile(new URL('../src/auth-login.css', import.meta.url), 'utf8');
const globalCss = await readFile(new URL('../src/styles.css', import.meta.url), 'utf8');
const signInGate = await readFile(new URL('../src/SignInGate.tsx', import.meta.url), 'utf8');
const main = await readFile(new URL('../src/main.tsx', import.meta.url), 'utf8');

test('first-party sign-in uses the canonical enterprise token vocabulary', () => {
  assert.match(authCss, /var\(--wg-canvas\)/);
  assert.match(authCss, /var\(--wg-surface\)/);
  assert.match(authCss, /var\(--wg-border\)/);
  assert.match(authCss, /var\(--wg-text\)/);
  assert.match(authCss, /var\(--wg-accent\)/);
  assert.match(authCss, /var\(--wg-radius-dialog\)/);
  assert.doesNotMatch(authCss, /--auth-/);
  assert.doesNotMatch(authCss, /#[0-9a-f]{3,8}\b/i);
  assert.doesNotMatch(authCss, /linear-gradient|radial-gradient|backdrop-filter/i);
});

test('retired branded authentication theme stays out of global styles', () => {
  assert.doesNotMatch(globalCss, /\.auth-brand-panel|\.auth-brand-copy|\.auth-trust-row|\.auth-wordmark|\.auth-spinner/);
  assert.doesNotMatch(globalCss, /@keyframes\s+auth-spin/);
});

test('sign-in composition is restrained rather than a marketing hero', () => {
  assert.match(signInGate, /className="auth-login-surface"/);
  assert.match(signInGate, /Enterprise identity control plane/);
  assert.match(signInGate, /Secure administration/);
  assert.match(authCss, /font-size:\s*var\(--wg-page-title\)/);
  assert.doesNotMatch(signInGate, /AuthControlPlaneGraphic|People\. Access\. Governance|Connected\./);
  assert.doesNotMatch(main, /auth-login-viewport\.css/);
});

test('sign-in preserves server-resolved tenant and session context', () => {
  assert.match(signInGate, /Tenant/);
  assert.match(signInGate, /Resolved by the server from this Console route/);
  assert.match(signInGate, /Secure, server-managed browser session/);
  assert.match(signInGate, /Tenant routing and session authority remain server-resolved/);
  assert.doesNotMatch(signInGate, /tenantId|tenantKey|Tenant selector|Choose tenant/i);
});

test('required sign-in controls remain explicit and accessible', () => {
  assert.match(signInGate, /label="Username"[\s\S]*autoComplete="username"[\s\S]*required/);
  assert.match(signInGate, /label="Password"[\s\S]*autoComplete="current-password"[\s\S]*required/);
  assert.match(signInGate, /aria-label=\{showPassword \? 'Hide password' : 'Show password'\}/);
  assert.match(authCss, /\.auth-password-toggle:focus-visible[\s\S]*var\(--wg-focus\)/);
  assert.match(authCss, /\.auth-input \.MuiOutlinedInput-root[\s\S]*min-height:\s*44px/);
});

test('mobile sign-in preserves the enterprise interaction target and viewport containment', () => {
  assert.match(authCss, /min-height:\s*100dvh/);
  assert.match(authCss, /overflow-x:\s*clip/);
  assert.match(authCss, /@media \(max-width: 720px\)/);
  assert.match(authCss, /\.auth-password-toggle[\s\S]*width:\s*var\(--wg-mobile-target\)[\s\S]*height:\s*var\(--wg-mobile-target\)/);
  assert.match(authCss, /grid-template-columns:\s*minmax\(0,\s*1fr\)/);
});

test('sign-in honors reduced motion and exposes only supported authentication controls', () => {
  assert.match(authCss, /@media \(prefers-reduced-motion: reduce\)/);
  assert.doesNotMatch(signInGate, /Forgot password|Remember me|Sign in with|auth-language-control|auth-form-topbar/);
  assert.doesNotMatch(signInGate, /localStorage|sessionStorage/);
});
