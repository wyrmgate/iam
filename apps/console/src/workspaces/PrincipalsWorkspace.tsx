import { type FormEvent, type ReactNode, useEffect, useState } from 'react';
import { ApiError, apiRequest, type ApiResult, V1_ROOT, withCursor } from '../api';
import {
  Box,
  Button,
  ConfirmDialog,
  DataTable,
  Page,
  PageHeader,
  StatePanel,
  StatusChip,
  Surface,
  TableBody,
  TableCell,
  TableHead,
  TableRow,
  TextInput,
  Typography,
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
        <Typography component="span" variant="caption" className="mono" sx={{ display: 'block', mt: 0.5 }}>
          Correlation: {correlationId}
        </Typography>
      ) : null}
    </StatePanel>
  );
}

function DetailDatum({ label, children }: { label: string; children: ReactNode }) {
  return (
    <Box sx={{ minWidth: 0, p: 1.5, border: 1, borderColor: 'divider', borderRadius: 1, bgcolor: 'background.default' }}>
      <Typography component="dt" variant="caption" color="text.secondary" sx={{ textTransform: 'uppercase', fontWeight: 700 }}>
        {label}
      </Typography>
      <Typography component="dd" variant="body2" sx={{ m: 0, mt: 0.5, overflowWrap: 'anywhere' }}>
        {children}
      </Typography>
    </Box>
  );
}

function PrincipalDetail({ principal }: { principal: PrincipalResource }) {
  const correlated = Boolean(principal.identityId);

  return (
    <Box component="dl" sx={{ display: 'grid', gridTemplateColumns: { xs: '1fr', md: 'repeat(2, minmax(0, 1fr))' }, gap: 1.25, m: 0 }}>
      <DetailDatum label="Native principal key">
        <Typography component="span" className="mono" variant="body2">{principal.nativePrincipalKey}</Typography>
      </DetailDatum>
      <DetailDatum label="ApplicationTarget">
        <Typography component="span" className="mono" variant="body2">{principal.applicationTargetId}</Typography>
      </DetailDatum>
      <DetailDatum label="Identity correlation">
        <Box sx={{ display: 'flex', flexWrap: 'wrap', gap: 0.75, alignItems: 'center' }}>
          <StatusChip label={correlated ? 'Correlated' : 'Uncorrelated'} />
          {principal.identityId ? <Typography component="span" className="mono" variant="body2">{principal.identityId}</Typography> : null}
        </Box>
      </DetailDatum>
      <DetailDatum label="Lifecycle">
        <StatusChip label={principal.lifecycleState} />
      </DetailDatum>
      <DetailDatum label="Principal ID">
        <Typography component="span" className="mono" variant="body2">{principal.id}</Typography>
      </DetailDatum>
      <DetailDatum label="Kind">{principal.kind}</DetailDatum>
      <DetailDatum label="Revision">{principal.revision}</DetailDatum>
      <DetailDatum label="Updated">{principal.updatedAt}</DetailDatum>
    </Box>
  );
}

export function PrincipalsWorkspace() {
  const [pages, setPages] = useState<PrincipalPage[]>([]);
  const [listLoading, setListLoading] = useState(true);
  const [listError, setListError] = useState<unknown>(null);
  const [refreshVersion, setRefreshVersion] = useState(0);

  const [applicationTargetId, setApplicationTargetId] = useState('');
  const [nativePrincipalKey, setNativePrincipalKey] = useState('');
  const [registering, setRegistering] = useState(false);
  const [registerError, setRegisterError] = useState<unknown>(null);

  const [principalId, setPrincipalId] = useState('');
  const [detail, setDetail] = useState<ApiResult<PrincipalResource> | null>(null);
  const [detailLoading, setDetailLoading] = useState(false);
  const [detailError, setDetailError] = useState<unknown>(null);

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

  async function openPrincipal(targetPrincipalId = principalId) {
    const normalizedPrincipalId = targetPrincipalId.trim();
    if (!normalizedPrincipalId) return;

    setDetailLoading(true);
    setDetailError(null);
    setCorrelationError(null);
    setCorrelationIdentityId('');
    setConfirmCorrelation(false);
    try {
      const result = await apiRequest<PrincipalResource>(principalPath(normalizedPrincipalId));
      setPrincipalId(normalizedPrincipalId);
      setDetail(result);
    } catch (cause) {
      setDetail(null);
      setDetailError(cause);
    } finally {
      setDetailLoading(false);
    }
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
      setPrincipalId(result.data.id);
      setDetail(result);
      setDetailError(null);
      setCorrelationIdentityId('');
      setCorrelationError(null);
      setRefreshVersion((current) => current + 1);
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
      setRefreshVersion((current) => current + 1);
    } catch (cause) {
      setCorrelationError(cause);
      setConfirmCorrelation(false);
    } finally {
      setCorrelating(false);
    }
  }

  return (
    <Page width="wide">
      <PageHeader
        eyebrow="Identity"
        title="Principals"
        description="Technical account authority owned by Identity. Operate Identity-owned technical accounts without treating them as governed Identities or provider observations. Registration creates an uncorrelated Principal; correlation is an explicit one-way operation."
      />

      <StatePanel state="informational" title="Principal is the technical account, not the governed subject">
        A Principal represents an account in one ApplicationTarget and may be temporarily uncorrelated. Provider observation does not become Principal authority, and operation-time server authorization remains final.
      </StatePanel>

      <Surface
        title="Register technical account"
        description="Register a known account against an existing ApplicationTarget. The server fixes the public registration shape to an uncorrelated ACCOUNT Principal in ACTIVE lifecycle state."
      >
        <Box component="form" onSubmit={registerPrincipal} sx={{ display: 'grid', gap: 1.5 }}>
          <Box sx={{ display: 'grid', gridTemplateColumns: { xs: '1fr', md: 'repeat(2, minmax(0, 1fr))' }, gap: 1.5 }}>
            <TextInput
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
          </Box>
          <StatePanel state="informational" title="Registration remains uncorrelated">
            Registration does not select or create a governed Identity, change provider lifecycle, or accept credential material. Correlate the Principal separately after reviewing the authoritative account.
          </StatePanel>
          {registerError !== null ? <PrincipalErrorState error={registerError} context="register" /> : null}
          <Button
            type="submit"
            variant="contained"
            disabled={registering || !applicationTargetId.trim() || !nativePrincipalKey.trim()}
            sx={{ justifySelf: 'start' }}
          >
            {registering ? 'Registering…' : 'Register Principal'}
          </Button>
        </Box>
      </Surface>

      <Surface
        title="Authoritative Principals"
        description="Identity-owned Principal state only. Provider observations, desired state, fulfillment state, Credentials, and connector execution data are not part of this resource."
        actions={<Button variant="outlined" disabled={listLoading} onClick={() => setRefreshVersion((current) => current + 1)}>Refresh</Button>}
      >
        {listLoading && principals.length === 0 ? (
          <StatePanel state="loading" title="Loading Principals">Reading authoritative Principal state.</StatePanel>
        ) : null}
        {!listLoading && principals.length === 0 && listError === null ? (
          <StatePanel state="empty" title="No Principals found">No authoritative technical accounts are currently visible to this administrative context.</StatePanel>
        ) : null}
        {listError !== null ? <PrincipalErrorState error={listError} context="list" /> : null}
        {principals.length > 0 ? (
          <DataTable minWidth={940} ariaLabel="Authoritative Principals">
            <TableHead>
              <TableRow>
                <TableCell component="th" scope="col">Native principal key</TableCell>
                <TableCell component="th" scope="col">ApplicationTarget</TableCell>
                <TableCell component="th" scope="col">Identity correlation</TableCell>
                <TableCell component="th" scope="col">Lifecycle</TableCell>
                <TableCell component="th" scope="col" align="right">Open</TableCell>
              </TableRow>
            </TableHead>
            <TableBody>
              {principals.map((principal) => (
                <TableRow key={principal.id} hover>
                  <TableCell>
                    <Typography component="span" variant="body2" className="mono" sx={{ fontWeight: 700 }}>
                      {principal.nativePrincipalKey}
                    </Typography>
                  </TableCell>
                  <TableCell>
                    <Typography component="span" variant="body2" className="mono">{principal.applicationTargetId}</Typography>
                  </TableCell>
                  <TableCell>
                    <Box sx={{ display: 'grid', gap: 0.5, justifyItems: 'start' }}>
                      <StatusChip label={principal.identityId ? 'Correlated' : 'Uncorrelated'} />
                      {principal.identityId ? (
                        <Typography component="span" variant="caption" className="mono" color="text.secondary">
                          {principal.identityId}
                        </Typography>
                      ) : null}
                    </Box>
                  </TableCell>
                  <TableCell><StatusChip label={principal.lifecycleState} /></TableCell>
                  <TableCell align="right">
                    <Button
                      size="small"
                      variant="text"
                      aria-label={`Open Principal ${principal.nativePrincipalKey}`}
                      onClick={() => void openPrincipal(principal.id)}
                    >
                      View
                    </Button>
                  </TableCell>
                </TableRow>
              ))}
            </TableBody>
          </DataTable>
        ) : null}
        {nextCursor ? (
          <Button variant="outlined" disabled={listLoading} onClick={() => void loadMore()} sx={{ mt: 1.5 }}>
            {listLoading ? 'Loading…' : 'Load more'}
          </Button>
        ) : null}
      </Surface>

      <Surface title="Open Principal by ID" description="Read the current authoritative Principal and revision before performing correlation.">
        <Box sx={{ display: 'flex', flexDirection: { xs: 'column', sm: 'row' }, gap: 1, alignItems: { sm: 'center' } }}>
          <TextInput
            label="Principal ID"
            value={principalId}
            onChange={(event) => setPrincipalId(event.target.value)}
            sx={{ flex: 1 }}
          />
          <Button variant="outlined" disabled={detailLoading || !principalId.trim()} onClick={() => void openPrincipal()}>
            {detailLoading ? 'Loading…' : 'Lookup'}
          </Button>
        </Box>
        {detailLoading ? <StatePanel state="loading" title="Loading Principal">Reading the current authoritative resource.</StatePanel> : null}
        {detailError !== null ? <PrincipalErrorState error={detailError} context="detail" /> : null}
      </Surface>

      {detail ? (
        <Surface
          title={detail.data.nativePrincipalKey}
          description="Authoritative Principal detail. The governed Identity relationship is shown separately from the technical account and its ApplicationTarget."
        >
          <PrincipalDetail principal={detail.data} />

          <Box sx={{ mt: 2, pt: 2, borderTop: 1, borderColor: 'divider', display: 'grid', gap: 1.5 }}>
            {detail.data.identityId ? (
              <StatePanel state="informational" title="Correlated to governed Identity">
                This Principal is correlated to Identity <Typography component="span" variant="body2" className="mono">{detail.data.identityId}</Typography>. The current public operation is one-way: this workspace does not reassign or clear an existing correlation.
              </StatePanel>
            ) : (
              <>
                <StatePanel state="informational" title="Uncorrelated technical account">
                  This account exists as authoritative Principal state without a governed Identity link. Correlation changes that Identity-owned relationship; it does not create AccessAssignment authority or perform provider mutation.
                </StatePanel>
                <Box component="form" onSubmit={requestCorrelation} sx={{ display: 'grid', gap: 1.25 }}>
                  <TextInput
                    label="Governed Identity ID"
                    required
                    value={correlationIdentityId}
                    helperText="The server requires a same-tenant canonical Identity and revalidates authorization and the current Principal revision."
                    onChange={(event) => setCorrelationIdentityId(event.target.value)}
                  />
                  {correlationError !== null ? <PrincipalErrorState error={correlationError} context="correlate" /> : null}
                  <Button
                    type="submit"
                    variant="outlined"
                    disabled={correlating || !correlationIdentityId.trim()}
                    sx={{ justifySelf: 'start' }}
                  >
                    {correlating ? 'Correlating…' : 'Review correlation'}
                  </Button>
                </Box>
              </>
            )}
          </Box>
        </Surface>
      ) : null}

      <ConfirmDialog
        open={confirmCorrelation}
        title="Correlate this Principal?"
        confirmLabel="Correlate to Identity"
        busy={correlating}
        onCancel={() => setConfirmCorrelation(false)}
        onConfirm={() => void correlatePrincipal()}
      >
        Correlate Principal {detail?.data.nativePrincipalKey ?? ''} to Identity {correlationIdentityId.trim()}. The accepted public contract is one-way: an already correlated Principal cannot be reassigned or cleared through this operation.
      </ConfirmDialog>
    </Page>
  );
}
