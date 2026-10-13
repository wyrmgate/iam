import { Tab as MuiTab, Tabs as MuiTabs, type TabProps, type TabsProps } from '@mui/material';

export function Tabs(props: TabsProps) {
  return (
    <MuiTabs
      variant="scrollable"
      scrollButtons="auto"
      allowScrollButtonsMobile
      {...props}
      sx={{ minHeight: 42, borderBottom: 1, borderColor: 'divider', ...props.sx }}
    />
  );
}

export function Tab(props: TabProps) {
  return <MuiTab disableRipple {...props} sx={{ minHeight: 42, textTransform: 'none', fontWeight: 700, ...props.sx }} />;
}
