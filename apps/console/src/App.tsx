import { useEffect, useState } from 'react';
import {
  getCurrentAdministrativeAuthority,
  getSystemInfo,
  type CurrentAdministrativeAuthority,
  type SystemInfo,
} from './api';
import { OverviewPage } from './OverviewPage';
import { PlatformTenantDetailPage } from './PlatformTenantDetailPage';
import { PlatformTenantsPage } from './PlatformTenantsPage';
import { RequestsApprovalsWithAccessLinks } from './RequestsApprovalsWithAccessLinks';
import { TenantOverviewPage } from './TenantOverviewPage';
import { useConsolePathname } from './consoleLocation';
import {
  identityDetailPath,
  matchConsoleRoute,
  navigateConsole,
  platformTenantDetailPath,
  type ConsoleRouteId,
} from './consoleNavigation';
import { Button, Page, PageHeader } from './ui';
import { AccessWorkspace } from './workspaces/AccessWorkspace';
import { AdministrationWorkspace } from './workspaces/AdministrationWorkspace';
import { AuditWorkspaceWithAccessLink } from './workspaces/AuditWorkspaceWithAccessLink';
import { CatalogWorkspace } from './workspaces/CatalogWorkspace';
import { CredentialsWorkspace } from './workspaces/CredentialsWorkspace';
import { PoliciesWorkspace, ReviewsWorkspace } from './workspaces/GovernanceWorkspace';
import { IdentityCollectionPage } from './workspaces/IdentityCollectionPage';
import { IdentityWorkspaceWithAccessLinks } from './workspaces/IdentityWorkspaceWithAccessLinks';
import { IntegrationsWorkspace } from './workspaces/IntegrationsWorkspace';
import { PrincipalsWorkspace } from './workspaces/PrincipalsWorkspace';

type LoadState<T> =
  | { status: 'loading' }
  | { status: 'ready'; value: T }
  | { status: 'error'; error: unknown };

type RouteKey = ConsoleRouteId | 'not-found';

export function App() {
  const pathname = useConsolePathname();
  const [system, setSystem] = useState<LoadState<SystemInfo>>({ status: 'loading' });
  const [authority, setAuthority] = useState<LoadState<CurrentAdministrativeAuthority>>({ status: 'loading' });

  useEffect(() => {
    const controller = new AbortController();
    getSystemInfo(controller.signal)
      .then((value) => setSystem({ status: 'ready', value }))
      .catch((error: unknown) => {
        if (error instanceof DOMException && error.name === 'AbortError') return;
        setSystem({ status: 'error', error });
      });
    return () => controller.abort();
  }, []);

  useEffect(() => {
    const controller = new AbortController();
    getCurrentAdministrativeAuthority(controller.signal)
      .then((value) => setAuthority({ status: 'ready', value }))
      .catch((error: unknown) => {
        if (error instanceof DOMException && error.name === 'AbortError') return;
        setAuthority({ status: 'error', error });
      });
    return () => controller.abort();
  }, [pathname]);

  const routeMatch = matchConsoleRoute(pathname);
  const route: RouteKey = routeMatch?.route.id ?? 'not-found';
  const identityId = routeMatch?.params.identityId;
  const platformTenantId = routeMatch?.params.tenantId;
  function go(path: string) { navigateConsole(path); }

  return (
    <>
      {route === 'overview' && <OverviewPage system={system} authority={authority} />}
      {route === 'platform-tenants' && !platformTenantId && (
        <PlatformTenantsPage onSelect={(tenantId) => go(platformTenantDetailPath(tenantId))} />
      )}
      {route === 'platform-tenants' && platformTenantId && (
        <PlatformTenantDetailPage tenantId={platformTenantId} onBack={() => go('/platform/tenants')} />
      )}
      {route === 'tenant' && <TenantOverviewPage authority={authority.status === 'ready' ? authority.value : null} />}
      {route === 'identities' && !identityId && (
        <IdentityCollectionPage
          authority={authority.status === 'ready' ? authority.value : null}
          onSelect={(id) => go(identityDetailPath(id))}
        />
      )}
      {route === 'identities' && identityId && (
        <IdentityWorkspaceWithAccessLinks
          selectedId={identityId}
          authority={authority.status === 'ready' ? authority.value : null}
          onSelect={(id) => go(id ? identityDetailPath(id) : '/identities')}
        />
      )}
      {route === 'principals' && <PrincipalsWorkspace />}
      {route === 'catalog' && <CatalogWorkspace />}
      {route === 'access' && <AccessWorkspace />}
      {route === 'requests' && <RequestsApprovalsWithAccessLinks />}
      {route === 'reviews' && <ReviewsWorkspace />}
      {route === 'policies' && <PoliciesWorkspace />}
      {route === 'credentials' && <CredentialsWorkspace />}
      {route === 'integrations' && <IntegrationsWorkspace />}
      {route === 'administration' && <AdministrationWorkspace />}
      {route === 'audit' && <AuditWorkspaceWithAccessLink />}
      {route === 'not-found' && (
        <Page>
          <PageHeader eyebrow="404" title="Page not found" description="The requested Console route is not available." />
          <Button variant="outlined" onClick={() => go('/')} sx={{ justifySelf: 'start' }}>Return to overview</Button>
        </Page>
      )}
    </>
  );
}
