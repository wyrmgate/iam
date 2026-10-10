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

export type AdministrativePermissionResource = {
  resourceType: string;
  action: string;
  key: string;
};

export type AdministrativeScopeResource = {
  type: 'GLOBAL' | 'SPECIFIC_RESOURCE' | 'CANONICAL_ATTRIBUTE_CLASSIFICATION';
  resourceType?: string | null;
  resourceId?: string | null;
  scopeKey?: string | null;
};

export type EffectiveAdministrativeAuthority = {
  permission: AdministrativePermissionResource;
  scope: AdministrativeScopeResource;
  source: 'DIRECT_GRANT' | 'DELEGATION' | 'ELEVATION' | 'BREAK_GLASS';
  sourceId: string;
  validFrom?: string | null;
  validUntil?: string | null;
};

export type CurrentAdministrativeAuthority = {
  tenantId: string;
  actorIdentityId: string;
  administrativelyEligible: boolean;
  evaluatedAt: string;
  authorities: EffectiveAdministrativeAuthority[];
};

export type AuthSession = { authenticated: boolean };
export type LoginInput = {
  clientId: string;
  applicationTargetId: string;
  principalKey: string;
  password: string;
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

export type ApiResult<T> = {
  data: T;
  etag: string | null;
  correlationId: string | null;
};

export type RequestOptions = {
  method?: 'GET' | 'POST' | 'PATCH' | 'PUT' | 'DELETE';
  body?: unknown;
  etag?: string;
  idempotent?: boolean;
  signal?: AbortSignal;
  accept?: string;
};

const API_ROOT = '/api';
export const V1_ROOT = `${API_ROOT}/v1`;
const CSRF_COOKIE = '__Host-wyrmgate_csrf';
const CSRF_HEADER = 'X-Wyrmgate-CSRF';

export function createIdempotencyKey(): string {
  return `console-${crypto.randomUUID()}`;
}

function cookieValue(name: string): string | null {
  if (typeof document === 'undefined') return null;
  for (const raw of document.cookie.split(';')) {
    const [key, ...rest] = raw.trim().split('=');
    if (key === name) return rest.join('=');
  }
  return null;
}

export function mutationHeaders(options: Pick<RequestOptions, 'etag' | 'idempotent' | 'accept'>): Headers {
  const headers = new Headers({ Accept: options.accept ?? 'application/json' });
  if (options.etag) headers.set('If-Match', options.etag);
  if (options.idempotent) headers.set('Idempotency-Key', createIdempotencyKey());
  const csrf = cookieValue(CSRF_COOKIE);
  if (csrf) headers.set(CSRF_HEADER, csrf);
  return headers;
}

function safeErrorMessage(payload: unknown, status: number): string {
  if (!payload || typeof payload !== 'object') return `Request failed with status ${status}`;
  const source = payload as Record<string, unknown>;
  const candidate = source.message ?? source.detail ?? source.error;
  return typeof candidate === 'string' && candidate.trim()
    ? candidate
    : `Request failed with status ${status}`;
}

export async function apiRequest<T>(path: string, options: RequestOptions = {}): Promise<ApiResult<T>> {
  if (!path.startsWith('/api/')) throw new Error('Console API calls must use same-origin /api/* paths');
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
      message = safeErrorMessage(await response.json(), response.status);
    } catch {
      // Never surface arbitrary HTML, stack traces, secret material, or proxy bodies.
    }
    throw new ApiError(response.status, message, correlationId);
  }

  const contentType = response.headers.get('content-type') ?? '';
  const data = response.status === 204 || !contentType.includes('json')
    ? (undefined as T)
    : (await response.json()) as T;

  return {
    data,
    etag: response.headers.get('ETag'),
    correlationId,
  };
}

export async function apiDownload(path: string, options: RequestOptions = {}): Promise<Blob> {
  if (!path.startsWith('/api/')) throw new Error('Console downloads must use same-origin /api/* paths');
  const headers = mutationHeaders({ ...options, accept: options.accept ?? 'application/x-ndjson' });
  const response = await fetch(path, {
    method: options.method ?? 'POST',
    headers,
    signal: options.signal,
    credentials: 'same-origin',
  });
  if (!response.ok) {
    throw new ApiError(response.status, `Download failed with status ${response.status}`, response.headers.get('X-Correlation-Id'));
  }
  return response.blob();
}

export function withCursor(path: string, cursor?: string | null, extra?: Record<string, string | undefined>): string {
  const [base, existing = ''] = path.split('?', 2);
  const query = new URLSearchParams(existing);
  if (!query.has('limit')) query.set('limit', '50');
  if (cursor) query.set('cursor', cursor);
  for (const [key, value] of Object.entries(extra ?? {})) {
    if (value) query.set(key, value);
  }
  return `${base}?${query.toString()}`;
}

export async function getAuthSession(signal?: AbortSignal): Promise<AuthSession> {
  return (await apiRequest<AuthSession>(`${API_ROOT}/auth/session`, { signal })).data;
}

export async function signIn(input: LoginInput): Promise<AuthSession> {
  return (await apiRequest<AuthSession>(`${API_ROOT}/auth/login`, { method: 'POST', body: input })).data;
}

export async function signOut(): Promise<void> {
  await apiRequest<void>(`${API_ROOT}/auth/logout`, { method: 'POST' });
}

export async function getSystemInfo(signal?: AbortSignal): Promise<SystemInfo> {
  return (await apiRequest<SystemInfo>(`${API_ROOT}/system/info`, { signal })).data;
}

export async function getCurrentAdministrativeAuthority(signal?: AbortSignal): Promise<CurrentAdministrativeAuthority> {
  return (await apiRequest<CurrentAdministrativeAuthority>(`${V1_ROOT}/current-administrative-authority`, { signal })).data;
}

export async function listIdentities(cursor?: string, signal?: AbortSignal): Promise<IdentityPage> {
  return (await apiRequest<IdentityPage>(withCursor(`${V1_ROOT}/identities`, cursor), { signal })).data;
}

export async function getIdentity(identityId: string, signal?: AbortSignal): Promise<ApiResult<IdentityResource>> {
  return apiRequest<IdentityResource>(`${V1_ROOT}/identities/${encodeURIComponent(identityId)}`, { signal });
}

export type IdentityLifecycleAction = 'activate' | 'suspend' | 'deactivate' | 'decommission';

export async function transitionIdentity(
  identityId: string,
  action: IdentityLifecycleAction,
  etag: string,
): Promise<ApiResult<IdentityResource>> {
  return apiRequest<IdentityResource>(`${V1_ROOT}/identities/${encodeURIComponent(identityId)}:${action}`, {
    method: 'POST',
    etag,
    idempotent: true,
  });
}
