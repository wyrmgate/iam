import { ApiError } from './api';

export type InstallationPhase =
  | 'PLANNED'
  | 'TENANT_CREATED'
  | 'IDENTITY_CREATED'
  | 'CONSOLE_REGISTERED'
  | 'PRINCIPAL_CREATED'
  | 'CREDENTIAL_ENROLLED'
  | 'ADMIN_BOOTSTRAPPED'
  | 'VERIFIED'
  | 'SEALED';

export type InstallationStatus = {
  status: 'UNINITIALIZED' | 'IN_PROGRESS' | 'SEALED';
  phase: InstallationPhase | null;
  tenantDisplayName: string | null;
  identityDisplayName: string | null;
  username: string | null;
  consoleRedirectUri: string;
  setupSessionAuthenticated: boolean;
};

export type InstallationInput = {
  tenantDisplayName: string;
  identityDisplayName: string;
  username: string;
  password: string;
};

const STATUS_PATH = '/api/setup/status';
const SESSION_PATH = '/api/setup/session';
const INITIALIZE_PATH = '/api/setup/initialize';
const CSRF_COOKIE = '__Host-wyrmgate_setup_csrf';
const CSRF_HEADER = 'X-Wyrmgate-Setup-CSRF';

function cookieValue(name: string): string | null {
  for (const raw of document.cookie.split(';')) {
    const [key, ...rest] = raw.trim().split('=');
    if (key === name) return rest.join('=');
  }
  return null;
}

async function decode<T>(response: Response): Promise<T> {
  if (!response.ok) {
    let message = `Request failed with status ${response.status}`;
    let code: string | null = null;
    try {
      const payload = await response.json() as Record<string, unknown>;
      if (typeof payload.message === 'string' && payload.message.trim()) message = payload.message;
      if (typeof payload.code === 'string' && payload.code.trim()) code = payload.code;
    } catch {
      // Do not surface arbitrary proxy/HTML bodies or secret-bearing diagnostics.
    }
    throw new ApiError(response.status, message, response.headers.get('X-Correlation-Id'), code);
  }
  return await response.json() as T;
}

export async function getInstallationStatus(signal?: AbortSignal): Promise<InstallationStatus> {
  const response = await fetch(STATUS_PATH, {
    method: 'GET',
    credentials: 'same-origin',
    headers: { Accept: 'application/json' },
    signal,
  });
  return decode<InstallationStatus>(response);
}

export async function establishSetupSession(setupSecret: string): Promise<InstallationStatus> {
  const response = await fetch(SESSION_PATH, {
    method: 'POST',
    credentials: 'same-origin',
    headers: { Accept: 'application/json', 'Content-Type': 'application/json' },
    body: JSON.stringify({ setupSecret }),
  });
  return decode<InstallationStatus>(response);
}

export async function initializeInstallation(input: InstallationInput): Promise<InstallationStatus> {
  const csrf = cookieValue(CSRF_COOKIE);
  const headers = new Headers({ Accept: 'application/json', 'Content-Type': 'application/json' });
  if (csrf) headers.set(CSRF_HEADER, csrf);
  const response = await fetch(INITIALIZE_PATH, {
    method: 'POST',
    credentials: 'same-origin',
    headers,
    body: JSON.stringify(input),
  });
  return decode<InstallationStatus>(response);
}
