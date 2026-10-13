import test from 'node:test';
import { assertSourceContains, assertSourceExcludes, readConsoleSources } from './helpers/source-contract.mjs';

const { shell, page } = await readConsoleSources({
  shell: 'shell-foundation.css',
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
    shell,
    /\.product-shell\s*\{[\s\S]*min-width:\s*0;[\s\S]*max-width:\s*100%/,
    /\.shell-stage\s*\{[\s\S]*min-width:\s*0;[\s\S]*width:\s*100%;[\s\S]*max-width:\s*100%/,
    /\.shell-workspace\s*\{[\s\S]*width:\s*100%;[\s\S]*max-width:\s*100%;[\s\S]*box-sizing:\s*border-box/,
    /\.product-shell \.app-shell > \.content\s*\{[\s\S]*min-width:\s*0;[\s\S]*max-width:\s*100%;[\s\S]*box-sizing:\s*border-box/,
  );
});

test('narrow and zoom-equivalent widths retain reachable shell context without semantic substitution', () => {
  assertSourceContains(
    shell,
    /@media \(max-width: 760px\)/,
    /\.navigation-dock,[\s\S]*max-width:\s*calc\(100vw - 44px\)/,
    /\.shell-location\s*\{[\s\S]*overflow:\s*hidden/,
    /\.shell-scope\s*\{[\s\S]*text-overflow:\s*ellipsis/,
    /\.context-rail\s*\{[\s\S]*max-width:\s*calc\(100vw - 24px\)/,
    /@media \(max-width: 480px\)/,
    /@media \(max-width: 360px\)/,
  );
  assertSourceExcludes(shell, /display:\s*none;[^}]*\.context-trigger/);
});

test('reduced-motion shell behavior remains explicit', () => {
  assertSourceContains(
    shell,
    /@media \(prefers-reduced-motion: reduce\)/,
    /\.product-shell,[\s\S]*\.navigation-dock[\s\S]*transition:\s*none/,
  );
});
