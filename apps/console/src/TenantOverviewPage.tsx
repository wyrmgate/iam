import { useEffect, useState } from 'react';
import type { CurrentAdministrativeAuthority } from './api';
import {
  getIamOperationsOverview,
  type IamOperationsOverview,
  type OverviewMetric,
  type OverviewStatus,
} from './iamOperationsApi';
import { navigateConsole } from './consoleNavigation';
import {
  Box,
  Button,
  DataTable,
  DataTableRow,
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

type LoadState =
  | { status: 'idle' }
  | { status: 'loading' }
  | { status: 'ready'; value: IamOperationsOverview }
  | { status: 'error'; error: unknown };

function shortId(value: string): string {
  return value.length <= 32 ? value : `${value.slice(0, 12)}…${value.slice(-8)}`;
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
  if (metric.status !== 'AVAILABLE' || metric.value === null) return null;
  return metric.value.toLocaleString();
}

function errorMessage(error: unknown): string {
  return error instanceof Error ? error.message : 'Wyrmgate could not load the current Tenant overview.';
}

function Definition({ term, children }: { term: string; children: React.ReactNode }) {
  return (
    <Box component="div" sx={{ display: 'grid', gap: 0.5, minWidth: 0 }}>
      <Typography component="dt" variant="caption" color="text.secondary">{term}</Typography>
      <Typography component="dd" variant="body2" sx={{ m: 0, fontWeight: 600, overflowWrap: 'anywhere' }}>{children}</Typography>
    </Box>
  );
}

function MetricRow({
  label,
  metric,
  detail,
  action,
  onOpen,
}: {
  label: string;
  metric: OverviewMetric;
  detail: string;
  action: string;
  onOpen: () => void;
}) {
  const value = metricText(metric);
  return (
    <Box
      sx={{
        display: 'grid',
        gridTemplateColumns: { xs: '1fr auto', sm: 'minmax(0, 1fr) auto auto' },
        columnGap: 2,
        rowGap: 0.5,
        alignItems: 'center',
        py: 1.25,
        borderBottom: '1px solid',
        borderColor: 'divider',
        '&:last-of-type': { borderBottom: 0, pb: 0 },
        '&:first-of-type': { pt: 0 },
      }}
    >
      <Box sx={{ minWidth: 0 }}>
        <Typography variant="body2" sx={{ fontWeight: 600 }}>{label}</Typography>
        <Typography variant="caption" color="text.secondary">{detail}</Typography>
      </Box>
      <Box sx={{ justifySelf: 'end' }}>
        {value !== null
          ? <Typography variant="body2" sx={{ fontWeight: 700 }}>{value}</Typography>
          : <StateIndicator label={statusLabel(metric.status)} tone={statusTone(metric.status)} />}
      </Box>
      <Button
        variant="text"
        size="small"
        onClick={onOpen}
        sx={{ gridColumn: { xs: '1 / -1', sm: 'auto' }, justifySelf: { xs: 'start', sm: 'end' } }}
      >
        {action}
      </Button>
    </Box>
  );
}

function AttentionRow({
  label,
  metric,
  description,
  path,
}: {
  label: string;
  metric: OverviewMetric;
  description: string;
  path: string;
}) {
  const value = metricText(metric);
  const tone: StateIndicatorTone = metric.status !== 'AVAILABLE'
    ? statusTone(metric.status)
    : (metric.value ?? 0) > 0 ? 'warning' : 'success';
  const state = metric.status === 'AVAILABLE'
    ? `${value ?? '0'} ${label.toLowerCase()}`
    : statusLabel(metric.status);

  return (
    <Box
      sx={{
        display: 'grid',
        gridTemplateColumns: { xs: '1fr', sm: 'minmax(0, 1fr) auto' },
        gap: 1,
        py: 1.25,
        borderBottom: '1px solid',
        borderColor: 'divider',
        '&:last-of-type': { borderBottom: 0, pb: 0 },
        '&:first-of-type': { pt: 0 },
      }}
    >
      <Box>
        <StateIndicator label={state} tone={tone} />
        <Typography variant="caption" color="text.secondary" sx={{ display: 'block', mt: 0.5 }}>{description}</Typography>
      </Box>
      <Button variant="text" size="small" onClick={() => navigateConsole(path)} sx={{ justifySelf: { xs: 'start', sm: 'end' } }}>
        Review
      </Button>
    </Box>
  );
}

function IntegrationSummary({ overview }: { overview: IamOperationsOverview }) {
  const provisioning = overview.provisioning;
  const reconciliation = overview.reconciliation;

  return (
    <Box sx={{ display: 'grid', gap: 2 }}>
      <Box>
        <Typography variant="body2" sx={{ fontWeight: 600, mb: 1 }}>Provisioning</Typography>
        {provisioning.status === 'AVAILABLE' && provisioning.summary ? (
          <Box component="dl" sx={{ m: 0, display: 'grid', gridTemplateColumns: 'repeat(2, minmax(0, 1fr))', gap: 2 }}>
            <Definition term="Running now">{provisioning.summary.runningTasksNow.toLocaleString()}</Definition>
            <Definition term="Final failures · 24h">{provisioning.summary.finalFailedAttempts.toLocaleString()}</Definition>
            <Definition term="Succeeded · 24h">{provisioning.summary.succeededAttempts.toLocaleString()}</Definition>
            <Definition term="Retryable failures · 24h">{provisioning.summary.retryableFailedAttempts.toLocaleString()}</Definition>
          </Box>
        ) : <StateIndicator label={statusLabel(provisioning.status)} tone={statusTone(provisioning.status)} />}
      </Box>

      <Box sx={{ pt: 2, borderTop: '1px solid', borderColor: 'divider' }}>
        <Typography variant="body2" sx={{ fontWeight: 600, mb: 1 }}>Reconciliation</Typography>
        {reconciliation.status === 'AVAILABLE' && reconciliation.summary ? (
          <Box component="dl" sx={{ m: 0, display: 'grid', gridTemplateColumns: 'repeat(2, minmax(0, 1fr))', gap: 2 }}>
            <Definition term="Running now">{reconciliation.summary.runningNow.toLocaleString()}</Definition>
            <Definition term="Failed · 24h">{reconciliation.summary.failedInWindow.toLocaleString()}</Definition>
            <Definition term="Complete + succeeded · 24h">{reconciliation.summary.completeSucceededInWindow.toLocaleString()}</Definition>
            <Definition term="Last effectively complete">{formatDateTime(reconciliation.summary.lastCompleteAt)}</Definition>
          </Box>
        ) : <StateIndicator label={statusLabel(reconciliation.status)} tone={statusTone(reconciliation.status)} />}
      </Box>

      <Button variant="outlined" size="small" onClick={() => navigateConsole('/integrations')} sx={{ justifySelf: 'start' }}>
        Open integrations
      </Button>
    </Box>
  );
}

export function TenantOverviewPage({ authority }: Props) {
  const [overview, setOverview] = useState<LoadState>({ status: 'idle' });
  const [reloadKey, setReloadKey] = useState(0);

  useEffect(() => {
    if (!authority) {
      setOverview({ status: 'idle' });
      return;
    }
    const controller = new AbortController();
    setOverview({ status: 'loading' });
    getIamOperationsOverview('24h', 'ALL', 'OLDEST', controller.signal)
      .then((value) => setOverview({ status: 'ready', value }))
      .catch((error: unknown) => {
        if (error instanceof DOMException && error.name === 'AbortError') return;
        setOverview({ status: 'error', error });
      });
    return () => controller.abort();
  }, [authority?.tenantId, reloadKey]);

  return (
    <Page width="standard" aria-labelledby="tenant-page-title">
      <PageHeader
        eyebrow="Administration"
        title={<span id="tenant-page-title">Tenant Overview</span>}
        description={authority
          ? `Current server-resolved Tenant · ${shortId(authority.tenantId)}`
          : 'Current server-resolved Tenant'}
        actions={(
          <Button
            variant="outlined"
            disabled={!authority || overview.status === 'loading'}
            onClick={() => setReloadKey((value) => value + 1)}
          >
            {overview.status === 'loading' ? 'Refreshing…' : 'Refresh'}
          </Button>
        )}
      />

      {!authority ? (
        <StatePanel state="loading" title="Resolving Tenant context">
          Reading the current governed actor and server-resolved Tenant isolation boundary.
        </StatePanel>
      ) : (
        <Surface title="Tenant details" description="Control-plane context for the Tenant currently resolved by the server.">
          <Box
            component="dl"
            sx={{
              m: 0,
              display: 'grid',
              gridTemplateColumns: { xs: '1fr', sm: 'repeat(2, minmax(0, 1fr))' },
              gap: 2.5,
            }}
          >
            <Definition term="Tenant ID">{authority.tenantId}</Definition>
            <Definition term="Current actor">{authority.actorIdentityId}</Definition>
            <Definition term="Administrative eligibility">{authority.administrativelyEligible ? 'Eligible' : 'Not eligible'}</Definition>
            <Definition term="Authority evaluated"><time dateTime={authority.evaluatedAt}>{formatDateTime(authority.evaluatedAt)}</time></Definition>
          </Box>
        </Surface>
      )}

      {overview.status === 'loading' ? (
        <StatePanel state="loading" title="Loading Tenant overview">
          Reading tenant-scoped IAM, governance, integration and evidence projections.
        </StatePanel>
      ) : null}

      {overview.status === 'error' ? (
        <StatePanel
          state="error"
          title="Tenant overview is unavailable"
          action={<Button variant="outlined" onClick={() => setReloadKey((value) => value + 1)}>Retry</Button>}
        >
          {errorMessage(overview.error)} Linked capability workspaces remain server-authorized independently.
        </StatePanel>
      ) : null}

      {overview.status === 'ready' ? (
        <>
          <Box sx={{ display: 'grid', gridTemplateColumns: { xs: '1fr', lg: 'minmax(0, 2fr) minmax(320px, 1fr)' }, gap: 3 }}>
            <Surface
              title="IAM estate"
              description={`Tenant-scoped authoritative and projected inventory as of ${formatDateTime(overview.value.environment.asOf)}.`}
            >
              <MetricRow
                label="Identities"
                metric={overview.value.environment.activeIdentities}
                detail="Active governed subjects in this Tenant."
                action="Open identities"
                onOpen={() => navigateConsole('/identities')}
              />
              <MetricRow
                label="Applications"
                metric={overview.value.environment.applications}
                detail="Tenant-local applications, including bindings admitted from platform offerings."
                action="Open Catalog"
                onOpen={() => navigateConsole('/catalog')}
              />
              <MetricRow
                label="Access assignments"
                metric={overview.value.environment.activeAccessAssignments}
                detail="Authoritative business access intent; EffectiveAccess remains a separate projection."
                action="Open access"
                onOpen={() => navigateConsole('/access')}
              />
              <MetricRow
                label="Connectors"
                metric={overview.value.environment.activeConnectors}
                detail="Active tenant-scoped Integration connector instances."
                action="Open integrations"
                onOpen={() => navigateConsole('/integrations')}
              />
              <MetricRow
                label="Uncorrelated principals"
                metric={overview.value.environment.uncorrelatedPrincipals}
                detail="Technical accounts not currently correlated to a governed Identity."
                action="Open principals"
                onOpen={() => navigateConsole('/principals')}
              />
            </Surface>

            <Surface title="Requires attention" description="Current tenant-scoped work and operational exceptions.">
              <AttentionRow
                label="Pending approvals"
                metric={overview.value.attention.pendingApprovals}
                description="Governance approval work awaiting decision."
                path="/requests?view=approvals"
              />
              <AttentionRow
                label="Pending reviews"
                metric={overview.value.attention.pendingReviews}
                description="Access-review items awaiting reviewer action."
                path="/reviews"
              />
              <AttentionRow
                label="Final provisioning failures"
                metric={overview.value.attention.finalProvisioningFailures}
                description="Provisioning failures requiring operator investigation."
                path="/integrations"
              />
              <AttentionRow
                label="Failed reconciliations"
                metric={overview.value.attention.failedReconciliations}
                description="Reconciliation runs that failed in the selected 24-hour window."
                path="/integrations"
              />
            </Surface>
          </Box>

          <Box sx={{ display: 'grid', gridTemplateColumns: { xs: '1fr', lg: 'minmax(0, 2fr) minmax(320px, 1fr)' }, gap: 3 }}>
            <Surface title="Integration posture" description="Operational fulfillment and provider-observation posture; not Tenant business state.">
              <IntegrationSummary overview={overview.value} />
            </Surface>

            <Surface title="Administration" description="Tenant administrative authority is distinct from application/business access.">
              <Box component="dl" sx={{ m: 0, display: 'grid', gap: 2 }}>
                <Definition term="Current actor">{shortId(authority?.actorIdentityId ?? overview.value.tenantId)}</Definition>
                <Definition term="Administrative eligibility">{authority?.administrativelyEligible ? 'Eligible' : 'Not eligible'}</Definition>
                <Definition term="Effective authority entries">{authority?.authorities.length.toLocaleString() ?? '—'}</Definition>
              </Box>
              <Typography variant="caption" color="text.secondary" sx={{ display: 'block', mt: 2 }}>
                Tenant Owner and other grants remain Administration-owned authority. Platform Owner authority remains deployment-scoped.
              </Typography>
              <Button variant="outlined" size="small" onClick={() => navigateConsole('/administration')} sx={{ mt: 2 }}>
                Open administrators
              </Button>
            </Surface>
          </Box>

          <Surface
            title="Recent evidence"
            description="Recent denied or failed Audit evidence for this Tenant. This is evidence, not mutable governance state."
          >
            {overview.value.securityEvidence.status !== 'AVAILABLE' ? (
              <StateIndicator
                label={statusLabel(overview.value.securityEvidence.status)}
                tone={statusTone(overview.value.securityEvidence.status)}
              />
            ) : overview.value.securityEvidence.recent.length === 0 ? (
              <StatePanel state="empty" title="No recent denied or failed evidence">
                No matching Audit evidence occurred in the current 24-hour window.
              </StatePanel>
            ) : (
              <DataTable ariaLabel="Recent Tenant Audit evidence" minWidth={760}>
                <TableHead>
                  <TableRow>
                    <TableCell>Time</TableCell>
                    <TableCell>Outcome</TableCell>
                    <TableCell>Action</TableCell>
                    <TableCell>Resource</TableCell>
                  </TableRow>
                </TableHead>
                <TableBody>
                  {overview.value.securityEvidence.recent.map((item) => (
                    <DataTableRow key={item.id}>
                      <TableCell><time dateTime={item.occurredAt}>{formatDateTime(item.occurredAt)}</time></TableCell>
                      <TableCell><StateIndicator label={item.outcome} tone="danger" /></TableCell>
                      <TableCell>{item.actionType}</TableCell>
                      <TableCell>
                        <Typography variant="body2" sx={{ fontWeight: 600 }}>{item.resourceType}</Typography>
                        {item.resourceId ? <Typography variant="caption" color="text.secondary">{shortId(item.resourceId)}</Typography> : null}
                      </TableCell>
                    </DataTableRow>
                  ))}
                </TableBody>
              </DataTable>
            )}
            <Button variant="text" size="small" onClick={() => navigateConsole('/audit')} sx={{ mt: 2 }}>
              Open Audit
            </Button>
          </Surface>
        </>
      ) : null}
    </Page>
  );
}
