import { useEffect, useMemo, useState, type ReactNode } from 'react';
import type { CurrentAdministrativeAuthority } from './api';
import {
  getIamOperationsOverview,
  type IamOperationsOverview,
  type OverviewMetric,
  type OverviewStatus,
} from './iamOperationsApi';
import { navigateConsole } from './consoleNavigation';
import { getTenantContext, type TenantContext } from './tenantContextApi';
import {
  Box,
  Button,
  DataTable,
  DataTableRow,
  Page,
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
  | 'applications'
  | 'access'
  | 'organizations'
  | 'governance'
  | 'sources'
  | 'administrators'
  | 'audit'
  | 'settings'
  | 'connectors'
  | 'check'
  | 'warning'
  | 'edit';

type TenantTab = {
  label: string;
  icon: IconName;
  path?: string;
  disabledReason?: string;
};

const tenantTabs: TenantTab[] = [
  { label: 'Overview', icon: 'overview', path: '/tenant' },
  { label: 'Identities', icon: 'identities', path: '/identities' },
  { label: 'Applications', icon: 'applications', path: '/catalog' },
  { label: 'Access', icon: 'access', path: '/access' },
  { label: 'Organizations', icon: 'organizations', disabledReason: 'Organization workspace is not exposed by the current Console contract.' },
  { label: 'Governance', icon: 'governance', path: '/requests' },
  { label: 'Sources & Integrations', icon: 'sources', path: '/integrations' },
  { label: 'Administrators', icon: 'administrators', path: '/administration' },
  { label: 'Audit', icon: 'audit', path: '/audit' },
  { label: 'Settings', icon: 'settings', disabledReason: 'Tenant settings are not exposed by the current Console contract.' },
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
    case 'applications':
      content = <><path d="m12 2.8 8 4.5v9.4l-8 4.5-8-4.5V7.3Z" /><path d="m4.4 7.5 7.6 4.3 7.6-4.3" /><path d="M12 11.8v9" /></>;
      break;
    case 'access':
      content = <><circle cx="8" cy="15" r="4" /><path d="m10.9 12.1 8-8" /><path d="m16 7 2 2" /><path d="m14 9 2 2" /></>;
      break;
    case 'organizations':
      content = <><path d="M4 21V6h10v15" /><path d="M14 10h6v11" /><path d="M7 9h1M10 9h1M7 13h1M10 13h1M7 17h1M10 17h1M17 13h1M17 17h1" /></>;
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
    case 'settings':
      content = <><circle cx="12" cy="12" r="3" /><path d="M19 13.5v-3l-2-.7a7 7 0 0 0-.7-1.7l.9-1.9-2.1-2.1-1.9.9a7 7 0 0 0-1.7-.7L10.5 2h-3l-.7 2.3a7 7 0 0 0-1.7.7l-1.9-.9-2.1 2.1.9 1.9a7 7 0 0 0-.7 1.7l-2 .7v3l2 .7a7 7 0 0 0 .7 1.7l-.9 1.9 2.1 2.1 1.9-.9a7 7 0 0 0 1.7.7l.7 2.3h3l.7-2.3a7 7 0 0 0 1.7-.7l1.9.9 2.1-2.1-.9-1.9a7 7 0 0 0 .7-1.7Z" transform="translate(1.5 0) scale(.88)" /></>;
      break;
    case 'connectors':
      content = <><circle cx="12" cy="5" r="2.5" /><circle cx="5" cy="18" r="2.5" /><circle cx="19" cy="18" r="2.5" /><path d="M12 7.5v4M7.1 16.3l3.5-4.8M16.9 16.3l-3.5-4.8" /></>;
      break;
    case 'check':
      content = <><circle cx="12" cy="12" r="9" /><path d="m8 12 2.6 2.6L16.5 9" /></>;
      break;
    case 'warning':
      content = <><path d="M12 3 2.8 20h18.4Z" /><path d="M12 9v5M12 17.2v.1" /></>;
      break;
    case 'edit':
      content = <><path d="m4 20 4.2-1 10.9-10.9-3.2-3.2L5 15.8Z" /><path d="m14.8 6 3.2 3.2" /></>;
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

function formatTime(value?: string | null): string {
  if (!value) return '—';
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) return '—';
  return date.toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' });
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
    <Box component="div" sx={{ display: 'grid', gridTemplateColumns: '104px minmax(0, 1fr)', gap: 1.5, py: 0.65, alignItems: 'center' }}>
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

function HealthSignalRow({ label, state, tone, path }: {
  label: string;
  state: string;
  tone: StateIndicatorTone;
  path?: string;
}) {
  const icon = tone === 'success' ? 'check' : 'warning';
  return (
    <Box sx={{ display: 'grid', gridTemplateColumns: '24px minmax(0, 1fr) auto', gap: 1, alignItems: 'center', minHeight: 39, py: 0.7, borderBottom: '1px solid', borderColor: 'divider', '&:last-child': { borderBottom: 0 } }}>
      <Box sx={{ color: toneColor(tone) }}><LineIcon name={icon} size={19} /></Box>
      <Typography variant="body2" sx={{ minWidth: 0, fontWeight: 500 }}>{label}</Typography>
      <Box sx={{ display: 'flex', alignItems: 'center', gap: 0.75 }}>
        <Typography variant="caption" color="text.secondary" sx={{ whiteSpace: 'nowrap' }}>{state}</Typography>
        {path ? <Button variant="text" size="small" onClick={() => navigateConsole(path)} sx={{ minWidth: 0, px: 0.5 }}>View</Button> : null}
      </Box>
    </Box>
  );
}

function BreakdownPlaceholder({ total, totalLabel, message, action, path }: {
  total: OverviewMetric;
  totalLabel: string;
  message: string;
  action: string;
  path: string;
}) {
  const value = metricText(total);
  return (
    <Box sx={{ minHeight: 176, display: 'grid', gridTemplateColumns: { xs: '1fr', sm: '146px minmax(0, 1fr)' }, gap: 2.25, alignItems: 'center' }}>
      <Box
        sx={{
          width: 132,
          height: 132,
          borderRadius: '50%',
          border: '14px solid',
          borderColor: 'action.hover',
          display: 'grid',
          placeItems: 'center',
          justifySelf: { xs: 'start', sm: 'center' },
          boxSizing: 'border-box',
        }}
      >
        <Box sx={{ textAlign: 'center', px: 1 }}>
          {value !== null
            ? <Typography sx={{ fontSize: 20, lineHeight: 1.2, fontWeight: 700 }}>{value}</Typography>
            : <StateIndicator label={statusLabel(total.status)} tone={statusTone(total.status)} />}
          <Typography variant="caption" color="text.secondary" sx={{ display: 'block', mt: 0.25 }}>{totalLabel}</Typography>
        </Box>
      </Box>
      <Box sx={{ minWidth: 0 }}>
        <Box sx={{ display: 'flex', alignItems: 'center', gap: 1, mb: 0.75 }}>
          <Box sx={{ width: 8, height: 8, borderRadius: '50%', bgcolor: 'action.disabled' }} />
          <Typography variant="body2" sx={{ fontWeight: 600 }}>Breakdown not exposed</Typography>
        </Box>
        <Typography variant="caption" color="text.secondary" sx={{ display: 'block', lineHeight: 1.45, mb: 1 }}>{message}</Typography>
        <Button variant="text" size="small" onClick={() => navigateConsole(path)} sx={{ px: 0 }}>{action}</Button>
      </Box>
    </Box>
  );
}

function AccessBreakdownPlaceholder({ total }: { total: OverviewMetric }) {
  const value = metricText(total);
  return (
    <Box sx={{ minHeight: 176, display: 'grid', alignContent: 'center', gap: 2 }}>
      <Box>
        {value !== null
          ? <Typography sx={{ fontSize: 24, fontWeight: 700 }}>{value}</Typography>
          : <StateIndicator label={statusLabel(total.status)} tone={statusTone(total.status)} />}
        <Typography variant="caption" color="text.secondary">Authoritative active assignments</Typography>
      </Box>
      <Box sx={{ display: 'grid', gap: 0.8 }}>
        <Typography variant="body2" sx={{ fontWeight: 600 }}>Per-application aggregation not exposed</Typography>
        <Box sx={{ height: 10, borderRadius: 5, bgcolor: 'action.hover', overflow: 'hidden' }}><Box sx={{ width: '42%', height: '100%', bgcolor: 'action.disabledBackground' }} /></Box>
        <Typography variant="caption" color="text.secondary">AccessAssignment remains authoritative business intent and EffectiveAccess remains a projection.</Typography>
      </Box>
      <Button variant="text" size="small" onClick={() => navigateConsole('/access')} sx={{ justifySelf: 'start', px: 0 }}>View access</Button>
    </Box>
  );
}

function RecentActivity({ overview }: { overview: IamOperationsOverview }) {
  if (overview.securityEvidence.status !== 'AVAILABLE') {
    return <StateIndicator label={statusLabel(overview.securityEvidence.status)} tone={statusTone(overview.securityEvidence.status)} />;
  }
  if (overview.securityEvidence.recent.length === 0) {
    return <StatePanel state="empty" title="No recent matching evidence">No denied or failed Audit records were returned in the last 24 hours.</StatePanel>;
  }
  return (
    <DataTable ariaLabel="Recent Tenant Audit activity" minWidth={430}>
      <TableHead><TableRow><TableCell>Time</TableCell><TableCell>Event</TableCell><TableCell>Resource</TableCell></TableRow></TableHead>
      <TableBody>
        {overview.securityEvidence.recent.slice(0, 5).map((item) => (
          <DataTableRow key={item.id}>
            <TableCell>{formatTime(item.occurredAt)}</TableCell>
            <TableCell><Typography variant="body2" sx={{ fontWeight: 500 }}>{item.actionType}</Typography><Typography variant="caption" color="text.secondary">{item.outcome}</Typography></TableCell>
            <TableCell>{item.resourceType}</TableCell>
          </DataTableRow>
        ))}
      </TableBody>
    </DataTable>
  );
}

function WorkItems({ overview }: { overview: IamOperationsOverview }) {
  if (overview.myWork.status !== 'AVAILABLE') {
    return <StateIndicator label={statusLabel(overview.myWork.status)} tone={statusTone(overview.myWork.status)} />;
  }
  if (overview.myWork.items.length === 0) {
    return <StatePanel state="empty" title="No assigned governance items">There are no pending approvals or reviews assigned to the current actor.</StatePanel>;
  }
  return (
    <DataTable ariaLabel="Open governance items" minWidth={680}>
      <TableHead><TableRow><TableCell>Type</TableCell><TableCell>Subject</TableCell><TableCell>Status</TableCell><TableCell>Created At</TableCell><TableCell align="right">Action</TableCell></TableRow></TableHead>
      <TableBody>
        {overview.myWork.items.slice(0, 5).map((item) => (
          <DataTableRow key={`${item.kind}-${item.id}`}>
            <TableCell>{item.kind === 'APPROVAL' ? 'Approval' : 'Review'}</TableCell>
            <TableCell><Typography variant="body2" sx={{ fontWeight: 500 }}>{item.subjectKind.replaceAll('_', ' ')}</Typography><Typography variant="caption" color="text.secondary">{shortId(item.subjectId)}</Typography></TableCell>
            <TableCell><StateIndicator label={item.kind === 'APPROVAL' ? `Stage ${item.stageOrdinal + 1}` : 'Pending'} tone="warning" /></TableCell>
            <TableCell>{formatDateTime(item.createdAt)}</TableCell>
            <TableCell align="right"><Button variant="text" size="small" onClick={() => navigateConsole(item.kind === 'APPROVAL' ? `/requests?view=approvals&approvalCaseId=${encodeURIComponent(item.id)}` : `/reviews?reviewItemId=${encodeURIComponent(item.id)}`)}>Open</Button></TableCell>
          </DataTableRow>
        ))}
      </TableBody>
    </DataTable>
  );
}

function IdentitySourcesSummary({ overview }: { overview: IamOperationsOverview }) {
  const connectorValue = metricText(overview.environment.activeConnectors);
  const reconciliation = overview.reconciliation;
  return (
    <>
      <DataTable ariaLabel="Identity source operational summary" minWidth={520}>
        <TableHead><TableRow><TableCell>Signal</TableCell><TableCell>Status</TableCell><TableCell>Value</TableCell><TableCell>As Of</TableCell></TableRow></TableHead>
        <TableBody>
          <DataTableRow>
            <TableCell><Typography variant="body2" sx={{ fontWeight: 500 }}>Connector inventory</Typography><Typography variant="caption" color="text.secondary">Integration</Typography></TableCell>
            <TableCell><StateIndicator label={overview.environment.activeConnectors.status === 'AVAILABLE' ? 'Available' : statusLabel(overview.environment.activeConnectors.status)} tone={statusTone(overview.environment.activeConnectors.status)} /></TableCell>
            <TableCell>{connectorValue ?? '—'}</TableCell>
            <TableCell>{formatDateTime(overview.environment.asOf)}</TableCell>
          </DataTableRow>
          <DataTableRow>
            <TableCell><Typography variant="body2" sx={{ fontWeight: 500 }}>Reconciliation</Typography><Typography variant="caption" color="text.secondary">Latest effectively complete run</Typography></TableCell>
            <TableCell><StateIndicator label={statusLabel(reconciliation.status)} tone={statusTone(reconciliation.status)} /></TableCell>
            <TableCell>{reconciliation.status === 'AVAILABLE' && reconciliation.summary ? `${reconciliation.summary.failedInWindow.toLocaleString()} failed · 24h` : '—'}</TableCell>
            <TableCell>{reconciliation.status === 'AVAILABLE' ? formatDateTime(reconciliation.summary?.lastCompleteAt) : '—'}</TableCell>
          </DataTableRow>
        </TableBody>
      </DataTable>
      <Typography variant="caption" color="text.secondary" sx={{ display: 'block', mt: 1 }}>Source list not exposed. The current projection does not provide fake HR system, directory, or manual-source rows.</Typography>
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

  const governanceIssues = useMemo(() => overview.status === 'ready'
    ? sumMetrics(overview.value.attention.pendingApprovals, overview.value.attention.pendingReviews)
    : undefined, [overview]);

  const tenantName = tenant.status === 'ready' ? tenant.value.displayName : 'Current Tenant';
  const initial = tenantName.trim().slice(0, 1).toUpperCase() || 'T';
  const loading = tenant.status === 'loading' || overview.status === 'loading';

  return (
    <Page width="wide" aria-labelledby="tenant-page-title" sx={{ gap: 0 }}>
      <Box component="nav" aria-label="Breadcrumb" sx={{ display: 'flex', alignItems: 'center', gap: 0.8, color: 'text.secondary', mb: 1.25 }}>
        <Typography variant="caption">Tenants</Typography><Typography variant="caption">›</Typography><Typography variant="caption">{tenantName}</Typography><Typography variant="caption">›</Typography><Typography variant="caption" color="text.primary">Overview</Typography>
      </Box>

      <Box sx={{ display: 'grid', gridTemplateColumns: { xs: '1fr', sm: '64px minmax(0, 1fr) auto' }, gap: 2, alignItems: 'center', mb: 2.25 }}>
        <Box sx={{ width: 56, height: 56, borderRadius: '50%', bgcolor: 'primary.main', color: 'primary.contrastText', display: 'grid', placeItems: 'center', fontSize: 24, fontWeight: 500 }}>{initial}</Box>
        <Box sx={{ minWidth: 0 }}>
          <Box sx={{ display: 'flex', alignItems: 'center', gap: 1, flexWrap: 'wrap' }}>
            <Typography component="h1" id="tenant-page-title" sx={{ fontSize: 26, lineHeight: 1.15, fontWeight: 700 }}>{tenantName}</Typography>
          </Box>
          <Typography variant="body2" color="text.secondary" sx={{ mt: 0.45 }}>{tenant.status === 'ready' ? `Tenant ID: ${tenant.value.id}` : authority ? `Tenant ID: ${authority.tenantId}` : 'Resolving Tenant context'}</Typography>
          {tenant.status === 'ready' ? <Typography variant="caption" color="text.secondary" sx={{ display: 'block', mt: 0.35 }}>Revision {tenant.value.revision} · Created {formatDateTime(tenant.value.createdAt)}</Typography> : null}
        </Box>
        <Button
          variant="outlined"
          disabled
          title="No Tenant mutation actions are exposed by the current Console contract."
          endIcon={<Typography component="span" aria-hidden="true" sx={{ fontSize: 14 }}>⌄</Typography>}
          sx={{ justifySelf: { xs: 'start', sm: 'end' } }}
        >Tenant Actions</Button>
      </Box>

      <Box component="nav" aria-label="Tenant sections" sx={{ display: 'flex', gap: 0.2, overflowX: 'auto', borderBottom: '1px solid', borderColor: 'divider', mb: 1.75 }}>
        {tenantTabs.map((tab) => (
          <Button
            key={tab.label}
            variant="text"
            size="small"
            disabled={!tab.path}
            title={tab.disabledReason}
            startIcon={<LineIcon name={tab.icon} size={16} />}
            onClick={tab.path ? () => navigateConsole(tab.path!) : undefined}
            sx={{
              flex: '0 0 auto',
              minHeight: 42,
              px: 1.15,
              borderRadius: 0,
              borderBottom: '2px solid',
              borderBottomColor: tab.path === '/tenant' ? 'primary.main' : 'transparent',
              color: tab.path === '/tenant' ? 'primary.main' : 'text.secondary',
              fontWeight: tab.path === '/tenant' ? 700 : 500,
              fontSize: 12,
            }}
          >{tab.label}</Button>
        ))}
      </Box>

      {!authority || loading ? <StatePanel state="loading" title="Loading Tenant overview">Reading canonical Tenant metadata and tenant-scoped IAM projections.</StatePanel> : null}
      {tenant.status === 'error' ? <StatePanel state="error" title="Tenant information is unavailable" action={<Button variant="outlined" onClick={() => setReloadKey((value) => value + 1)}>Retry</Button>}>{errorMessage(tenant.error, 'Wyrmgate could not read the current Tenant metadata.')}</StatePanel> : null}
      {overview.status === 'error' ? <StatePanel state="error" title="Tenant operations are unavailable" action={<Button variant="outlined" onClick={() => setReloadKey((value) => value + 1)}>Retry</Button>}>{errorMessage(overview.error, 'Wyrmgate could not read the Tenant operations projection.')}</StatePanel> : null}

      {overview.status === 'ready' ? (
        <Box sx={{ display: 'grid', gap: 1.5 }}>
          <Box sx={{ display: 'grid', gridTemplateColumns: { xs: '1fr', sm: 'repeat(2, minmax(0, 1fr))', lg: 'repeat(6, minmax(0, 1fr))' }, gap: 1.5 }}>
            <SummaryTile label="Identities" icon="identities" metric={overview.value.environment.activeIdentities} detail="Active governed subjects" path="/identities" tone="info" />
            <SummaryTile label="Applications" icon="applications" metric={overview.value.environment.applications} detail="Tenant-local Catalog applications" path="/catalog" tone="info" />
            <SummaryTile label="Active Access" icon="access" metric={overview.value.environment.activeAccessAssignments} detail="Authoritative assignments" path="/access" tone="warning" />
            <SummaryTile label="Governance Issues" icon="warning" metric={governanceIssues} detail="Pending approvals + reviews" path="/requests" tone="danger" />
            <SummaryTile label="Sources" icon="sources" detail="Source count is not exposed by the current projection" path="/integrations" tone="info" />
            <SummaryTile label="Connectors" icon="connectors" metric={overview.value.environment.activeConnectors} detail="Active connector instances" path="/integrations" tone="info" />
          </Box>

          <Box sx={{ display: 'grid', gridTemplateColumns: { xs: '1fr', lg: '1.15fr 1fr 1fr' }, gap: 1.5 }}>
            <Surface
              title="Tenant Information"
              actions={<Button variant="outlined" size="small" disabled startIcon={<LineIcon name="edit" size={15} />} title="Tenant mutation is not exposed by the current Console contract.">Edit</Button>}
              sx={{ height: '100%', p: 2 }}
            >
              {tenant.status === 'ready' ? <Box component="dl" sx={{ m: 0 }}>
                <Definition term="Name">{tenant.value.displayName}</Definition>
                <Definition term="Tenant ID">{tenant.value.id}</Definition>
                <Definition term="Created At">{formatDateTime(tenant.value.createdAt)}</Definition>
                <Definition term="Updated At">{formatDateTime(tenant.value.updatedAt)}</Definition>
                <Definition term="Revision">{tenant.value.revision}</Definition>
              </Box> : <StateIndicator label="Unavailable" tone="danger" />}
            </Surface>

            <Surface
              title="Tenant Health"
              aria-label="Tenant Health — explicit operational signals, not a synthetic health score"
              actions={<Button variant="text" size="small" onClick={() => navigateConsole('/administration')}>View Details</Button>}
              sx={{ height: '100%', p: 2 }}
            >
              <HealthSignalRow label="Administrative actor eligible" state={authority?.administrativelyEligible ? 'Eligible' : 'Not eligible'} tone={authority?.administrativelyEligible ? 'success' : 'danger'} path="/administration" />
              <HealthSignalRow label="Pending approvals" state={metricText(overview.value.attention.pendingApprovals) ?? statusLabel(overview.value.attention.pendingApprovals.status)} tone={overview.value.attention.pendingApprovals.status === 'AVAILABLE' && (overview.value.attention.pendingApprovals.value ?? 0) === 0 ? 'success' : 'warning'} path="/requests?view=approvals" />
              <HealthSignalRow label="Pending reviews" state={metricText(overview.value.attention.pendingReviews) ?? statusLabel(overview.value.attention.pendingReviews.status)} tone={overview.value.attention.pendingReviews.status === 'AVAILABLE' && (overview.value.attention.pendingReviews.value ?? 0) === 0 ? 'success' : 'warning'} path="/reviews" />
              <HealthSignalRow label="Provisioning failures" state={metricText(overview.value.attention.finalProvisioningFailures) ?? statusLabel(overview.value.attention.finalProvisioningFailures.status)} tone={overview.value.attention.finalProvisioningFailures.status === 'AVAILABLE' && (overview.value.attention.finalProvisioningFailures.value ?? 0) === 0 ? 'success' : 'warning'} path="/integrations" />
              <HealthSignalRow label="Failed reconciliations" state={metricText(overview.value.attention.failedReconciliations) ?? statusLabel(overview.value.attention.failedReconciliations.status)} tone={overview.value.attention.failedReconciliations.status === 'AVAILABLE' && (overview.value.attention.failedReconciliations.value ?? 0) === 0 ? 'success' : 'warning'} path="/integrations" />
            </Surface>

            <Surface
              title="Recent Activity"
              aria-label="Recent denied or failed Audit evidence"
              actions={<Button variant="text" size="small" onClick={() => navigateConsole('/audit')}>View All</Button>}
              sx={{ height: '100%', p: 2 }}
            >
              <RecentActivity overview={overview.value} />
            </Surface>
          </Box>

          <Box sx={{ display: 'grid', gridTemplateColumns: { xs: '1fr', lg: 'repeat(3, minmax(0, 1fr))' }, gap: 1.5 }}>
            <Surface title="Identities by Type" sx={{ height: '100%', p: 2 }}>
              <BreakdownPlaceholder total={overview.value.environment.activeIdentities} totalLabel="Identities" message="The current Console projection exposes the authoritative active total but not PERSON / SERVICE / WORKLOAD distribution." action="View identities" path="/identities" />
            </Surface>
            <Surface title="Applications by Origin" sx={{ height: '100%', p: 2 }}>
              <BreakdownPlaceholder total={overview.value.environment.applications} totalLabel="Applications" message="TENANT_LOCAL / PLATFORM_SHARED / PLATFORM_INTERNAL origin distribution is not exposed by the current Console projection." action="View applications" path="/catalog" />
            </Surface>
            <Surface title="Active Access by Application" actions={<Button variant="text" size="small" onClick={() => navigateConsole('/access')}>View All</Button>} sx={{ height: '100%', p: 2 }}>
              <AccessBreakdownPlaceholder total={overview.value.environment.activeAccessAssignments} />
            </Surface>
          </Box>

          <Box sx={{ display: 'grid', gridTemplateColumns: { xs: '1fr', lg: '1.35fr 1fr' }, gap: 1.5 }}>
            <Surface title="Open Governance Items" actions={<Button variant="text" size="small" onClick={() => navigateConsole('/requests')}>View All</Button>} sx={{ height: '100%', p: 2 }}>
              <WorkItems overview={overview.value} />
            </Surface>
            <Surface title="Identity Sources" actions={<Button variant="text" size="small" onClick={() => navigateConsole('/integrations')}>View All</Button>} sx={{ height: '100%', p: 2 }}>
              <IdentitySourcesSummary overview={overview.value} />
            </Surface>
          </Box>
        </Box>
      ) : null}
    </Page>
  );
}
