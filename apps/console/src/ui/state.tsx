import {
  Alert as MuiAlert,
  Box,
  Chip as MuiChip,
  CircularProgress,
  Typography,
  type AlertProps,
  type ChipProps,
} from '@mui/material';
import type { ReactNode } from 'react';

export function Alert(props: AlertProps) {
  return <MuiAlert variant="outlined" {...props} />;
}

export type StatePanelState =
  | 'loading'
  | 'empty'
  | 'informational'
  | 'warning'
  | 'error'
  | 'forbidden'
  | 'not-found'
  | 'conflict'
  | 'stale';

export type StatePanelProps = {
  title?: ReactNode;
  children: ReactNode;
  state?: StatePanelState;
  /** Backward-compatible visual severity for existing callers. Prefer `state` for new state surfaces. */
  severity?: AlertProps['severity'];
  action?: ReactNode;
};

type StatePresentation = {
  severity?: AlertProps['severity'];
  role: 'status' | 'alert';
  live?: 'polite';
  busy?: boolean;
  loading?: boolean;
};

const statePresentation: Record<StatePanelState, StatePresentation> = {
  loading: { role: 'status', live: 'polite', busy: true, loading: true },
  empty: { role: 'status', live: 'polite' },
  informational: { severity: 'info', role: 'status', live: 'polite' },
  warning: { severity: 'warning', role: 'alert' },
  error: { severity: 'error', role: 'alert' },
  forbidden: { severity: 'error', role: 'alert' },
  'not-found': { severity: 'info', role: 'status', live: 'polite' },
  conflict: { severity: 'warning', role: 'alert' },
  stale: { severity: 'warning', role: 'alert' },
};

function stateFromLegacySeverity(severity: AlertProps['severity'] | undefined): StatePanelState {
  if (severity === 'error') return 'error';
  if (severity === 'warning') return 'warning';
  return 'informational';
}

function StatePanelContent({
  title,
  children,
  action,
  loading,
}: Pick<StatePanelProps, 'title' | 'children' | 'action'> & { loading?: boolean }) {
  return (
    <Box sx={{ display: 'flex', alignItems: 'flex-start', gap: 1.25, width: '100%', minWidth: 0 }}>
      {loading ? <CircularProgress size={18} thickness={5} aria-hidden="true" sx={{ mt: 0.25, flexShrink: 0 }} /> : null}
      <Box sx={{ flex: 1, minWidth: 0 }}>
        {title ? (
          <Typography component="strong" variant="subtitle2" sx={{ display: 'block', mb: 0.5 }}>
            {title}
          </Typography>
        ) : null}
        <Typography component="div" variant="body2" sx={{ lineHeight: 1.5 }}>
          {children}
        </Typography>
      </Box>
      {action ? <Box sx={{ flexShrink: 0, ml: 'auto' }}>{action}</Box> : null}
    </Box>
  );
}

export function StatePanel({ title, children, state, severity, action }: StatePanelProps) {
  const resolvedState = state ?? stateFromLegacySeverity(severity);
  const presentation = statePresentation[resolvedState];
  const semantics = {
    role: presentation.role,
    'aria-live': presentation.live,
    'aria-busy': presentation.busy || undefined,
    'aria-atomic': presentation.role === 'status' ? true : undefined,
    'data-state': resolvedState,
  } as const;
  const content = (
    <StatePanelContent title={title} action={action} loading={presentation.loading}>
      {children}
    </StatePanelContent>
  );

  if (!presentation.severity) {
    return (
      <Box
        {...semantics}
        sx={{
          border: 1,
          borderColor: 'divider',
          borderRadius: 1,
          bgcolor: 'background.paper',
          px: 1.5,
          py: 1.25,
        }}
      >
        {content}
      </Box>
    );
  }

  return (
    <MuiAlert
      {...semantics}
      severity={state ? presentation.severity : severity ?? presentation.severity}
      variant="outlined"
      sx={{ alignItems: 'flex-start', '& .MuiAlert-message': { width: '100%' } }}
    >
      {content}
    </MuiAlert>
  );
}

export type StateIndicatorTone = 'success' | 'info' | 'warning' | 'danger' | 'neutral';

const indicatorColor: Record<StateIndicatorTone, string> = {
  success: 'success.main',
  info: 'info.main',
  warning: 'warning.main',
  danger: 'error.main',
  neutral: 'text.secondary',
};

/**
 * Compact non-pill state presentation for lifecycle, fulfillment, risk or similar typed state.
 * The caller owns domain meaning and chooses the tone; this primitive supplies only presentation.
 */
export function StateIndicator({ label, tone = 'neutral' }: { label: ReactNode; tone?: StateIndicatorTone }) {
  return (
    <Box component="span" sx={{ display: 'inline-flex', alignItems: 'center', gap: 0.75, minWidth: 0, color: indicatorColor[tone] }}>
      <Box component="span" aria-hidden="true" sx={{ width: 7, height: 7, flex: '0 0 auto', borderRadius: '50%', bgcolor: 'currentColor' }} />
      <Typography component="span" variant="caption" sx={{ color: 'text.primary', fontWeight: 600, lineHeight: 1.3 }}>
        {label}
      </Typography>
    </Box>
  );
}

export function StatusChip(props: ChipProps) {
  return <MuiChip size="small" variant="outlined" {...props} />;
}
