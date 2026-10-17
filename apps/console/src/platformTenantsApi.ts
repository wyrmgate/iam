import { V1_ROOT, apiRequest, withCursor } from './api';

export type PlatformTenantResource = {
  id: string;
  displayName: string;
  revision: number;
  createdAt: string;
  updatedAt: string;
};

export type PlatformTenantPage = {
  items: PlatformTenantResource[];
  nextCursor?: string | null;
};

export async function listPlatformTenants(cursor?: string | null, signal?: AbortSignal): Promise<PlatformTenantPage> {
  return (
    await apiRequest<PlatformTenantPage>(
      withCursor(`${V1_ROOT}/console/platform/tenants`, cursor ?? undefined),
      { signal },
    )
  ).data;
}
