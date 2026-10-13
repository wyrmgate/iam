import { withCursor } from '../api';

export const nullable = (value: string | boolean | undefined) => String(value ?? '').trim() || null;
export const iso = (value: string | boolean | undefined) => nullable(value) ? new Date(String(value)).toISOString() : null;
export const csv = (value: string | boolean | undefined) => String(value ?? '').split(/[\n,]/).map((item) => item.trim()).filter(Boolean);
export const list = (path: string, extra?: Record<string, string | undefined>) => (cursor?: string | null) => withCursor(path, cursor, extra);
