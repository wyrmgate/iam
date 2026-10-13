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
import { type ReactNode, useEffect, useId, useRef } from 'react';

type DismissPolicyProps = {
  busy?: boolean;
  dismissOnEscape?: boolean;
  dismissOnBackdrop?: boolean;
};

type ModalProps = DialogProps & DismissPolicyProps & {
  children: ReactNode;
  title?: ReactNode;
  description?: ReactNode;
};

export function Modal({
  children,
  title,
  description,
  busy = false,
  dismissOnEscape = true,
  dismissOnBackdrop = true,
  onClose,
  open,
  ...props
}: ModalProps) {
  const titleId = useId();
  const descriptionId = useId();
  const initialFocusRef = useRef<HTMLDivElement>(null);

  useEffect(() => {
    if (open) initialFocusRef.current?.focus();
  }, [open]);

  const handleClose: NonNullable<DialogProps['onClose']> = (event, reason) => {
    if (busy) return;
    if (reason === 'escapeKeyDown' && !dismissOnEscape) return;
    if (reason === 'backdropClick' && !dismissOnBackdrop) return;
    onClose?.(event, reason);
  };

  return (
    <Dialog
      fullWidth
      maxWidth="sm"
      {...props}
      open={open}
      onClose={handleClose}
      aria-labelledby={title ? titleId : props['aria-labelledby']}
      aria-describedby={description ? descriptionId : props['aria-describedby']}
      aria-busy={busy || undefined}
    >
      {title ? <DialogTitle id={titleId}>{title}</DialogTitle> : null}
      <DialogContent ref={initialFocusRef} tabIndex={-1} sx={{ p: title ? undefined : 0, outline: 'none' }}>
        {description ? <Typography id={descriptionId} color="text.secondary" sx={{ mb: 2 }}>{description}</Typography> : null}
        {children}
      </DialogContent>
    </Dialog>
  );
}

type SidePanelProps = DrawerProps & DismissPolicyProps & {
  children: ReactNode;
  title?: ReactNode;
  description?: ReactNode;
};

export function SidePanel({
  children,
  title,
  description,
  busy = false,
  dismissOnEscape = true,
  dismissOnBackdrop = true,
  onClose,
  open,
  ...props
}: SidePanelProps) {
  const titleId = useId();
  const descriptionId = useId();
  const initialFocusRef = useRef<HTMLDivElement>(null);

  useEffect(() => {
    if (open) initialFocusRef.current?.focus();
  }, [open]);

  const handleClose: NonNullable<DrawerProps['onClose']> = (event, reason) => {
    if (busy) return;
    if (reason === 'escapeKeyDown' && !dismissOnEscape) return;
    if (reason === 'backdropClick' && !dismissOnBackdrop) return;
    onClose?.(event, reason);
  };

  return (
    <Drawer
      anchor="right"
      {...props}
      open={open}
      onClose={handleClose}
      aria-labelledby={title ? titleId : props['aria-labelledby']}
      aria-describedby={description ? descriptionId : props['aria-describedby']}
      aria-busy={busy || undefined}
    >
      <Box
        ref={initialFocusRef}
        tabIndex={-1}
        sx={{ width: { xs: 'min(100vw, 420px)', sm: 420, md: 480 }, maxWidth: '100vw', height: '100%', outline: 'none', overflowY: 'auto' }}
      >
        {title ? <Typography id={titleId} component="h2" variant="h6" sx={{ px: 3, pt: 3 }}>{title}</Typography> : null}
        {description ? <Typography id={descriptionId} color="text.secondary" sx={{ px: 3, pt: 1 }}>{description}</Typography> : null}
        {children}
      </Box>
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
  dismissOnEscape?: boolean;
  dismissOnBackdrop?: boolean;
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
  dismissOnEscape = true,
  dismissOnBackdrop = true,
  onConfirm,
  onCancel,
}: ConfirmDialogProps) {
  const titleId = useId();
  const descriptionId = useId();
  const cancelButtonRef = useRef<HTMLButtonElement>(null);

  useEffect(() => {
    if (open) cancelButtonRef.current?.focus();
  }, [open]);

  const handleClose: NonNullable<DialogProps['onClose']> = (_event, reason) => {
    if (busy) return;
    if (reason === 'escapeKeyDown' && !dismissOnEscape) return;
    if (reason === 'backdropClick' && !dismissOnBackdrop) return;
    onCancel();
  };

  return (
    <Dialog
      open={open}
      onClose={handleClose}
      fullWidth
      maxWidth="sm"
      aria-labelledby={titleId}
      aria-describedby={descriptionId}
      aria-busy={busy || undefined}
      slotProps={{
        paper: {
          sx: {
            m: { xs: 2, sm: 4 },
            width: { xs: 'calc(100% - 32px)', sm: '100%' },
            maxHeight: { xs: 'calc(100% - 32px)', sm: 'calc(100% - 64px)' },
          },
        },
      }}
    >
      <DialogTitle id={titleId}>{title}</DialogTitle>
      <DialogContent>
        <Typography id={descriptionId} color="text.secondary" sx={{ lineHeight: 1.6 }}>{children}</Typography>
      </DialogContent>
      <DialogActions sx={{ px: 3, pb: 2.5, pt: 1.5, gap: 1, flexWrap: 'wrap', justifyContent: 'flex-end' }}>
        <MuiButton ref={cancelButtonRef} onClick={onCancel} disabled={busy}>{cancelLabel}</MuiButton>
        <MuiButton
          color={destructive ? 'error' : 'primary'}
          variant="contained"
          onClick={onConfirm}
          disabled={busy}
          aria-disabled={busy || undefined}
        >
          {busy ? 'Working…' : confirmLabel}
        </MuiButton>
      </DialogActions>
    </Dialog>
  );
}
