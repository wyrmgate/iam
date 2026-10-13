import test from 'node:test';
import { assertSourceContains, assertSourceExcludes, readConsoleSources } from './helpers/source-contract.mjs';

const { shell, shellBase, page } = await readConsoleSources({
  shell: 'shell-foundation.css',
  shellBase: 'shell.css',
  page: 'ui/page.tsx',
});

test('page primitives remain shrink-safe and constrain shared action groups', () => {
  assertSourceContains(
    page,
    /maxWidth:\s*'100%'/,
    /minWidth:\s*0/,
    /boxSizing:\s*'border-box'/,
    /overflowWrap:\s*'anywhere'/,
    /width:\s*\{ xs: '100%', sm: 'auto' \}/,
    /whiteSpace:\s*'normal'/,
    /export function PageHeader/,
    /export function PageToolbar/,
    /export function Surface/,
  );
});

test('responsive page foundation keeps table form and dialog ownership outside this module', () => {
  assertSourceExcludes(
    page,
    /DataTable|TableContainer|TextField|SelectField|ConfirmDialog|Modal|SidePanel/,
  );
});

test('shell grid and workspace cannot force avoidable horizontal overflow', () => {
  assertSourceContains(
    shellBase,
    /\.product-shell\s*\{[\s\S]*grid-template-columns:[\s\S]*minmax\(0, 1fr\)/,
    /\.shell-stage\s*\{[\s\S]*min-width:\s*0;[\s\S]*width:\s*100%/,
    /\.shell-workspace\s*\{[\s\S]*min-width:\s*0;[\s\S]*width:\s*100%;[\s\S]*box-sizing:\s*border-box/,
    /\.product-shell \.app-shell > \.content\s*\{[\s\S]*min-width:\s*0;[\s\S]*max-width:\s*100%/,
  );
});

test('narrow and zoom-equivalent widths retain reachable navigation and current location', () => {
  assertSourceContains(
    shell,
    /@media \(max-width: 760px\)/,
    /\.navigation-dock\s*\{[\s\S]*max-width:\s*calc\(100vw - 44px\)/,
    /\.navigation-dock\.mobile-open\s*\{[\s\S]*transform:\s*translateX\(0\)/,
    /\.mobile-menu-trigger\s*\{[\s\S]*display:\s*grid/,
    /\.shell-location-copy strong\s*\{[\s\S]*max-width:\s*42vw/,
    /@media \(max-width: 420px\)/,
  );
  assertSourceExcludes(shell, /nth-of-type\(n\+6\)[\s\S]*display:\s*none/);
});

test('reduced-motion shell behavior remains explicit', () => {
  assertSourceContains(
    shell,
    /@media \(prefers-reduced-motion: reduce\)/,
    /\.navigation-dock\s*\{[\s\S]*transition:\s*none/,
  );
});
