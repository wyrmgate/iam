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
    h1: {
      fontSize: '1.75rem',
      lineHeight: 1.2,
      fontWeight: 760,
      letterSpacing: '-0.025em',
    },
    h2: {
      fontSize: '1.375rem',
      lineHeight: 1.25,
      fontWeight: 740,
      letterSpacing: '-0.02em',
    },
    h3: {
      fontSize: '1.125rem',
      lineHeight: 1.3,
      fontWeight: 720,
    },
    h4: {
      fontSize: '1rem',
      lineHeight: 1.35,
      fontWeight: 700,
    },
    h5: { fontSize: '0.925rem', lineHeight: 1.4, fontWeight: 700 },
    h6: { fontSize: '0.875rem', lineHeight: 1.4, fontWeight: 700 },
    body1: { fontSize: '0.875rem', lineHeight: tokens.typography.bodyLineHeight },
    body2: { fontSize: '0.8125rem', lineHeight: tokens.typography.bodyLineHeight },
    subtitle1: { fontSize: '0.875rem', lineHeight: 1.4, fontWeight: 650 },
    subtitle2: { fontSize: '0.8125rem', lineHeight: 1.4, fontWeight: 650 },
    caption: { fontSize: '0.75rem', lineHeight: 1.4 },
    overline: {
      fontSize: '0.6875rem',
      lineHeight: 1.4,
      fontWeight: 750,
      letterSpacing: '0.06em',
    },
    button: {
      fontSize: '0.8125rem',
      lineHeight: tokens.typography.compactLineHeight,
      textTransform: 'none',
      fontWeight: 700,
    },
  },
  components: {
    MuiCssBaseline: {
      styleOverrides: {
        body: {
          minWidth: 320,
          backgroundColor: tokens.color.neutral.canvas,
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
    MuiButton: {
      defaultProps: { disableElevation: true, size: 'small' },
      styleOverrides: {
        root: {
          minHeight: tokens.density.compactControlHeight,
          borderRadius: tokens.shape.radiusControl,
          paddingInline: 12,
        },
      },
    },
    MuiIconButton: {
      defaultProps: { size: 'small' },
      styleOverrides: {
        root: { borderRadius: tokens.shape.radiusControl },
      },
    },
    MuiTextField: {
      defaultProps: { size: 'small', fullWidth: true },
    },
    MuiFormControl: {
      defaultProps: { size: 'small', fullWidth: true },
    },
    MuiInputLabel: {
      styleOverrides: {
        root: { fontSize: '0.8125rem' },
      },
    },
    MuiOutlinedInput: {
      styleOverrides: {
        root: {
          minHeight: tokens.density.controlHeight,
          borderRadius: tokens.shape.radiusControl,
          backgroundColor: tokens.color.neutral.surface,
          '&.Mui-focused .MuiOutlinedInput-notchedOutline': {
            borderColor: tokens.color.product.accent,
            boxShadow: `0 0 0 2px ${tokens.color.product.accentFocus}55`,
          },
        },
        input: {
          paddingBlock: 8,
          paddingInline: 10,
          fontSize: '0.875rem',
        },
      },
    },
    MuiFormHelperText: {
      styleOverrides: {
        root: { marginInline: 0, fontSize: '0.75rem', lineHeight: 1.4 },
      },
    },
    MuiPaper: {
      styleOverrides: {
        root: { backgroundImage: 'none' },
      },
    },
    MuiCard: {
      defaultProps: { elevation: 0 },
      styleOverrides: {
        root: {
          border: `1px solid ${tokens.color.neutral.border}`,
          borderRadius: tokens.shape.radiusSurface,
        },
      },
    },
    MuiTable: {
      defaultProps: { size: 'small' },
    },
    MuiTableCell: {
      styleOverrides: {
        root: {
          minHeight: tokens.density.tableRowHeight,
          paddingBlock: tokens.density.tableCellBlockPadding,
          paddingInline: tokens.density.tableCellInlinePadding,
          borderBottomColor: tokens.color.neutral.border,
          fontSize: '0.8125rem',
        },
        head: {
          backgroundColor: tokens.color.neutral.surfaceSubtle,
          color: tokens.color.neutral.textSecondary,
          fontWeight: 700,
        },
      },
    },
    MuiChip: {
      defaultProps: { size: 'small' },
      styleOverrides: {
        root: {
          height: 24,
          borderRadius: tokens.shape.radiusSmall,
          fontSize: '0.75rem',
          fontWeight: 650,
        },
      },
    },
    MuiTabs: {
      styleOverrides: {
        root: { minHeight: 38 },
      },
    },
    MuiTab: {
      styleOverrides: {
        root: {
          minHeight: 38,
          minWidth: 0,
          paddingBlock: 8,
          paddingInline: 12,
          textTransform: 'none',
          fontSize: '0.8125rem',
          fontWeight: 700,
        },
      },
    },
    MuiDialog: {
      styleOverrides: {
        paper: {
          border: `1px solid ${tokens.color.neutral.border}`,
          borderRadius: tokens.shape.radiusDialog,
        },
      },
    },
    MuiDialogTitle: {
      styleOverrides: {
        root: { padding: '16px 20px 8px', fontSize: '1rem', fontWeight: 720 },
      },
    },
    MuiDialogContent: {
      styleOverrides: {
        root: { padding: '12px 20px' },
      },
    },
    MuiDialogActions: {
      styleOverrides: {
        root: { padding: '12px 20px 16px', gap: tokens.spacing.controlGap },
      },
    },
    MuiDrawer: {
      styleOverrides: {
        paper: { borderColor: tokens.color.neutral.border },
      },
    },
    MuiTooltip: {
      defaultProps: { arrow: true, enterDelay: 500 },
      styleOverrides: {
        tooltip: { fontSize: '0.75rem', lineHeight: 1.35 },
      },
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
