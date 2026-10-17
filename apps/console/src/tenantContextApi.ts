import { apiRequest, V1_ROOT } from './api';

export type TenantContext = {
  id: string;
  displayName: string;
  revision: number;
  createdAt: string;
  updatedAt: string;
};

export async function getTenantContext(signal?: AbortSignal): Promise<TenantContext> {
  return (await apiRequest<TenantContext>(`${V1_ROOT}/console/tenant-context`, { signal })).data;
}
