import {
  Tab as MuiTab,
  Tabs as MuiTabs,
  type TabProps,
  type TabsProps,
} from '@mui/material';
import { useState, type HTMLAttributes, type ReactNode } from 'react';

type TabsAccessibleName =
  | { 'aria-label': string; 'aria-labelledby'?: never }
  | { 'aria-label'?: never; 'aria-labelledby': string };

type TabsValue = TabsProps['value'];

type TabsCommonProps = Omit<TabsProps, 'value' | 'aria-label' | 'aria-labelledby'> &
  TabsAccessibleName;

export type ControlledTabsProps = TabsCommonProps & {
  value: TabsValue;
  defaultValue?: never;
};

export type UncontrolledTabsProps = TabsCommonProps & {
  value?: never;
  defaultValue: TabsValue;
};

export type WyrmgateTabsProps = ControlledTabsProps | UncontrolledTabsProps;

export type TabPanelProps = Omit<HTMLAttributes<HTMLDivElement>, 'id' | 'role'> & {
  groupId: string;
  tabId: string;
  active: boolean;
  children?: ReactNode;
  keepMounted?: boolean;
};

function normalizeIdPart(value: string) {
  return value.trim().replace(/[^A-Za-z0-9_-]+/g, '-');
}

function tabElementId(groupId: string, tabId: string) {
  return `${normalizeIdPart(groupId)}-tab-${normalizeIdPart(tabId)}`;
}

function tabPanelElementId(groupId: string, tabId: string) {
  return `${normalizeIdPart(groupId)}-tabpanel-${normalizeIdPart(tabId)}`;
}

/**
 * Returns the explicit relationship hooks a Tab needs for a matching TabPanel.
 * Route/view state remains owned by the caller; this helper only provides DOM ids.
 */
export function getTabA11yProps(groupId: string, tabId: string) {
  return {
    id: tabElementId(groupId, tabId),
    'aria-controls': tabPanelElementId(groupId, tabId),
  } as const;
}

/**
 * Shared horizontal tab list. Callers choose either controlled `value` or
 * uncontrolled `defaultValue`; routing and workspace semantics stay outside this primitive.
 */
export function Tabs(props: WyrmgateTabsProps) {
  const {
    value: controlledValue,
    defaultValue,
    onChange,
    sx,
    ...rest
  } = props;
  const [uncontrolledValue, setUncontrolledValue] = useState<TabsValue>(defaultValue);
  const controlled = controlledValue !== undefined;
  const value = controlled ? controlledValue : uncontrolledValue;

  return (
    <MuiTabs
      variant="scrollable"
      orientation="horizontal"
      scrollButtons="auto"
      allowScrollButtonsMobile
      selectionFollowsFocus={false}
      {...rest}
      value={value}
      onChange={(event, nextValue) => {
        if (!controlled) setUncontrolledValue(nextValue);
        onChange?.(event, nextValue);
      }}
      sx={{
        minWidth: 0,
        maxWidth: '100%',
        minHeight: 42,
        borderBottom: 1,
        borderColor: 'divider',
        '& .MuiTabs-scroller': {
          minWidth: 0,
          overscrollBehaviorInline: 'contain',
          scrollBehavior: 'smooth',
        },
        '& .MuiTabs-scrollButtons.Mui-disabled': {
          opacity: 0.3,
        },
        '@media (prefers-reduced-motion: reduce)': {
          '& .MuiTabs-scroller': {
            scrollBehavior: 'auto',
          },
        },
        ...sx,
      }}
    />
  );
}

export function Tab(props: TabProps) {
  return (
    <MuiTab
      disableRipple
      {...props}
      sx={{
        minHeight: 42,
        minWidth: 'max-content',
        textTransform: 'none',
        fontWeight: 700,
        whiteSpace: 'nowrap',
        ...props.sx,
      }}
    />
  );
}

/**
 * Accessible panel companion for Tab. `active` is intentionally caller-owned so
 * local state, URL state, or another view model can control visibility explicitly.
 */
export function TabPanel({
  groupId,
  tabId,
  active,
  keepMounted = false,
  children,
  ...props
}: TabPanelProps) {
  return (
    <div
      {...props}
      id={tabPanelElementId(groupId, tabId)}
      role="tabpanel"
      aria-labelledby={tabElementId(groupId, tabId)}
      hidden={!active}
      tabIndex={active ? 0 : -1}
    >
      {active || keepMounted ? children : null}
    </div>
  );
}
