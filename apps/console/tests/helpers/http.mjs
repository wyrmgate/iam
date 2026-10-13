export function jsonResponse(body, init = {}) {
  return new Response(JSON.stringify(body), {
    status: init.status ?? 200,
    headers: { 'content-type': 'application/json', ...(init.headers ?? {}) },
  });
}

export function installFetch(t, handler) {
  const hadFetch = Object.prototype.hasOwnProperty.call(globalThis, 'fetch');
  const originalFetch = globalThis.fetch;

  globalThis.fetch = handler;
  t.after(() => {
    if (hadFetch) {
      globalThis.fetch = originalFetch;
    } else {
      delete globalThis.fetch;
    }
  });
}

export function captureFetch(t, responder) {
  let capturedRequest = null;

  installFetch(t, async (url, init = {}) => {
    capturedRequest = { url: String(url), init };
    return responder(capturedRequest);
  });

  return () => capturedRequest;
}
