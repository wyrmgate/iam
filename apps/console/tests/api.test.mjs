import test from 'node:test';
import assert from 'node:assert/strict';
import { ApiError, createIdempotencyKey, getIdentity, listIdentities, mutationHeaders, transitionIdentity } from '../src/api.ts';

function response(body, init = {}) {
  return new Response(JSON.stringify(body), {
    status: init.status ?? 200,
    headers: { 'content-type': 'application/json', ...(init.headers ?? {}) },
  });
}

test('idempotency keys are causal, unique, and contract-sized', () => {
  const first = createIdempotencyKey();
  const second = createIdempotencyKey();
  assert.match(first, /^console-[0-9a-f-]{36}$/);
  assert.notEqual(first, second);
  assert.ok(first.length >= 8 && first.length <= 200);
});

test('mutation headers carry strong ETag and an idempotency key', () => {
  const headers = mutationHeaders({ etag: '"rev-7"', idempotent: true });
  assert.equal(headers.get('If-Match'), '"rev-7"');
  assert.match(headers.get('Idempotency-Key'), /^console-[0-9a-f-]{36}$/);
});

test('identity pagination passes the opaque cursor through without interpretation', async (t) => {
  const originalFetch = globalThis.fetch;
  t.after(() => { globalThis.fetch = originalFetch; });
  let requestedUrl;
  globalThis.fetch = async (url) => {
    requestedUrl = String(url);
    return response({ items: [], nextCursor: null });
  };

  const cursor = 'opaque.cursor/with+characters==';
  await listIdentities(cursor);
  const url = new URL(requestedUrl, 'https://console.example');
  assert.equal(url.pathname, '/api/v1/identities');
  assert.equal(url.searchParams.get('cursor'), cursor);
  assert.equal(url.searchParams.get('limit'), '50');
});

test('identity reads preserve the authoritative ETag for later mutation', async (t) => {
  const originalFetch = globalThis.fetch;
  t.after(() => { globalThis.fetch = originalFetch; });
  globalThis.fetch = async () => response({
    id: '00000000-0000-0000-0000-000000000001',
    type: 'PERSON',
    profile: { kind: 'PERSON' },
    lifecycleState: 'ACTIVE',
    displayName: 'Ada Example',
    revision: 4,
    createdAt: '2026-10-05T00:00:00Z',
    updatedAt: '2026-10-05T00:00:00Z',
  }, { headers: { ETag: '"rev-4"' } });

  const result = await getIdentity('00000000-0000-0000-0000-000000000001');
  assert.equal(result.etag, '"rev-4"');
});

test('lifecycle commands send If-Match and Idempotency-Key to the semantic endpoint', async (t) => {
  const originalFetch = globalThis.fetch;
  t.after(() => { globalThis.fetch = originalFetch; });
  let request;
  globalThis.fetch = async (url, init) => {
    request = { url: String(url), init };
    return response({
      id: '00000000-0000-0000-0000-000000000001',
      type: 'PERSON',
      profile: { kind: 'PERSON' },
      lifecycleState: 'SUSPENDED',
      displayName: 'Ada Example',
      revision: 5,
      createdAt: '2026-10-05T00:00:00Z',
      updatedAt: '2026-10-05T00:01:00Z',
    }, { headers: { ETag: '"rev-5"' } });
  };

  await transitionIdentity('00000000-0000-0000-0000-000000000001', 'suspend', '"rev-4"');
  assert.equal(request.url, '/api/v1/identities/00000000-0000-0000-0000-000000000001:suspend');
  assert.equal(request.init.method, 'POST');
  assert.equal(request.init.headers.get('If-Match'), '"rev-4"');
  assert.match(request.init.headers.get('Idempotency-Key'), /^console-[0-9a-f-]{36}$/);
});

test('403 remains a server authorization decision with correlation context', async (t) => {
  const originalFetch = globalThis.fetch;
  t.after(() => { globalThis.fetch = originalFetch; });
  globalThis.fetch = async () => response({ message: 'Administrative authorization denied' }, {
    status: 403,
    headers: { 'X-Correlation-Id': '00000000-0000-0000-0000-000000000099' },
  });

  await assert.rejects(
    () => listIdentities(),
    (error) => error instanceof ApiError
      && error.status === 403
      && error.correlationId === '00000000-0000-0000-0000-000000000099',
  );
});
