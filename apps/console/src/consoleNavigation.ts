import type { CurrentAdministrativeAuthority } from './api';

export type ShellGroupId =
  | 'overview'
  | 'work'
  | 'identity'
  | 'access'
  | 'governance'
  | 'catalog'
  | 'integration'
  | 'credentials'
  | 'platform'
  | 'administration'
  | 'audit';

export type ShellGroup = {
  id: ShellGroupId;
  code: string;
  label: string;
};

export type ConsoleRouteId =
  | 'overview'
  | 'tenant'
  | 'platform-tenants'
  | 'requests'
  | 'reviews'
  | 'identities'
  | 'principals'
  | 'access'
  | 'policies'
  | 'catalog'
  | 'integrations'
  | 'credentials'
  | 'administration'
  | 'audit';

export type ConsoleRoute = {
  id: ConsoleRouteId;
  path: string;
  label: string;
  group: ShellGroupId;
  resourceTypes?: string[];
  visibleWithoutAdministrativeEligibility?: boolean;
  platformOwnerOnly?: boolean;
};

export type ConsoleRouteMatch = {
  route: ConsoleRoute;
  params: Readonly<Record<string, string>>;
};

export const shellGroups: ShellGroup[] = [
  { id: 'overview', code: 'OV', label: 'Overview' },
  { id: 'work', code: 'WK', label: 'My Work' },
  { id: 'identity', code: 'ID', label: 'Identity' },
  { id: 'access', code: 'AC', label: 'Access' },
  { id: 'governance', code: 'GV', label: 'Governance' },
  { id: 'catalog', code: 'CT', label: 'Catalog' },
  { id: 'integration', code: 'IN', label: 'Integration' },
  { id: 'credentials', code: 'CR', label: 'Credentials' },
  { id: 'platform', code: 'PL', label: 'Platform' },
  { id: 'administration', code: 'AD', label: 'Administration' },
  { id: 'audit', code: 'AU', label: 'Audit' },
];

export const consoleRoutes: ConsoleRoute[] = [
  { id: 'overview', path: '/', label: 'Overview', group: 'overview', visibleWithoutAdministrativeEligibility: true },
  { id: 'requests', path: '/requests', label: 'Requests & approvals', group: 'work', visibleWithoutAdministrativeEligibility: true },
  { id: 'reviews', path: '/reviews', label: 'Reviews', group: 'work', visibleWithoutAdministrativeEligibility: true },
  { id: 'identities', path: '/identities', label: 'Identities', group: 'identity', resourceTypes: ['identity', 'canonical-attribute-value'] },
  { id: 'principals', path: '/principals', label: 'Principals', group: 'identity', resourceTypes: ['principal', 'source-correlation-policy', 'source-lifecycle-policy', 'source-absence-policy'] },
  { id: 'access', path: '/access', label: 'Access assignments', group: 'access', resourceTypes: ['access-assignment', 'effective-access', 'lifecycle-access-policy'] },
  { id: 'policies', path: '/policies', label: 'Policies & exceptions', group: 'governance', resourceTypes: ['governance-policy', 'governance-exception', 'source-correlation-policy', 'source-lifecycle-policy', 'source-absence-policy'] },
  { id: 'catalog', path: '/catalog', label: 'Catalog', group: 'catalog', resourceTypes: ['application', 'sso-client', 'application-target', 'entitlement', 'role', 'role-version'] },
  { id: 'integrations', path: '/integrations', label: 'Integrations', group: 'integration', resourceTypes: ['connector', 'connector-binding', 'connector-worker', 'entitlement-observation-mapping', 'provisioning-job', 'provisioning-task', 'provisioning-attempt', 'reconciliation-run'] },
  { id: 'credentials', path: '/credentials', label: 'Credentials', group: 'credentials', resourceTypes: ['credential', 'credential-rotation'] },
  { id: 'platform-tenants', path: '/platform/tenants', label: 'Tenants', group: 'platform', platformOwnerOnly: true },
  { id: 'tenant', path: '/tenant', label: 'Current Tenant', group: 'administration' },
  { id: 'administration', path: '/administration', label: 'Administration', group: 'administration', visibleWithoutAdministrativeEligibility: true },
  { id: 'audit', path: '/audit', label: 'Audit', group: 'audit', resourceTypes: ['audit', 'evidence-snapshot'] },
];

function decodedPathSegment(value: string): string | null {
  try {
    const decoded = decodeURIComponent(value);
    return decoded.length > 0 ? decoded : null;
  } catch {
    return null;
  }
}

export function matchConsoleRoute(pathname: string): ConsoleRouteMatch | null {
  const exact = consoleRoutes.find((route) => route.path === pathname);
  if (exact) return { route: exact, params: {} };

  const platformTenantMatch = /^\/platform\/tenants\/([^/]+)$/.exec(pathname);
  if (platformTenantMatch) {
    const tenantId = decodedPathSegment(platformTenantMatch[1]);
    const route = consoleRoutes.find((candidate) => candidate.id === 'platform-tenants');
    if (tenantId && route) return { route, params: { tenantId } };
  }

  const identityMatch = /^\/identities\/([^/]+)$/.exec(pathname);
  if (identityMatch) {
    const identityId = decodedPathSegment(identityMatch[1]);
    const route = consoleRoutes.find((candidate) => candidate.id === 'identities');
    if (identityId && route) return { route, params: { identityId } };
  }

  return null;
}

export function activeRouteFor(pathname: string): ConsoleRoute | null {
  return matchConsoleRoute(pathname)?.route ?? null;
}

export function identityDetailPath(identityId: string): string {
  return `/identities/${encodeURIComponent(identityId)}`;
}

export function platformTenantDetailPath(tenantId: string): string {
  return `/platform/tenants/${encodeURIComponent(tenantId)}`;
}

export const CONSOLE_NAVIGATION_EVENT = 'wyrmgate:console-navigation';

export function navigateConsole(path: string): void {
  const target = new URL(path, window.location.origin);
  const current = `${window.location.pathname}${window.location.search}${window.location.hash}`;
  const next = `${target.pathname}${target.search}${target.hash}`;
  if (current === next) return;
  window.history.pushState({}, '', next);
  window.dispatchEvent(new Event(CONSOLE_NAVIGATION_EVENT));
  window.scrollTo({ top: 0 });
}

export function replaceConsole(path: string): void {
  const target = new URL(path, window.location.origin);
  const next = `${target.pathname}${target.search}${target.hash}`;
  window.history.replaceState({}, '', next);
  window.dispatchEvent(new Event(CONSOLE_NAVIGATION_EVENT));
}

export function visibleConsoleRoutes(authority: CurrentAdministrativeAuthority | null): ConsoleRoute[] {
  if (!authority) return consoleRoutes.filter((route) => !route.platformOwnerOnly);
  if (!authority.administrativelyEligible) {
    return consoleRoutes.filter((route) => route.visibleWithoutAdministrativeEligibility === true);
  }
  const available = new Set(authority.authorities.map((item) => item.permission.resourceType));
  return consoleRoutes.filter((route) => {
    if (route.platformOwnerOnly) return authority.viablePlatformOwner;
    return route.visibleWithoutAdministrativeEligibility === true
      || !route.resourceTypes
      || route.resourceTypes.some((resourceType) => available.has(resourceType));
  });
}

export function shellGroupFor(id: ShellGroupId): ShellGroup {
  return shellGroups.find((group) => group.id === id)!;
}
