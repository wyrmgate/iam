import { CssBaseline, ThemeProvider, createTheme } from '@mui/material';
import type { ReactNode } from 'react';

import { wyrmgateDesignTokens as tokens } from './designTokens';

const theme = createTheme({
  spacing: tokens.spacing.unit,
  palette: {
    mode: 'light',
    primary: {
      main: tokens.color.product.accent,
      dark: tokens.color.product.accentHover,
      light: tokens.color.product.accentSubtle,
    },
    error: { main: tokens.color.semantic.danger },
    warning: { main: tokens.color.semantic.warning },
    success: { main: tokens.color.semantic.success },
    info: { main: tokens.color.semantic.info },
    background: {
      default: tokens.color.neutral.canvas,
      paper: tokens.color.neutral.surface,
    },
    text: {
      primary: tokens.color.neutral.text,
      secondary: tokens.color.neutral.textSecondary,
      disabled: tokens.color.neutral.textDisabled,
    },
    divider: tokens.color.neutral.border,
  },
  shape: { borderRadius: tokens.shape.radiusSurface },
  typography: {
    fontFamily: tokens.typography.fontFamily,
    fontSize: tokens.typography.baseFontSize,
    h1: { fontSize: '1.75rem', lineHeight: 1.2, fontWeight: 700, letterSpacing: '-0.02em' },
    h2: { fontSize: '1.5rem', lineHeight: 1.25, fontWeight: 700, letterSpacing: '-0.018em' },
    h3: { fontSize: '1.25rem', lineHeight: 1.3, fontWeight: 700, letterSpacing: '-0.012em' },
    h4: { fontSize: '1.5rem', lineHeight: 1.3, fontWeight: 700, letterSpacing: '-0.018em' },
    h5: { fontSize: '1rem', lineHeight: 1.4, fontWeight: 700 },
    h6: { fontSize: '0.875rem', lineHeight: 1.4, fontWeight: 700 },
    body1: { fontSize: '0.875rem', lineHeight: tokens.typography.bodyLineHeight, fontWeight: 400 },
    body2: { fontSize: '0.8125rem', lineHeight: tokens.typography.bodyLineHeight, fontWeight: 400 },
    subtitle1: { fontSize: '0.875rem', lineHeight: 1.4, fontWeight: 600 },
    subtitle2: { fontSize: '0.8125rem', lineHeight: 1.4, fontWeight: 600 },
    caption: { fontSize: '0.75rem', lineHeight: 1.4 },
    overline: { fontSize: '0.6875rem', lineHeight: 1.4, fontWeight: 700, letterSpacing: '0.08em' },
    button: { fontSize: '0.8125rem', lineHeight: tokens.typography.compactLineHeight, textTransform: 'none', fontWeight: 600 },
  },
  components: {
    MuiCssBaseline: {
      styleOverrides: {
        body: {
          minWidth: 320,
          backgroundColor: tokens.color.neutral.canvas,
          color: tokens.color.neutral.text,
          WebkitFontSmoothing: 'antialiased',
        },
        '::selection': {
          backgroundColor: tokens.color.product.accentSubtle,
          color: tokens.color.neutral.text,
        },
        '@media (prefers-reduced-motion: reduce)': {
          '*, *::before, *::after': {
            animationDuration: '0.01ms !important',
            animationIterationCount: '1 !important',
            scrollBehavior: 'auto !important',
            transitionDuration: '0.01ms !important',
          },
        },
      },
    },
    MuiButtonBase: {
      styleOverrides: {
        root: {
          '&:focus-visible': {
            outline: `3px solid ${tokens.color.product.accentFocus}`,
            outlineOffset: 2,
          },
        },
      },
    },
    MuiButton: {
      defaultProps: { disableElevation: true, size: 'small' },
      styleOverrides: {
        root: {
          minHeight: tokens.density.compactControlHeight,
          borderRadius: tokens.shape.radiusControl,
          paddingInline: 12,
          letterSpacing: 0,
          boxShadow: 'none',
          '&.MuiButton-containedPrimary': {
            boxShadow: 'none',
            '&:hover': { boxShadow: 'none' },
          },
          '@media (max-width: 760px)': {
            minHeight: tokens.density.mobileTargetHeight,
          },
        },
        outlined: {
          backgroundColor: tokens.color.neutral.surface,
          borderColor: tokens.color.neutral.borderStrong,
          '&:hover': { backgroundColor: tokens.color.neutral.surfaceSubtle, borderColor: tokens.color.product.accent },
        },
        text: {
          '&:hover': { backgroundColor: tokens.color.product.accentSubtle },
        },
      },
    },
    MuiIconButton: {
      defaultProps: { size: 'small' },
      styleOverrides: {
        root: {
          borderRadius: tokens.shape.radiusControl,
          '@media (max-width: 760px)': { minWidth: tokens.density.mobileTargetHeight, minHeight: tokens.density.mobileTargetHeight },
        },
      },
    },
    MuiTextField: { defaultProps: { size: 'small', fullWidth: true } },
    MuiFormControl: { defaultProps: { size: 'small', fullWidth: true } },
    MuiInputLabel: {
      styleOverrides: { root: { fontSize: '0.8125rem', fontWeight: 600 } },
    },
    MuiOutlinedInput: {
      styleOverrides: {
        root: {
          minHeight: tokens.density.controlHeight,
          borderRadius: tokens.shape.radiusControl,
          backgroundColor: tokens.color.neutral.surface,
          '&:hover .MuiOutlinedInput-notchedOutline': { borderColor: tokens.color.neutral.borderStrong },
          '&.Mui-focused .MuiOutlinedInput-notchedOutline': {
            borderColor: tokens.color.product.accent,
            boxShadow: `0 0 0 2px ${tokens.color.product.accentFocus}55`,
          },
          '@media (max-width: 760px)': { minHeight: tokens.density.mobileTargetHeight },
        },
        input: { paddingBlock: 8, paddingInline: 10, fontSize: '0.875rem' },
      },
    },
    MuiFormHelperText: {
      styleOverrides: { root: { marginInline: 0, fontSize: '0.75rem', lineHeight: 1.4 } },
    },
    MuiPaper: {
      styleOverrides: { root: { backgroundImage: 'none' } },
    },
    MuiCard: {
      defaultProps: { elevation: 0 },
      styleOverrides: {
        root: {
          border: `1px solid ${tokens.color.neutral.border}`,
          borderRadius: tokens.shape.radiusSurface,
          boxShadow: 'none',
        },
      },
    },
    MuiTable: { defaultProps: { size: 'small' } },
    MuiTableRow: {
      styleOverrides: {
        root: {
          '&.MuiTableRow-hover:hover': { backgroundColor: tokens.color.neutral.surfaceSubtle },
          '&.Mui-selected, &.Mui-selected:hover': {
            backgroundColor: tokens.color.neutral.surfaceSelected,
            boxShadow: `inset 3px 0 ${tokens.color.product.accent}`,
          },
        },
      },
    },
    MuiTableCell: {
      styleOverrides: {
        root: {
          minHeight: tokens.density.tableRowHeight,
          paddingBlock: tokens.density.tableCellBlockPadding,
          paddingInline: tokens.density.tableCellInlinePadding,
          borderBottomColor: tokens.color.neutral.border,
          fontSize: `${tokens.typography.tableTextSize}px`,
          verticalAlign: 'middle',
        },
        head: {
          backgroundColor: tokens.color.neutral.surfaceSubtle,
          color: tokens.color.neutral.textSecondary,
          fontWeight: 700,
          fontSize: `${tokens.typography.tableHeaderSize}px`,
          letterSpacing: '.015em',
        },
      },
    },
    MuiChip: {
      defaultProps: { size: 'small' },
      styleOverrides: {
        root: { height: 24, borderRadius: tokens.shape.radiusBadge, fontSize: '0.75rem', fontWeight: 600 },
      },
    },
    MuiTabs: {
      styleOverrides: {
        root: {
          minHeight: 42,
          borderBottom: `1px solid ${tokens.color.neutral.border}`,
        },
        indicator: { height: 2, borderRadius: '2px 2px 0 0' },
      },
    },
    MuiTab: {
      styleOverrides: {
        root: {
          minHeight: 42,
          minWidth: 0,
          paddingBlock: 9,
          paddingInline: 14,
          textTransform: 'none',
          fontSize: '0.8125rem',
          fontWeight: 600,
          color: tokens.color.neutral.textSecondary,
          '&.Mui-selected': { color: tokens.color.neutral.text },
        },
      },
    },
    MuiDialog: {
      styleOverrides: {
        paper: {
          border: `1px solid ${tokens.color.neutral.border}`,
          borderRadius: tokens.shape.radiusDialog,
          boxShadow: '0 24px 72px rgba(15, 23, 42, .20)',
        },
      },
    },
    MuiDialogTitle: {
      styleOverrides: { root: { padding: '18px 20px 8px', fontSize: '1rem', fontWeight: 700 } },
    },
    MuiDialogContent: { styleOverrides: { root: { padding: '12px 20px' } } },
    MuiDialogActions: { styleOverrides: { root: { padding: '12px 20px 18px', gap: tokens.spacing.controlGap } } },
    MuiDrawer: {
      styleOverrides: { paper: { borderColor: tokens.color.neutral.border } },
    },
    MuiTooltip: {
      defaultProps: { arrow: true, enterDelay: 500 },
      styleOverrides: { tooltip: { fontSize: '0.75rem', lineHeight: 1.35 } },
    },
  },
});

export function WyrmgateUiProvider({ children }: { children: ReactNode }) {
  return (
    <ThemeProvider theme={theme}>
      <CssBaseline />
      {children}
    </ThemeProvider>
  );
}
