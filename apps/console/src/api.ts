export type IdentityType = 'PERSON' | 'SERVICE' | 'WORKLOAD';
export type IdentityLifecycleState = 'PENDING' | 'ACTIVE' | 'SUSPENDED' | 'INACTIVE' | 'DECOMMISSIONED';

export type IdentityResource = {
  id: string;
  type: IdentityType;
  profile: { kind: IdentityType };
  lifecycleState: IdentityLifecycleState;
  displayName: string;
  revision: number;
  createdAt: string;
  updatedAt: string;
};

export type IdentityPage = {
  items: IdentityResource[];
  nextCursor?: string | null;
};

export type SystemInfo = {
  name: string;
  status: string;
};

export class ApiError extends Error {
  readonly status: number;
  readonly correlationId: string | null;

  constructor(status: number, message: string, correlationId: string | null) {
    super(message);
    this.name = 'ApiError';
    this.status = status;
    this.correlationId = correlationId;
  }
}

type ApiResult<T> = {
  data: T;
  etag: string | null;
  correlationId: string | null;
};

type RequestOptions = {
  method?: 'GET' | 'POST' | 'PATCH';
  body?: unknown;
  etag?: string;
  idempotent?: boolean;
  signal?: AbortSignal;
};

const API_ROOT = '/api';
const V1_ROOT = `${API_ROOT}/v1`;

export function createIdempotencyKey(): string {
  return `console-${crypto.randomUUID()}`;
}

export function mutationHeaders(options: Pick<RequestOptions, 'etag' | 'idempotent'>): Headers {
  const headers = new Headers({ Accept: 'application/json' });
  if (options.etag) headers.set('If-Match', options.etag);
  if (options.idempotent) headers.set('Idempotency-Key', createIdempotencyKey());
  return headers;
}

async function request<T>(path: string, options: RequestOptions = {}): Promise<ApiResult<T>> {
  if (!path.startsWith('/')) throw new Error('API path must be absolute');
  const headers = mutationHeaders(options);
  if (options.body !== undefined) headers.set('Content-Type', 'application/json');

  const response = await fetch(path, {
    method: options.method ?? 'GET',
    headers,
    body: options.body === undefined ? undefined : JSON.stringify(options.body),
    signal: options.signal,
    credentials: 'same-origin',
  });
  const correlationId = response.headers.get('X-Correlation-Id');

  if (!response.ok) {
    let message = `Request failed with status ${response.status}`;
    try {
      const payload = (await response.json()) as { message?: unknown; error?: unknown; detail?: unknown };
      const candidate = payload.message ?? payload.detail ?? payload.error;
      if (typeof candidate === 'string' && candidate.trim()) message = candidate;
    } catch {
      // Do not surface arbitrary backend bodies, secrets, or HTML error pages.
    }
    throw new ApiError(response.status, message, correlationId);
  }

  return {
    data: (await response.json()) as T,
    etag: response.headers.get('ETag'),
    correlationId,
  };
}

export async function getSystemInfo(signal?: AbortSignal): Promise<SystemInfo> {
  return (await request<SystemInfo>(`${API_ROOT}/system/info`, { signal })).data;
}

export async function listIdentities(cursor?: string, signal?: AbortSignal): Promise<IdentityPage> {
  const query = new URLSearchParams({ limit: '50' });
  if (cursor) query.set('cursor', cursor);
  return (await request<IdentityPage>(`${V1_ROOT}/identities?${query.toString()}`, { signal })).data;
}

export async function getIdentity(identityId: string, signal?: AbortSignal): Promise<ApiResult<IdentityResource>> {
  return request<IdentityResource>(`${V1_ROOT}/identities/${encodeURIComponent(identityId)}`, { signal });
}

export type IdentityLifecycleAction = 'activate' | 'suspend' | 'deactivate' | 'decommission';

export async function transitionIdentity(
  identityId: string,
  action: IdentityLifecycleAction,
  etag: string,
): Promise<ApiResult<IdentityResource>> {
  return request<IdentityResource>(`${V1_ROOT}/identities/${encodeURIComponent(identityId)}:${action}`, {
    method: 'POST',
    etag,
    idempotent: true,
  });
}
