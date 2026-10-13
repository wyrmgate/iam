import assert from 'node:assert/strict';
import test from 'node:test';
import { installBrowserHistory } from './helpers/browser.mjs';
import { captureFetch, jsonResponse } from './helpers/http.mjs';
import { assertSourceContains, assertSourceExcludes, readConsoleSource } from './helpers/source-contract.mjs';

test('HTTP capture records request details for semantic API assertions', async (t) => {
  const capturedRequest = captureFetch(t, async () => jsonResponse({ ok: true }, { status: 202 }));

  const response = await fetch('/api/v1/example', {
    method: 'POST',
    headers: new Headers({ 'If-Match': '"rev-3"' }),
  });

  assert.equal(response.status, 202);
  assert.equal(capturedRequest().url, '/api/v1/example');
  assert.equal(capturedRequest().init.method, 'POST');
  assert.equal(capturedRequest().init.headers.get('If-Match'), '"rev-3"');
});

test('browser history harness provides deterministic location and popstate behavior', (t) => {
  const browser = installBrowserHistory(t, 'https://console.example/identities?limit=20');
  let popstateCount = 0;
  window.addEventListener('popstate', () => { popstateCount += 1; });

  history.pushState({ source: 'test' }, '', '/reviews?status=OPEN');
  assert.equal(location.pathname, '/reviews');
  assert.equal(location.search, '?status=OPEN');
  assert.deepEqual(history.state, { source: 'test' });

  browser.dispatchPopState();
  assert.equal(popstateCount, 1);
});

test('source contract helpers load Console source and support semantic assertions', async () => {
  const api = await readConsoleSource('api.ts');

  assertSourceContains(api, /credentials:\s*'same-origin'/, /Idempotency-Key/);
  assertSourceExcludes(api, /localStorage|sessionStorage/);
});
