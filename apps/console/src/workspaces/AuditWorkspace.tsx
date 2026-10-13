import { V1_ROOT } from '../api';
import { type Spec, Workspace, WorkspaceTabs } from '../SemanticWorkspace';
import { Box, StatePanel, Surface, Typography } from '../ui';
import { iso, list, nullable } from './workspaceSpecHelpers';

const auditSelection = (values: Record<string, string | boolean>) => ({
  occurredFrom: iso(values.occurredFrom),
  occurredUntil: iso(values.occurredUntil),
  actorId: nullable(values.actorId),
  actionType: nullable(values.actionType),
  resourceType: nullable(values.resourceType),
  resourceId: nullable(values.resourceId),
  outcome: nullable(values.outcome),
  correlationId: nullable(values.correlationId),
});

const selectionFields = [
  { name: 'occurredFrom', label: 'Occurred from', type: 'datetime-local' as const, required: true, help: 'Inclusive occurrence-time boundary for the accepted Audit selection.' },
  { name: 'occurredUntil', label: 'Occurred until', type: 'datetime-local' as const, required: true, help: 'Exclusive occurrence-time boundary for the accepted Audit selection.' },
  { name: 'actorId', label: 'Actor Identity ID', help: 'Optional exact actor filter.' },
  { name: 'actionType', label: 'Action type', help: 'Optional exact semantic action-type filter.' },
  { name: 'resourceType', label: 'Resource type', help: 'Optional exact semantic resource-type filter.' },
  { name: 'resourceId', label: 'Resource ID', help: 'Optional exact resource identifier filter.' },
  { name: 'outcome', label: 'Outcome', type: 'select' as const, options: ['SUCCESS', 'DENIED', 'FAILURE'], help: 'Optional normalized exact outcome filter.' },
  { name: 'correlationId', label: 'Correlation ID', help: 'Optional exact correlation identifier filter.' },
];

const recordSpec: Spec = {
  eyebrow: 'Audit · immutable occurrence evidence',
  title: 'Audit Records',
  description: 'Append-only security and governance evidence. AuditRecord explains a recorded occurrence; it is not generic logging, current business authority, or an event-store command source.',
  list: list(`${V1_ROOT}/audit-records`),
  detail: (id) => `${V1_ROOT}/audit-records/${encodeURIComponent(id)}`,
  columns: ['occurredAt', 'recordedAt', 'actionType', 'resourceType', 'outcome', 'actorId', 'correlationId'],
};

const exportSpec: Spec = {
  eyebrow: 'Audit · durable export process',
  title: 'Audit Exports',
  description: 'A durable asynchronous export freezes membership with occurrence filters plus a server-selected recording cutoff. The downloadable NDJSON artifact is a result of the process, not the AuditRecord authority itself.',
  detail: (id) => `${V1_ROOT}/audit-exports/${encodeURIComponent(id)}`,
  create: {
    path: `${V1_ROOT}/audit-exports`,
    fields: selectionFields,
    body: (values) => auditSelection(values),
  },
  actions: [
    {
      label: 'Download completed NDJSON',
      path: (id) => `${V1_ROOT}/audit-exports/${encodeURIComponent(id)}:download`,
      download: true,
      requireEtag: false,
      idempotent: false,
    },
  ],
};

const evidenceSpec: Spec = {
  eyebrow: 'Audit · immutable decision-time context',
  title: 'Evidence Snapshots',
  description: 'EvidenceSnapshot preserves closed, typed decision-time context. It is immutable evidence and never becomes current mutation authority.',
  list: list(`${V1_ROOT}/evidence-snapshots`),
  detail: (id) => `${V1_ROOT}/evidence-snapshots/${encodeURIComponent(id)}`,
  columns: ['occurredAt', 'recordedAt', 'snapshotType', 'subjectResourceType', 'subjectResourceId', 'decision'],
};

const holdSpec: Spec = {
  eyebrow: 'Audit · evidence lifecycle control',
  title: 'Legal Holds',
  description: 'An ACTIVE legal hold protects every matching AuditRecord from destructive purge. Release is explicit and revision-guarded; age or retention eligibility never releases a hold.',
  detail: (id) => `${V1_ROOT}/audit-evidence-lifecycle/legal-holds/${encodeURIComponent(id)}`,
  create: {
    path: `${V1_ROOT}/audit-evidence-lifecycle/legal-holds`,
    fields: [
      ...selectionFields,
      { name: 'reasonCode', label: 'Reason code', required: true, help: 'Non-secret governed reason code for the hold.' },
      { name: 'caseReference', label: 'Case reference', required: true, help: 'External legal/compliance case reference; do not enter secret material.' },
    ],
    body: (values) => ({
      selection: auditSelection(values),
      reasonCode: values.reasonCode,
      caseReference: values.caseReference,
    }),
    warning: 'Creating an ACTIVE legal hold prevents every matching evidence record from destructive purge until an explicitly authorized release succeeds.',
  },
  actions: [
    {
      label: 'Release legal hold',
      path: (id) => `${V1_ROOT}/audit-evidence-lifecycle/legal-holds/${encodeURIComponent(id)}:release`,
      destructive: true,
      idempotent: false,
    },
  ],
};

const purgeSpec: Spec = {
  eyebrow: 'Audit · dual-control destructive evidence lifecycle',
  title: 'Purge Operations',
  description: 'Purge is a durable, bounded destructive evidence process. It is not direct deletion: request, separate approval, execution-time retention/archive/hold revalidation, and a database purge fence all remain server-owned.',
  detail: (id) => `${V1_ROOT}/audit-evidence-lifecycle/purges/${encodeURIComponent(id)}`,
  create: {
    path: `${V1_ROOT}/audit-evidence-lifecycle/purges`,
    fields: [
      { name: 'archiveSegmentId', label: 'Verified archive segment ID', required: true, help: 'The server revalidates that this immutable archive covers the full selected evidence set.' },
      ...selectionFields,
      { name: 'reasonCode', label: 'Destruction reason code', required: true, help: 'Non-secret governed reason for requesting destructive evidence lifecycle processing.' },
    ],
    body: (values) => ({
      archiveSegmentId: values.archiveSegmentId,
      selection: auditSelection(values),
      reasonCode: values.reasonCode,
    }),
    warning: 'Requesting purge does not authorize deletion. A different governed Identity must approve it, ACTIVE legal holds dominate, and execution fails closed unless every retention/archive prerequisite is still valid.',
  },
  actions: [
    {
      label: 'Approve dual-control purge',
      path: (id) => `${V1_ROOT}/audit-evidence-lifecycle/purges/${encodeURIComponent(id)}:approve`,
      destructive: true,
      idempotent: false,
    },
  ],
};

function RecordWorkspace() {
  return (
    <Box sx={{ display: 'grid', gap: 2 }}>
      <StatePanel state="informational" title="Evidence, not operational logging">
        AuditRecord is immutable semantic evidence. Stable actor/resource identifiers remain authoritative references; optional material snapshots only preserve bounded explanatory display context, while integrity metadata provides tamper-detection rather than authorization.
      </StatePanel>
      <Surface title="Bounded read semantics" description="Collection access stays within the accepted Audit query contract.">
        <Typography variant="body2">
          Server search is deterministic and bounded: exact actor, action type, resource type, resource ID, outcome, and correlation filters only; pagination remains server-owned with opaque continuation cursors.
        </Typography>
      </Surface>
      <Workspace spec={recordSpec} />
    </Box>
  );
}

function ExportWorkspace() {
  return (
    <Box sx={{ display: 'grid', gap: 2 }}>
      <StatePanel state="informational" title="Process status and artifact are separate">
        REQUESTED and RUNNING describe durable export work; SUCCEEDED records completion metadata such as count, digest, and artifact expiry. Download is available only through the explicit action and is re-authorized by the server at request time.
      </StatePanel>
      <StatePanel state="warning" title="Download authorization is current">
        Possessing an export ID or having started the export does not grant browser-side download authority. A current server authorization decision is final, and an expired artifact does not rewrite the completed export evidence.
      </StatePanel>
      <Workspace spec={exportSpec} />
    </Box>
  );
}

function EvidenceWorkspace() {
  return (
    <Box sx={{ display: 'grid', gap: 2 }}>
      <StatePanel state="informational" title="Decision-time context, not present authority">
        EvidenceSnapshot preserves typed subject, policy, related-resource, decision, and display context as it existed for evidence purposes. Current capability state must be read from its owning capability instead.
      </StatePanel>
      <Workspace spec={evidenceSpec} />
    </Box>
  );
}

function HoldWorkspace() {
  return (
    <Box sx={{ display: 'grid', gap: 2 }}>
      <StatePanel state="warning" title="ACTIVE hold dominates destructive retention">
        Matching evidence cannot be purged while a legal hold is ACTIVE. Retention age, archive availability, or a pending purge never overrides the hold; release requires the current revision and server authorization.
      </StatePanel>
      <Workspace spec={holdSpec} />
    </Box>
  );
}

function PurgeWorkspace() {
  return (
    <Box sx={{ display: 'grid', gap: 2 }}>
      <StatePanel state="error" title="Destructive evidence lifecycle · dual control">
        Purge removes only the bounded online AuditRecord rows proven eligible by the server. The requester cannot self-approve; approval is evidence, not deletion authority, and execution revalidates retention age, verified archive coverage, ACTIVE holds, requester/approver separation, revision, and operation state immediately before deletion.
      </StatePanel>
      <Surface title="What purge preserves" description="Successful online-row removal does not erase the evidence lifecycle itself.">
        <Box component="ul" sx={{ m: 0, pl: 2.5, display: 'grid', gap: 1 }}>
          <Typography component="li" variant="body2">Verified immutable archive evidence and archived-record query continuity remain available.</Typography>
          <Typography component="li" variant="body2">Legal-hold history, purge history, approval evidence, selected archive reference, and deletion outcome remain evidence.</Typography>
          <Typography component="li" variant="body2">There is no generic delete endpoint, wildcard purge, browser-side retention decision, or arbitrary status mutation.</Typography>
        </Box>
      </Surface>
      <Workspace spec={purgeSpec} />
    </Box>
  );
}

export function AuditWorkspace() {
  return (
    <Box sx={{ display: 'grid', gap: 2 }}>
      <Surface title="Audit evidence model" description="Each surface has a distinct role in the accepted Audit contract.">
        <Box component="ul" sx={{ m: 0, pl: 2.5, display: 'grid', gap: 1 }}>
          <Typography component="li" variant="body2"><strong>AuditRecord</strong> — append-only semantic occurrence evidence.</Typography>
          <Typography component="li" variant="body2"><strong>EvidenceSnapshot</strong> — immutable typed decision-time context.</Typography>
          <Typography component="li" variant="body2"><strong>Export artifact</strong> — durable, snapshot-frozen NDJSON result with current download authorization.</Typography>
          <Typography component="li" variant="body2"><strong>Legal hold</strong> — explicit lifecycle control that blocks matching destructive purge while ACTIVE.</Typography>
          <Typography component="li" variant="body2"><strong>Purge operation</strong> — bounded dual-control destructive process, never direct deletion.</Typography>
        </Box>
      </Surface>
      <WorkspaceTabs
        tabs={[
          { id: 'records', label: 'Records · immutable', content: <RecordWorkspace /> },
          { id: 'exports', label: 'Exports · durable', content: <ExportWorkspace /> },
          { id: 'evidence', label: 'Snapshots · decision-time', content: <EvidenceWorkspace /> },
          { id: 'holds', label: 'Legal holds · protective', content: <HoldWorkspace /> },
          { id: 'purges', label: 'Purges · dual control', content: <PurgeWorkspace /> },
        ]}
      />
    </Box>
  );
}
