import {
  Alert as MuiAlert,
  Chip as MuiChip,
  Typography,
  type AlertProps,
  type ChipProps,
} from '@mui/material';
import type { ReactNode } from 'react';

export function Alert(props: AlertProps) {
  return <MuiAlert variant="outlined" {...props} />;
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
