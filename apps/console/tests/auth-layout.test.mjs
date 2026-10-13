import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import test from 'node:test';

const viewportCss = await readFile(new URL('../src/auth-login-viewport.css', import.meta.url), 'utf8');
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

test('short desktop viewports compact instead of forcing the login taller', () => {
  assert.match(viewportCss, /@media \(min-width: 981px\) and \(max-height: 800px\)/);
  assert.match(viewportCss, /\.auth-form-panel-enterprise[\s\S]*padding:/);
  assert.match(viewportCss, /\.auth-network[\s\S]*36dvh/);
});

test('stacked tablet and mobile layouts do not inherit desktop dynamic viewport minimums', () => {
  assert.match(viewportCss, /@media \(max-width: 980px\)[\s\S]*\.auth-brand-panel-enterprise[\s\S]*min-height:\s*43rem/);
  assert.match(viewportCss, /@media \(max-width: 680px\)[\s\S]*\.auth-form-panel-enterprise[\s\S]*min-height:\s*auto/);
});
