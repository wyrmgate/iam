import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';

export async function readConsoleSource(path) {
  return readFile(new URL(`../../src/${path}`, import.meta.url), 'utf8');
}

export async function readConsoleSources(paths) {
  const entries = await Promise.all(
    Object.entries(paths).map(async ([key, path]) => [key, await readConsoleSource(path)]),
  );
  return Object.fromEntries(entries);
}

export function assertSourceContains(source, ...patterns) {
  for (const pattern of patterns) {
    assert.match(source, pattern);
  }
}

export function assertSourceExcludes(source, ...patterns) {
  for (const pattern of patterns) {
    assert.doesNotMatch(source, pattern);
  }
}
