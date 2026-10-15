import { wyrmgateDesignTokens as tokens } from './designTokens';

const px = (value: number) => `${value}px`;

export const enterpriseCssVariables = {
  '--wg-canvas': tokens.color.neutral.canvas,
  '--wg-surface': tokens.color.neutral.surface,
  '--wg-surface-subtle': tokens.color.neutral.surfaceSubtle,
  '--wg-surface-selected': tokens.color.neutral.surfaceSelected,
  '--wg-border': tokens.color.neutral.border,
  '--wg-border-strong': tokens.color.neutral.borderStrong,
  '--wg-text': tokens.color.neutral.text,
  '--wg-text-secondary': tokens.color.neutral.textSecondary,
  '--wg-text-muted': tokens.color.neutral.textMuted,
  '--wg-accent': tokens.color.product.accent,
  '--wg-accent-hover': tokens.color.product.accentHover,
  '--wg-accent-subtle': tokens.color.product.accentSubtle,
  '--wg-focus': tokens.color.product.accentFocus,
  '--wg-success': tokens.color.semantic.success,
  '--wg-success-subtle': tokens.color.semantic.successSubtle,
  '--wg-warning': tokens.color.semantic.warning,
  '--wg-warning-subtle': tokens.color.semantic.warningSubtle,
  '--wg-danger': tokens.color.semantic.danger,
  '--wg-danger-subtle': tokens.color.semantic.dangerSubtle,
  '--wg-info': tokens.color.semantic.info,
  '--wg-info-subtle': tokens.color.semantic.infoSubtle,
  '--wg-radius-badge': px(tokens.shape.radiusBadge),
  '--wg-radius-control': px(tokens.shape.radiusControl),
  '--wg-radius-surface': px(tokens.shape.radiusSurface),
  '--wg-radius-dialog': px(tokens.shape.radiusDialog),
  '--wg-control-height': px(tokens.density.controlHeight),
  '--wg-mobile-target': px(tokens.density.mobileTargetHeight),
  '--wg-page-title': px(tokens.typography.pageTitleSize),
  '--wg-section-title': px(tokens.typography.sectionTitleSize),
  '--wg-table-text': px(tokens.typography.tableTextSize),
  '--wg-table-header': px(tokens.typography.tableHeaderSize),
} as const;

export function installEnterpriseCssVariables(root: HTMLElement = document.documentElement) {
  for (const [name, value] of Object.entries(enterpriseCssVariables)) {
    root.style.setProperty(name, value);
  }
}
