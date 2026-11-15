import test from 'node:test';
import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import { onRequest } from '../functions/[[path]].js';

const routes = JSON.parse(await readFile(new URL('../public/_routes.json', import.meta.url), 'utf8'));
const allowed = ['/.well-known/openid-configuration', '/oauth2/jwks', '/oauth2/authorize', '/oauth2/token'];

test('public protocol edge forwards exact paths and never follows relying-party redirects', async (t) => {
  assert.deepEqual(routes.include, ['/api/*', ...allowed]);
  let calls = [];
  t.mock.method(globalThis, 'fetch', async (url, init) => {
    calls.push({ url: url.toString(), init });
    return new Response(null, { status: 302, headers: { Location: 'https://client.example/callback', 'Cache-Control': 'no-store' } });
  });
  for (const path of allowed) {
    const response = await onRequest({ request: new Request('https://console.example' + path + '?state=opaque'), env: { IAM_BACKEND_ORIGIN: 'https://server.example' } });
    assert.equal(response.status, 302);
    assert.equal(response.headers.get('Location'), 'https://client.example/callback');
    assert.equal(calls.at(-1).url, 'https://server.example' + path + '?state=opaque');
    assert.equal(calls.at(-1).init.redirect, 'manual');
  }
  const response = await onRequest({ request: new Request('https://console.example/oauth2/unknown'), env: { IAM_BACKEND_ORIGIN: 'https://server.example' } });
  assert.equal(response.status, 404);
  assert.equal(calls.length, allowed.length);
});
