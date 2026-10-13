import {
  Box,
  Button as MuiButton,
  Dialog,
  DialogActions,
  DialogContent,
  DialogTitle,
  Drawer,
  Typography,
  type DialogProps,
  type DrawerProps,
} from '@mui/material';
import type { ReactNode } from 'react';

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
