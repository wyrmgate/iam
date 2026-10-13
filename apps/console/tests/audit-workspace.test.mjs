import test from 'node:test';
import { assertSourceContains, assertSourceExcludes, readConsoleSource } from './helpers/source-contract.mjs';

const auditWorkspace = await readConsoleSource('workspaces/AuditWorkspace.tsx');

test('Audit workspace distinguishes immutable records, decision-time snapshots, exports, holds, and purge processes', () => {
  assertSourceContains(
    auditWorkspace,
    /Audit evidence model/,
    /AuditRecord[\s\S]*append-only semantic occurrence evidence/,
    /EvidenceSnapshot[\s\S]*immutable typed decision-time context/,
    /Export artifact[\s\S]*snapshot-frozen NDJSON result/,
    /Legal hold[\s\S]*blocks matching destructive purge while ACTIVE/,
    /Purge operation[\s\S]*bounded dual-control destructive process/,
  );
});

test('Audit record and EvidenceSnapshot presentation preserves evidence versus authority boundaries', () => {
  assertSourceContains(
    auditWorkspace,
    /Evidence, not operational logging/,
    /AuditRecord is immutable semantic evidence/,
    /tamper-detection rather than authorization/,
    /Decision-time context, not present authority/,
    /Current capability state must be read from its owning capability instead/,
  );
  assertSourceExcludes(
    auditWorkspace,
    /generic logging console|event store editor|current mutation authority editor/,
  );
});

test('Audit export workflow exposes only accepted create, status, and re-authorized download semantics', () => {
  assertSourceContains(
    auditWorkspace,
    /\/audit-exports/,
    /occurredFrom/,
    /occurredUntil/,
    /REQUESTED and RUNNING/,
    /SUCCEEDED records completion metadata/,
    /Download completed NDJSON/,
    /re-authorized by the server at request time/,
  );
  assertSourceExcludes(
    auditWorkspace,
    /signedUrl|artifactReference|storageReference|downloadUrl/,
  );
});

test('Legal hold presentation makes ACTIVE hold dominance and explicit release consequences clear', () => {
  assertSourceContains(
    auditWorkspace,
    /ACTIVE hold dominates destructive retention/,
    /Retention age, archive availability, or a pending purge never overrides the hold/,
    /Release legal hold/,
    /destructive: true/,
    /current revision and server authorization/,
  );
});

test('Purge is presented as dual-control destructive evidence lifecycle rather than generic delete', () => {
  assertSourceContains(
    auditWorkspace,
    /Destructive evidence lifecycle · dual control/,
    /requester cannot self-approve/,
    /approval is evidence, not deletion authority/,
    /retention age, verified archive coverage, ACTIVE holds, requester\/approver separation, revision, and operation state/,
    /Approve dual-control purge/,
    /There is no generic delete endpoint, wildcard purge, browser-side retention decision, or arbitrary status mutation/,
  );
  assertSourceExcludes(
    auditWorkspace,
    /method:\s*['"]DELETE['"]|\/audit-records\/.*:delete|Delete audit record/,
  );
});

test('Audit selection fields remain bounded to accepted exact filters and opaque pagination stays delegated', () => {
  assertSourceContains(
    auditWorkspace,
    /actorId/,
    /actionType/,
    /resourceType/,
    /resourceId/,
    /outcome/,
    /correlationId/,
    /exact actor, action type, resource type, resource ID, outcome, and correlation filters only/,
    /opaque continuation cursors/,
  );
  assertSourceExcludes(
    auditWorkspace,
    /free-form search|full-text search|jsonPath|SQL|query expression|wildcard filter/,
  );
});

test('Audit workspace stays within accepted public routes', () => {
  assertSourceContains(
    auditWorkspace,
    /\/audit-records/,
    /\/evidence-snapshots/,
    /\/audit-exports/,
    /\/audit-evidence-lifecycle\/legal-holds/,
    /\/audit-evidence-lifecycle\/purges/,
  );
  assertSourceExcludes(
    auditWorkspace,
    /\/audit-archives|\/retention-policies|\/siem|\/logs|\/events/,
  );
});
