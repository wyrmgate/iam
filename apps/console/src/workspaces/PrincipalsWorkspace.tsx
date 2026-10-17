import { type FormEvent, type ReactNode, useEffect, useState } from 'react';
import { ApiError, apiRequest, type ApiResult, V1_ROOT, withCursor } from '../api';
import { identityDetailPath, navigateConsole } from '../consoleNavigation';
import {
  Box,
  Button,
  ConfirmDialog,
  DataTable,
  DataTableActionCell,
  DataTableRow,
  Modal,
  Page,
  PageHeader,
  StateIndicator,
  StatePanel,
  StatusChip,
  Surface,
  TableBody,
  TableCell,
  TableHead,
  TableRow,
  TextInput,
  Typography,
  type StateIndicatorTone,
} from '../ui';

type PrincipalLifecycleState = 'ACTIVE' | 'DISABLED';

type PrincipalResource = {
  id: string;
  applicationTargetId: string;
  identityId?: string | null;
  kind: 'ACCOUNT';
  nativePrincipalKey: string;
  lifecycleState: PrincipalLifecycleState;
  revision: number;
  createdAt: string;
  updatedAt: string;
};

type PrincipalPage = {
  items: PrincipalResource[];
  nextCursor?: string | null;
};

type ErrorContext = 'list' | 'register' | 'detail' | 'correlate';

const principalsPath = `${V1_ROOT}/principals`;
const principalPath = (principalId: string) => `${principalsPath}/${encodeURIComponent(principalId)}`;
const principalCorrelationPath = (principalId: string) => `${principalPath(principalId)}:correlate`;
const monoSx = { fontFamily: 'ui-monospace, SFMono-Regular, Menlo, Monaco, Consolas, monospace', overflowWrap: 'anywhere' } as const;

function formatDateTime(value: string): string {
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) return value;
  return date.toLocaleString([], { year: 'numeric', month: 'short', day: 'numeric', hour: '2-digit', minute: '2-digit' });
}

function lifecycleTone(state: PrincipalLifecycleState): StateIndicatorTone {
  return state === 'ACTIVE' ? 'success' : 'neutral';
}

function PrincipalErrorState({ error, context }: { error: unknown; context: ErrorContext }) {
  let state: 'error' | 'forbidden' | 'not-found' | 'conflict' | 'stale' = 'error';
  let title = context === 'list'
    ? 'Principals unavailable'
    : context === 'register'
      ? 'Principal registration failed'
      : context === 'detail'
        ? 'Principal unavailable'
        : 'Principal correlation failed';
  let detail = error instanceof Error ? error.message : 'Unable to complete the Principal operation.';
  let correlationId: string | null = null;

  if (error instanceof ApiError) {
    correlationId = error.correlationId;
    if (error.status === 401) {
      title = 'Authentication required';
      detail = 'A trusted control-plane session is required to work with Principals.';
    } else if (error.status === 403) {
      state = 'forbidden';
      title = 'Not authorized';
      detail = 'Administrative Authorization denied this operation. Operation-time server authorization remains final.';
    } else if (error.status === 404) {
      state = 'not-found';
      title = 'Principal not found';
    } else if (error.status === 409) {
      state = 'conflict';
      title = 'Principal operation conflict';
    } else if (error.status === 412) {
      state = 'stale';
      title = 'Principal revision changed';
      detail = 'Refresh the authoritative Principal and retry against its current revision.';
    }
  }

  return (
    <StatePanel state={state} title={title}>
      {detail}
      {correlationId ? (
        <Typography component="span" variant="caption" sx={{ ...monoSx, display: 'block', mt: 0.5 }}>
          Correlation: {correlationId}
        </Typography>
      ) : null}
    </StatePanel>
  );
}

function DefinitionList({ items }: { items: { label: string; value: ReactNode; mono?: boolean }[] }) {
  return (
    <Box
      component="dl"
      sx={{
        m: 0,
        display: 'grid',
        gridTemplateColumns: { xs: '1fr', sm: 'minmax(10rem, 13rem) minmax(0, 1fr)' },
        columnGap: 2,
        '& > dt, & > dd': { py: 1, m: 0, borderBottom: 1, borderColor: 'divider' },
        '& > dt': { color: 'text.secondary', fontSize: '0.75rem' },
        '& > dd': { fontSize: '0.8125rem', fontWeight: 600, minWidth: 0 },
      }}
    >
      {items.flatMap((item) => [
        <Box component="dt" key={`${item.label}-label`}>{item.label}</Box>,
        <Box component="dd" key={`${item.label}-value`} sx={item.mono ? monoSx : undefined}>{item.value}</Box>,
      ])}
    </Box>
  );
}

export function PrincipalsWorkspace() {
  const [pages, setPages] = useState<PrincipalPage[]>([]);
  const [listLoading, setListLoading] = useState(true);
  const [listError, setListError] = useState<unknown>(null);
  const [refreshVersion, setRefreshVersion] = useState(0);

  const [registerOpen, setRegisterOpen] = useState(false);
  const [applicationTargetId, setApplicationTargetId] = useState('');
  const [nativePrincipalKey, setNativePrincipalKey] = useState('');
  const [registering, setRegistering] = useState(false);
  const [registerError, setRegisterError] = useState<unknown>(null);

  const [lookupOpen, setLookupOpen] = useState(false);
  const [principalLookup, setPrincipalLookup] = useState('');
  const [detail, setDetail] = useState<ApiResult<PrincipalResource> | null>(null);
  const [detailLoading, setDetailLoading] = useState(false);
  const [detailError, setDetailError] = useState<unknown>(null);

  const [correlationOpen, setCorrelationOpen] = useState(false);
  const [correlationIdentityId, setCorrelationIdentityId] = useState('');
  const [correlationError, setCorrelationError] = useState<unknown>(null);
  const [correlating, setCorrelating] = useState(false);
  const [confirmCorrelation, setConfirmCorrelation] = useState(false);

  const principals = pages.flatMap((page) => page.items);
  const nextCursor = pages.at(-1)?.nextCursor ?? null;

  useEffect(() => {
    const controller = new AbortController();
    setListLoading(true);
    setListError(null);
    setPages([]);
    apiRequest<PrincipalPage>(withCursor(principalsPath), { signal: controller.signal })
      .then(({ data }) => setPages([data]))
      .catch((cause) => {
        if (!(cause instanceof DOMException && cause.name === 'AbortError')) setListError(cause);
      })
      .finally(() => setListLoading(false));
    return () => controller.abort();
  }, [refreshVersion]);

  async function openPrincipal(targetPrincipalId: string) {
    const normalizedPrincipalId = targetPrincipalId.trim();
    if (!normalizedPrincipalId) return;

    setDetailLoading(true);
    setDetailError(null);
    setCorrelationError(null);
    setCorrelationIdentityId('');
    setConfirmCorrelation(false);
    try {
      const result = await apiRequest<PrincipalResource>(principalPath(normalizedPrincipalId));
      setDetail(result);
      setLookupOpen(false);
      setPrincipalLookup('');
      window.scrollTo({ top: 0 });
    } catch (cause) {
      setDetail(null);
      setDetailError(cause);
    } finally {
      setDetailLoading(false);
    }
  }

  function closeDetail() {
    setDetail(null);
    setDetailError(null);
    setCorrelationOpen(false);
    setCorrelationIdentityId('');
    setCorrelationError(null);
    setConfirmCorrelation(false);
  }

  async function refreshDetail() {
    if (!detail) return;
    await openPrincipal(detail.data.id);
  }

  async function loadMore() {
    if (!nextCursor) return;
    setListLoading(true);
    setListError(null);
    try {
      const { data } = await apiRequest<PrincipalPage>(withCursor(principalsPath, nextCursor));
      setPages((current) => [...current, data]);
    } catch (cause) {
      setListError(cause);
    } finally {
      setListLoading(false);
    }
  }

  async function registerPrincipal(event: FormEvent) {
    event.preventDefault();
    const normalizedApplicationTargetId = applicationTargetId.trim();
    const normalizedNativePrincipalKey = nativePrincipalKey.trim();
    if (!normalizedApplicationTargetId || !normalizedNativePrincipalKey) return;

    setRegistering(true);
    setRegisterError(null);
    try {
      const result = await apiRequest<PrincipalResource>(principalsPath, {
        method: 'POST',
        idempotent: true,
        body: {
          applicationTargetId: normalizedApplicationTargetId,
          nativePrincipalKey: normalizedNativePrincipalKey,
        },
      });
      setApplicationTargetId('');
      setNativePrincipalKey('');
      setRegisterOpen(false);
      setDetail(result);
      setDetailError(null);
      setCorrelationIdentityId('');
      setCorrelationError(null);
      setRefreshVersion((current) => current + 1);
      window.scrollTo({ top: 0 });
    } catch (cause) {
      setRegisterError(cause);
    } finally {
      setRegistering(false);
    }
  }

  function requestCorrelation(event: FormEvent) {
    event.preventDefault();
    if (!detail || detail.data.identityId || !correlationIdentityId.trim()) return;
    if (!detail.etag) {
      setCorrelationError(new Error('The current Principal ETag is unavailable. Refresh the Principal before correlating it.'));
      return;
    }
    setCorrelationError(null);
    setConfirmCorrelation(true);
  }

  async function correlatePrincipal() {
    if (!detail || detail.data.identityId || !detail.etag) return;
    const normalizedIdentityId = correlationIdentityId.trim();
    if (!normalizedIdentityId) return;

    setCorrelating(true);
    setCorrelationError(null);
    try {
      const result = await apiRequest<PrincipalResource>(principalCorrelationPath(detail.data.id), {
        method: 'POST',
        etag: detail.etag,
        idempotent: true,
        body: { identityId: normalizedIdentityId },
      });
      setDetail(result);
      setCorrelationIdentityId('');
      setConfirmCorrelation(false);
      setCorrelationOpen(false);
      setRefreshVersion((current) => current + 1);
    } catch (cause) {
      setCorrelationError(cause);
      setConfirmCorrelation(false);
    } finally {
      setCorrelating(false);
    }
  }

  if (detail) {
    const principal = detail.data;
    const correlated = Boolean(principal.identityId);

    return (
      <Page width="wide">
        <Button variant="text" sx={{ justifySelf: 'start' }} onClick={closeDetail}>Back to Principals</Button>
        <PageHeader
          eyebrow="Principal · technical account"
          title={(
            <Box sx={{ display: 'flex', alignItems: 'center', flexWrap: 'wrap', gap: 1.25 }}>
              <Box component="span" sx={monoSx}>{principal.nativePrincipalKey}</Box>
              <StatusChip label={principal.kind} />
              <StateIndicator label={principal.lifecycleState} tone={lifecycleTone(principal.lifecycleState)} />
            </Box>
          )}
          description={(
            <Box sx={{ display: 'flex', flexWrap: 'wrap', gap: 1.5, color: 'text.secondary' }}>
              <Box component="span">Principal ID: <Box component="span" sx={monoSx}>{principal.id}</Box></Box>
              <Box component="span">Updated {formatDateTime(principal.updatedAt)}</Box>
            </Box>
          )}
          actions={(
            <>
              <Button variant="outlined" disabled={detailLoading} onClick={() => void refreshDetail()}>{detailLoading ? 'Refreshing…' : 'Refresh'}</Button>
              {!correlated ? <Button variant="contained" onClick={() => setCorrelationOpen(true)}>Correlate to Identity</Button> : null}
            </>
          )}
        />

        {detailError !== null ? <PrincipalErrorState error={detailError} context="detail" /> : null}
        {correlationError !== null && !correlationOpen ? <PrincipalErrorState error={correlationError} context="correlate" /> : null}

        <Surface title="Technical account" description="Authoritative Identity-owned Principal state. Provider observation, desired state, fulfillment and Credentials remain separate resources.">
          <DefinitionList items={[
            { label: 'Native principal key', value: principal.nativePrincipalKey, mono: true },
            { label: 'ApplicationTarget', value: principal.applicationTargetId, mono: true },
            { label: 'Kind', value: principal.kind },
            { label: 'Lifecycle', value: <StateIndicator label={principal.lifecycleState} tone={lifecycleTone(principal.lifecycleState)} /> },
            { label: 'Created', value: formatDateTime(principal.createdAt) },
            { label: 'Updated', value: formatDateTime(principal.updatedAt) },
            { label: 'Principal ID', value: principal.id, mono: true },
            { label: 'Revision', value: principal.revision },
          ]} />
        </Surface>

        <Surface
          title="Governed Identity relationship"
          description="Principal is the technical account, not the governed subject. Correlation is an explicit Identity-owned relationship and does not create AccessAssignment authority or provider mutation."
          actions={correlated && principal.identityId ? <Button variant="outlined" size="small" onClick={() => navigateConsole(identityDetailPath(principal.identityId!))}>Open governed Identity</Button> : undefined}
        >
          {correlated && principal.identityId ? (
            <Box sx={{ display: 'grid', gap: 1 }}>
              <Box sx={{ display: 'flex', flexWrap: 'wrap', gap: 1, alignItems: 'center' }}>
                <StatusChip label="Correlated" />
                <Typography variant="body2" sx={monoSx}>{principal.identityId}</Typography>
              </Box>
              <Typography variant="body2" color="text.secondary">
                The accepted public operation is one-way: this Principal cannot be reassigned or cleared through the current Principal API.
              </Typography>
            </Box>
          ) : (
            <Box sx={{ display: 'grid', gap: 1 }}>
              <StatusChip label="Uncorrelated" />
              <Typography variant="body2" color="text.secondary">
                This authoritative technical account is not yet linked to a governed Identity. Correlation requires a current same-tenant Identity, the current Principal revision, causal idempotency and operation-time authorization.
              </Typography>
              <Button variant="outlined" sx={{ justifySelf: 'start' }} onClick={() => setCorrelationOpen(true)}>Review Identity correlation</Button>
            </Box>
          )}
        </Surface>

        <StatePanel state="informational" title="Authority boundaries remain separate">
          Credential belongs to Principal but is governed by the Credential capability. Provider observations, desired technical state, provisioning fulfillment and connector execution remain outside this authoritative Principal view.
        </StatePanel>

        <Modal
          open={correlationOpen && !correlated}
          title="Correlate Principal to governed Identity"
          description="Correlation is a one-way authoritative relationship in the current public contract. Review the target Identity before confirming."
          busy={correlating}
          onClose={() => {
            if (!correlating) {
              setCorrelationOpen(false);
              setCorrelationIdentityId('');
              setCorrelationError(null);
            }
          }}
        >
          <Box component="form" onSubmit={requestCorrelation} sx={{ display: 'grid', gap: 1.5 }}>
            <TextInput
              autoFocus
              label="Governed Identity ID"
              required
              value={correlationIdentityId}
              helperText="The server requires a same-tenant canonical Identity and revalidates authorization and the current Principal revision."
              onChange={(event) => setCorrelationIdentityId(event.target.value)}
            />
            {correlationError !== null ? <PrincipalErrorState error={correlationError} context="correlate" /> : null}
            <Box sx={{ display: 'flex', justifyContent: 'flex-end', gap: 1 }}>
              <Button variant="text" disabled={correlating} onClick={() => setCorrelationOpen(false)}>Cancel</Button>
              <Button type="submit" variant="contained" disabled={correlating || !correlationIdentityId.trim()}>Review correlation</Button>
            </Box>
          </Box>
        </Modal>

        <ConfirmDialog
          open={confirmCorrelation}
          title="Correlate this Principal?"
          confirmLabel="Correlate to Identity"
          busy={correlating}
          onCancel={() => setConfirmCorrelation(false)}
          onConfirm={() => void correlatePrincipal()}
        >
          Correlate Principal {principal.nativePrincipalKey} to Identity {correlationIdentityId.trim()}. The accepted public contract is one-way: an already correlated Principal cannot be reassigned or cleared through this operation. Correlation does not grant access or mutate provider state.
        </ConfirmDialog>
      </Page>
    );
  }

  return (
    <Page width="wide" aria-labelledby="principals-page-title">
      <PageHeader
        eyebrow="Identity · technical representations"
        title={<span id="principals-page-title">Principals</span>}
        description="Find and investigate authoritative technical accounts without treating them as governed Identities, Credentials, or provider observations."
        actions={(
          <>
            <Button variant="outlined" disabled={listLoading} onClick={() => setRefreshVersion((current) => current + 1)}>{listLoading ? 'Refreshing…' : 'Refresh'}</Button>
            <Button variant="contained" onClick={() => setRegisterOpen(true)}>Register Principal</Button>
          </>
        )}
      />

      <Surface
        title="Authoritative Principals"
        description="Identity-owned Principal state only. Provider observations, desired state, fulfillment state, Credentials, and connector execution data are not part of this resource."
        actions={<Button variant="outlined" size="small" onClick={() => setLookupOpen(true)}>Open by Principal ID</Button>}
      >
        {listLoading && principals.length === 0 ? (
          <StatePanel state="loading" title="Loading Principals">Reading authoritative Principal state.</StatePanel>
        ) : null}
        {!listLoading && principals.length === 0 && listError === null ? (
          <StatePanel state="empty" title="No Principals found">No authoritative technical accounts are currently visible to this administrative context.</StatePanel>
        ) : null}
        {listError !== null ? <PrincipalErrorState error={listError} context="list" /> : null}
        {principals.length > 0 ? (
          <>
            <DataTable minWidth={980} ariaLabel="Authoritative Principals">
              <TableHead>
                <TableRow>
                  <TableCell component="th" scope="col">Technical account</TableCell>
                  <TableCell component="th" scope="col">ApplicationTarget</TableCell>
                  <TableCell component="th" scope="col">Identity correlation</TableCell>
                  <TableCell component="th" scope="col">Lifecycle</TableCell>
                  <TableCell component="th" scope="col">Updated</TableCell>
                  <TableCell component="th" scope="col" align="right">Action</TableCell>
                </TableRow>
              </TableHead>
              <TableBody>
                {principals.map((principal) => (
                  <DataTableRow key={principal.id} interactive>
                    <TableCell>
                      <Button variant="text" size="small" onClick={() => void openPrincipal(principal.id)} sx={{ p: 0, minHeight: 0, justifyContent: 'flex-start', textAlign: 'left' }}>
                        <Box sx={{ display: 'grid', gap: 0.25 }}>
                          <Typography component="span" variant="body2" sx={{ ...monoSx, color: 'text.primary', fontWeight: 700 }}>{principal.nativePrincipalKey}</Typography>
                          <Typography component="span" variant="caption" color="text.secondary" sx={monoSx}>{principal.id}</Typography>
                        </Box>
                      </Button>
                    </TableCell>
                    <TableCell><Typography component="span" variant="body2" sx={monoSx}>{principal.applicationTargetId}</Typography></TableCell>
                    <TableCell>
                      <Box sx={{ display: 'grid', gap: 0.5, justifyItems: 'start' }}>
                        <StatusChip label={principal.identityId ? 'Correlated' : 'Uncorrelated'} />
                        {principal.identityId ? <Typography component="span" variant="caption" color="text.secondary" sx={monoSx}>{principal.identityId}</Typography> : null}
                      </Box>
                    </TableCell>
                    <TableCell><StateIndicator label={principal.lifecycleState} tone={lifecycleTone(principal.lifecycleState)} /></TableCell>
                    <TableCell><time dateTime={principal.updatedAt}>{formatDateTime(principal.updatedAt)}</time></TableCell>
                    <DataTableActionCell><Button size="small" variant="outlined" aria-label={`Open Principal ${principal.nativePrincipalKey}`} onClick={() => void openPrincipal(principal.id)}>View</Button></DataTableActionCell>
                  </DataTableRow>
                ))}
              </TableBody>
            </DataTable>
            <Box sx={{ display: 'flex', flexDirection: { xs: 'column', sm: 'row' }, alignItems: { xs: 'stretch', sm: 'center' }, justifyContent: 'space-between', gap: 1.25, mt: 2 }}>
              <Typography variant="caption" color="text.secondary">{nextCursor ? 'More Principals are available.' : 'End of visible collection.'}</Typography>
              {nextCursor ? <Button variant="outlined" disabled={listLoading} onClick={() => void loadMore()}>{listLoading ? 'Loading…' : 'Load more Principals'}</Button> : null}
            </Box>
          </>
        ) : null}
      </Surface>

      <StatePanel state="informational" title="Principal is the technical account, not the governed subject">
        A Principal represents an account in one ApplicationTarget and may be temporarily uncorrelated. Provider observation does not become Principal authority, Credentials remain separate, and operation-time server authorization remains final.
      </StatePanel>

      <Modal
        open={registerOpen}
        title="Register Principal"
        description="Register one known technical account. The server fixes the public shape to uncorrelated ACCOUNT + ACTIVE; registration does not create provider state or choose a governed Identity."
        busy={registering}
        onClose={() => {
          if (!registering) {
            setRegisterOpen(false);
            setRegisterError(null);
          }
        }}
      >
        <Box component="form" onSubmit={registerPrincipal} sx={{ display: 'grid', gap: 1.5 }}>
          <TextInput
            autoFocus
            label="Application Target ID"
            required
            value={applicationTargetId}
            helperText="Catalog-owned technical target for this account. Registration does not create provider state."
            onChange={(event) => setApplicationTargetId(event.target.value)}
          />
          <TextInput
            label="Native principal key"
            required
            value={nativePrincipalKey}
            helperText="Target-native account key only. Do not enter passwords, tokens, private keys, or connector secrets."
            onChange={(event) => setNativePrincipalKey(event.target.value)}
          />
          <StatePanel state="informational" title="Registration remains uncorrelated">
            Registration does not select or create a governed Identity, change provider lifecycle, or accept credential material. Correlate the Principal separately after reviewing the authoritative account.
          </StatePanel>
          {registerError !== null ? <PrincipalErrorState error={registerError} context="register" /> : null}
          <Box sx={{ display: 'flex', justifyContent: 'flex-end', gap: 1 }}>
            <Button variant="text" disabled={registering} onClick={() => setRegisterOpen(false)}>Cancel</Button>
            <Button type="submit" variant="contained" disabled={registering || !applicationTargetId.trim() || !nativePrincipalKey.trim()}>{registering ? 'Registering…' : 'Register Principal'}</Button>
          </Box>
        </Box>
      </Modal>

      <Modal open={lookupOpen} title="Open Principal by ID" description="Direct resource lookup is an advanced support path and remains server-authorized." busy={detailLoading} onClose={() => { if (!detailLoading) { setLookupOpen(false); setDetailError(null); } }}>
        <Box sx={{ display: 'grid', gap: 1.5 }}>
          <TextInput autoFocus label="Principal ID" value={principalLookup} onChange={(event) => setPrincipalLookup(event.target.value)} onKeyDown={(event) => { if (event.key === 'Enter' && principalLookup.trim()) void openPrincipal(principalLookup); }} />
          {detailError !== null ? <PrincipalErrorState error={detailError} context="detail" /> : null}
          <Box sx={{ display: 'flex', justifyContent: 'flex-end', gap: 1 }}>
            <Button variant="text" disabled={detailLoading} onClick={() => setLookupOpen(false)}>Cancel</Button>
            <Button variant="contained" disabled={detailLoading || !principalLookup.trim()} onClick={() => void openPrincipal(principalLookup)}>{detailLoading ? 'Loading…' : 'Open Principal'}</Button>
          </Box>
        </Box>
      </Modal>
    </Page>
  );
}
