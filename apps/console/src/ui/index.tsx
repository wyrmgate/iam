import {
  Alert as MuiAlert,
  Box,
  Button as MuiButton,
  CircularProgress,
  Dialog,
  DialogContent,
  Drawer,
  Paper,
  TextField,
  Typography,
  type AlertProps,
  type ButtonProps,
  type DialogProps,
  type DrawerProps,
  type TextFieldProps,
} from '@mui/material';
import type { ReactNode } from 'react';

export { Box, Paper, Typography, CircularProgress };

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
