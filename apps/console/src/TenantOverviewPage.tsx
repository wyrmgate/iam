import { useEffect, useMemo, useState, type ReactNode } from 'react';
import type { CurrentAdministrativeAuthority } from './api';
import {
  getIamOperationsOverview,
  type IamOperationsOverview,
  type OverviewBreakdown,
  type OverviewMetric,
  type OverviewSourceSystem,
  type OverviewStatus,
} from './iamOperationsApi';
import { navigateConsole, visibleConsoleRoutes, type ConsoleRouteId } from './consoleNavigation';
import { getTenantContext, type TenantContext } from './tenantContextApi';
import {
  Box,
  Button,
  DataTable,
  DataTableRow,
  DonutChart,
  Page,
  SelectInput,
  StateIndicator,
  StatePanel,
  Surface,
  TableBody,
  TableCell,
  TableHead,
  TableRow,
  Typography,
  type StateIndicatorTone,
} from './ui';

type Props = {
  authority: CurrentAdministrativeAuthority | null;
};

type LoadState<T> =
  | { status: 'idle' }
  | { status: 'loading' }
  | { status: 'ready'; value: T }
  | { status: 'error'; error: unknown };

type IconName =
  | 'overview'
  | 'identities'
  | 'principals'
  | 'applications'
  | 'access'
  | 'governance'
  | 'sources'
  | 'administrators'
  | 'audit'
  | 'credentials'
  | 'connectors'
  | 'warning';

type TenantSection = {
  label: string;
  icon: IconName;
  path: string;
  routeId?: ConsoleRouteId;
};

type BreakdownLabel = {
  key: string;
  label: string;
};

const tenantSections: TenantSection[] = [
  { label: 'Overview', icon: 'overview', path: '/tenant' },
  { label: 'Identities', icon: 'identities', path: '/identities', routeId: 'identities' },
  { label: 'Principals', icon: 'principals', path: '/principals', routeId: 'principals' },
  { label: 'Applications', icon: 'applications', path: '/catalog', routeId: 'catalog' },
  { label: 'Access', icon: 'access', path: '/access', routeId: 'access' },
  { label: 'Governance', icon: 'governance', path: '/requests', routeId: 'requests' },
  { label: 'Credentials', icon: 'credentials', path: '/credentials', routeId: 'credentials' },
  { label: 'Sources & Integrations', icon: 'sources', path: '/integrations', routeId: 'integrations' },
  { label: 'Administration', icon: 'administrators', path: '/administration', routeId: 'administration' },
  { label: 'Audit', icon: 'audit', path: '/audit', routeId: 'audit' },
];

const identityTypeLabels: BreakdownLabel[] = [
  { key: 'PERSON', label: 'People' },
  { key: 'SERVICE', label: 'Service identities' },
  { key: 'WORKLOAD', label: 'Workload identities' },
];

const applicationOriginLabels: BreakdownLabel[] = [
  { key: 'TENANT_LOCAL', label: 'Tenant local' },
  { key: 'PLATFORM_SHARED', label: 'Platform shared' },
  { key: 'PLATFORM_INTERNAL', label: 'Platform internal' },
];

function LineIcon({ name, size = 20 }: { name: IconName; size?: number }) {
  let content: ReactNode;
  switch (name) {
    case 'overview':
      content = <><path d="M3 10.5 12 3l9 7.5" /><path d="M5.5 9.5V21h13V9.5" /><path d="M9.5 21v-6h5v6" /></>;
      break;
    case 'identities':
      content = <><circle cx="9" cy="8" r="3" /><path d="M3.5 20c.4-3.6 2.4-5.5 5.5-5.5s5.1 1.9 5.5 5.5" /><path d="M15.5 5.5a3 3 0 0 1 0 5.5" /><path d="M16.5 14.7c2.5.4 3.9 2.1 4 5.3" /></>;
      break;
    case 'principals':
      content = <><circle cx="8" cy="8" r="3" /><path d="M2.8 19c.5-3.5 2.2-5.3 5.2-5.3s4.7 1.8 5.2 5.3" /><rect x="14.5" y="7" width="6.5" height="9" rx="1.5" /><path d="M17.75 10v3" /></>;
      break;
    case 'applications':
      content = <><path d="m12 2.8 8 4.5v9.4l-8 4.5-8-4.5V7.3Z" /><path d="m4.4 7.5 7.6 4.3 7.6-4.3" /><path d="M12 11.8v9" /></>;
      break;
    case 'access':
      content = <><circle cx="8" cy="15" r="4" /><path d="m10.9 12.1 8-8" /><path d="m16 7 2 2" /><path d="m14 9 2 2" /></>;
      break;
    case 'governance':
      content = <><path d="M12 3 5 6v5c0 4.6 2.8 8.1 7 10 4.2-1.9 7-5.4 7-10V6Z" /><path d="m9 12 2 2 4-4" /></>;
      break;
    case 'sources':
      content = <><ellipse cx="12" cy="5.5" rx="7" ry="3" /><path d="M5 5.5v6c0 1.7 3.1 3 7 3s7-1.3 7-3v-6" /><path d="M5 11.5v6c0 1.7 3.1 3 7 3s7-1.3 7-3v-6" /></>;
      break;
    case 'administrators':
      content = <><circle cx="10" cy="8" r="3" /><path d="M4 20c.5-3.8 2.6-5.7 6-5.7 2 0 3.6.7 4.7 2" /><path d="m16.5 18 1.7 1.7 3.3-3.7" /></>;
      break;
    case 'audit':
      content = <><rect x="5" y="4" width="14" height="17" rx="2" /><path d="M9 4V2.8h6V4" /><path d="M8.5 9h7M8.5 13h7M8.5 17h4" /></>;
      break;
    case 'credentials':
      content = <><rect x="3" y="6" width="18" height="12" rx="2" /><path d="M7 10h5M7 14h3M16 10h1M16 14h1" /></>;
      break;
    case 'connectors':
      content = <><circle cx="12" cy="5" r="2.5" /><circle cx="5" cy="18" r="2.5" /><circle cx="19" cy="18" r="2.5" /><path d="M12 7.5v4M7.1 16.3l3.5-4.8M16.9 16.3l-3.5-4.8" /></>;
      break;
    case 'warning':
      content = <><path d="M12 3 2.8 20h18.4Z" /><path d="M12 9v5M12 17.2v.1" /></>;
      break;
  }
  return (
    <svg
      aria-hidden="true"
      width={size}
      height={size}
      viewBox="0 0 24 24"
      fill="none"
      stroke="currentColor"
      strokeWidth="1.8"
      strokeLinecap="round"
      strokeLinejoin="round"
      style={{ display: 'block', flex: '0 0 auto' }}
    >
      {content}
    </svg>
  );
}

function shortId(value?: string | null): string {
  if (!value) return '—';
  return value.length <= 28 ? value : `${value.slice(0, 12)}…${value.slice(-8)}`;
}

function humanize(value: string): string {
  return value
    .toLowerCase()
    .replaceAll('_', ' ')
    .replace(/(^|\s)\S/g, (letter) => letter.toUpperCase());
}

function formatDateTime(value?: string | null): string {
  if (!value) return '—';
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) return value;
  return date.toLocaleString([], {
    year: 'numeric',
    month: 'short',
    day: 'numeric',
    hour: '2-digit',
    minute: '2-digit',
  });
}

function statusTone(status: OverviewStatus): StateIndicatorTone {
  switch (status) {
    case 'AVAILABLE': return 'success';
    case 'NOT_AUTHORIZED': return 'warning';
    case 'UNAVAILABLE': return 'danger';
    default: return 'neutral';
  }
}

function statusLabel(status: OverviewStatus): string {
  if (status === 'NOT_AUTHORIZED') return 'Not authorized';
  if (status === 'UNAVAILABLE') return 'Unavailable';
  if (status === 'UNSUPPORTED') return 'Not supported';
  return 'Available';
}

function metricText(metric: OverviewMetric): string | null {
  return metric.status === 'AVAILABLE' && metric.value !== null ? metric.value.toLocaleString() : null;
}

function metricFromCount(status: OverviewStatus, value: number | null): OverviewMetric {
  return { status, value: status === 'AVAILABLE' ? value : null };
}

function sumMetrics(...metrics: OverviewMetric[]): OverviewMetric {
  const firstUnavailable = metrics.find((metric) => metric.status !== 'AVAILABLE' || metric.value === null);
  if (firstUnavailable) return { status: firstUnavailable.status, value: null };
  return { status: 'AVAILABLE', value: metrics.reduce((sum, metric) => sum + (metric.value ?? 0), 0) };
}

function errorMessage(error: unknown, fallback: string): string {
  return error instanceof Error ? error.message : fallback;
}

function toneColor(tone: StateIndicatorTone): string {
  if (tone === 'success') return 'success.main';
  if (tone === 'warning') return 'warning.main';
  if (tone === 'danger') return 'error.main';
  if (tone === 'info') return 'info.main';
  return 'text.secondary';
}

function Definition({ term, children }: { term: string; children: ReactNode }) {
  return (
    <Box component="div" sx={{ display: 'grid', gridTemplateColumns: { xs: '1fr', sm: '110px minmax(0, 1fr)' }, gap: { xs: 0.25, sm: 1.5 }, py: 0.75, alignItems: { sm: 'center' } }}>
      <Typography component="dt" variant="caption" color="text.secondary">{term}</Typography>
      <Typography component="dd" variant="body2" sx={{ m: 0, fontWeight: 500, overflowWrap: 'anywhere' }}>{children}</Typography>
    </Box>
  );
}

function SummaryTile({ label, icon, metric, detail, path, tone = 'info' }: {
  label: string;
  icon: IconName;
  metric?: OverviewMetric;
  detail: string;
  path?: string;
  tone?: StateIndicatorTone;
}) {
  const value = metric ? metricText(metric) : null;
  return (
    <Box
      component={path ? 'button' : 'div'}
      type={path ? 'button' : undefined}
      onClick={path ? () => navigateConsole(path) : undefined}
      sx={{
        appearance: 'none',
        textAlign: 'left',
        width: '100%',
        minWidth: 0,
        minHeight: 104,
        p: 2,
        border: '1px solid',
        borderColor: 'divider',
        borderRadius: 1,
        bgcolor: 'background.paper',
        color: 'text.primary',
        cursor: path ? 'pointer' : 'default',
        transition: 'border-color 120ms ease, background-color 120ms ease',
        '&:hover': path ? { borderColor: 'primary.main', bgcolor: 'action.hover' } : undefined,
        '&:focus-visible': path ? { outline: '2px solid', outlineColor: 'primary.main', outlineOffset: 2 } : undefined,
      }}
    >
      <Box sx={{ display: 'grid', gridTemplateColumns: '28px minmax(0, 1fr)', gap: 1.25, alignItems: 'start' }}>
        <Box sx={{ color: toneColor(tone), pt: 0.15 }}><LineIcon name={icon} size={24} /></Box>
        <Box sx={{ minWidth: 0 }}>
          <Typography variant="caption" color="text.secondary" sx={{ display: 'block', lineHeight: 1.25 }}>{label}</Typography>
          <Box sx={{ minHeight: 34, display: 'flex', alignItems: 'center', mt: 0.25 }}>
            {metric ? (
              value !== null
                ? <Typography sx={{ fontSize: 22, lineHeight: 1.2, fontWeight: 700 }}>{value}</Typography>
                : <StateIndicator label={statusLabel(metric.status)} tone={statusTone(metric.status)} />
            ) : <Typography sx={{ fontSize: 22, lineHeight: 1.2, fontWeight: 700 }}>—</Typography>}
          </Box>
          <Typography variant="caption" color="text.secondary" sx={{ display: 'block', mt: 0.5, lineHeight: 1.35 }}>{detail}</Typography>
        </Box>
      </Box>
    </Box>
  );
}

function breakdownRows(breakdown: OverviewBreakdown, labels: BreakdownLabel[]): Array<{ key: string; label: string; value: number }> {
  const values = new Map(breakdown.items.map((item) => [item.key, item.value]));
  const known = labels.map((item) => ({ ...item, value: values.get(item.key) ?? 0 }));
  const knownKeys = new Set(labels.map((item) => item.key));
  const additional = breakdown.items
    .filter((item) => !knownKeys.has(item.key))
    .map((item) => ({ key: item.key, label: humanize(item.key), value: item.value }));
  return [...known, ...additional];
}

function BreakdownSummary({ breakdown, total, totalLabel, labels, action, path }: {
  breakdown: OverviewBreakdown;
  total: OverviewMetric;
  totalLabel: string;
  labels: BreakdownLabel[];
  action: string;
  path?: string;
}) {
  if (breakdown.status !== 'AVAILABLE') {
    return (
      <Box sx={{ minHeight: 176, display: 'grid', alignContent: 'center', gap: 1.5 }}>
        <StateIndicator label={statusLabel(breakdown.status)} tone={statusTone(breakdown.status)} />
        <Typography variant="caption" color="text.secondary">This governed breakdown is not available to the current actor.</Typography>
        {path ? <Button variant="text" size="small" onClick={() => navigateConsole(path)} sx={{ justifySelf: 'start', px: 0 }}>{action}</Button> : null}
      </Box>
    );
  }

  const rows = breakdownRows(breakdown, labels);
  const fallbackTotal = rows.reduce((sum, row) => sum + row.value, 0);
  const totalValue = total.status === 'AVAILABLE' && total.value !== null ? total.value : fallbackTotal;

  return (
    <Box sx={{ minHeight: 210, display: 'grid', gap: 1.5, alignContent: 'start' }}>
      <DonutChart ariaLabel={`${totalLabel} distribution`} items={rows} totalLabel={totalLabel} totalValue={totalValue} size={148} />
      {path ? <Button variant="text" size="small" onClick={() => navigateConsole(path)} sx={{ justifySelf: 'start', px: 0 }}>{action}</Button> : null}
    </Box>
  );
}

function ownerContinuity(overview: IamOperationsOverview): { state: string; tone: StateIndicatorTone } {
  const owners = overview.tenantDashboard.tenantOwners;
  if (owners.status !== 'AVAILABLE' || owners.effectiveOwnerCount === null || owners.viableOwnerCount === null) {
    return { state: statusLabel(owners.status), tone: statusTone(owners.status) };
  }
  if (owners.viableOwnerCount === 0) {
    return { state: owners.effectiveOwnerCount === 0 ? 'No effective owner' : `0 / ${owners.effectiveOwnerCount} viable`, tone: 'danger' };
  }
  if (owners.viableOwnerCount < owners.effectiveOwnerCount) {
    return { state: `${owners.viableOwnerCount} / ${owners.effectiveOwnerCount} viable`, tone: 'warning' };
  }
  return { state: `${owners.viableOwnerCount} viable`, tone: 'success' };
}

function HealthRow({ label, state, tone, path }: { label: string; state: string; tone: StateIndicatorTone; path?: string }) {
  return (
    <Box sx={{ display: 'grid', gridTemplateColumns: 'minmax(0, 1fr) auto', gap: 1, alignItems: 'center', py: 0.8, borderBottom: '1px solid', borderColor: 'divider', '&:last-of-type': { borderBottom: 0 } }}>
      <Typography variant="body2">{label}</Typography>
      <Box sx={{ display: 'flex', alignItems: 'center', gap: 0.75 }}>
        <StateIndicator label={state} tone={tone} />
        {path ? <Button variant="text" size="small" onClick={() => navigateConsole(path)} sx={{ minWidth: 0, px: 0.5 }}>View</Button> : null}
      </Box>
    </Box>
  );
}

function RecentActivity({ overview, auditPath }: { overview: IamOperationsOverview; auditPath?: string }) {
  const activity = overview.tenantDashboard.recentActivity;
  if (activity.status !== 'AVAILABLE') return <StateIndicator label={statusLabel(activity.status)} tone={statusTone(activity.status)} />;
  if (activity.items.length === 0) return <StatePanel state="empty" title="No recent Audit activity">No Audit records were returned in the last 24 hours.</StatePanel>;
  const items = activity.items.slice(0, 5);
  const outcomeTone = (outcome: string): StateIndicatorTone => outcome === 'SUCCESS' ? 'success' : outcome === 'FAILURE' ? 'danger' : 'warning';
  return (
    <>
      <Box sx={{ display: { xs: 'grid', md: 'none' }, gap: 1 }}>
        {items.map((item) => (
          <Box key={item.id} sx={{ border: '1px solid', borderColor: 'divider', borderRadius: 1, p: 1.5, display: 'grid', gap: 0.6 }}>
            <Box sx={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', gap: 1 }}>
              <Typography variant="body2" sx={{ fontWeight: 600 }}>{humanize(item.actionType)}</Typography>
              <StateIndicator label={humanize(item.outcome)} tone={outcomeTone(item.outcome)} />
            </Box>
            <Typography variant="caption" color="text.secondary">{item.resourceDisplayLabel ?? humanize(item.resourceType)}</Typography>
            <Typography variant="caption" color="text.secondary">{item.actorDisplayLabel ?? shortId(item.actorId)} · {formatDateTime(item.occurredAt)}</Typography>
          </Box>
        ))}
      </Box>
      <Box sx={{ display: { xs: 'none', md: 'block' } }}>
        <DataTable ariaLabel="Recent Tenant Audit activity" minWidth={600}>
          <TableHead><TableRow><TableCell>Time</TableCell><TableCell>Actor</TableCell><TableCell>Event</TableCell><TableCell>Resource</TableCell></TableRow></TableHead>
          <TableBody>
            {items.map((item) => (
              <DataTableRow key={item.id}>
                <TableCell>{formatDateTime(item.occurredAt)}</TableCell>
                <TableCell>{item.actorDisplayLabel ?? shortId(item.actorId)}</TableCell>
                <TableCell><Typography variant="body2" sx={{ fontWeight: 500 }}>{humanize(item.actionType)}</Typography><StateIndicator label={humanize(item.outcome)} tone={outcomeTone(item.outcome)} /></TableCell>
                <TableCell>{item.resourceDisplayLabel ?? humanize(item.resourceType)}</TableCell>
              </DataTableRow>
            ))}
          </TableBody>
        </DataTable>
      </Box>
      {auditPath ? <Button variant="text" size="small" onClick={() => navigateConsole(auditPath)} sx={{ mt: 1.25, px: 0 }}>View audit</Button> : null}
    </>
  );
}

function sourceConnectorState(source: OverviewSourceSystem): { label: string; tone: StateIndicatorTone } {
  const operational = source.operational;
  if (operational.status !== 'AVAILABLE') return { label: statusLabel(operational.status), tone: statusTone(operational.status) };
  if ((operational.activeConnectorCount ?? 0) > 0) return { label: `${operational.activeConnectorCount} active`, tone: 'success' };
  if ((operational.activeBindingCount ?? 0) > 0) return { label: 'No active connector', tone: 'warning' };
  return { label: 'No active binding', tone: 'neutral' };
}

function SourcesSummary({ overview, integrationsPath }: { overview: IamOperationsOverview; integrationsPath?: string }) {
  const sources = overview.tenantDashboard.sources;
  if (sources.status !== 'AVAILABLE') return <StateIndicator label={statusLabel(sources.status)} tone={statusTone(sources.status)} />;
  if (sources.items.length === 0) return <StatePanel state="empty" title="No Identity sources">No SourceSystem records are configured for this Tenant.</StatePanel>;
  return (
    <>
      <Box sx={{ display: { xs: 'grid', md: 'none' }, gap: 1 }}>
        {sources.items.map((source) => {
          const connector = sourceConnectorState(source);
          return (
            <Box key={source.id} sx={{ border: '1px solid', borderColor: 'divider', borderRadius: 1, p: 1.5, display: 'grid', gap: 0.6 }}>
              <Box sx={{ display: 'flex', justifyContent: 'space-between', gap: 1 }}>
                <Box><Typography variant="body2" sx={{ fontWeight: 600 }}>{source.name}</Typography><Typography variant="caption" color="text.secondary">{source.code}</Typography></Box>
                <StateIndicator label={connector.label} tone={connector.tone} />
              </Box>
              <Typography variant="caption" color="text.secondary">{source.linkedIdentityCount.toLocaleString()} linked identities</Typography>
              <Typography variant="caption" color="text.secondary">Last complete: {formatDateTime(source.operational.lastCompleteAt)}</Typography>
            </Box>
          );
        })}
      </Box>
      <Box sx={{ display: { xs: 'none', md: 'block' } }}>
        <DataTable ariaLabel="Identity source operational summary" minWidth={720}>
          <TableHead><TableRow><TableCell>Source</TableCell><TableCell align="right">Linked identities</TableCell><TableCell>Connector posture</TableCell><TableCell>Last complete</TableCell></TableRow></TableHead>
          <TableBody>
            {sources.items.map((source) => {
              const connector = sourceConnectorState(source);
              return (
                <DataTableRow key={source.id}>
                  <TableCell><Typography variant="body2" sx={{ fontWeight: 500 }}>{source.name}</Typography><Typography variant="caption" color="text.secondary">{source.code}</Typography></TableCell>
                  <TableCell align="right">{source.linkedIdentityCount.toLocaleString()}</TableCell>
                  <TableCell><StateIndicator label={connector.label} tone={connector.tone} /></TableCell>
                  <TableCell>{formatDateTime(source.operational.lastCompleteAt)}</TableCell>
                </DataTableRow>
              );
            })}
          </TableBody>
        </DataTable>
      </Box>
      {integrationsPath ? <Button variant="text" size="small" onClick={() => navigateConsole(integrationsPath)} sx={{ mt: 1.25, px: 0 }}>View integrations</Button> : null}
    </>
  );
}

export function TenantOverviewPage({ authority }: Props) {
  const [tenant, setTenant] = useState<LoadState<TenantContext>>({ status: 'idle' });
  const [overview, setOverview] = useState<LoadState<IamOperationsOverview>>({ status: 'idle' });
  const [reloadKey, setReloadKey] = useState(0);

  useEffect(() => {
    if (!authority) {
      setTenant({ status: 'idle' });
      setOverview({ status: 'idle' });
      return;
    }
    const controller = new AbortController();
    setTenant({ status: 'loading' });
    setOverview({ status: 'loading' });
    getTenantContext(controller.signal)
      .then((value) => setTenant({ status: 'ready', value }))
      .catch((error: unknown) => {
        if (error instanceof DOMException && error.name === 'AbortError') return;
        setTenant({ status: 'error', error });
      });
    getIamOperationsOverview('24h', 'ALL', 'OLDEST', controller.signal)
      .then((value) => setOverview({ status: 'ready', value }))
      .catch((error: unknown) => {
        if (error instanceof DOMException && error.name === 'AbortError') return;
        setOverview({ status: 'error', error });
      });
    return () => controller.abort();
  }, [authority?.tenantId, reloadKey]);

  const allowedRoutes = useMemo(() => visibleConsoleRoutes(authority), [authority]);
  const allowedRouteIds = useMemo(() => new Set(allowedRoutes.map((route) => route.id)), [allowedRoutes]);
  const availableSections = useMemo(
    () => tenantSections.filter((section) => !section.routeId || allowedRouteIds.has(section.routeId)),
    [allowedRouteIds],
  );
  const routePath = (routeId: ConsoleRouteId, path: string): string | undefined => allowedRouteIds.has(routeId) ? path : undefined;

  const governanceIssues = useMemo(() => overview.status === 'ready'
    ? sumMetrics(overview.value.attention.pendingApprovals, overview.value.attention.pendingReviews)
    : undefined, [overview]);
  const sourceCount = useMemo(() => overview.status === 'ready'
    ? metricFromCount(overview.value.tenantDashboard.sources.status, overview.value.tenantDashboard.sources.totalCount)
    : undefined, [overview]);

  const tenantName = tenant.status === 'ready' ? tenant.value.displayName : 'Tenant Management';
  const initial = tenantName.trim().slice(0, 1).toUpperCase() || 'T';
  const loading = tenant.status === 'loading' || overview.status === 'loading';

  return (
    <Page width="wide" aria-labelledby="tenant-page-title" sx={{ gap: 0 }}>
      <Box component="nav" aria-label="Breadcrumb" sx={{ display: { xs: 'none', sm: 'flex' }, alignItems: 'center', gap: 0.8, color: 'text.secondary', mb: 1.25 }}>
        <Typography variant="caption">Administration</Typography><Typography variant="caption">›</Typography><Typography variant="caption">Tenant Management</Typography><Typography variant="caption">›</Typography><Typography variant="caption" color="text.primary">Overview</Typography>
      </Box>

      <Box sx={{ display: 'grid', gridTemplateColumns: { xs: '48px minmax(0, 1fr)', sm: '64px minmax(0, 1fr) auto' }, gap: { xs: 1.5, sm: 2 }, alignItems: 'center', mb: 2.25 }}>
        <Box sx={{ width: { xs: 44, sm: 56 }, height: { xs: 44, sm: 56 }, borderRadius: '50%', bgcolor: 'primary.main', color: 'primary.contrastText', display: 'grid', placeItems: 'center', fontSize: { xs: 20, sm: 24 }, fontWeight: 600 }}>{initial}</Box>
        <Box sx={{ minWidth: 0 }}>
          <Typography component="h1" id="tenant-page-title" sx={{ fontSize: { xs: 22, sm: 26 }, lineHeight: 1.15, fontWeight: 700, overflowWrap: 'anywhere' }}>{tenantName}</Typography>
          <Typography variant="body2" color="text.secondary" sx={{ mt: 0.45, overflowWrap: 'anywhere' }}>{tenant.status === 'ready' ? `Tenant ID: ${tenant.value.id}` : authority ? `Tenant ID: ${authority.tenantId}` : 'Resolving server-derived Tenant context'}</Typography>
          {tenant.status === 'ready' ? <Typography variant="caption" color="text.secondary" sx={{ display: { xs: 'none', sm: 'block' }, mt: 0.35 }}>Revision {tenant.value.revision} · Created {formatDateTime(tenant.value.createdAt)}</Typography> : null}
        </Box>
        <Box sx={{ display: { xs: 'none', sm: 'flex' }, alignItems: 'center', gap: 1, justifySelf: 'end' }}>
          <StateIndicator label="Current Tenant context" tone="info" />
        </Box>
      </Box>

      <Box sx={{ display: { xs: 'block', sm: 'none' }, mb: 1.5 }}>
        <SelectInput
          fullWidth
          label="Tenant area"
          value="/tenant"
          options={availableSections.map((section) => ({ value: section.path, label: section.label }))}
          onValueChange={(path) => { if (path && path !== '/tenant') navigateConsole(String(path)); }}
        />
      </Box>

      <Box component="nav" aria-label="Tenant management sections" sx={{ display: { xs: 'none', sm: 'flex' }, gap: 0.2, overflowX: 'auto', borderBottom: '1px solid', borderColor: 'divider', mb: 1.75 }}>
        {availableSections.map((section) => (
          <Button
            key={section.path}
            variant="text"
            size="small"
            startIcon={<LineIcon name={section.icon} size={16} />}
            onClick={() => navigateConsole(section.path)}
            aria-current={section.path === '/tenant' ? 'page' : undefined}
            sx={{
              flex: '0 0 auto',
              minHeight: 42,
              px: 1.15,
              borderRadius: 0,
              borderBottom: '2px solid',
              borderBottomColor: section.path === '/tenant' ? 'primary.main' : 'transparent',
              color: section.path === '/tenant' ? 'primary.main' : 'text.secondary',
              fontWeight: section.path === '/tenant' ? 700 : 500,
              fontSize: 12,
            }}
          >{section.label}</Button>
        ))}
      </Box>

      {!authority || loading ? <StatePanel state="loading" title="Loading Tenant Management">Reading canonical Tenant metadata and tenant-scoped IAM projections.</StatePanel> : null}
      {tenant.status === 'error' ? <StatePanel state="error" title="Tenant information is unavailable" action={<Button variant="outlined" onClick={() => setReloadKey((value) => value + 1)}>Retry</Button>}>{errorMessage(tenant.error, 'Wyrmgate could not read the current Tenant metadata.')}</StatePanel> : null}
      {overview.status === 'error' ? <StatePanel state="error" title="Tenant operations are unavailable" action={<Button variant="outlined" onClick={() => setReloadKey((value) => value + 1)}>Retry</Button>}>{errorMessage(overview.error, 'Wyrmgate could not read the Tenant operations projection.')}</StatePanel> : null}

      {overview.status === 'ready' ? (() => {
        const continuity = ownerContinuity(overview.value);
        const adminPath = routePath('administration', '/administration');
        const requestsPath = routePath('requests', '/requests');
        const reviewsPath = routePath('reviews', '/reviews');
        const integrationsPath = routePath('integrations', '/integrations');
        const auditPath = routePath('audit', '/audit');
        return (
          <Box sx={{ display: 'grid', gap: { xs: 1.25, sm: 1.5 } }}>
            <Box sx={{ display: 'grid', gridTemplateColumns: { xs: '1fr', sm: 'repeat(2, minmax(0, 1fr))', lg: 'repeat(3, minmax(0, 1fr))', xl: 'repeat(6, minmax(0, 1fr))' }, gap: 1.5 }}>
              <SummaryTile label="Identities" icon="identities" metric={overview.value.environment.activeIdentities} detail="Active governed subjects" path={routePath('identities', '/identities')} tone="info" />
              <SummaryTile label="Applications" icon="applications" metric={overview.value.environment.applications} detail="Active tenant-bound Catalog applications" path={routePath('catalog', '/catalog')} tone="info" />
              <SummaryTile label="Active Access" icon="access" metric={overview.value.environment.activeAccessAssignments} detail="Authoritative AccessAssignments" path={routePath('access', '/access')} tone="warning" />
              <SummaryTile label="Governance Issues" icon="warning" metric={governanceIssues} detail="Pending approvals + reviews" path={requestsPath} tone="danger" />
              <SummaryTile label="Sources" icon="sources" metric={sourceCount} detail="Configured Identity SourceSystems" path={integrationsPath} tone="info" />
              <SummaryTile label="Connectors" icon="connectors" metric={overview.value.environment.activeConnectors} detail="Active connector instances" path={integrationsPath} tone="info" />
            </Box>

            <Box sx={{ display: 'grid', gridTemplateColumns: { xs: '1fr', lg: 'minmax(0, 0.9fr) minmax(0, 1.1fr)' }, gap: 1.5 }}>
              <Surface title="Tenant information" description="Canonical Platform-owned metadata for the server-resolved current Tenant." sx={{ height: '100%', p: 2 }}>
                {tenant.status === 'ready' ? <Box component="dl" sx={{ m: 0 }}>
                  <Definition term="Name">{tenant.value.displayName}</Definition>
                  <Definition term="Tenant ID">{tenant.value.id}</Definition>
                  <Definition term="Revision">{tenant.value.revision}</Definition>
                  <Definition term="Created">{formatDateTime(tenant.value.createdAt)}</Definition>
                  <Definition term="Updated">{formatDateTime(tenant.value.updatedAt)}</Definition>
                </Box> : <StateIndicator label="Unavailable" tone="danger" />}
              </Surface>

              <Surface title="Administration & operations" description="Explicit authority and operational signals; this is not a synthetic Tenant health score." sx={{ height: '100%', p: 2 }}>
                <HealthRow label="Tenant Owner continuity" state={continuity.state} tone={continuity.tone} path={adminPath} />
                <HealthRow label="Administrative actor" state={authority?.administrativelyEligible ? 'Eligible' : 'Not eligible'} tone={authority?.administrativelyEligible ? 'success' : 'danger'} path={adminPath} />
                <HealthRow label="Pending approvals" state={metricText(overview.value.attention.pendingApprovals) ?? statusLabel(overview.value.attention.pendingApprovals.status)} tone={overview.value.attention.pendingApprovals.status === 'AVAILABLE' && (overview.value.attention.pendingApprovals.value ?? 0) === 0 ? 'success' : 'warning'} path={requestsPath} />
                <HealthRow label="Pending reviews" state={metricText(overview.value.attention.pendingReviews) ?? statusLabel(overview.value.attention.pendingReviews.status)} tone={overview.value.attention.pendingReviews.status === 'AVAILABLE' && (overview.value.attention.pendingReviews.value ?? 0) === 0 ? 'success' : 'warning'} path={reviewsPath} />
                <HealthRow label="Provisioning failures" state={metricText(overview.value.attention.finalProvisioningFailures) ?? statusLabel(overview.value.attention.finalProvisioningFailures.status)} tone={overview.value.attention.finalProvisioningFailures.status === 'AVAILABLE' && (overview.value.attention.finalProvisioningFailures.value ?? 0) === 0 ? 'success' : 'warning'} path={integrationsPath} />
                <HealthRow label="Failed reconciliations" state={metricText(overview.value.attention.failedReconciliations) ?? statusLabel(overview.value.attention.failedReconciliations.status)} tone={overview.value.attention.failedReconciliations.status === 'AVAILABLE' && (overview.value.attention.failedReconciliations.value ?? 0) === 0 ? 'success' : 'warning'} path={integrationsPath} />
              </Surface>
            </Box>

            <Box sx={{ display: 'grid', gridTemplateColumns: { xs: '1fr', lg: 'repeat(2, minmax(0, 1fr))' }, gap: 1.5 }}>
              <Surface title="Identities by type" sx={{ height: '100%', p: 2 }}>
                <BreakdownSummary breakdown={overview.value.tenantDashboard.identityTypes} total={overview.value.environment.activeIdentities} totalLabel="Active identities" labels={identityTypeLabels} action="View identities" path={routePath('identities', '/identities')} />
              </Surface>
              <Surface title="Applications by origin" sx={{ height: '100%', p: 2 }}>
                <BreakdownSummary breakdown={overview.value.tenantDashboard.applicationOrigins} total={overview.value.environment.applications} totalLabel="Active applications" labels={applicationOriginLabels} action="View applications" path={routePath('catalog', '/catalog')} />
              </Surface>
            </Box>

            <Box sx={{ display: 'grid', gridTemplateColumns: { xs: '1fr', xl: 'minmax(0, 1fr) minmax(0, 1fr)' }, gap: 1.5 }}>
              <Surface title="Recent activity" description="Bounded Audit-owned activity for this Tenant." sx={{ p: 2 }}>
                <RecentActivity overview={overview.value} auditPath={auditPath} />
              </Surface>
              <Surface title="Identity sources" description="Identity-owned SourceSystems with separate Integration operational posture." sx={{ p: 2 }}>
                <SourcesSummary overview={overview.value} integrationsPath={integrationsPath} />
              </Surface>
            </Box>

            <StatePanel state="empty" title="Access by application is intentionally not shown">
              AccessAssignment remains authoritative business access intent and EffectiveAccess remains a projection. The current Console contract does not expose an exact bounded per-application Access aggregation, so Tenant Management does not approximate it from truncated Catalog data.
            </StatePanel>
          </Box>
        );
      })() : null}
    </Page>
  );
}
