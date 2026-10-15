export type AccessView = 'assignments' | 'effective' | 'policy';
export type TargetKind = 'ROLE' | 'ENTITLEMENT';
export type PrincipalConstraintKind = 'ANY' | 'SPECIFIC';
export type AssignmentLifecycle = 'SCHEDULED' | 'ACTIVE' | 'SUSPENDED' | 'REVOKED' | 'EXPIRED' | 'CANCELLED';
export type AssignmentValidity = 'CURRENT' | 'FUTURE' | 'ENDED';
export type AssignmentProvenance = 'MANUAL' | 'REQUEST_ITEM' | 'LIFECYCLE_POLICY_RULE';

export type AssignmentResource = {
  id: string;
  identityId: string;
  identityLabel?: string | null;
  targetKind: TargetKind;
  targetId: string;
  targetLabel?: string | null;
  principalConstraintKind: PrincipalConstraintKind;
  specificPrincipalId?: string | null;
  principalLabel?: string | null;
  provenanceKind: AssignmentProvenance;
  provenanceRefId?: string | null;
  lifecycleState: AssignmentLifecycle;
  validity: AssignmentValidity;
  validityEvaluatedAt: string;
  validFrom?: string | null;
  validUntil?: string | null;
  revision: number;
  createdAt: string;
  updatedAt: string;
};

export type AssignmentPage = {
  items: AssignmentResource[];
  nextCursor?: string | null;
  evaluatedAt: string;
};

export type AssignmentFilters = {
  identityId?: string;
  lifecycleState?: AssignmentLifecycle;
  targetKind?: TargetKind;
  targetId?: string;
  provenance?: AssignmentProvenance;
  validity?: AssignmentValidity;
};

export type EffectiveResource = {
  id: string;
  identityId: string;
  identityLabel?: string | null;
  entitlementId: string;
  entitlementLabel?: string | null;
  principalConstraintKey: string;
  supportCount: number;
  computedAt: string;
  projectionGeneration: number;
};

export type EffectivePage = {
  items: EffectiveResource[];
  nextCursor?: string | null;
};

export type EffectiveSupport = {
  accessAssignmentId: string;
  pathHash: string;
  pathDepth: number;
  roleVersionPath: string[];
};

export type EffectiveDetail = {
  effective: EffectiveResource;
  supports: EffectiveSupport[];
};

export type OptionResource = {
  id: string;
  label: string;
  secondary?: string | null;
  state?: string | null;
};

export type OptionPage = { items: OptionResource[] };

export type LifecycleExpectedValue = {
  type: 'STRING' | 'BOOLEAN' | 'INTEGER' | 'DECIMAL' | 'DATE' | 'DATETIME' | 'ENUM';
  stringValue?: string | null;
  booleanValue?: boolean | null;
  integerValue?: number | null;
  decimalValue?: string | null;
  dateValue?: string | null;
  dateTimeValue?: string | null;
  enumValue?: string | null;
};

export type LifecyclePolicyRule = {
  ruleId: string;
  predicateKind: string;
  canonicalKey?: string | null;
  expectedString?: string | null;
  expectedBoolean?: boolean | null;
  expectedInteger?: number | null;
  expectedDecimal?: string | null;
  expectedDate?: string | null;
  expectedDateTime?: string | null;
  expectedEnum?: string | null;
  expectedSet?: LifecycleExpectedValue[];
  targetKind: TargetKind;
  targetId: string;
};

export type LifecyclePolicyResource = {
  id: string;
  versionNumber: number;
  state: 'ACTIVE' | 'SUPERSEDED';
  rules: LifecyclePolicyRule[];
  createdAt: string;
  activatedAt: string;
  supersededAt?: string | null;
};

export type AuditRecord = {
  id: string;
  occurredAt: string;
  actionType: string;
  resourceType: string;
  resourceId?: string | null;
  outcome: 'SUCCESS' | 'DENIED' | 'FAILURE';
};

export type AuditRecordPage = {
  items: AuditRecord[];
  nextCursor?: string | null;
};
