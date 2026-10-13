import { CssBaseline, ThemeProvider, createTheme } from '@mui/material';
import type { ReactNode } from 'react';

const theme = createTheme({
  palette: {
    mode: 'light',
    primary: { main: '#235ea7', dark: '#164987', light: '#eaf2fb' },
    error: { main: '#a12b35' },
    warning: { main: '#875f12' },
    success: { main: '#257149' },
    background: { default: '#f4f7fb', paper: '#ffffff' },
    text: { primary: '#172033', secondary: '#66758a' },
    divider: '#d9e1ea',
  },
  shape: { borderRadius: 9 },
  typography: {
    fontFamily: 'Inter, ui-sans-serif, system-ui, -apple-system, BlinkMacSystemFont, "Segoe UI", sans-serif',
    h1: { fontWeight: 760, letterSpacing: '-0.025em' },
    h2: { fontWeight: 740, letterSpacing: '-0.02em' },
    h3: { fontWeight: 720 },
    button: { textTransform: 'none', fontWeight: 700 },
  },
  components: {
    MuiButton: {
      defaultProps: { disableElevation: true },
      styleOverrides: { root: { minHeight: 40, borderRadius: 8 } },
    },
    MuiTextField: {
      defaultProps: { size: 'small', fullWidth: true },
    },
    MuiPaper: {
      styleOverrides: { root: { backgroundImage: 'none' } },
    },
    MuiDialog: {
      styleOverrides: { paper: { border: '1px solid #d9e1ea' } },
    },
    MuiDrawer: {
      styleOverrides: { paper: { borderLeft: '1px solid #d9e1ea' } },
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
