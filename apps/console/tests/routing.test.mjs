import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import test from 'node:test';

const app = await readFile(new URL('../src/App.tsx', import.meta.url), 'utf8');
const location = await readFile(new URL('../src/consoleLocation.ts', import.meta.url), 'utf8');
const shell = await readFile(new URL('../src/ShellChrome.tsx', import.meta.url), 'utf8');
const navigation = await readFile(new URL('../src/consoleNavigation.ts', import.meta.url), 'utf8');
const redirects = await readFile(new URL('../public/_redirects', import.meta.url), 'utf8');

const primaryPaths = [
  '/identities',
  '/principals',
  '/catalog',
  '/access',
  '/requests',
  '/reviews',
  '/policies',
  '/credentials',
  '/integrations',
  '/administration',
  '/audit',
];

test('application routing is derived from the shared navigation registry', () => {
  assert.match(app, /matchConsoleRoute\(pathname\)/);
  assert.match(app, /navigateConsole\(path\)/);
  assert.doesNotMatch(app, /visibleConsoleRoutes/);
  assert.doesNotMatch(app, /const nav:\s*NavItem\[\]/);
  assert.doesNotMatch(app, /pathname\.match\(\/\^\\\/identities/);
  assert.match(shell, /visibleConsoleRoutes\(authority\)/);
  assert.match(navigation, /export function matchConsoleRoute/);
  assert.match(navigation, /return null;/);
});

test('legacy application frame is removed and shell chrome remains the single frame owner', () => {
  assert.doesNotMatch(app, /className="app-shell"/);
  assert.doesNotMatch(app, /className="sidebar"/);
  assert.doesNotMatch(app, /className="content"/);
  assert.doesNotMatch(app, /aria-label="Primary navigation"/);
  assert.match(shell, /className=(?:"product-shell"|\{`product-shell)/);
  assert.match(shell, /<aside[\s\S]*aria-label="Product navigation"/);
  assert.match(shell, /<main className="shell-workspace">\{children\}<\/main>/);
});

test('all current primary workspaces have stable directly served URLs', () => {
  for (const path of primaryPaths) {
    assert.match(navigation, new RegExp(`path: '${path.replaceAll('/', '\\/')}'`));
    assert.match(redirects, new RegExp(`^${path.replaceAll('/', '\\/')} \\/index\\.html 200$`, 'm'));
  }
  assert.match(redirects, /^\/identities\/\* \/index\.html 200$/m);
  assert.doesNotMatch(redirects, /^\/api\//m);
  assert.doesNotMatch(redirects, /^\/\* /m);
});

test('identity detail is the supported addressable subview and unknown routes stay unknown', () => {
  assert.match(navigation, /\^\\\/identities\\\/\(\[\^\/\]\+\)\$/);
  assert.match(navigation, /params: \{ identityId \}/);
  assert.match(navigation, /identityDetailPath/);
  assert.match(app, /routeMatch\?\.params\.identityId/);
  assert.match(app, /routeMatch\?\.route\.id \?\? 'not-found'/);
  assert.match(app, /title="Page not found"/);
});

test('in-app navigation and browser history use one shared pathname subscription helper', () => {
  assert.match(navigation, /window\.history\.pushState/);
  assert.match(navigation, /CONSOLE_NAVIGATION_EVENT/);
  assert.match(location, /addEventListener\('popstate'/);
  assert.match(location, /addEventListener\(CONSOLE_NAVIGATION_EVENT/);
  assert.match(location, /removeEventListener\('popstate'/);
  assert.match(app, /useConsolePathname\(\)/);
  assert.match(shell, /useConsolePathname\(\)/);
  assert.doesNotMatch(app, /addEventListener\('popstate'/);
  assert.doesNotMatch(shell, /addEventListener\('popstate'/);
  assert.match(shell, /navigateConsole\(path\)/);
});

test('permission-aware navigation remains only a visibility optimization', () => {
  assert.match(navigation, /visibleConsoleRoutes/);
  assert.match(navigation, /visibleWithoutAdministrativeEligibility/);
  assert.match(shell, /Server authorization remains final/);
  assert.doesNotMatch(navigation, /localStorage|sessionStorage/);
});
