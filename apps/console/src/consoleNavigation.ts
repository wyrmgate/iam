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
  | 'administration'
  | 'audit';

export type ShellGroup = {
  id: ShellGroupId;
  code: string;
  label: string;
};

export type ConsoleRoute = {
  path: string;
  label: string;
  group: ShellGroupId;
  resourceTypes?: string[];
  visibleWithoutAdministrativeEligibility?: boolean;
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
  { id: 'administration', code: 'AD', label: 'Administration' },
  { id: 'audit', code: 'AU', label: 'Audit' },
];

export const consoleRoutes: ConsoleRoute[] = [
  { path: '/', label: 'Overview', group: 'overview', visibleWithoutAdministrativeEligibility: true },
  { path: '/requests', label: 'Requests & approvals', group: 'work', visibleWithoutAdministrativeEligibility: true },
  { path: '/reviews', label: 'Reviews', group: 'work', visibleWithoutAdministrativeEligibility: true },
  { path: '/identities', label: 'Identities', group: 'identity', resourceTypes: ['identity', 'canonical-attribute-value'] },
  { path: '/principals', label: 'Principals', group: 'identity', resourceTypes: ['principal', 'source-correlation-policy', 'source-lifecycle-policy', 'source-absence-policy'] },
  { path: '/access', label: 'Access assignments', group: 'access', resourceTypes: ['access-assignment', 'effective-access', 'lifecycle-access-policy'] },
  { path: '/policies', label: 'Policies & exceptions', group: 'governance', resourceTypes: ['governance-policy', 'governance-exception', 'source-correlation-policy', 'source-lifecycle-policy', 'source-absence-policy'] },
  { path: '/catalog', label: 'Catalog', group: 'catalog', resourceTypes: ['application', 'sso-client', 'application-target', 'entitlement', 'role', 'role-version'] },
  { path: '/integrations', label: 'Integrations', group: 'integration', resourceTypes: ['connector', 'connector-binding', 'connector-worker', 'entitlement-observation-mapping', 'provisioning-job', 'provisioning-task', 'provisioning-attempt', 'reconciliation-run'] },
  { path: '/credentials', label: 'Credentials', group: 'credentials', resourceTypes: ['credential', 'credential-rotation'] },
  { path: '/administration', label: 'Administration', group: 'administration', visibleWithoutAdministrativeEligibility: true },
  { path: '/audit', label: 'Audit', group: 'audit', resourceTypes: ['audit', 'evidence-snapshot'] },
];

export function activeRouteFor(pathname: string): ConsoleRoute {
  if (/^\/identities\/[^/]+$/.test(pathname)) {
    return consoleRoutes.find((route) => route.path === '/identities')!;
  }
  return consoleRoutes.find((route) => route.path === pathname) ?? consoleRoutes[0];
}

export function visibleConsoleRoutes(authority: CurrentAdministrativeAuthority | null): ConsoleRoute[] {
  if (!authority) return consoleRoutes;
  if (!authority.administrativelyEligible) {
    return consoleRoutes.filter((route) => route.visibleWithoutAdministrativeEligibility === true);
  }
  const available = new Set(authority.authorities.map((item) => item.permission.resourceType));
  return consoleRoutes.filter((route) =>
    route.visibleWithoutAdministrativeEligibility === true
    || !route.resourceTypes
    || route.resourceTypes.some((resourceType) => available.has(resourceType)),
  );
}

export function shellGroupFor(id: ShellGroupId): ShellGroup {
  return shellGroups.find((group) => group.id === id)!;
}
