export async function onRequest(context) {
  const configuredOrigin = context.env.IAM_BACKEND_ORIGIN;

  if (!configuredOrigin) {
    return new Response(JSON.stringify({ error: 'backend_unavailable' }), {
      status: 503,
      headers: { 'content-type': 'application/json; charset=utf-8' },
    });
  }

  let backendOrigin;
  try {
    backendOrigin = new URL(configuredOrigin);
  } catch {
    return new Response(JSON.stringify({ error: 'backend_unavailable' }), {
      status: 503,
      headers: { 'content-type': 'application/json; charset=utf-8' },
    });
  }

  if (!['http:', 'https:'].includes(backendOrigin.protocol) || backendOrigin.username || backendOrigin.password || backendOrigin.search || backendOrigin.hash || (backendOrigin.pathname && backendOrigin.pathname !== '/')) {
    return new Response(JSON.stringify({ error: 'backend_unavailable' }), {
      status: 503,
      headers: { 'content-type': 'application/json; charset=utf-8' },
    });
  }

  const requestUrl = new URL(context.request.url);
  const targetUrl = new URL(`${requestUrl.pathname}${requestUrl.search}`, backendOrigin);
  const headers = new Headers(context.request.headers);
  headers.delete('host');

  const init = {
    method: context.request.method,
    headers,
    redirect: 'manual',
  };

  if (context.request.method !== 'GET' && context.request.method !== 'HEAD') {
    init.body = context.request.body;
  }

  try {
    return await fetch(targetUrl, init);
  } catch {
    return new Response(JSON.stringify({ error: 'backend_unavailable' }), {
      status: 502,
      headers: { 'content-type': 'application/json; charset=utf-8' },
    });
  }
}
