import { Box, Paper, Typography, type BoxProps, type PaperProps } from '@mui/material';
import type { ReactNode } from 'react';

import { wyrmgateDesignTokens as tokens } from './designTokens';

type PageWidth = 'narrow' | 'standard' | 'wide';
type PageProps = Omit<BoxProps, 'children'> & { children: ReactNode; width?: PageWidth };

const pageWidths: Record<PageWidth, string> = {
  narrow: tokens.layout.pageNarrow,
  standard: tokens.layout.pageStandard,
  wide: tokens.layout.pageWide,
};

const responsiveActionGroup = {
  minWidth: 0,
  maxWidth: '100%',
  width: { xs: '100%', sm: 'auto' },
  display: 'flex',
  flexWrap: 'wrap',
  alignItems: 'center',
  gap: 1,
  '& > *': { minWidth: 0, maxWidth: '100%' },
  '& .MuiButton-root': {
    maxWidth: '100%',
    whiteSpace: 'normal',
    overflowWrap: 'anywhere',
    textAlign: 'center',
  },
} as const;

export function Page({ children, width = 'standard', sx, ...props }: PageProps) {
  return (
    <Box
      component="section"
      data-page-width={width}
      sx={{
        width: `min(${pageWidths[width]}, 100%)`,
        maxWidth: '100%',
        minWidth: 0,
        boxSizing: 'border-box',
        mx: 'auto',
        display: 'grid',
        gap: { xs: 2, md: 2.5 },
        overflowWrap: 'anywhere',
        '& > *': { minWidth: 0, maxWidth: '100%' },
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
        minWidth: 0,
        maxWidth: '100%',
        display: 'flex',
        flexDirection: { xs: 'column', md: actions ? 'row' : 'column' },
        justifyContent: 'space-between',
        alignItems: { xs: 'stretch', md: actions ? 'flex-start' : 'stretch' },
        gap: { xs: 1.5, md: 2.5 },
        pb: { xs: 0.5, md: 1 },
      }}
    >
      <Box sx={{ maxWidth: '64rem', minWidth: 0, overflowWrap: 'anywhere' }}>
        {eyebrow ? (
          <Typography
            component="p"
            variant="overline"
            sx={{ color: 'text.secondary', fontWeight: 700, letterSpacing: '.075em', lineHeight: 1.5, mb: 0.25 }}
          >
            {eyebrow}
          </Typography>
        ) : null}
        <Typography component="h1" variant="h4" sx={{ m: 0, mb: description ? 0.75 : 0, overflowWrap: 'anywhere' }}>
          {title}
        </Typography>
        {description ? (
          <Typography
            component="p"
            color="text.secondary"
            sx={{ maxWidth: '56rem', lineHeight: 1.55, m: 0, overflowWrap: 'anywhere' }}
          >
            {description}
          </Typography>
        ) : null}
      </Box>
      {actions ? <Box sx={{ ...responsiveActionGroup, flex: { md: '0 1 auto' } }}>{actions}</Box> : null}
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
        maxWidth: '100%',
        display: 'flex',
        flexDirection: { xs: 'column', sm: 'row' },
        alignItems: { xs: 'stretch', sm: 'center' },
        justifyContent: 'space-between',
        gap: 1.25,
        py: 0.25,
        ...sx,
      }}
      {...props}
    >
      {leading ? <Box sx={{ minWidth: 0, maxWidth: '100%', flex: '1 1 auto', overflowWrap: 'anywhere' }}>{leading}</Box> : null}
      <Box sx={{ ...responsiveActionGroup, justifyContent: { xs: 'flex-start', sm: 'flex-end' }, flex: { sm: '0 1 auto' } }}>{children}</Box>
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
    <Paper
      variant="outlined"
      sx={{
        width: '100%',
        maxWidth: '100%',
        minWidth: 0,
        boxSizing: 'border-box',
        p: { xs: 2, md: 2.25 },
        borderColor: 'divider',
        borderRadius: `${tokens.shape.radiusSurface}px`,
        bgcolor: 'background.paper',
        boxShadow: 'none',
        overflowWrap: 'anywhere',
        '& > *': { minWidth: 0, maxWidth: '100%' },
        ...sx,
      }}
      {...props}
    >
      {(title || description || actions) ? (
        <Box
          sx={{
            minWidth: 0,
            maxWidth: '100%',
            display: 'flex',
            flexDirection: { xs: 'column', sm: actions ? 'row' : 'column' },
            justifyContent: 'space-between',
            alignItems: { xs: 'stretch', sm: actions ? 'flex-start' : 'stretch' },
            gap: 1.25,
            mb: 1.75,
          }}
        >
          <Box sx={{ minWidth: 0, maxWidth: '100%', overflowWrap: 'anywhere' }}>
            {title ? <Typography component="h2" variant="h6" sx={{ overflowWrap: 'anywhere', color: 'text.primary' }}>{title}</Typography> : null}
            {description ? (
              <Typography color="text.secondary" variant="body2" sx={{ mt: title ? 0.4 : 0, maxWidth: '56rem', lineHeight: 1.55, overflowWrap: 'anywhere' }}>
                {description}
              </Typography>
            ) : null}
          </Box>
          {actions ? <Box sx={{ ...responsiveActionGroup, flex: { sm: '0 1 auto' } }}>{actions}</Box> : null}
        </Box>
      ) : null}
      {children}
    </Paper>
  );
}
