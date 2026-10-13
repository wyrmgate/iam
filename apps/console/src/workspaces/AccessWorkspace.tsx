import { V1_ROOT } from '../api';
import { type Spec, Workspace, WorkspaceTabs } from '../SemanticWorkspace';
import { Box, StatePanel, Surface, Typography } from '../ui';
import { iso, list, nullable } from './workspaceSpecHelpers';

const assignmentSpec: Spec = {
  eyebrow: 'Access · authoritative intent',
  title: 'Access Assignments',
  description: 'Authoritative business access intent. Assignment lifecycle and validity decide whether access contributes; provider fulfillment is separate.',
  list: list(`${V1_ROOT}/access-assignments`),
  detail: (id) => `${V1_ROOT}/access-assignments/${encodeURIComponent(id)}`,
  columns: ['identityId', 'targetKind', 'roleId', 'entitlementId', 'principalConstraintKind', 'lifecycleState', 'validFrom', 'validUntil'],
  create: {
    path: `${V1_ROOT}/access-assignments`,
    fields: [
      { name: 'identityId', label: 'Identity ID', required: true, help: 'Governed subject that owns this authoritative access intent.' },
      { name: 'targetKind', label: 'Target kind', type: 'select', options: ['ROLE', 'ENTITLEMENT'], required: true, help: 'Choose exactly one governed Role or Entitlement target.' },
      { name: 'roleId', label: 'Role ID', help: 'Required only when target kind is ROLE.' },
      { name: 'entitlementId', label: 'Entitlement ID', help: 'Required only when target kind is ENTITLEMENT.' },
      { name: 'principalConstraintKind', label: 'Principal constraint', type: 'select', options: ['ANY', 'SPECIFIC'], required: true, help: 'ANY allows governed realization; SPECIFIC binds intent to one Principal.' },
      { name: 'specificPrincipalId', label: 'Specific Principal ID', help: 'Required only for a SPECIFIC principal constraint.' },
      { name: 'validFrom', label: 'Valid from', type: 'datetime-local', help: 'Before this instant, a scheduled assignment is not effective.' },
      { name: 'validUntil', label: 'Valid until', type: 'datetime-local', help: 'At this instant, authority ends even if materialized lifecycle state updates later.' },
    ],
    body: (values) => ({
      identityId: values.identityId,
      targetKind: values.targetKind,
      roleId: nullable(values.roleId),
      entitlementId: nullable(values.entitlementId),
      principalConstraintKind: values.principalConstraintKind,
      specificPrincipalId: nullable(values.specificPrincipalId),
      validFrom: iso(values.validFrom),
      validUntil: iso(values.validUntil),
    }),
  },
  actions: [
    {
      label: 'Suspend current access',
      path: (id) => `${V1_ROOT}/access-assignments/${encodeURIComponent(id)}/suspend`,
      destructive: true,
    },
    {
      label: 'Resume suspended access',
      path: (id) => `${V1_ROOT}/access-assignments/${encodeURIComponent(id)}/resume`,
    },
    {
      label: 'Cancel scheduled access',
      path: (id) => `${V1_ROOT}/access-assignments/${encodeURIComponent(id)}/cancel`,
      destructive: true,
    },
    {
      label: 'Revoke current access',
      path: (id) => `${V1_ROOT}/access-assignments/${encodeURIComponent(id)}/revoke`,
      destructive: true,
    },
  ],
};

const effectiveSpec: Spec = {
  eyebrow: 'Access · derived projection',
  title: 'Effective Access',
  description: 'Read-only entitlement-level projection derived from currently effective authoritative assignments and active role composition.',
  list: list(`${V1_ROOT}/effective-access`),
  detail: (id) => `${V1_ROOT}/effective-access/${encodeURIComponent(id)}`,
  columns: ['identityId', 'entitlementId', 'principalConstraintKey', 'supportCount'],
};

const lifecyclePolicySpec: Spec = {
  eyebrow: 'Access · typed policy',
  title: 'Lifecycle Access Policy',
  description: 'Current immutable Access-owned lifecycle/birthright policy. Activation creates a successor version; policy content is a closed typed model, not a generic expression language.',
  detail: () => `${V1_ROOT}/lifecycle-access-policy`,
};

function AssignmentWorkspace() {
  return (
    <Box sx={{ display: 'grid', gap: 2 }}>
      <StatePanel state="informational" title="Authoritative business intent">
        Target, principal constraint, lifecycle, provenance, and validity describe what IAM has decided. Technical provisioning progress and provider observation do not change this assignment state.
      </StatePanel>
      <StatePanel state="warning" title="Lifecycle operations have business consequences">
        Suspend removes current authority without making the assignment terminal. Resume restores only a still-valid suspended assignment. Cancel terminates future scheduled access; revoke terminates current access immediately. Server authorization, revision checks, and idempotency remain final.
      </StatePanel>
      <Workspace spec={assignmentSpec} />
    </Box>
  );
}

function EffectiveAccessWorkspace() {
  return (
    <Box sx={{ display: 'grid', gap: 2 }}>
      <StatePanel state="informational" title="Derived, read-only projection">
        EffectiveAccess explains currently supported entitlement access. It is rebuilt from authoritative assignments and role-version paths, so no edit or lifecycle operation is available here.
      </StatePanel>
      <Workspace spec={effectiveSpec} />
    </Box>
  );
}

function LifecyclePolicyWorkspace() {
  return (
    <Box sx={{ display: 'grid', gap: 2 }}>
      <StatePanel state="informational" title="Closed typed predicate model">
        Rules are limited to ALWAYS; exact typed SINGLE equality; one-value typed MULTI CONTAINS; and bounded homogeneous CONTAINS_ANY / CONTAINS_ALL expected sets. DECIMAL, DATE, and DATETIME comparisons use the accepted exact semantics.
      </StatePanel>
      <Surface title="Policy boundaries" description="These boundaries are part of the accepted Access contract.">
        <Box component="ul" sx={{ m: 0, pl: 2.5, display: 'grid', gap: 1 }}>
          <Typography component="li" variant="body2">Stable rule IDs remain AccessAssignment provenance.</Typography>
          <Typography component="li" variant="body2">Activation publishes an immutable successor and supersedes the previous active version.</Typography>
          <Typography component="li" variant="body2">No arbitrary JSON expressions, generic status mutation, compound AND/OR/NOT, relationship predicates, or browser-side governance are introduced.</Typography>
          <Typography component="li" variant="body2">Privilege-increasing reconciliation remains subject to mandatory server-side Governance evaluation.</Typography>
        </Box>
      </Surface>
      <Workspace spec={lifecyclePolicySpec} />
    </Box>
  );
}

export function AccessWorkspace() {
  return (
    <WorkspaceTabs
      tabs={[
        { id: 'intent', label: 'Assignments · authoritative', content: <AssignmentWorkspace /> },
        { id: 'effective', label: 'Effective access · read-only', content: <EffectiveAccessWorkspace /> },
        { id: 'policy', label: 'Lifecycle policy · typed', content: <LifecyclePolicyWorkspace /> },
      ]}
    />
  );
}
