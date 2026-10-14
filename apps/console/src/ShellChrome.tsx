import { useEffect, useMemo, useState, type KeyboardEvent, type ReactNode } from 'react';
import {
  getCurrentAdministrativeAuthority,
  getSystemInfo,
  type CurrentAdministrativeAuthority,
  type SystemInfo,
} from './api';
import {
  activeRouteFor,
  navigateConsole,
  shellGroupFor,
  shellGroups,
  visibleConsoleRoutes,
} from './consoleNavigation';
import { useConsolePathname } from './consoleLocation';

type ShellChromeProps = {
  children: ReactNode;
  onSignOut?: () => Promise<void> | void;
  signOutBusy?: boolean;
};

function shortId(value: string | undefined | null): string {
  if (!value) return 'Unavailable';
  if (value.length <= 18) return value;
  return `${value.slice(0, 8)}…${value.slice(-6)}`;
}

function actorInitials(value: string | undefined | null): string {
  if (!value) return '—';
  return value.slice(0, 2).toUpperCase();
}

export function ShellChrome({ children, onSignOut, signOutBusy = false }: ShellChromeProps) {
  const pathname = useConsolePathname();
  const [authority, setAuthority] = useState<CurrentAdministrativeAuthority | null>(null);
  const [authorityUnavailable, setAuthorityUnavailable] = useState(false);
  const [system, setSystem] = useState<SystemInfo | null>(null);
  const [systemUnavailable, setSystemUnavailable] = useState(false);
  const [mobileNavigationOpen, setMobileNavigationOpen] = useState(false);
  const [commandOpen, setCommandOpen] = useState(false);
  const [query, setQuery] = useState('');
  const [selectedCommand, setSelectedCommand] = useState(0);
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
      } else if (event.key === 'Escape') {
        setCommandOpen(false);
        setActorOpen(false);
        setMobileNavigationOpen(false);
      }
    };
    window.addEventListener('keydown', onKeyDown);
    return () => window.removeEventListener('keydown', onKeyDown);
  }, []);

  useEffect(() => {
    setActorOpen(false);
    setMobileNavigationOpen(false);
  }, [pathname]);

  useEffect(() => {
    setSelectedCommand(0);
  }, [query, commandOpen]);

  function openRoute(path: string) {
    navigateConsole(path);
    setCommandOpen(false);
    setQuery('');
    setMobileNavigationOpen(false);
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

  const currentGroup = currentRoute ? shellGroupFor(currentRoute.group) : null;
  const currentGroupLabel = currentGroup?.label ?? 'Console';
  const currentRouteLabel = currentRoute?.label ?? 'Page not found';
  const runtimeOnline = Boolean(system && system.status.toLowerCase() !== 'unavailable');

  const actorMenu = actorOpen ? (
    <div className="actor-menu">
      <p className="menu-kicker">Server-derived session context</p>
      <strong>{shortId(authority?.actorIdentityId)}</strong>
      <span>{authority ? `${authority.authorities.length} effective administrative authority source${authority.authorities.length === 1 ? '' : 's'}` : 'Authority projection unavailable'}</span>
      <button onClick={() => openRoute('/administration')}>View my administrative authority</button>
      {onSignOut ? <button disabled={signOutBusy} onClick={() => void onSignOut()}>{signOutBusy ? 'Signing out…' : 'Sign out'}</button> : <span className="external-session-note">Session managed externally</span>}
    </div>
  ) : null;

  return (
    <div className="product-shell">
      <aside className={`navigation-dock ${mobileNavigationOpen ? 'mobile-open' : ''}`} aria-label="Product navigation">
        <button className="dock-brand" aria-label="Wyrmgate overview" onClick={() => openRoute('/')}>
          <span className="dock-brand-glyph" aria-hidden="true">W</span>
          <span className="dock-brand-copy"><strong>Wyrmgate</strong><small>IAM operator console</small></span>
        </button>

        <nav className="dock-primary" aria-label="Primary navigation">
          {allowedGroups.map((group) => {
            const destinations = allowedRoutes.filter((route) => route.group === group.id);
            const active = currentRoute?.group === group.id;
            return (
              <section className={`dock-group ${active ? 'active' : ''}`} key={group.id} aria-label={group.label}>
                <div className="dock-group-heading">
                  <span className="dock-code" aria-hidden="true">{group.code}</span>
                  <span>{group.label}</span>
                </div>
                <div className="dock-subroutes">
                  {destinations.map((route) => (
                    <button
                      key={route.path}
                      className={currentRoute?.id === route.id ? 'active' : ''}
                      aria-current={currentRoute?.id === route.id ? 'page' : undefined}
                      onClick={() => openRoute(route.path)}
                    >
                      <span>{route.label}</span>
                    </button>
                  ))}
                </div>
              </section>
            );
          })}
        </nav>

        <div className="dock-utility">
          <button className="dock-command" onClick={() => setCommandOpen(true)}>
            <span>Quick navigation</span>
            <kbd>{navigator.platform.toLowerCase().includes('mac') ? '⌘K' : 'Ctrl K'}</kbd>
          </button>
          <div className="dock-runtime" aria-label="Runtime status">
            <span className={`runtime-dot ${runtimeOnline ? 'online' : ''}`} aria-hidden="true" />
            <span>{systemUnavailable ? 'Runtime unavailable' : system ? system.status : 'Checking runtime'}</span>
          </div>
          <div className="dock-session-wrap">
            <button className="dock-session" aria-expanded={actorOpen} onClick={() => setActorOpen((value) => !value)}>
              <span className="dock-session-mark" aria-hidden="true">{actorInitials(authority?.actorIdentityId)}</span>
              <span className="dock-session-copy">
                <strong>{authorityUnavailable ? 'Actor unavailable' : shortId(authority?.actorIdentityId)}</strong>
                <small>{authority ? `Tenant ${shortId(authority.tenantId)}` : 'Resolving tenant…'}</small>
              </span>
              <span aria-hidden="true">⌄</span>
            </button>
            {actorMenu}
          </div>
        </div>
      </aside>

      {mobileNavigationOpen ? <button className="mobile-nav-scrim" aria-label="Close navigation" onClick={() => setMobileNavigationOpen(false)} /> : null}

      <section className="shell-stage">
        <header className="shell-topbar">
          <div className="shell-location">
            <button className="mobile-menu-trigger" aria-label="Open navigation" onClick={() => setMobileNavigationOpen(true)}>☰</button>
            <div className="shell-location-copy">
              <span className="shell-eyebrow">{currentGroupLabel}</span>
              <strong>{currentRouteLabel}</strong>
            </div>
          </div>

          <div className="shell-actions">
            <div className="shell-context-summary" aria-label="Current server-derived context">
              <span><small>Tenant</small>{authority ? shortId(authority.tenantId) : authorityUnavailable ? 'Unavailable' : 'Resolving…'}</span>
              <span className="runtime-summary"><small>Runtime</small><span className={`runtime-dot ${runtimeOnline ? 'online' : ''}`} aria-hidden="true" />{systemUnavailable ? 'Unavailable' : system ? system.status : 'Checking…'}</span>
            </div>
            <button className="command-trigger" onClick={() => setCommandOpen(true)}>
              <span>Navigate</span><kbd>{navigator.platform.toLowerCase().includes('mac') ? '⌘K' : 'Ctrl K'}</kbd>
            </button>
            <div className="actor-control">
              <button className="actor-trigger" aria-expanded={actorOpen} onClick={() => setActorOpen((value) => !value)}>
                <span className="actor-mark" aria-hidden="true">{actorInitials(authority?.actorIdentityId)}</span>
                <span className="actor-copy"><strong>Current actor</strong><small>{shortId(authority?.actorIdentityId)}</small></span>
                <span aria-hidden="true">⌄</span>
              </button>
              {actorMenu}
            </div>
          </div>
        </header>

        <main className="shell-workspace">{children}</main>
      </section>

      {commandOpen ? (
        <div className="command-backdrop" role="presentation" onMouseDown={(event) => { if (event.currentTarget === event.target) setCommandOpen(false); }}>
          <section className="command-palette" role="dialog" aria-modal="true" aria-label="Wyrmgate navigation">
            <div className="command-input-row">
              <span aria-hidden="true">⌕</span>
              <input
                autoFocus
                value={query}
                onChange={(event) => setQuery(event.target.value)}
                onKeyDown={commandKeyDown}
                placeholder="Go to a Wyrmgate workspace…"
                aria-label="Search navigation destinations"
              />
              <kbd>Esc</kbd>
            </div>
            <div className="command-results">
              <div className="command-section-label">Workspaces</div>
              {paletteRoutes.length === 0 ? <div className="command-empty">No available destination matches that query.</div> : null}
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
      ) : null}
    </div>
  );
}
