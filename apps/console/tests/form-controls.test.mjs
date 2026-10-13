import assert from 'node:assert/strict';
import test from 'node:test';

import {
  assertSourceContains,
  assertSourceExcludes,
  readConsoleSource,
} from './helpers/source-contract.mjs';

const forms = await readConsoleSource('ui/forms.tsx');

test('form primitives preserve the Wyrmgate UI boundary and shared density baseline', () => {
  assertSourceContains(
    forms,
    /export function TextInput/,
    /export function SelectInput/,
    /export function CheckboxInput/,
    /variant="outlined"/,
  );
  assertSourceExcludes(
    forms,
    /height:\s*\d+/,
    /minHeight:\s*\d+/,
    /#[0-9a-fA-F]{6}/,
  );
});

test('text and select fields expose read-only and stable label/help relationships', () => {
  assertSourceContains(
    forms,
    /useId/,
    /wyrmgate-field-/,
    /wyrmgate-select-/,
    /readOnly\?: boolean/,
    /InputProps=\{\{ \.\.\.InputProps, readOnly \}\}/,
  );
});

test('select contract supports typed Wyrmgate option values and labels', () => {
  assertSourceContains(
    forms,
    /export type SelectOption<T extends string \| number = string>/,
    /value: T;/,
    /label: ReactNode;/,
    /options: readonly SelectOptionInput<T>\[\]/,
    /onValueChange\?: \(value: T \| ''\) => void/,
    /normalizeOption/,
    /disabled=\{option\.disabled\}/,
  );
  assertSourceExcludes(forms, /children:\s*ReactNode/);
});

test('checkbox help and errors are explicitly described to the control', () => {
  assertSourceContains(
    forms,
    /error\?: ReactNode/,
    /required\?: boolean/,
    /disabled\?: boolean/,
    /readOnly\?: boolean/,
    /aria-describedby/,
    /aria-readonly/,
    /FormHelperText id=\{descriptionId\}/,
    /error=\{Boolean\(error\)\}/,
  );
});

test('generic form primitives do not encode IAM validation or workflow semantics', () => {
  assertSourceExcludes(
    forms,
    /tenant/i,
    /permission/i,
    /workflow/i,
    /credential/i,
    /apiRequest/,
    /fetch\(/,
  );
});
