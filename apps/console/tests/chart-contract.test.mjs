import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import test from 'node:test';

const chart = await readFile(new URL('../src/ui/charts.tsx', import.meta.url), 'utf8');
const index = await readFile(new URL('../src/ui/index.tsx', import.meta.url), 'utf8');
const tokens = await readFile(new URL('../src/ui/designTokens.ts', import.meta.url), 'utf8');
const standard = await readFile(new URL('../../../docs/engineering/console-enterprise-ui-standard.md', import.meta.url), 'utf8');

test('shared DonutChart is exported from the application-facing UI boundary', () => {
  assert.match(index, /export \{ DonutChart, type DonutChartItem, type DonutChartProps \} from '\.\/charts'/);
  assert.match(chart, /export function DonutChart/);
});

test('DonutChart preserves accessible color-independent values', () => {
  assert.match(chart, /role="img"/);
  assert.match(chart, /aria-label=\{ariaLabel\}/);
  assert.match(chart, /component="ul"/);
  assert.match(chart, /item\.value\.toLocaleString\(\)/);
  assert.match(chart, /percentage\(item\.value, visualTotal\)/);
  assert.doesNotMatch(chart, /animation|transition|gradient/i);
});

test('categorical charts use dedicated non-semantic visualization tokens', () => {
  assert.match(tokens, /visualization:\s*\{/);
  assert.match(tokens, /categorical:/);
  assert.match(tokens, /track:/);
  assert.match(chart, /color\.visualization\.categorical/);
  assert.match(chart, /color\.visualization\.track/);
  assert.doesNotMatch(chart, /semantic\.(success|warning|danger)|lifecycle\./);
});

test('enterprise UI standard defines charts as governed visualization rather than decoration', () => {
  assert.match(standard, /## Charts and visualization/);
  assert.match(standard, /must not manufacture a distribution, trend, risk score or aggregation/i);
  assert.match(standard, /dedicated non-semantic visualization palette/i);
  assert.match(standard, /accessible chart label plus visible textual legend\/data values/i);
  assert.match(standard, /render an explicit state instead of an illustrative or inferred chart/i);
});
