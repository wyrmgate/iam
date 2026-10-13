export const wyrmgateDesignTokens = {
  color: {
    product: {
      accent: '#235ea7',
      accentHover: '#164987',
      accentSubtle: '#eaf2fb',
      accentFocus: '#8bb7e8',
    },
    neutral: {
      canvas: '#f4f7fb',
      surface: '#ffffff',
      surfaceSubtle: '#f8fafc',
      border: '#d9e1ea',
      borderStrong: '#bcc8d8',
      text: '#172033',
      textSecondary: '#66758a',
      textDisabled: '#8b98a9',
    },
    semantic: {
      success: '#257149',
      successSubtle: '#e9f5ee',
      warning: '#875f12',
      warningSubtle: '#fff6dd',
      danger: '#a12b35',
      dangerSubtle: '#fcecef',
      info: '#356b9b',
      infoSubtle: '#edf5fc',
    },
    lifecycle: {
      active: '#257149',
      pending: '#6f5b1f',
      suspended: '#9a5b13',
      inactive: '#66758a',
    },
  },
  spacing: {
    unit: 8,
    compact: 4,
    controlGap: 8,
    sectionGap: 16,
    workspaceGap: 24,
  },
  density: {
    controlHeight: 36,
    compactControlHeight: 32,
    tableRowHeight: 40,
    tableCellBlockPadding: 8,
    tableCellInlinePadding: 12,
  },
  shape: {
    radiusSmall: 6,
    radiusControl: 8,
    radiusSurface: 9,
    radiusDialog: 12,
  },
  typography: {
    fontFamily: 'Inter, ui-sans-serif, system-ui, -apple-system, BlinkMacSystemFont, "Segoe UI", sans-serif',
    baseFontSize: 14,
    bodyLineHeight: 1.45,
    compactLineHeight: 1.3,
  },
} as const;

export type WyrmgateDesignTokens = typeof wyrmgateDesignTokens;
