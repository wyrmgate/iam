import test from 'node:test';
import { assertSourceContains, assertSourceExcludes, readConsoleSource } from './helpers/source-contract.mjs';

const dialogs = await readConsoleSource('ui/dialogs.tsx');

test('dialogs expose accessible title and description association', () => {
  assertSourceContains(
    dialogs,
    /useId\(\)/,
    /aria-labelledby=\{title \? titleId/,
    /aria-describedby=\{description \? descriptionId/,
    /aria-labelledby=\{titleId\}/,
    /aria-describedby=\{descriptionId\}/,
  );
});

test('dialogs establish deliberate initial focus while leaving MUI focus restoration enabled', () => {
  assertSourceContains(
    dialogs,
    /initialFocusRef\.current\?\.focus\(\)/,
    /cancelButtonRef\.current\?\.focus\(\)/,
    /ref=\{cancelButtonRef\}/,
  );
  assertSourceExcludes(dialogs, /disableRestoreFocus/);
});

test('busy dialogs block accidental dismissal and duplicate confirmation', () => {
  assertSourceContains(
    dialogs,
    /if \(busy\) return/,
    /onClose=\{handleClose\}/,
    /disabled=\{busy\}/,
    /aria-busy=\{busy \|\| undefined\}/,
    /\{busy \? 'Working…' : confirmLabel\}/,
  );
});

test('escape and backdrop dismissal are explicit primitive policies', () => {
  assertSourceContains(
    dialogs,
    /dismissOnEscape = true/,
    /dismissOnBackdrop = true/,
    /reason === 'escapeKeyDown' && !dismissOnEscape/,
    /reason === 'backdropClick' && !dismissOnBackdrop/,
  );
});

test('confirmation actions remain cancel-first with explicit destructive emphasis', () => {
  assertSourceContains(
    dialogs,
    /<MuiButton ref=\{cancelButtonRef\}[\s\S]*\{cancelLabel\}<\/MuiButton>[\s\S]*color=\{destructive \? 'error' : 'primary'\}/,
    /variant="contained"/,
  );
});

test('dialog and panel sizing remain generic and narrow-screen safe', () => {
  assertSourceContains(
    dialogs,
    /fullWidth[\s\S]*maxWidth="sm"/,
    /width: \{ xs: 'min\(100vw, 420px\)', sm: 420, md: 480 \}/,
    /maxWidth: '100vw'/,
    /width: \{ xs: 'calc\(100% - 32px\)', sm: '100%' \}/,
  );
});

test('dialog foundation does not encode IAM, API, or authentication semantics', () => {
  assertSourceExcludes(
    dialogs,
    /ETag|If-Match|Idempotency|AccessAssignment|Governance|Credential|OAuth|OIDC|login|tenant/i,
  );
});
