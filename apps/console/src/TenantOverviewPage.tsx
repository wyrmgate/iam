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
  PageHeader,
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

type BreakdownLabel = {
  key: string;
  label: string;
};

type DefinitionItem = {
  label: string;
  value: ReactNode;
  mono?: boolean;
};

type RelatedDestination = {
  label: string;
  routeId: ConsoleRouteId;
  path: string;
};

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

const relatedDestinations: RelatedDestination[] = [
  { label: 'Identities', routeId: 'identities', path: '/identities' },
  { label: 'Access', routeId: 'access', path: '/access' },
  { label: 'Governance', routeId: 'requests', path: '/requests' },
  { label: 'Integrations', routeId: 'integrations', path: '/integrations' },
  { label: 'Administration', routeId: 'administration', path: '/administration' },
];

const monoSx = {
  fontFamily: 'ui-monospace, SFMono-Regular, Menlo, Monaco, Consolas, monospace',
  overflowWrap: 'anywhere',
} as const;

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

function errorMessage(error: unknown, fallback: string): string {
  return error instanceof Error ? error.message : fallback;
}

function DefinitionList({ items }: { items: DefinitionItem[] }) {
  return (
    <Box
      component="dl"
      sx={{
        m: 0,
        display: 'grid',
        gridTemplateColumns: { xs: '1fr', sm: 'minmax(9rem, 12rem) minmax(0, 1fr)' },
        columnGap: 2,
        '& > dt, & > dd': { py: 1, m: 0, borderBottom: 1, borderColor: 'divider' },
        '& > dt': { color: 'text.secondary', fontSize: '0.75rem' },
        '& > dd': { fontSize: '0.8125rem', fontWeight: 600, overflowWrap: 'anywhere' },
      }}
    >
      {items.flatMap((item) => [
        <Box component="dt" key={`${item.label}-label`}>{item.label}</Box>,
        <Box component="dd" key={`${item.label}-value`} sx={item.mono ? monoSx : undefined}>{item.value}</Box>,
      ])}
    </Box>
  );
}

function MetricSummary({ label, metric, detail, path }: {
  label: string;
  metric: OverviewMetric;
  detail: string;
  path?: string;
}) {
  const value = metricText(metric);
  return (
    <Box
      sx={{
        minWidth: 0,
        py: 1.25,
        px: { xs: 0, sm: 1.5 },
        borderTop: 1,
        borderColor: 'divider',
        display: 'grid',
        alignContent: 'start',
        gap: 0.35,
      }}
    >
      <Typography variant="caption" color="text.secondary">{label}</Typography>
      {value !== null
        ? <Typography sx={{ fontSize: '1.35rem', lineHeight: 1.2, fontWeight: 700 }}>{value}</Typography>
        : <StateIndicator label={statusLabel(metric.status)} tone={statusTone(metric.status)} />}
      <Typography variant="caption" color="text.secondary" sx={{ lineHeight: 1.4 }}>{detail}</Typography>
      {path ? (
        <Button
          variant="text"
          size="small"
          onClick={() => navigateConsole(path)}
          sx={{ justifySelf: 'start', px: 0, mt: 0.25, minHeight: { xs: 44, sm: 32 } }}
        >
          Open
        </Button>
      ) : null}
    </Box>
  );
}

function SignalRow({ label, state, tone, path }: {
  label: string;
  state: string;
  tone: StateIndicatorTone;
  path?: string;
}) {
  return (
    <Box
      sx={{
        display: 'grid',
        gridTemplateColumns: { xs: 'minmax(0, 1fr) auto', sm: 'minmax(0, 1fr) auto auto' },
        gap: 1,
        alignItems: 'center',
        py: 1,
        borderBottom: 1,
        borderColor: 'divider',
        '&:last-of-type': { borderBottom: 0 },
      }}
    >
      <Typography variant="body2">{label}</Typography>
      <StateIndicator label={state} tone={tone} />
      {path ? (
        <Button
          variant="text"
          size="small"
          onClick={() => navigateConsole(path)}
          sx={{ gridColumn: { xs: '1 / -1', sm: 'auto' }, justifySelf: { xs: 'start', sm: 'end' }, px: 0.5, minHeight: { xs: 44, sm: 32 } }}
        >
          View
        </Button>
      ) : null}
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
      <Box sx={{ minHeight: 176, display: 'grid', alignContent: 'center', gap: 1.25 }}>
        <StateIndicator label={statusLabel(breakdown.status)} tone={statusTone(breakdown.status)} />
        <Typography variant="caption" color="text.secondary">This governed breakdown is not available to the current actor.</Typography>
        {path ? <Button variant="text" size="small" onClick={() => navigateConsole(path)} sx={{ justifySelf: 'start', px: 0, minHeight: { xs: 44, sm: 32 } }}>{action}</Button> : null}
      </Box>
    );
  }

  const rows = breakdownRows(breakdown, labels);
  const fallbackTotal = rows.reduce((sum, row) => sum + row.value, 0);
  const totalValue = total.status === 'AVAILABLE' && total.value !== null ? total.value : fallbackTotal;

  return (
    <Box sx={{ minHeight: 210, display: 'grid', gap: 1.25, alignContent: 'start' }}>
      <DonutChart ariaLabel={`${totalLabel} distribution`} items={rows} totalLabel={totalLabel} totalValue={totalValue} size={148} />
      {path ? <Button variant="text" size="small" onClick={() => navigateConsole(path)} sx={{ justifySelf: 'start', px: 0, minHeight: { xs: 44, sm: 32 } }}>{action}</Button> : null}
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

function attentionTone(metric: OverviewMetric): StateIndicatorTone {
  if (metric.status !== 'AVAILABLE') return statusTone(metric.status);
  return (metric.value ?? 0) === 0 ? 'success' : 'warning';
}

function attentionLabel(metric: OverviewMetric): string {
  return metricText(metric) ?? statusLabel(metric.status);
}

function RecentActivity({ overview, auditPath }: { overview: IamOperationsOverview; auditPath?: string }) {
  const activity = overview.tenantDashboard.recentActivity;
  if (activity.status !== 'AVAILABLE') return <StateIndicator label={statusLabel(activity.status)} tone={statusTone(activity.status)} />;
  if (activity.items.length === 0) return <StatePanel state="empty" title="No recent Audit activity">No Audit records were returned in the last 24 hours.</StatePanel>;
  const items = activity.items.slice(0, 5);
  const outcomeTone = (outcome: string): StateIndicatorTone => outcome === 'SUCCESS' ? 'success' : outcome === 'FAILURE' ? 'danger' : 'warning';

  return (
    <>
      <DataTable ariaLabel="Recent Tenant Audit activity" minWidth={620}>
        <TableHead>
          <TableRow>
            <TableCell>Time</TableCell>
            <TableCell>Actor</TableCell>
            <TableCell>Event</TableCell>
            <TableCell>Resource</TableCell>
          </TableRow>
        </TableHead>
        <TableBody>
          {items.map((item) => (
            <DataTableRow key={item.id}>
              <TableCell>{formatDateTime(item.occurredAt)}</TableCell>
              <TableCell>{item.actorDisplayLabel ?? shortId(item.actorId)}</TableCell>
              <TableCell>
                <Typography variant="body2" sx={{ fontWeight: 500 }}>{humanize(item.actionType)}</Typography>
                <StateIndicator label={humanize(item.outcome)} tone={outcomeTone(item.outcome)} />
              </TableCell>
              <TableCell>{item.resourceDisplayLabel ?? humanize(item.resourceType)}</TableCell>
            </DataTableRow>
          ))}
        </TableBody>
      </DataTable>
      {auditPath ? <Button variant="text" size="small" onClick={() => navigateConsole(auditPath)} sx={{ mt: 1.25, px: 0, minHeight: { xs: 44, sm: 32 } }}>View audit</Button> : null}
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
      <DataTable ariaLabel="Identity source operational summary" minWidth={720}>
        <TableHead>
          <TableRow>
            <TableCell>Source</TableCell>
            <TableCell align="right">Linked identities</TableCell>
            <TableCell>Connector posture</TableCell>
            <TableCell>Last complete</TableCell>
          </TableRow>
        </TableHead>
        <TableBody>
          {sources.items.map((source) => {
            const connector = sourceConnectorState(source);
            return (
              <DataTableRow key={source.id}>
                <TableCell>
                  <Typography variant="body2" sx={{ fontWeight: 500 }}>{source.name}</Typography>
                  <Typography variant="caption" color="text.secondary">{source.code}</Typography>
                </TableCell>
                <TableCell align="right">{source.linkedIdentityCount.toLocaleString()}</TableCell>
                <TableCell><StateIndicator label={connector.label} tone={connector.tone} /></TableCell>
                <TableCell>{formatDateTime(source.operational.lastCompleteAt)}</TableCell>
              </DataTableRow>
            );
          })}
        </TableBody>
      </DataTable>
      {integrationsPath ? <Button variant="text" size="small" onClick={() => navigateConsole(integrationsPath)} sx={{ mt: 1.25, px: 0, minHeight: { xs: 44, sm: 32 } }}>View integrations</Button> : null}
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
  const routePath = (routeId: ConsoleRouteId, path: string): string | undefined => allowedRouteIds.has(routeId) ? path : undefined;
  const visibleDestinations = useMemo(
    () => relatedDestinations.filter((destination) => allowedRouteIds.has(destination.routeId)),
    [allowedRouteIds],
  );

  const sourceCount = useMemo(() => overview.status === 'ready'
    ? metricFromCount(overview.value.tenantDashboard.sources.status, overview.value.tenantDashboard.sources.totalCount)
    : undefined, [overview]);

  const tenantName = tenant.status === 'ready' ? tenant.value.displayName : 'Tenant Management';
  const tenantId = tenant.status === 'ready' ? tenant.value.id : authority?.tenantId;
  const loading = tenant.status === 'loading' || overview.status === 'loading';

  return (
    <Page width="wide" aria-labelledby="tenant-page-title">
      <PageHeader
        eyebrow="Administration · current Tenant"
        title={<Box component="span" id="tenant-page-title">{tenantName}</Box>}
        description={tenantId ? `Server-resolved current Tenant context · Tenant ID ${tenantId}` : 'Resolving server-derived Tenant context.'}
        actions={<StateIndicator label="Current Tenant context" tone="info" />}
      />

      {visibleDestinations.length > 0 ? (
        <Box component="nav" aria-label="Related Tenant workspaces" sx={{ display: 'flex', flexWrap: 'wrap', alignItems: 'center', gap: { xs: 0.5, sm: 1 } }}>
          <Typography variant="caption" color="text.secondary" sx={{ mr: 0.5 }}>Related workspaces</Typography>
          {visibleDestinations.map((destination) => (
            <Button
              key={destination.routeId}
              variant="text"
              size="small"
              onClick={() => navigateConsole(destination.path)}
              sx={{ minHeight: { xs: 44, sm: 32 }, px: 1 }}
            >
              {destination.label}
            </Button>
          ))}
        </Box>
      ) : null}

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
        const identityPath = routePath('identities', '/identities');
        const catalogPath = routePath('catalog', '/catalog');
        const accessPath = routePath('access', '/access');

        return (
          <>
            <Surface title="Governed operational summary" description="Current bounded Tenant-scoped projections. Values are presented individually rather than combined into a synthetic score.">
              <Box sx={{ display: 'grid', gridTemplateColumns: { xs: '1fr', sm: 'repeat(2, minmax(0, 1fr))', lg: 'repeat(5, minmax(0, 1fr))' }, columnGap: { sm: 1 } }}>
                <MetricSummary label="Active identities" metric={overview.value.environment.activeIdentities} detail="Governed subjects" path={identityPath} />
                <MetricSummary label="Applications" metric={overview.value.environment.applications} detail="Active tenant-bound Catalog applications" path={catalogPath} />
                <MetricSummary label="Active access assignments" metric={overview.value.environment.activeAccessAssignments} detail="Authoritative AccessAssignment intent" path={accessPath} />
                <MetricSummary label="Identity sources" metric={sourceCount ?? { status: 'UNAVAILABLE', value: null }} detail="Configured SourceSystems" path={integrationsPath} />
                <MetricSummary label="Active connectors" metric={overview.value.environment.activeConnectors} detail="Active connector instances" path={integrationsPath} />
              </Box>
            </Surface>

            <Box sx={{ display: 'grid', gridTemplateColumns: { xs: '1fr', lg: 'minmax(0, 0.9fr) minmax(0, 1.1fr)' }, gap: { xs: 2, md: 3 } }}>
              <Surface title="Tenant information" description="Canonical Platform-owned metadata for the server-resolved current Tenant." sx={{ height: '100%' }}>
                {tenant.status === 'ready' ? (
                  <DefinitionList items={[
                    { label: 'Display name', value: tenant.value.displayName },
                    { label: 'Tenant ID', value: tenant.value.id, mono: true },
                    { label: 'Revision', value: tenant.value.revision },
                    { label: 'Created', value: formatDateTime(tenant.value.createdAt) },
                    { label: 'Updated', value: formatDateTime(tenant.value.updatedAt) },
                  ]} />
                ) : <StateIndicator label="Unavailable" tone="danger" />}
              </Surface>

              <Surface title="Administrative continuity" description="Current authority and Tenant Owner continuity signals; not a Tenant lifecycle or health score." sx={{ height: '100%' }}>
                <SignalRow label="Tenant Owner continuity" state={continuity.state} tone={continuity.tone} path={adminPath} />
                <SignalRow label="Administrative actor" state={authority?.administrativelyEligible ? 'Eligible' : 'Not eligible'} tone={authority?.administrativelyEligible ? 'success' : 'danger'} path={adminPath} />
              </Surface>
            </Box>

            <Surface title="Governance and operational attention" description="Explicit current work and failure signals from their owning semantic projections.">
              <Box sx={{ display: 'grid', gridTemplateColumns: { xs: '1fr', lg: 'repeat(2, minmax(0, 1fr))' }, columnGap: 3 }}>
                <SignalRow label="Pending approvals" state={attentionLabel(overview.value.attention.pendingApprovals)} tone={attentionTone(overview.value.attention.pendingApprovals)} path={requestsPath} />
                <SignalRow label="Pending reviews" state={attentionLabel(overview.value.attention.pendingReviews)} tone={attentionTone(overview.value.attention.pendingReviews)} path={reviewsPath} />
                <SignalRow label="Provisioning failures" state={attentionLabel(overview.value.attention.finalProvisioningFailures)} tone={attentionTone(overview.value.attention.finalProvisioningFailures)} path={integrationsPath} />
                <SignalRow label="Failed reconciliations" state={attentionLabel(overview.value.attention.failedReconciliations)} tone={attentionTone(overview.value.attention.failedReconciliations)} path={integrationsPath} />
              </Box>
            </Surface>

            <Box sx={{ display: 'grid', gridTemplateColumns: { xs: '1fr', lg: 'repeat(2, minmax(0, 1fr))' }, gap: { xs: 2, md: 3 } }}>
              <Surface title="Identities by type" description="Exact bounded governed Identity distribution." sx={{ height: '100%' }}>
                <BreakdownSummary breakdown={overview.value.tenantDashboard.identityTypes} total={overview.value.environment.activeIdentities} totalLabel="Active identities" labels={identityTypeLabels} action="View identities" path={identityPath} />
              </Surface>
              <Surface title="Applications by origin" description="Exact bounded governed Catalog application distribution." sx={{ height: '100%' }}>
                <BreakdownSummary breakdown={overview.value.tenantDashboard.applicationOrigins} total={overview.value.environment.applications} totalLabel="Active applications" labels={applicationOriginLabels} action="View applications" path={catalogPath} />
              </Surface>
            </Box>

            <Surface title="Recent activity" description="Bounded Audit-owned activity for this Tenant.">
              <RecentActivity overview={overview.value} auditPath={auditPath} />
            </Surface>

            <Surface title="Identity sources" description="Identity-owned SourceSystems with Integration operational posture shown separately from governed source identity.">
              <SourcesSummary overview={overview.value} integrationsPath={integrationsPath} />
            </Surface>
          </>
        );
      })() : null}
    </Page>
  );
}
