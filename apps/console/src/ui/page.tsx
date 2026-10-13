import { Box, Paper, Typography, type BoxProps, type PaperProps } from '@mui/material';
import type { ReactNode } from 'react';

type PageWidth = 'narrow' | 'standard' | 'wide';
type PageProps = Omit<BoxProps, 'children'> & { children: ReactNode; width?: PageWidth };

const pageWidths: Record<PageWidth, string> = {
  narrow: '52rem',
  standard: '76rem',
  wide: '96rem',
};

export function Page({ children, width = 'standard', sx, ...props }: PageProps) {
  return (
    <Box
      component="section"
      data-page-width={width}
      sx={{
        width: `min(${pageWidths[width]}, 100%)`,
        mx: 'auto',
        display: 'grid',
        gap: { xs: 2, md: 2.5 },
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
        display: 'flex',
        flexDirection: { xs: 'column', md: actions ? 'row' : 'column' },
        justifyContent: 'space-between',
        alignItems: { xs: 'stretch', md: actions ? 'flex-start' : 'stretch' },
        gap: 2,
      }}
    >
      <Box sx={{ maxWidth: '64rem', minWidth: 0 }}>
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
      {actions ? (
        <Box sx={{ display: 'flex', flexWrap: 'wrap', gap: 1, flex: '0 0 auto' }}>
          {actions}
        </Box>
      ) : null}
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
        display: 'flex',
        flexDirection: { xs: 'column', sm: 'row' },
        alignItems: { xs: 'stretch', sm: 'center' },
        justifyContent: 'space-between',
        gap: 1.25,
        ...sx,
      }}
      {...props}
    >
      {leading ? <Box sx={{ minWidth: 0, flex: '1 1 auto' }}>{leading}</Box> : <span />}
      <Box sx={{ display: 'flex', flexWrap: 'wrap', gap: 1, justifyContent: { sm: 'flex-end' } }}>{children}</Box>
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
    <Paper variant="outlined" sx={{ minWidth: 0, p: { xs: 2, md: 2.5 }, borderColor: 'divider', ...sx }} {...props}>
      {(title || description || actions) ? (
        <Box
          sx={{
            display: 'flex',
            flexDirection: { xs: 'column', sm: actions ? 'row' : 'column' },
            justifyContent: 'space-between',
            alignItems: { xs: 'stretch', sm: actions ? 'flex-start' : 'stretch' },
            gap: 1.5,
            mb: 2,
          }}
        >
          <Box sx={{ minWidth: 0 }}>
            {title ? <Typography component="h2" variant="h6">{title}</Typography> : null}
            {description ? <Typography color="text.secondary" variant="body2" sx={{ mt: title ? 0.5 : 0, lineHeight: 1.55 }}>{description}</Typography> : null}
          </Box>
          {actions ? (
            <Box sx={{ display: 'flex', flexWrap: 'wrap', gap: 1 }}>
              {actions}
            </Box>
          ) : null}
        </Box>
      ) : null}
      {children}
    </Paper>
  );
}
