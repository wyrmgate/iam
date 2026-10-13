import { useEffect, useMemo, useState, type KeyboardEvent, type ReactNode } from 'react';
import {
  getCurrentAdministrativeAuthority,
  getSystemInfo,
  type CurrentAdministrativeAuthority,
  type SystemInfo,
} from './api';
import {
  activeRouteFor,
  consoleRoutes,
  shellGroupFor,
  shellGroups,
  visibleConsoleRoutes,
  type ShellGroup,
  type ShellGroupId,
} from './consoleNavigation';

type ShellChromeProps = {
  children: ReactNode;
  onSignOut?: () => Promise<void> | void;
  signOutBusy?: boolean;
};

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

function shortId(value: string | undefined | null): string {
  if (!value) return 'Unavailable';
  if (value.length <= 18) return value;
  return `${value.slice(0, 8)}…${value.slice(-6)}`;
}

export function ShellChrome({ children, onSignOut, signOutBusy = false }: ShellChromeProps) {
  const pathname = usePathname();
  const [authority, setAuthority] = useState<CurrentAdministrativeAuthority | null>(null);
  const [authorityUnavailable, setAuthorityUnavailable] = useState(false);
  const [system, setSystem] = useState<SystemInfo | null>(null);
  const [systemUnavailable, setSystemUnavailable] = useState(false);
  const [navigationCollapsed, setNavigationCollapsed] = useState(false);
  const [mobileNavigationOpen, setMobileNavigationOpen] = useState(false);
  const [openGroup, setOpenGroup] = useState<ShellGroupId | null>(null);
  const [commandOpen, setCommandOpen] = useState(false);
  const [query, setQuery] = useState('');
  const [selectedCommand, setSelectedCommand] = useState(0);
  const [contextOpen, setContextOpen] = useState(false);
  const [actorOpen, setActorOpen] = useState(false);

  const currentRoute = activeRouteFor(pathname);
  const allowedRoutes = useMemo(() => visibleConsoleRoutes(authority), [authority]);
  const allowedGroups = useMemo(
    () => shellGroups.filter((group) => allowedRoutes.some((route) => route.group === group.id)),
    [allowedRoutes],
  );
  const paletteRoutes = useMemo(() => {
    const needle = query.trim().toLowerCase();
    if (!needle) return allowedRoutes;
    return allowedRoutes.filter((route) => `${shellGroupFor(route.group).label} ${route.label}`.toLowerCase().includes(needle));
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
        setMobileNavigationOpen(false);
      }
    };
    window.addEventListener('keydown', onKeyDown);
    return () => window.removeEventListener('keydown', onKeyDown);
  }, []);

  useEffect(() => {
    setOpenGroup(null);
    setActorOpen(false);
    setMobileNavigationOpen(false);
  }, [pathname]);

  useEffect(() => {
    setSelectedCommand(0);
  }, [query, commandOpen]);

  function openRoute(path: string) {
    navigate(path);
    setCommandOpen(false);
    setQuery('');
    setOpenGroup(null);
    setMobileNavigationOpen(false);
  }

  function activateCollapsedGroup(group: ShellGroup) {
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
  const currentGroup = shellGroupFor(currentRoute.group);
  const runtimeOnline = Boolean(system && system.status.toLowerCase() !== 'unavailable');

  return (
    <div className={`product-shell ${navigationCollapsed ? 'nav-collapsed' : ''}`}>
      <aside className={`navigation-dock ${mobileNavigationOpen ? 'mobile-open' : ''}`} aria-label="Product navigation">
        <button className="dock-brand" aria-label="Wyrmgate overview" onClick={() => openRoute('/')}>
          <span className="dock-brand-glyph" aria-hidden="true">W</span>
          <span className="dock-brand-copy"><strong>Wyrmgate</strong><small>IAM Console</small></span>
        </button>

        <nav className="dock-primary" aria-label="Primary navigation">
          {allowedGroups.map((group) => {
            const destinations = allowedRoutes.filter((route) => route.group === group.id);
            const active = currentRoute.group === group.id;
            const singleDestination = destinations.length === 1 ? destinations[0] : null;
            return (
              <div className={`dock-group ${active ? 'active' : ''}`} key={group.id}>
                <button
                  className={`dock-item dock-group-trigger ${active ? 'active' : ''}`}
                  aria-label={group.label}
                  aria-expanded={destinations.length > 1 ? openGroup === group.id : undefined}
                  onClick={() => singleDestination ? openRoute(singleDestination.path) : activateCollapsedGroup(group)}
                >
                  <span className="dock-code" aria-hidden="true">{group.code}</span>
                  <span className="dock-label">{group.label}</span>
                  {destinations.length > 1 ? <span className="dock-chevron" aria-hidden="true">›</span> : null}
                  <span className="dock-hover-label">{group.label}</span>
                </button>
                {destinations.length > 1 ? (
                  <div className="dock-subroutes">
                    {destinations.map((route) => (
                      <button
                        key={route.path}
                        className={currentRoute.path === route.path ? 'active' : ''}
                        onClick={() => openRoute(route.path)}
                      >
                        {route.label}
                      </button>
                    ))}
                  </div>
                ) : null}
              </div>
            );
          })}
        </nav>

        <div className="dock-utility">
          <button className="dock-item dock-utility-item" aria-label="Open command palette" onClick={() => setCommandOpen(true)}>
            <span className="dock-code" aria-hidden="true">⌘</span>
            <span className="dock-label">Navigate</span>
            <span className="dock-shortcut">{navigator.platform.toLowerCase().includes('mac') ? '⌘K' : 'Ctrl K'}</span>
            <span className="dock-hover-label">Command palette</span>
          </button>
          <button className="dock-item dock-utility-item runtime-dock" aria-label="Open runtime context" onClick={() => setContextOpen(true)}>
            <span className={`runtime-dot ${runtimeOnline ? 'online' : ''}`} aria-hidden="true" />
            <span className="dock-label">{systemUnavailable ? 'Runtime unavailable' : system ? system.status : 'Checking runtime'}</span>
            <span className="dock-hover-label">Runtime context</span>
          </button>
          <button
            className="dock-item dock-collapse"
            aria-label={navigationCollapsed ? 'Expand navigation' : 'Collapse navigation'}
            onClick={() => {
              setNavigationCollapsed((value) => !value);
              setOpenGroup(null);
            }}
          >
            <span className="dock-code" aria-hidden="true">{navigationCollapsed ? '»' : '«'}</span>
            <span className="dock-label">Collapse navigation</span>
            <span className="dock-hover-label">{navigationCollapsed ? 'Expand navigation' : 'Collapse navigation'}</span>
          </button>
        </div>

        {navigationCollapsed && openGroup && (
          <div className="dock-flyout" role="menu" aria-label={`${shellGroupFor(openGroup).label} navigation`}>
            <div className="flyout-heading">
              <span>{shellGroupFor(openGroup).code}</span>
              <strong>{shellGroupFor(openGroup).label}</strong>
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

      {mobileNavigationOpen ? <button className="mobile-nav-scrim" aria-label="Close navigation" onClick={() => setMobileNavigationOpen(false)} /> : null}
      {navigationCollapsed && openGroup ? <button className="shell-scrim" aria-label="Close navigation flyout" onClick={() => setOpenGroup(null)} /> : null}

      <section className="shell-stage">
        <header className="shell-topbar">
          <div className="shell-location">
            <button className="mobile-menu-trigger" aria-label="Open navigation" onClick={() => setMobileNavigationOpen(true)}>☰</button>
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
                  <span><small>{shellGroupFor(route.group).label}</small><strong>{route.label}</strong></span>
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
