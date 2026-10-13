import { type FormEvent, type ReactNode, useEffect, useId, useMemo, useState } from 'react';
import { ApiError, apiDownload, apiRequest, type ApiResult } from './api';
import {
  Box,
  Button,
  CheckboxInput,
  ConfirmDialog,
  DataTable,
  Page,
  PageHeader,
  PageToolbar,
  SelectInput,
  SidePanel,
  StatePanel,
  StatusChip,
  Surface,
  Tab,
  TabPanel,
  TableBody,
  TableCell,
  TableHead,
  TableRow,
  Tabs,
  TextInput,
  Typography,
  getTabA11yProps,
} from './ui';

export type Resource = Record<string, any>;
export type ResourcePage = { items: Resource[]; nextCursor?: string | null };
export type Values = Record<string, string | boolean>;
export type Field = {
  name: string;
  label: string;
  type?: 'text' | 'number' | 'datetime-local' | 'textarea' | 'checkbox' | 'select';
  options?: string[];
  required?: boolean;
  help?: string;
  placeholder?: string;
};
export type Action = {
  label: string;
  path: (id: string) => string;
  destructive?: boolean;
  requireEtag?: boolean;
  idempotent?: boolean;
  fields?: Field[];
  body?: (values: Values) => unknown;
  download?: boolean;
};
export type Spec = {
  eyebrow: string;
  title: string;
  description: string;
  list?: (cursor?: string | null) => string;
  detail: (id: string) => string;
  columns?: string[];
  create?: { path: string; fields: Field[]; body: (values: Values) => unknown; warning?: string };
  actions?: Action[];
};

const redacted = /(secret|password|token|private|raw|configuration|referenceKey)/i;
const technicalField = /^(id|tenantId|revision|createdAt|updatedAt|etag)$/i;
const asText = (value: any) => value === null || value === undefined || value === '' ? '—' : typeof value === 'object' ? '[structured]' : String(value);

function fieldLabel(value: string) {
  return value
    .replace(/([a-z0-9])([A-Z])/g, '$1 $2')
    .replace(/[_-]+/g, ' ')
    .replace(/^./, (letter) => letter.toUpperCase());
}

function resourceLabel(resource: Resource, fallback: string) {
  for (const key of ['displayName', 'name', 'principalKey', 'username', 'code', 'id']) {
    const value = resource[key];
    if (typeof value === 'string' && value.trim()) return value;
  }
  return fallback;
}

function ErrorState({ error }: { error: unknown }) {
  let title = 'Request failed';
  let detail = error instanceof Error ? error.message : 'Unable to complete request.';
  let correlation: string | null = null;
  if (error instanceof ApiError) {
    correlation = error.correlationId;
    if (error.status === 401) { title = 'Authentication required'; detail = 'A trusted control-plane session is required.'; }
    else if (error.status === 403) { title = 'Not authorized'; detail = 'Administrative Authorization denied this operation. Server authorization is final.'; }
    else if (error.status === 409) { title = 'Conflict'; }
    else if (error.status === 412) { title = 'Stale revision'; detail = 'Refresh and retry with the latest authoritative ETag.'; }
    else if (error.status === 503) { title = 'Required evaluator unavailable'; }
  }
  return (
    <StatePanel severity="error" title={title}>
      {detail}{correlation ? <Typography component="span" className="mono" variant="caption" sx={{ display: 'block', mt: 0.5 }}>Correlation: {correlation}</Typography> : null}
    </StatePanel>
  );
}

export function FieldControl({ field, value, onChange }: { field: Field; value?: string | boolean; onChange: (value: string | boolean) => void }) {
  if (field.type === 'checkbox') {
    return <CheckboxInput label={field.label} help={field.help} checked={Boolean(value)} onChange={onChange} />;
  }
  if (field.type === 'select') {
    return (
      <SelectInput
        label={field.label}
        required={field.required}
        options={field.options ?? []}
        value={String(value ?? '')}
        helperText={field.help}
        onChange={(event) => onChange(event.target.value)}
      />
    );
  }
  return (
    <TextInput
      label={field.label}
      type={field.type === 'textarea' ? 'text' : field.type ?? 'text'}
      multiline={field.type === 'textarea'}
      minRows={field.type === 'textarea' ? 3 : undefined}
      required={field.required}
      value={String(value ?? '')}
      placeholder={field.placeholder}
      helperText={field.help}
      onChange={(event) => onChange(event.target.value)}
    />
  );
}

function SemanticForm({ fields, label, warning, onSubmit }: { fields: Field[]; label: string; warning?: string; onSubmit: (values: Values) => Promise<void> }) {
  const initial = useMemo(() => Object.fromEntries(fields.map((field) => [field.name, field.type === 'checkbox' ? false : ''])) as Values, [fields]);
  const [values, setValues] = useState<Values>(initial);
  const [pending, setPending] = useState(false);
  const [error, setError] = useState<unknown>(null);
  const [confirmOpen, setConfirmOpen] = useState(false);

  async function execute() {
    setPending(true);
    setError(null);
    try {
      await onSubmit(values);
      setValues(initial);
      setConfirmOpen(false);
    } catch (cause) {
      setError(cause);
    } finally {
      setPending(false);
    }
  }

  function submit(event: FormEvent) {
    event.preventDefault();
    if (warning) {
      setConfirmOpen(true);
      return;
    }
    void execute();
  }

  return (
    <Box component="form" onSubmit={submit} sx={{ display: 'grid', gap: 2.5, p: 3 }}>
      <Box sx={{ display: 'grid', gridTemplateColumns: { xs: '1fr', md: 'repeat(2, minmax(0, 1fr))' }, gap: 1.5 }}>
        {fields.map((field) => (
          <FieldControl key={field.name} field={field} value={values[field.name]} onChange={(value) => setValues((current) => ({ ...current, [field.name]: value }))} />
        ))}
      </Box>
      {warning ? <StatePanel severity="warning">{warning}</StatePanel> : null}
      {error !== null ? <ErrorState error={error} /> : null}
      <Button variant="contained" disabled={pending} type="submit" sx={{ justifySelf: 'start' }}>{pending ? 'Working…' : label}</Button>
      <ConfirmDialog
        open={confirmOpen}
        title="Confirm sensitive input boundary"
        confirmLabel={label}
        busy={pending}
        onCancel={() => setConfirmOpen(false)}
        onConfirm={() => void execute()}
      >
        {warning ?? ''}
      </ConfirmDialog>
    </Box>
  );
}

function DetailFields({ resource }: { resource: Resource }) {
  const entries = Object.entries(resource);
  const primary = entries.filter(([key]) => !technicalField.test(key));
  const technical = entries.filter(([key]) => technicalField.test(key));

  function fieldGrid(values: Array<[string, any]>) {
    return (
      <Box component="dl" sx={{ display: 'grid', gridTemplateColumns: { xs: '1fr', sm: 'repeat(2, minmax(0, 1fr))' }, gap: 0, m: 0 }}>
        {values.map(([key, value]) => (
          <Box key={key} sx={{ minWidth: 0, py: 1.25, pr: 2, borderBottom: 1, borderColor: 'divider' }}>
            <Typography component="dt" variant="caption" color="text.secondary" sx={{ fontWeight: 700 }}>{fieldLabel(key)}</Typography>
            <Typography component="dd" variant="body2" sx={{ m: 0, mt: 0.35, overflowWrap: 'anywhere', fontWeight: 550 }}>
              {redacted.test(key) ? 'Configured / redacted' : asText(value)}
            </Typography>
          </Box>
        ))}
      </Box>
    );
  }

  return (
    <Box sx={{ display: 'grid', gap: 2 }}>
      {primary.length ? fieldGrid(primary) : <StatePanel>No additional resource attributes were returned.</StatePanel>}
      {technical.length ? (
        <Box component="details" sx={{ borderTop: 1, borderColor: 'divider', pt: 1.5 }}>
          <Typography component="summary" variant="body2" sx={{ cursor: 'pointer', color: 'text.secondary', fontWeight: 700 }}>
            Technical identifiers and revision metadata
          </Typography>
          <Box sx={{ mt: 1 }}>{fieldGrid(technical)}</Box>
        </Box>
      ) : null}
    </Box>
  );
}

export function Workspace({ spec }: { spec: Spec }) {
  const [pages, setPages] = useState<ResourcePage[]>([]);
  const [loading, setLoading] = useState(Boolean(spec.list));
  const [error, setError] = useState<unknown>(null);
  const [refresh, setRefresh] = useState(0);
  const [id, setId] = useState('');
  const [detail, setDetail] = useState<ApiResult<Resource> | null>(null);
  const [actionValues, setActionValues] = useState<Values>({});
  const [pendingAction, setPendingAction] = useState('');
  const [confirmation, setConfirmation] = useState<Action | null>(null);
  const [createOpen, setCreateOpen] = useState(false);
  const items = pages.flatMap((page) => page.items);
  const next = pages.at(-1)?.nextCursor ?? null;
  const selectedLabel = detail ? resourceLabel(detail.data, id || spec.title) : null;

  useEffect(() => {
    if (!spec.list) return;
    const controller = new AbortController();
    setLoading(true);
    setError(null);
    setPages([]);
    apiRequest<ResourcePage>(spec.list(null), { signal: controller.signal })
      .then(({ data }) => setPages([data]))
      .catch((cause) => { if (!(cause instanceof DOMException && cause.name === 'AbortError')) setError(cause); })
      .finally(() => setLoading(false));
    return () => controller.abort();
  }, [refresh, spec.list]);

  async function lookup(target = id) {
    if (!target) return;
    setError(null);
    try {
      setDetail(await apiRequest<Resource>(spec.detail(target)));
      setId(target);
      setActionValues({});
    } catch (cause) {
      setError(cause);
      setDetail(null);
    }
  }

  async function loadMore() {
    if (!spec.list || !next) return;
    setLoading(true);
    try {
      const { data } = await apiRequest<ResourcePage>(spec.list(next));
      setPages((current) => [...current, data]);
    } catch (cause) {
      setError(cause);
    } finally {
      setLoading(false);
    }
  }

  async function create(values: Values) {
    if (!spec.create) return;
    const result = await apiRequest<Resource>(spec.create.path, { method: 'POST', idempotent: true, body: spec.create.body(values) });
    const createdId = result.data?.id;
    if (typeof createdId === 'string') {
      setId(createdId);
      setDetail(result);
    }
    setCreateOpen(false);
    setRefresh((value) => value + 1);
  }

  async function executeAction(action: Action) {
    if (!id || !detail) return;
    setPendingAction(action.label);
    setError(null);
    try {
      if (action.download) {
        const blob = await apiDownload(action.path(id), { method: 'POST' });
        const url = URL.createObjectURL(blob);
        const anchor = document.createElement('a');
        anchor.href = url;
        anchor.download = `wyrmgate-audit-export-${id}.ndjson`;
        anchor.click();
        URL.revokeObjectURL(url);
      } else {
        const result = await apiRequest<Resource>(action.path(id), {
          method: 'POST',
          idempotent: action.idempotent !== false,
          etag: action.requireEtag === false ? undefined : detail.etag ?? undefined,
          body: action.body ? action.body(actionValues) : undefined,
        });
        setDetail(result);
        setRefresh((value) => value + 1);
      }
      setActionValues({});
      setConfirmation(null);
    } catch (cause) {
      setError(cause);
    } finally {
      setPendingAction('');
    }
  }

  function requestAction(action: Action) {
    if (action.destructive) {
      setConfirmation(action);
      return;
    }
    void executeAction(action);
  }

  const actions = spec.create ? <Button variant="contained" onClick={() => setCreateOpen(true)}>Create {spec.title.replace(/s$/, '')}</Button> : undefined;

  return (
    <Page width="wide">
      <PageHeader eyebrow={spec.eyebrow} title={spec.title} description={spec.description} actions={actions} />

      {error !== null ? <ErrorState error={error} /> : null}

      <Box sx={{ display: 'grid', gridTemplateColumns: { xs: '1fr', xl: spec.list ? 'minmax(0, 1.45fr) minmax(22rem, .75fr)' : '1fr' }, gap: 2.5, alignItems: 'start' }}>
        {spec.list ? (
          <Surface
            title="Collection"
            description={loading && items.length === 0 ? 'Loading current resources…' : `${items.length} resource${items.length === 1 ? '' : 's'} loaded from the current server view.`}
            actions={<StatusChip label={loading ? 'Loading' : next ? 'More available' : 'Current page loaded'} color={loading ? 'info' : 'default'} />}
          >
            {loading && items.length === 0 ? <StatePanel>Loading…</StatePanel> : null}
            {!loading && items.length === 0 && error === null ? <StatePanel title="No resources found">There are no resources in the current server result.</StatePanel> : null}
            {items.length > 0 ? (
              <DataTable minWidth={820} ariaLabel={`${spec.title} resources`}>
                <TableHead>
                  <TableRow>
                    {(spec.columns ?? ['id']).map((column) => <TableCell key={column} component="th" scope="col">{fieldLabel(column)}</TableCell>)}
                    <TableCell component="th" scope="col" align="right">Details</TableCell>
                  </TableRow>
                </TableHead>
                <TableBody>
                  {items.map((item, index) => {
                    const itemId = typeof item.id === 'string' ? item.id : null;
                    const selected = Boolean(itemId && itemId === id);
                    return (
                      <TableRow key={String(item.id ?? index)} hover selected={selected}>
                        {(spec.columns ?? ['id']).map((column) => <TableCell key={column}>{redacted.test(column) ? 'Redacted' : asText(item[column])}</TableCell>)}
                        <TableCell align="right">{itemId ? <Button size="small" variant={selected ? 'outlined' : 'text'} onClick={() => void lookup(itemId)}>{selected ? 'Selected' : 'Open'}</Button> : null}</TableCell>
                      </TableRow>
                    );
                  })}
                </TableBody>
              </DataTable>
            ) : null}
            {next ? (
              <PageToolbar sx={{ mt: 1.5 }}>
                <Button variant="outlined" disabled={loading} onClick={() => void loadMore()}>{loading ? 'Loading…' : 'Load more'}</Button>
              </PageToolbar>
            ) : null}
          </Surface>
        ) : null}

        <Box sx={{ display: 'grid', gap: 2 }}>
          {detail ? (
            <Surface
              title={selectedLabel ?? 'Selected resource'}
              description="Current server representation. Secret-shaped fields remain redacted and mutations continue to use the accepted semantic operations."
              actions={detail.etag ? <StatusChip label="Revision protected" color="info" /> : undefined}
              sx={{ position: { xl: spec.list ? 'sticky' : 'static' }, top: { xl: spec.list ? 92 : 'auto' } }}
            >
              <DetailFields resource={detail.data} />
              {spec.actions?.length ? (
                <Box sx={{ display: 'grid', gap: 2, mt: 2.5, pt: 2, borderTop: 1, borderColor: 'divider' }}>
                  <Box>
                    <Typography variant="subtitle2">Available operations</Typography>
                    <Typography variant="caption" color="text.secondary">Actions apply to the selected resource and remain subject to current server authorization and revision rules.</Typography>
                  </Box>
                  {spec.actions.map((action) => (
                    <Box key={action.label} sx={{ display: 'grid', gap: 1.25, p: 1.5, border: 1, borderColor: action.destructive ? 'error.light' : 'divider', borderRadius: 1, bgcolor: action.destructive ? 'error.50' : 'background.default' }}>
                      {action.fields?.length ? (
                        <Box sx={{ display: 'grid', gridTemplateColumns: { xs: '1fr', md: 'repeat(2, minmax(0, 1fr))' }, gap: 1.25 }}>
                          {action.fields.map((field) => <FieldControl key={field.name} field={field} value={actionValues[field.name]} onChange={(value) => setActionValues((current) => ({ ...current, [field.name]: value }))} />)}
                        </Box>
                      ) : null}
                      <Button color={action.destructive ? 'error' : 'primary'} variant={action.destructive ? 'outlined' : 'contained'} disabled={Boolean(pendingAction)} onClick={() => requestAction(action)} sx={{ justifySelf: 'start' }}>
                        {pendingAction === action.label ? 'Working…' : action.label}
                      </Button>
                    </Box>
                  ))}
                </Box>
              ) : null}
            </Surface>
          ) : (
            <Surface title={spec.list ? 'Select a resource' : 'Open resource'} description={spec.list ? 'Choose a row from the collection to inspect current state and available operations.' : 'Open a resource through its accepted semantic identifier.'}>
              <StatePanel title={spec.list ? 'Nothing selected' : 'Resource not loaded'}>
                {spec.list ? 'Select Open on a collection row. Direct identifier lookup remains available below for support and advanced workflows.' : 'Enter the resource identifier below to load its current server representation.'}
              </StatePanel>
            </Surface>
          )}
        </Box>
      </Box>

      <Box component="details" sx={{ border: 1, borderColor: 'divider', borderRadius: 1, bgcolor: 'background.paper', px: 2, py: 1.5 }}>
        <Typography component="summary" variant="body2" sx={{ cursor: 'pointer', fontWeight: 700 }}>
          Advanced: open directly by resource ID
        </Typography>
        <Box sx={{ display: 'flex', flexDirection: { xs: 'column', sm: 'row' }, gap: 1, alignItems: { sm: 'center' }, mt: 1.5 }}>
          <TextInput label="Resource ID" value={id} onChange={(event) => setId(event.target.value)} sx={{ flex: 1 }} />
          <Button variant="outlined" disabled={!id} onClick={() => void lookup()}>Open resource</Button>
        </Box>
      </Box>

      {spec.create ? (
        <SidePanel open={createOpen} title={`Create ${spec.title.replace(/s$/, '')}`} description="Create through the accepted semantic resource contract. Server authorization and validation remain final." onClose={() => setCreateOpen(false)}>
          <SemanticForm fields={spec.create.fields} label="Create" warning={spec.create.warning} onSubmit={create} />
        </SidePanel>
      ) : null}

      <ConfirmDialog
        open={confirmation !== null}
        title={`${confirmation?.label ?? 'Action'}?`}
        confirmLabel={confirmation?.label ?? 'Confirm'}
        destructive
        busy={Boolean(pendingAction)}
        onCancel={() => setConfirmation(null)}
        onConfirm={() => { if (confirmation) void executeAction(confirmation); }}
      >
        {confirmation?.label ?? 'This action'} changes authoritative state. Confirm the semantic operation before continuing.
      </ConfirmDialog>
    </Page>
  );
}

export function WorkspaceTabs({ tabs }: { tabs: Array<{ id: string; label: string; content: ReactNode }> }) {
  const [active, setActive] = useState(tabs[0]?.id ?? '');
  const generatedGroupId = useId();
  const groupId = `workspace-views-${generatedGroupId}`;

  return (
    <>
      <Tabs value={active} onChange={(_event, value: string) => setActive(value)} aria-label="Workspace views">
        {tabs.map((tab) => (
          <Tab key={tab.id} value={tab.id} label={tab.label} {...getTabA11yProps(groupId, tab.id)} />
        ))}
      </Tabs>
      {tabs.map((tab) => (
        <TabPanel key={tab.id} groupId={groupId} tabId={tab.id} active={tab.id === active}>
          {tab.content}
        </TabPanel>
      ))}
    </>
  );
}
