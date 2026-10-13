import {
  Alert as MuiAlert,
  Box,
  Button as MuiButton,
  Chip as MuiChip,
  CircularProgress,
  Dialog,
  DialogActions,
  DialogContent,
  DialogTitle,
  Drawer,
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
  type ButtonProps,
  type ChipProps,
  type DialogProps,
  type DrawerProps,
  type PaperProps,
  type StackProps,
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

type PageProps = StackProps & { children: ReactNode };

export function Page({ children, sx, ...props }: PageProps) {
  return (
    <Stack
      component="section"
      spacing={2.5}
      sx={{ width: 'min(82rem, 100%)', mx: 'auto', ...sx }}
      {...props}
    >
      {children}
    </Stack>
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
    <Stack
      component="header"
      direction={{ xs: 'column', md: actions ? 'row' : 'column' }}
      justifyContent="space-between"
      alignItems={{ xs: 'stretch', md: actions ? 'flex-start' : 'stretch' }}
      spacing={2}
    >
      <Box sx={{ maxWidth: 960 }}>
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
      {actions ? <Stack direction="row" spacing={1} flexWrap="wrap">{actions}</Stack> : null}
    </Stack>
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
    <Paper variant="outlined" sx={{ p: { xs: 2, md: 2.5 }, borderColor: 'divider', ...sx }} {...props}>
      {(title || description || actions) ? (
        <Stack
          direction={{ xs: 'column', sm: actions ? 'row' : 'column' }}
          justifyContent="space-between"
          alignItems={{ xs: 'stretch', sm: actions ? 'flex-start' : 'stretch' }}
          spacing={1.5}
          sx={{ mb: 2 }}
        >
          <Box>
            {title ? <Typography component="h2" variant="h6">{title}</Typography> : null}
            {description ? <Typography color="text.secondary" variant="body2" sx={{ mt: title ? 0.5 : 0, lineHeight: 1.55 }}>{description}</Typography> : null}
          </Box>
          {actions ? <Stack direction="row" spacing={1} flexWrap="wrap">{actions}</Stack> : null}
        </Stack>
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
