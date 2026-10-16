import { useEffect, useMemo, useState } from 'react';
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

type TenantTab = {
  label: string;
  path?: string;
  disabledReason?: string;
};

const tenantTabs: TenantTab[] = [
  { label: 'Overview', path: '/tenant' },
  { label: 'Identities', path: '/identities' },
  { label: 'Applications', path: '/catalog' },
  { label: 'Access', path: '/access' },
  { label: 'Organizations', disabledReason: 'Organization workspace is not exposed by the current Console contract.' },
  { label: 'Governance', path: '/requests' },
  { label: 'Sources & Integrations', path: '/integrations' },
  { label: 'Administrators', path: '/administration' },
  { label: 'Audit', path: '/audit' },
  { label: 'Settings', disabledReason: 'Tenant settings are not exposed by the current Console contract.' },
];

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

function Definition({ term, children }: { term: string; children: React.ReactNode }) {
  return (
    <Box component="div" sx={{ display: 'grid', gridTemplateColumns: '112px minmax(0, 1fr)', gap: 1.5, py: 0.75 }}>
      <Typography component="dt" variant="caption" color="text.secondary">{term}</Typography>
      <Typography component="dd" variant="body2" sx={{ m: 0, fontWeight: 600, overflowWrap: 'anywhere' }}>{children}</Typography>
    </Box>
  );
}

function SummaryTile({ label, metric, detail, path }: {
  label: string;
  metric?: OverviewMetric;
  detail: string;
  path?: string;
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
        '&:hover': path ? { borderColor: 'primary.main' } : undefined,
        '&:focus-visible': path ? { outline: '2px solid', outlineColor: 'primary.main', outlineOffset: 2 } : undefined,
      }}
    >
      <Typography variant="caption" color="text.secondary">{label}</Typography>
      <Box sx={{ minHeight: 34, display: 'flex', alignItems: 'center', mt: 0.5 }}>
        {metric ? (
          value !== null
            ? <Typography sx={{ fontSize: 22, lineHeight: 1.25, fontWeight: 700 }}>{value}</Typography>
            : <StateIndicator label={statusLabel(metric.status)} tone={statusTone(metric.status)} />
        ) : <StateIndicator label="Not exposed" tone="neutral" />}
      </Box>
      <Typography variant="caption" color="text.secondary" sx={{ display: 'block', mt: 0.75 }}>{detail}</Typography>
    </Box>
  );
}

function SignalRow({ label, state, tone, detail, path }: {
  label: string;
  state: string;
  tone: StateIndicatorTone;
  detail?: string;
  path?: string;
}) {
  return (
    <Box sx={{ display: 'grid', gridTemplateColumns: 'minmax(0, 1fr) auto', gap: 1.5, alignItems: 'center', py: 1.15, borderBottom: '1px solid', borderColor: 'divider', '&:last-child': { borderBottom: 0 } }}>
      <Box sx={{ minWidth: 0 }}>
        <Typography variant="body2" sx={{ fontWeight: 600 }}>{label}</Typography>
        {detail ? <Typography variant="caption" color="text.secondary">{detail}</Typography> : null}
      </Box>
      <Box sx={{ display: 'flex', alignItems: 'center', gap: 1 }}>
        <StateIndicator label={state} tone={tone} />
        {path ? <Button variant="text" size="small" onClick={() => navigateConsole(path)}>View</Button> : null}
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
    <Box sx={{ minHeight: 172, display: 'grid', gridTemplateColumns: { xs: '1fr', sm: '132px minmax(0, 1fr)' }, gap: 2.5, alignItems: 'center' }}>
      <Box
        sx={{
          width: 124,
          height: 124,
          borderRadius: '50%',
          border: '12px solid',
          borderColor: 'action.hover',
          display: 'grid',
          placeItems: 'center',
          justifySelf: { xs: 'start', sm: 'center' },
        }}
      >
        <Box sx={{ textAlign: 'center' }}>
          {value !== null
            ? <Typography sx={{ fontSize: 20, fontWeight: 700 }}>{value}</Typography>
            : <StateIndicator label={statusLabel(total.status)} tone={statusTone(total.status)} />}
          <Typography variant="caption" color="text.secondary">{totalLabel}</Typography>
        </Box>
      </Box>
      <Box>
        <Typography variant="body2" sx={{ fontWeight: 600 }}>Breakdown not exposed</Typography>
        <Typography variant="caption" color="text.secondary" sx={{ display: 'block', mt: 0.5, mb: 1.5 }}>{message}</Typography>
        <Button variant="text" size="small" onClick={() => navigateConsole(path)}>{action}</Button>
      </Box>
    </Box>
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
    <DataTable ariaLabel="Open governance items" minWidth={720}>
      <TableHead><TableRow><TableCell>Type</TableCell><TableCell>Subject</TableCell><TableCell>Created</TableCell><TableCell>Stage</TableCell><TableCell align="right">Action</TableCell></TableRow></TableHead>
      <TableBody>
        {overview.myWork.items.slice(0, 6).map((item) => (
          <DataTableRow key={`${item.kind}-${item.id}`}>
            <TableCell>{item.kind === 'APPROVAL' ? 'Approval' : 'Review'}</TableCell>
            <TableCell><Typography variant="body2" sx={{ fontWeight: 600 }}>{item.subjectKind.replaceAll('_', ' ')}</Typography><Typography variant="caption" color="text.secondary">{shortId(item.subjectId)}</Typography></TableCell>
            <TableCell>{formatDateTime(item.createdAt)}</TableCell>
            <TableCell>{item.kind === 'APPROVAL' ? `Stage ${item.stageOrdinal + 1}` : 'Pending'}</TableCell>
            <TableCell align="right"><Button variant="text" size="small" onClick={() => navigateConsole(item.kind === 'APPROVAL' ? `/requests?view=approvals&approvalCaseId=${encodeURIComponent(item.id)}` : `/reviews?reviewItemId=${encodeURIComponent(item.id)}`)}>Open</Button></TableCell>
          </DataTableRow>
        ))}
      </TableBody>
    </DataTable>
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
  const busy = tenant.status === 'loading' || overview.status === 'loading';

  return (
    <Page width="wide" aria-labelledby="tenant-page-title">
      <Box component="nav" aria-label="Breadcrumb" sx={{ display: 'flex', alignItems: 'center', gap: 1, color: 'text.secondary', mb: 1 }}>
        <Typography variant="caption">Tenants</Typography><Typography variant="caption">/</Typography><Typography variant="caption">{tenantName}</Typography><Typography variant="caption">/</Typography><Typography variant="caption" color="text.primary">Overview</Typography>
      </Box>

      <Box sx={{ display: 'grid', gridTemplateColumns: { xs: '1fr', sm: 'auto minmax(0, 1fr) auto' }, gap: 2, alignItems: 'center', mb: 2.5 }}>
        <Box sx={{ width: 56, height: 56, borderRadius: '50%', bgcolor: 'primary.main', color: 'primary.contrastText', display: 'grid', placeItems: 'center', fontSize: 24, fontWeight: 700 }}>{initial}</Box>
        <Box sx={{ minWidth: 0 }}>
          <Typography component="h1" id="tenant-page-title" sx={{ fontSize: 26, lineHeight: 1.2, fontWeight: 700 }}>{tenantName}</Typography>
          <Typography variant="body2" color="text.secondary" sx={{ mt: 0.5 }}>Tenant · {tenant.status === 'ready' ? `Tenant ID: ${tenant.value.id}` : authority ? `Tenant ID: ${authority.tenantId}` : 'Resolving Tenant context'}</Typography>
          {tenant.status === 'ready' ? <Typography variant="caption" color="text.secondary">Revision {tenant.value.revision} · Updated {formatDateTime(tenant.value.updatedAt)}</Typography> : null}
        </Box>
        <Button variant="outlined" disabled={!authority || busy} onClick={() => setReloadKey((value) => value + 1)} sx={{ justifySelf: { xs: 'start', sm: 'end' } }}>{busy ? 'Refreshing…' : 'Refresh'}</Button>
      </Box>

      <Box component="nav" aria-label="Tenant sections" sx={{ display: 'flex', gap: 0.5, overflowX: 'auto', borderBottom: '1px solid', borderColor: 'divider', mb: 2 }}>
        {tenantTabs.map((tab) => (
          <Button
            key={tab.label}
            variant="text"
            size="small"
            disabled={!tab.path}
            title={tab.disabledReason}
            onClick={tab.path ? () => navigateConsole(tab.path!) : undefined}
            sx={{ flex: '0 0 auto', minHeight: 40, borderRadius: 0, borderBottom: tab.path === '/tenant' ? '2px solid' : '2px solid transparent', borderBottomColor: tab.path === '/tenant' ? 'primary.main' : 'transparent', color: tab.path === '/tenant' ? 'primary.main' : 'text.secondary', fontWeight: tab.path === '/tenant' ? 700 : 500 }}
          >{tab.label}</Button>
        ))}
      </Box>

      {!authority || tenant.status === 'loading' || overview.status === 'loading' ? <StatePanel state="loading" title="Loading Tenant overview">Reading canonical Tenant metadata and tenant-scoped IAM projections.</StatePanel> : null}
      {tenant.status === 'error' ? <StatePanel state="error" title="Tenant information is unavailable" action={<Button variant="outlined" onClick={() => setReloadKey((value) => value + 1)}>Retry</Button>}>{errorMessage(tenant.error, 'Wyrmgate could not read the current Tenant metadata.')}</StatePanel> : null}
      {overview.status === 'error' ? <StatePanel state="error" title="Tenant operations are unavailable" action={<Button variant="outlined" onClick={() => setReloadKey((value) => value + 1)}>Retry</Button>}>{errorMessage(overview.error, 'Wyrmgate could not read the Tenant operations projection.')}</StatePanel> : null}

      {overview.status === 'ready' ? (
        <>
          <Box sx={{ display: 'grid', gridTemplateColumns: { xs: '1fr', sm: 'repeat(2, minmax(0, 1fr))', lg: 'repeat(6, minmax(0, 1fr))' }, gap: 1.5, mb: 2 }}>
            <SummaryTile label="Identities" metric={overview.value.environment.activeIdentities} detail="Active governed subjects" path="/identities" />
            <SummaryTile label="Applications" metric={overview.value.environment.applications} detail="Tenant-local Catalog applications" path="/catalog" />
            <SummaryTile label="Active Access" metric={overview.value.environment.activeAccessAssignments} detail="Authoritative assignments" path="/access" />
            <SummaryTile label="Governance Issues" metric={governanceIssues} detail="Pending approvals + reviews" path="/requests" />
            <SummaryTile label="Sources" detail="Source count is not exposed by the current projection" path="/integrations" />
            <SummaryTile label="Connectors" metric={overview.value.environment.activeConnectors} detail="Active connector instances" path="/integrations" />
          </Box>

          <Box sx={{ display: 'grid', gridTemplateColumns: { xs: '1fr', lg: '1.15fr 1fr 1fr' }, gap: 1.5, mb: 2 }}>
            <Surface title="Tenant Information">
              {tenant.status === 'ready' ? <Box component="dl" sx={{ m: 0 }}>
                <Definition term="Name">{tenant.value.displayName}</Definition>
                <Definition term="Tenant ID">{tenant.value.id}</Definition>
                <Definition term="Revision">{tenant.value.revision}</Definition>
                <Definition term="Created">{formatDateTime(tenant.value.createdAt)}</Definition>
                <Definition term="Updated">{formatDateTime(tenant.value.updatedAt)}</Definition>
              </Box> : <StateIndicator label="Unavailable" tone="danger" />}
            </Surface>

            <Surface title="Tenant Health" description="Explicit operational and governance signals; not a synthetic health score.">
              <SignalRow label="Administrative eligibility" state={authority?.administrativelyEligible ? 'Eligible' : 'Not eligible'} tone={authority?.administrativelyEligible ? 'success' : 'danger'} path="/administration" />
              <SignalRow label="Pending approvals" state={metricText(overview.value.attention.pendingApprovals) ?? statusLabel(overview.value.attention.pendingApprovals.status)} tone={overview.value.attention.pendingApprovals.status === 'AVAILABLE' && (overview.value.attention.pendingApprovals.value ?? 0) === 0 ? 'success' : 'warning'} path="/requests?view=approvals" />
              <SignalRow label="Pending reviews" state={metricText(overview.value.attention.pendingReviews) ?? statusLabel(overview.value.attention.pendingReviews.status)} tone={overview.value.attention.pendingReviews.status === 'AVAILABLE' && (overview.value.attention.pendingReviews.value ?? 0) === 0 ? 'success' : 'warning'} path="/reviews" />
              <SignalRow label="Final provisioning failures" state={metricText(overview.value.attention.finalProvisioningFailures) ?? statusLabel(overview.value.attention.finalProvisioningFailures.status)} tone={overview.value.attention.finalProvisioningFailures.status === 'AVAILABLE' && (overview.value.attention.finalProvisioningFailures.value ?? 0) === 0 ? 'success' : 'warning'} path="/integrations" />
              <SignalRow label="Failed reconciliations" state={metricText(overview.value.attention.failedReconciliations) ?? statusLabel(overview.value.attention.failedReconciliations.status)} tone={overview.value.attention.failedReconciliations.status === 'AVAILABLE' && (overview.value.attention.failedReconciliations.value ?? 0) === 0 ? 'success' : 'warning'} path="/integrations" />
            </Surface>

            <Surface title="Recent Activity" description="Recent denied or failed Audit evidence currently exposed by the Console projection.">
              {overview.value.securityEvidence.status !== 'AVAILABLE' ? <StateIndicator label={statusLabel(overview.value.securityEvidence.status)} tone={statusTone(overview.value.securityEvidence.status)} />
                : overview.value.securityEvidence.recent.length === 0 ? <StatePanel state="empty" title="No recent matching evidence">No denied or failed Audit records were returned in the last 24 hours.</StatePanel>
                  : <Box sx={{ display: 'grid' }}>{overview.value.securityEvidence.recent.slice(0, 5).map((item) => <Box key={item.id} sx={{ display: 'grid', gridTemplateColumns: '70px minmax(0, 1fr)', gap: 1.25, py: 1, borderBottom: '1px solid', borderColor: 'divider', '&:last-child': { borderBottom: 0 } }}><Typography variant="caption" color="text.secondary">{new Date(item.occurredAt).toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' })}</Typography><Box><Typography variant="body2" sx={{ fontWeight: 600 }}>{item.actionType}</Typography><Typography variant="caption" color="text.secondary">{item.outcome} · {item.resourceType}</Typography></Box></Box>)}</Box>}
              <Button variant="text" size="small" onClick={() => navigateConsole('/audit')} sx={{ mt: 1 }}>View all Audit evidence</Button>
            </Surface>
          </Box>

          <Box sx={{ display: 'grid', gridTemplateColumns: { xs: '1fr', lg: 'repeat(3, minmax(0, 1fr))' }, gap: 1.5, mb: 2 }}>
            <Surface title="Identities by Type"><BreakdownPlaceholder total={overview.value.environment.activeIdentities} totalLabel="Identities" message="The current Console projection exposes the authoritative active total but not PERSON / SERVICE / WORKLOAD distribution." action="View identities" path="/identities" /></Surface>
            <Surface title="Applications by Origin"><BreakdownPlaceholder total={overview.value.environment.applications} totalLabel="Applications" message="TENANT_LOCAL / PLATFORM_SHARED / PLATFORM_INTERNAL origin distribution is not exposed by the current Console projection." action="View applications" path="/catalog" /></Surface>
            <Surface title="Active Access by Application"><BreakdownPlaceholder total={overview.value.environment.activeAccessAssignments} totalLabel="Assignments" message="Per-application access aggregation is not exposed. AccessAssignment remains authoritative business intent and EffectiveAccess remains a projection." action="View access" path="/access" /></Surface>
          </Box>

          <Box sx={{ display: 'grid', gridTemplateColumns: { xs: '1fr', lg: '1.4fr 1fr' }, gap: 1.5 }}>
            <Surface title="Open Governance Items" description="Assigned approvals and reviews from the existing Governance work projection."><WorkItems overview={overview.value} /><Button variant="text" size="small" onClick={() => navigateConsole('/requests')} sx={{ mt: 1 }}>View all governance work</Button></Surface>
            <Surface title="Identity Sources" description="The current Console overview exposes connector totals and reconciliation posture, but not a canonical source collection for this panel.">
              <Box sx={{ display: 'grid', gap: 1.5 }}>
                <Box sx={{ display: 'flex', justifyContent: 'space-between', gap: 2, alignItems: 'center' }}><Typography variant="body2">Active connectors</Typography>{metricText(overview.value.environment.activeConnectors) !== null ? <Typography variant="body2" sx={{ fontWeight: 700 }}>{metricText(overview.value.environment.activeConnectors)}</Typography> : <StateIndicator label={statusLabel(overview.value.environment.activeConnectors.status)} tone={statusTone(overview.value.environment.activeConnectors.status)} />}</Box>
                <Box sx={{ display: 'flex', justifyContent: 'space-between', gap: 2, alignItems: 'center' }}><Typography variant="body2">Last effectively complete reconciliation</Typography><Typography variant="body2" sx={{ fontWeight: 600, textAlign: 'right' }}>{overview.value.reconciliation.status === 'AVAILABLE' ? formatDateTime(overview.value.reconciliation.summary?.lastCompleteAt) : statusLabel(overview.value.reconciliation.status)}</Typography></Box>
                <StatePanel state="empty" title="Source list not exposed">No fake HR system, directory, or manual-source rows are rendered. Open Integration to inspect the real connector and reconciliation state.</StatePanel>
                <Button variant="outlined" size="small" onClick={() => navigateConsole('/integrations')} sx={{ justifySelf: 'start' }}>Open sources & integrations</Button>
              </Box>
            </Surface>
          </Box>
        </>
      ) : null}
    </Page>
  );
}
