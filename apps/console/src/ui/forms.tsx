import {
  Box,
  Button as MuiButton,
  Checkbox,
  FormControl,
  FormControlLabel,
  FormHelperText,
  MenuItem,
  TextField,
  type ButtonProps,
  type CheckboxProps,
  type TextFieldProps,
} from '@mui/material';
import { useId, type ChangeEvent, type ReactNode } from 'react';

export function Button(props: ButtonProps) {
  return <MuiButton {...props} />;
}

type TextInputProps = Omit<TextFieldProps, 'variant'> & {
  readOnly?: boolean;
};

export function TextInput({ id, helperText, readOnly = false, slotProps, ...props }: TextInputProps) {
  const generatedId = useId();
  const fieldId = id ?? `wyrmgate-field-${generatedId.replaceAll(':', '')}`;

  return (
    <TextField
      id={fieldId}
      variant="outlined"
      helperText={helperText}
      slotProps={{ ...slotProps, input: { readOnly } }}
      {...props}
    />
  );
}

export type SelectOption<T extends string | number = string> = {
  value: T;
  label: ReactNode;
  disabled?: boolean;
};

type SelectValue = string | number;
type SelectOptionInput<T extends SelectValue> = T | SelectOption<T>;

type SelectInputProps<T extends SelectValue = string> = Omit<
  TextFieldProps,
  'children' | 'defaultValue' | 'onChange' | 'select' | 'value' | 'variant'
> & {
  options: readonly SelectOptionInput<T>[];
  value?: T | '';
  defaultValue?: T | '';
  emptyLabel?: ReactNode;
  readOnly?: boolean;
  onChange?: (event: ChangeEvent<HTMLInputElement | HTMLTextAreaElement>) => void;
  onValueChange?: (value: T | '') => void;
};

function normalizeOption<T extends SelectValue>(option: SelectOptionInput<T>): SelectOption<T> {
  return typeof option === 'object'
    ? option
    : { value: option, label: String(option) };
}

export function SelectInput<T extends SelectValue = string>({
  id,
  options,
  emptyLabel = 'Select…',
  readOnly = false,
  onChange,
  onValueChange,
  slotProps,
  ...props
}: SelectInputProps<T>) {
  const generatedId = useId();
  const fieldId = id ?? `wyrmgate-select-${generatedId.replaceAll(':', '')}`;

  function handleChange(event: ChangeEvent<HTMLInputElement | HTMLTextAreaElement>) {
    onChange?.(event);
    onValueChange?.(event.target.value as T | '');
  }

  return (
    <TextField
      id={fieldId}
      select
      variant="outlined"
      slotProps={{ ...slotProps, input: { readOnly } }}
      onChange={handleChange}
      {...props}
    >
      <MenuItem value="">{emptyLabel}</MenuItem>
      {options.map((rawOption) => {
        const option = normalizeOption(rawOption);
        return (
          <MenuItem key={String(option.value)} value={option.value} disabled={option.disabled}>
            {option.label}
          </MenuItem>
        );
      })}
    </TextField>
  );
}

type CheckboxInputProps = Omit<CheckboxProps, 'checked' | 'disabled' | 'onChange' | 'required'> & {
  label: ReactNode;
  checked: boolean;
  help?: ReactNode;
  error?: ReactNode;
  required?: boolean;
  disabled?: boolean;
  readOnly?: boolean;
  onChange: (checked: boolean) => void;
};

export function CheckboxInput({
  id,
  label,
  checked,
  help,
  error,
  required = false,
  disabled = false,
  readOnly = false,
  onChange,
  slotProps,
  ...props
}: CheckboxInputProps) {
  const generatedId = useId();
  const fieldId = id ?? `wyrmgate-checkbox-${generatedId.replaceAll(':', '')}`;
  const descriptionId = help || error ? `${fieldId}-description` : undefined;

  return (
    <FormControl error={Boolean(error)} required={required} disabled={disabled} component="fieldset" variant="standard">
      <FormControlLabel
        control={(
          <Checkbox
            {...props}
            id={fieldId}
            checked={checked}
            disabled={disabled}
            slotProps={{
              ...slotProps,
              input: {
                'aria-describedby': descriptionId,
                'aria-invalid': Boolean(error) || undefined,
                'aria-readonly': readOnly || undefined,
              },
            }}
            onChange={(event) => {
              if (!readOnly) onChange(event.target.checked);
            }}
          />
        )}
        label={label}
        required={required}
      />
      {descriptionId ? (
        <FormHelperText id={descriptionId} sx={{ ml: 4 }}>
          {error ?? help}
        </FormHelperText>
      ) : null}
    </FormControl>
  );
}
