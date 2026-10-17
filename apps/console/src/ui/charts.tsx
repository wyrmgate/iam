import { Box, Typography } from './layout';
import { wyrmgateDesignTokens } from './designTokens';

export type DonutChartItem = {
  key: string;
  label: string;
  value: number;
};

export type DonutChartProps = {
  ariaLabel: string;
  items: DonutChartItem[];
  totalLabel: string;
  totalValue?: number;
  size?: number;
};

const chartColors = wyrmgateDesignTokens.color.visualization.categorical;
const chartTrack = wyrmgateDesignTokens.color.visualization.track;

function percentage(value: number, total: number): string {
  if (total <= 0 || value <= 0) return '0%';
  const percent = (value / total) * 100;
  return percent >= 10 ? `${Math.round(percent)}%` : `${percent.toFixed(1)}%`;
}

export function DonutChart({ ariaLabel, items, totalLabel, totalValue, size = 156 }: DonutChartProps) {
  const normalized = items.map((item) => ({ ...item, value: Math.max(0, item.value) }));
  const calculatedTotal = normalized.reduce((sum, item) => sum + item.value, 0);
  const total = totalValue ?? calculatedTotal;
  const visualTotal = calculatedTotal;
  const radius = 48;
  const circumference = 2 * Math.PI * radius;
  let consumed = 0;

  return (
    <Box
      sx={{
        display: 'grid',
        gridTemplateColumns: { xs: '1fr', sm: `${size}px minmax(0, 1fr)` },
        gap: 2,
        alignItems: 'center',
        minWidth: 0,
      }}
    >
      <Box sx={{ position: 'relative', width: size, height: size, justifySelf: { xs: 'center', sm: 'start' } }}>
        <svg
          width={size}
          height={size}
          viewBox="0 0 120 120"
          role="img"
          aria-label={ariaLabel}
          style={{ display: 'block', transform: 'rotate(-90deg)' }}
        >
          <circle cx="60" cy="60" r={radius} fill="none" stroke={chartTrack} strokeWidth="14" />
          {visualTotal > 0 ? normalized.map((item, index) => {
            const segment = (item.value / visualTotal) * circumference;
            const offset = -consumed;
            consumed += segment;
            return (
              <circle
                key={item.key}
                cx="60"
                cy="60"
                r={radius}
                fill="none"
                stroke={chartColors[index % chartColors.length]}
                strokeWidth="14"
                strokeDasharray={`${segment} ${Math.max(0, circumference - segment)}`}
                strokeDashoffset={offset}
                pathLength={circumference}
              />
            );
          }) : null}
        </svg>
        <Box
          aria-hidden="true"
          sx={{
            position: 'absolute',
            inset: 0,
            display: 'grid',
            placeContent: 'center',
            textAlign: 'center',
            pointerEvents: 'none',
          }}
        >
          <Typography sx={{ fontSize: 24, lineHeight: 1.15, fontWeight: 700 }}>{total.toLocaleString()}</Typography>
          <Typography variant="caption" color="text.secondary">{totalLabel}</Typography>
        </Box>
      </Box>

      <Box component="ul" sx={{ listStyle: 'none', m: 0, p: 0, display: 'grid', gap: 1, minWidth: 0 }}>
        {normalized.map((item, index) => (
          <Box component="li" key={item.key} sx={{ display: 'grid', gridTemplateColumns: '12px minmax(0, 1fr) auto', gap: 1, alignItems: 'center', minWidth: 0 }}>
            <Box
              aria-hidden="true"
              sx={{ width: 10, height: 10, borderRadius: '50%', bgcolor: chartColors[index % chartColors.length] }}
            />
            <Typography variant="caption" sx={{ fontWeight: 600, minWidth: 0 }}>{item.label}</Typography>
            <Typography variant="caption" color="text.secondary" sx={{ whiteSpace: 'nowrap' }}>
              {item.value.toLocaleString()} · {percentage(item.value, visualTotal)}
            </Typography>
          </Box>
        ))}
      </Box>
    </Box>
  );
}
