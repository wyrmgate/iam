import {
  Alert as MuiAlert,
  Box,
  Button as MuiButton,
  Checkbox,
  Chip as MuiChip,
  CircularProgress,
  Dialog,
  DialogActions,
  DialogContent,
  DialogTitle,
  Drawer,
  FormControlLabel,
  MenuItem,
  Paper,
  Stack,
  Tab as MuiTab,
  Table,
  TableBody,
  TableCell,
  TableContainer,
  TableHead,
  TableRow,
  Tabs as MuiTabs,
  TextField,
  Typography,
  type AlertProps,
  type BoxProps,
  type ButtonProps,
  type CheckboxProps,
  type ChipProps,
  type DialogProps,
  type DrawerProps,
  type PaperProps,
  type TabProps,
  type TabsProps,
  type TextFieldProps,
} from '@mui/material';
import type { ReactNode } from 'react';

export {
  Box,
  CircularProgress,
  Stack,
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableRow,
  Typography,
};

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

export function Alert(props: AlertProps) {
  return <MuiAlert variant="outlined" {...props} />;
}

export function Modal({ children, ...props }: DialogProps & { children: ReactNode }) {
  return (
    <Dialog fullWidth maxWidth="sm" {...props}>
      <DialogContent sx={{ p: 0 }}>{children}</DialogContent>
    </Dialog>
  );
}

export function SidePanel({ children, ...props }: DrawerProps & { children: ReactNode }) {
  return (
    <Drawer anchor="right" {...props}>
      <Box sx={{ width: { xs: 'min(92vw, 360px)', sm: 360 }, height: '100%' }}>{children}</Box>
    </Drawer>
  );
}

type PageWidth = 'narrow' | 'standard' | 'wide';
type PageProps = Omit<BoxProps, 'children'> & { children: ReactNode; width?: PageWidth };

const pageWidths: Record<PageWidth, string> = {
  narrow: '52rem',
  standard: '76rem',
  wide: '96rem',
};

export function Page({ children, width = 'standard', sx, ...props }: PageProps) {
  return (
    <Box
      component="section"
      data-page-width={width}
      sx={{
        width: `min(${pageWidths[width]}, 100%)`,
        mx: 'auto',
        display: 'grid',
        gap: { xs: 2, md: 2.5 },
        ...sx,
      }}
      {...props}
    >
      {children}
    </Box>
  );
}

type PageHeaderProps = {
  eyebrow?: ReactNode;
  title: ReactNode;
  description?: ReactNode;
  actions?: ReactNode;
};

export function PageHeader({ eyebrow, title, description, actions }: PageHeaderProps) {
  return (
    <Box
      component="header"
      sx={{
        display: 'flex',
        flexDirection: { xs: 'column', md: actions ? 'row' : 'column' },
        justifyContent: 'space-between',
        alignItems: { xs: 'stretch', md: actions ? 'flex-start' : 'stretch' },
        gap: 2,
      }}
    >
      <Box sx={{ maxWidth: '64rem', minWidth: 0 }}>
        {eyebrow ? (
          <Typography
            component="p"
            variant="overline"
            sx={{ color: 'primary.main', fontWeight: 800, letterSpacing: '.1em', lineHeight: 1.6 }}
          >
            {eyebrow}
          </Typography>
        ) : null}
        <Typography component="h1" variant="h4" sx={{ mt: eyebrow ? 0.25 : 0, mb: description ? 0.75 : 0 }}>
          {title}
        </Typography>
        {description ? (
          <Typography component="p" color="text.secondary" sx={{ lineHeight: 1.65, m: 0 }}>
            {description}
          </Typography>
        ) : null}
      </Box>
      {actions ? (
        <Box sx={{ display: 'flex', flexWrap: 'wrap', gap: 1, flex: '0 0 auto' }}>
          {actions}
        </Box>
      ) : null}
    </Box>
  );
}

type PageToolbarProps = Omit<BoxProps, 'children'> & {
  children: ReactNode;
  leading?: ReactNode;
};

export function PageToolbar({ children, leading, sx, ...props }: PageToolbarProps) {
  return (
    <Box
      component="section"
      sx={{
        minWidth: 0,
        display: 'flex',
        flexDirection: { xs: 'column', sm: 'row' },
        alignItems: { xs: 'stretch', sm: 'center' },
        justifyContent: 'space-between',
        gap: 1.25,
        ...sx,
      }}
      {...props}
    >
      {leading ? <Box sx={{ minWidth: 0, flex: '1 1 auto' }}>{leading}</Box> : <span />}
      <Box sx={{ display: 'flex', flexWrap: 'wrap', gap: 1, justifyContent: { sm: 'flex-end' } }}>{children}</Box>
    </Box>
  );
}

type SurfaceProps = PaperProps & {
  children: ReactNode;
  title?: ReactNode;
  description?: ReactNode;
  actions?: ReactNode;
};

export function Surface({ children, title, description, actions, sx, ...props }: SurfaceProps) {
  return (
    <Paper variant="outlined" sx={{ minWidth: 0, p: { xs: 2, md: 2.5 }, borderColor: 'divider', ...sx }} {...props}>
      {(title || description || actions) ? (
        <Box
          sx={{
            display: 'flex',
            flexDirection: { xs: 'column', sm: actions ? 'row' : 'column' },
            justifyContent: 'space-between',
            alignItems: { xs: 'stretch', sm: actions ? 'flex-start' : 'stretch' },
            gap: 1.5,
            mb: 2,
          }}
        >
          <Box sx={{ minWidth: 0 }}>
            {title ? <Typography component="h2" variant="h6">{title}</Typography> : null}
            {description ? <Typography color="text.secondary" variant="body2" sx={{ mt: title ? 0.5 : 0, lineHeight: 1.55 }}>{description}</Typography> : null}
          </Box>
          {actions ? (
            <Box sx={{ display: 'flex', flexWrap: 'wrap', gap: 1 }}>
              {actions}
            </Box>
          ) : null}
        </Box>
      ) : null}
      {children}
    </Paper>
  );
}

type StatePanelProps = {
  title?: ReactNode;
  children: ReactNode;
  severity?: AlertProps['severity'];
  action?: ReactNode;
};

export function StatePanel({ title, children, severity = 'info', action }: StatePanelProps) {
  return (
    <MuiAlert
      severity={severity}
      variant="outlined"
      action={action}
      sx={{ alignItems: 'flex-start', '& .MuiAlert-message': { width: '100%' } }}
    >
      {title ? <Typography component="strong" variant="subtitle2" sx={{ display: 'block', mb: 0.25 }}>{title}</Typography> : null}
      <Typography component="div" variant="body2" sx={{ lineHeight: 1.55 }}>{children}</Typography>
    </MuiAlert>
  );
}

export function StatusChip(props: ChipProps) {
  return <MuiChip size="small" variant="outlined" {...props} />;
}

export function DataTable({ children, minWidth = 720 }: { children: ReactNode; minWidth?: number }) {
  return (
    <TableContainer component={Paper} variant="outlined" sx={{ borderColor: 'divider' }}>
      <Table size="small" sx={{ minWidth }}>{children}</Table>
    </TableContainer>
  );
}

export function Tabs(props: TabsProps) {
  return (
    <MuiTabs
      variant="scrollable"
      scrollButtons="auto"
      allowScrollButtonsMobile
      {...props}
      sx={{ minHeight: 42, borderBottom: 1, borderColor: 'divider', ...props.sx }}
    />
  );
}

export function Tab(props: TabProps) {
  return <MuiTab disableRipple {...props} sx={{ minHeight: 42, textTransform: 'none', fontWeight: 700, ...props.sx }} />;
}

type ConfirmDialogProps = {
  open: boolean;
  title: ReactNode;
  children: ReactNode;
  confirmLabel?: string;
  cancelLabel?: string;
  destructive?: boolean;
  busy?: boolean;
  onConfirm: () => void;
  onCancel: () => void;
};

export function ConfirmDialog({
  open,
  title,
  children,
  confirmLabel = 'Confirm',
  cancelLabel = 'Cancel',
  destructive = false,
  busy = false,
  onConfirm,
  onCancel,
}: ConfirmDialogProps) {
  return (
    <Dialog open={open} onClose={busy ? undefined : onCancel} fullWidth maxWidth="sm">
      <DialogTitle>{title}</DialogTitle>
      <DialogContent>
        <Typography color="text.secondary" sx={{ lineHeight: 1.6 }}>{children}</Typography>
      </DialogContent>
      <DialogActions sx={{ px: 3, pb: 2.5 }}>
        <MuiButton onClick={onCancel} disabled={busy}>{cancelLabel}</MuiButton>
        <MuiButton color={destructive ? 'error' : 'primary'} variant="contained" onClick={onConfirm} disabled={busy}>
          {busy ? 'Working…' : confirmLabel}
        </MuiButton>
      </DialogActions>
    </Dialog>
  );
}
