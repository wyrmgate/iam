import { useEffect, useMemo, useState, type KeyboardEvent, type ReactNode } from 'react';
import {
  getCurrentAdministrativeAuthority,
  getSystemInfo,
  type CurrentAdministrativeAuthority,
  type SystemInfo,
} from './api';

type RouteSpec = {
  path: string;
  label: string;
  group: ShellGroupId;
  resourceTypes?: string[];
  visibleWithoutAdministrativeEligibility?: boolean;
};

type ShellGroupId = 'overview' | 'identity' | 'access' | 'governance' | 'catalog' | 'integration' | 'security' | 'audit';

type ShellGroup = {
  id: ShellGroupId;
  code: string;
  label: string;
};

type ShellChromeProps = {
  children: ReactNode;
  onSignOut?: () => Promise<void> | void;
  signOutBusy?: boolean;
};

const groups: ShellGroup[] = [
  { id: 'overview', code: 'OV', label: 'Overview' },
  { id: 'identity', code: 'ID', label: 'Identity' },
  { id: 'access', code: 'AC', label: 'Access' },
  { id: 'governance', code: 'GV', label: 'Governance' },
  { id: 'catalog', code: 'CT', label: 'Catalog' },
  { id: 'integration', code: 'IN', label: 'Integration' },
  { id: 'security', code: 'SE', label: 'Security' },
  { id: 'audit', code: 'AU', label: 'Audit' },
];

const routes: RouteSpec[] = [
  { path: '/', label: 'Overview', group: 'overview', visibleWithoutAdministrativeEligibility: true },
  { path: '/identities', label: 'Identities', group: 'identity', resourceTypes: ['identity', 'canonical-attribute-value'] },
  { path: '/principals', label: 'Principals', group: 'identity', resourceTypes: ['principal', 'source-correlation-policy', 'source-lifecycle-policy', 'source-absence-policy'] },
  { path: '/access', label: 'Access assignments', group: 'access', resourceTypes: ['access-assignment', 'effective-access', 'lifecycle-access-policy'] },
  { path: '/requests', label: 'Requests & approvals', group: 'governance', visibleWithoutAdministrativeEligibility: true },
  { path: '/reviews', label: 'Reviews', group: 'governance', resourceTypes: ['review-campaign'] },
  { path: '/policies', label: 'Policies & exceptions', group: 'governance', resourceTypes: ['governance-policy', 'governance-exception', 'source-correlation-policy', 'source-lifecycle-policy', 'source-absence-policy'] },
  { path: '/catalog', label: 'Catalog', group: 'catalog', resourceTypes: ['application', 'sso-client', 'application-target', 'entitlement', 'role', 'role-version'] },
  { path: '/integrations', label: 'Integrations', group: 'integration', resourceTypes: ['connector', 'connector-binding', 'connector-worker', 'entitlement-observation-mapping', 'provisioning-job', 'provisioning-task', 'provisioning-attempt', 'reconciliation-run'] },
  { path: '/credentials', label: 'Credentials', group: 'security', resourceTypes: ['credential', 'credential-rotation'] },
  { path: '/administration', label: 'Administration', group: 'security', visibleWithoutAdministrativeEligibility: true },
  { path: '/audit', label: 'Audit', group: 'audit', resourceTypes: ['audit', 'evidence-snapshot'] },
];

function usePathname() {
  const [pathname, setPathname] = useState(window.location.pathname);
  useEffect(() => {
    const onPopState = () => setPathname(window.location.pathname);
    window.addEventListener('popstate', onPopState);
    return () => window.removeEventListener('popstate', onPopState);
  }, []);
  return pathname;
}

function navigate(path: string) {
  if (window.location.pathname !== path) {
    window.history.pushState({}, '', path);
    window.dispatchEvent(new PopStateEvent('popstate'));
  }
  window.scrollTo({ top: 0, behavior: 'smooth' });
}

function activeRouteFor(pathname: string): RouteSpec {
  if (/^\/identities\/[^/]+$/.test(pathname)) return routes.find((route) => route.path === '/identities')!;
  return routes.find((route) => route.path === pathname) ?? routes[0];
}

function visibleRoutes(authority: CurrentAdministrativeAuthority | null): RouteSpec[] {
  if (!authority) return routes;
  if (!authority.administrativelyEligible) {
    return routes.filter((route) => route.visibleWithoutAdministrativeEligibility === true);
  }
  const available = new Set(authority.authorities.map((item) => item.permission.resourceType));
  return routes.filter((route) => !route.resourceTypes || route.resourceTypes.some((resourceType) => available.has(resourceType)));
}

function shortId(value: string | undefined | null): string {
  if (!value) return 'Unavailable';
  if (value.length <= 18) return value;
  return `${value.slice(0, 8)}…${value.slice(-6)}`;
}

function groupFor(id: ShellGroupId): ShellGroup {
  return groups.find((group) => group.id === id)!;
}

export function ShellChrome({ children, onSignOut, signOutBusy = false }: ShellChromeProps) {
  const pathname = usePathname();
  const [authority, setAuthority] = useState<CurrentAdministrativeAuthority | null>(null);
  const [authorityUnavailable, setAuthorityUnavailable] = useState(false);
  const [system, setSystem] = useState<SystemInfo | null>(null);
  const [systemUnavailable, setSystemUnavailable] = useState(false);
  const [openGroup, setOpenGroup] = useState<ShellGroupId | null>(null);
  const [commandOpen, setCommandOpen] = useState(false);
  const [query, setQuery] = useState('');
  const [selectedCommand, setSelectedCommand] = useState(0);
  const [contextOpen, setContextOpen] = useState(false);
  const [actorOpen, setActorOpen] = useState(false);

  const currentRoute = activeRouteFor(pathname);
  const allowedRoutes = useMemo(() => visibleRoutes(authority), [authority]);
  const allowedGroups = useMemo(() => groups.filter((group) => allowedRoutes.some((route) => route.group === group.id)), [allowedRoutes]);
  const paletteRoutes = useMemo(() => {
    const needle = query.trim().toLowerCase();
    if (!needle) return allowedRoutes;
    return allowedRoutes.filter((route) => `${groupFor(route.group).label} ${route.label}`.toLowerCase().includes(needle));
  }, [allowedRoutes, query]);

  useEffect(() => {
    const controller = new AbortController();
    setAuthorityUnavailable(false);
    getCurrentAdministrativeAuthority(controller.signal)
      .then(setAuthority)
      .catch((error: unknown) => {
        if (error instanceof DOMException && error.name === 'AbortError') return;
        setAuthority(null);
        setAuthorityUnavailable(true);
      });
    return () => controller.abort();
  }, [pathname]);

  useEffect(() => {
    const controller = new AbortController();
    getSystemInfo(controller.signal)
      .then(setSystem)
      .catch((error: unknown) => {
        if (error instanceof DOMException && error.name === 'AbortError') return;
        setSystemUnavailable(true);
      });
    return () => controller.abort();
  }, []);

  useEffect(() => {
    const onKeyDown = (event: globalThis.KeyboardEvent) => {
      if ((event.metaKey || event.ctrlKey) && event.key.toLowerCase() === 'k') {
        event.preventDefault();
        setCommandOpen((value) => !value);
        return;
      }
      if (event.key === 'Escape') {
        setCommandOpen(false);
        setOpenGroup(null);
        setActorOpen(false);
        setContextOpen(false);
      }
    };
    window.addEventListener('keydown', onKeyDown);
    return () => window.removeEventListener('keydown', onKeyDown);
  }, []);

  useEffect(() => {
    setOpenGroup(null);
    setActorOpen(false);
  }, [pathname]);

  useEffect(() => {
    setSelectedCommand(0);
  }, [query, commandOpen]);

  function openRoute(path: string) {
    navigate(path);
    setCommandOpen(false);
    setQuery('');
    setOpenGroup(null);
  }

  function activateGroup(group: ShellGroup) {
    const destinations = allowedRoutes.filter((route) => route.group === group.id);
    if (destinations.length === 1) {
      openRoute(destinations[0].path);
      return;
    }
    setOpenGroup((current) => current === group.id ? null : group.id);
  }

  function commandKeyDown(event: KeyboardEvent<HTMLInputElement>) {
    if (event.key === 'ArrowDown') {
      event.preventDefault();
      if (paletteRoutes.length) setSelectedCommand((current) => (current + 1) % paletteRoutes.length);
    } else if (event.key === 'ArrowUp') {
      event.preventDefault();
      if (paletteRoutes.length) setSelectedCommand((current) => (current - 1 + paletteRoutes.length) % paletteRoutes.length);
    } else if (event.key === 'Enter') {
      event.preventDefault();
      const route = paletteRoutes[selectedCommand];
      if (route) openRoute(route.path);
    } else if (event.key === 'Escape') {
      setCommandOpen(false);
    }
  }

  const flyoutRoutes = openGroup ? allowedRoutes.filter((route) => route.group === openGroup) : [];
  const currentGroup = groupFor(currentRoute.group);
  const runtimeOnline = Boolean(system && system.status.toLowerCase() !== 'unavailable');

  return (
    <div className="product-shell">
      <aside className="navigation-dock" aria-label="Product navigation">
        <button className="dock-brand" aria-label="Wyrmgate overview" onClick={() => openRoute('/')}>
          <span className="dock-brand-glyph" aria-hidden="true">W</span>
        </button>
        <div className="dock-primary">
          {allowedGroups.map((group) => {
            const active = currentRoute.group === group.id;
            return (
              <button
                key={group.id}
                className={`dock-item ${active ? 'active' : ''}`}
                aria-label={group.label}
                aria-expanded={openGroup === group.id}
                onClick={() => activateGroup(group)}
              >
                <span className="dock-code" aria-hidden="true">{group.code}</span>
                <span className="dock-hover-label">{group.label}</span>
              </button>
            );
          })}
        </div>
        <div className="dock-utility">
          <button className="dock-item" aria-label="Open command palette" onClick={() => setCommandOpen(true)}>
            <span className="dock-code" aria-hidden="true">⌘</span>
            <span className="dock-hover-label">Command palette</span>
          </button>
          <button className="dock-item runtime-dock" aria-label="Open runtime context" onClick={() => setContextOpen(true)}>
            <span className={`runtime-dot ${runtimeOnline ? 'online' : ''}`} aria-hidden="true" />
            <span className="dock-hover-label">{systemUnavailable ? 'Runtime unavailable' : system ? system.status : 'Checking runtime'}</span>
          </button>
        </div>
        {openGroup && (
          <div className="dock-flyout" role="menu" aria-label={`${groupFor(openGroup).label} navigation`}>
            <div className="flyout-heading">
              <span>{groupFor(openGroup).code}</span>
              <strong>{groupFor(openGroup).label}</strong>
            </div>
            <div className="flyout-routes">
              {flyoutRoutes.map((route) => (
                <button
                  key={route.path}
                  className={currentRoute.path === route.path ? 'active' : ''}
                  role="menuitem"
                  onClick={() => openRoute(route.path)}
                >
                  <span>{route.label}</span>
                  <span aria-hidden="true">↗</span>
                </button>
              ))}
            </div>
          </div>
        )}
      </aside>

      {openGroup && <button className="shell-scrim" aria-label="Close navigation" onClick={() => setOpenGroup(null)} />}

      <section className="shell-stage">
        <header className="shell-topbar">
          <div className="shell-location">
            <span className="shell-scope">{authority ? `Tenant ${shortId(authority.tenantId)}` : authorityUnavailable ? 'Tenant unavailable' : 'Resolving tenant'}</span>
            <span className="shell-separator" aria-hidden="true">/</span>
            <span className="shell-breadcrumb"><strong>{currentGroup.label}</strong><span>›</span>{currentRoute.label}</span>
          </div>
          <div className="shell-actions">
            <button className="command-trigger" onClick={() => setCommandOpen(true)}>
              <span>Navigate</span><kbd>{navigator.platform.toLowerCase().includes('mac') ? '⌘K' : 'Ctrl K'}</kbd>
            </button>
            <button className={`context-trigger ${contextOpen ? 'active' : ''}`} aria-label="Open context rail" onClick={() => setContextOpen((value) => !value)}>
              Context
            </button>
            <div className="actor-control">
              <button className="actor-trigger" aria-expanded={actorOpen} onClick={() => setActorOpen((value) => !value)}>
                <span className="actor-mark" aria-hidden="true">{authority?.actorIdentityId.slice(0, 2).toUpperCase() ?? '—'}</span>
                <span className="actor-copy"><strong>Actor</strong><small>{shortId(authority?.actorIdentityId)}</small></span>
                <span aria-hidden="true">⌄</span>
              </button>
              {actorOpen && (
                <div className="actor-menu">
                  <p className="menu-kicker">Current actor</p>
                  <strong>{shortId(authority?.actorIdentityId)}</strong>
                  <span>{authority ? `${authority.authorities.length} effective authority source${authority.authorities.length === 1 ? '' : 's'}` : 'Authority projection unavailable'}</span>
                  <button onClick={() => openRoute('/administration')}>My administrative authority</button>
                  {onSignOut ? <button disabled={signOutBusy} onClick={() => void onSignOut()}>{signOutBusy ? 'Signing out…' : 'Sign out'}</button> : <span className="external-session-note">Session managed externally</span>}
                </div>
              )}
            </div>
          </div>
        </header>

        <main className="shell-workspace">{children}</main>
      </section>

      <aside className={`context-rail ${contextOpen ? 'open' : ''}`} aria-hidden={!contextOpen}>
        <div className="context-rail-header">
          <div><span className="menu-kicker">Wyrmgate context</span><strong>{currentGroup.label}</strong></div>
          <button aria-label="Close context" onClick={() => setContextOpen(false)}>×</button>
        </div>
        <dl className="context-list">
          <div><dt>Surface</dt><dd>{currentRoute.label}</dd></div>
          <div><dt>Tenant</dt><dd className="mono">{shortId(authority?.tenantId)}</dd></div>
          <div><dt>Actor identity</dt><dd className="mono">{shortId(authority?.actorIdentityId)}</dd></div>
          <div><dt>Administrative eligibility</dt><dd>{authority ? (authority.administrativelyEligible ? 'Eligible' : 'Not eligible') : 'Unavailable'}</dd></div>
          <div><dt>Authority sources</dt><dd>{authority?.authorities.length ?? '—'}</dd></div>
          <div><dt>Runtime</dt><dd>{systemUnavailable ? 'Unavailable' : system ? `${system.name} · ${system.status}` : 'Checking…'}</dd></div>
          <div><dt>Endpoint</dt><dd className="mono">{window.location.hostname}</dd></div>
        </dl>
        <p className="context-note">This rail reflects server-derived shell context. It does not infer administrative permission from browser claims.</p>
      </aside>

      {commandOpen && (
        <div className="command-backdrop" role="presentation" onMouseDown={(event) => { if (event.currentTarget === event.target) setCommandOpen(false); }}>
          <section className="command-palette" role="dialog" aria-modal="true" aria-label="Wyrmgate command palette">
            <div className="command-input-row">
              <span aria-hidden="true">⌘</span>
              <input
                autoFocus
                value={query}
                onChange={(event) => setQuery(event.target.value)}
                onKeyDown={commandKeyDown}
                placeholder="Navigate Wyrmgate…"
                aria-label="Search navigation commands"
              />
              <kbd>Esc</kbd>
            </div>
            <div className="command-results">
              <div className="command-section-label">Navigate</div>
              {paletteRoutes.length === 0 && <div className="command-empty">No available destination matches that query.</div>}
              {paletteRoutes.map((route, index) => (
                <button
                  key={route.path}
                  className={selectedCommand === index ? 'selected' : ''}
                  onMouseEnter={() => setSelectedCommand(index)}
                  onClick={() => openRoute(route.path)}
                >
                  <span><small>{groupFor(route.group).label}</small><strong>{route.label}</strong></span>
                  <span aria-hidden="true">↵</span>
                </button>
              ))}
            </div>
            <footer className="command-footer"><span>↑ ↓ Select</span><span>Enter Open</span><span>Server authorization remains final</span></footer>
          </section>
        </div>
      )}
    </div>
  );
}
