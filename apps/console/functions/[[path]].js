import { onRequest as proxy } from './api/[[path]].js';

// Exact public protocol allowlist; static console routes never invoke this function.
const paths = new Set([
  '/.well-known/openid-configuration', '/oauth2/jwks', '/oauth2/authorize', '/oauth2/token', '/oauth2/federation/start', '/oauth2/federation/callback',
]);

export async function onRequest(context) {
  const path = new URL(context.request.url).pathname;
  if (!paths.has(path)) return new Response('Not found', { status: 404, headers: { 'Cache-Control': 'no-store' } });
  return proxy(context);
}
