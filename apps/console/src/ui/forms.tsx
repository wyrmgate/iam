import {
  Box,
  Button as MuiButton,
  Checkbox,
  FormControlLabel,
  MenuItem,
  TextField,
  Typography,
  type ButtonProps,
  type CheckboxProps,
  type TextFieldProps,
} from '@mui/material';
import type { ReactNode } from 'react';

export function Button(props: ButtonProps) {
  return <MuiButton {...props} />;
}

export function TextInput(props: TextFieldProps) {
  return <TextField variant="outlined" {...props} />;
}

type SelectInputProps = Omit<TextFieldProps, 'select' | 'children'> & {
  options: string[];
  emptyLabel?: string;
};

export function SelectInput({ options, emptyLabel = 'Select…', ...props }: SelectInputProps) {
  return (
    <TextField select variant="outlined" {...props}>
      <MenuItem value="">{emptyLabel}</MenuItem>
      {options.map((option) => <MenuItem key={option} value={option}>{option}</MenuItem>)}
    </TextField>
  );
}

type CheckboxInputProps = Omit<CheckboxProps, 'onChange'> & {
  label: ReactNode;
  checked: boolean;
  help?: ReactNode;
  onChange: (checked: boolean) => void;
};

export function CheckboxInput({ label, checked, help, onChange, ...props }: CheckboxInputProps) {
  return (
    <Box>
      <FormControlLabel
        control={<Checkbox {...props} checked={checked} onChange={(event) => onChange(event.target.checked)} />}
        label={label}
      />
      {help ? <Typography variant="caption" color="text.secondary" sx={{ display: 'block', ml: 4 }}>{help}</Typography> : null}
    </Box>
  );
}
