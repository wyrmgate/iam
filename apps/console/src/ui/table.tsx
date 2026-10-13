import {
  Box,
  Paper,
  Table,
  TableBody,
  TableCell,
  TableContainer,
  TableHead,
  TableRow,
} from '@mui/material';
import type { TableCellProps, TableRowProps } from '@mui/material';
import type { ReactNode } from 'react';

export { Table, TableBody, TableCell, TableHead, TableRow };

export type DataTableProps = {
  children: ReactNode;
  minWidth?: number | string;
  ariaLabel?: string;
  ariaLabelledBy?: string;
  caption?: ReactNode;
  captionHidden?: boolean;
};

export function DataTable({
  children,
  minWidth = 720,
  ariaLabel,
  ariaLabelledBy,
  caption,
  captionHidden = false,
}: DataTableProps) {
  return (
    <TableContainer
      component={Paper}
      variant="outlined"
      sx={{
        maxWidth: '100%',
        overflowX: 'auto',
        borderColor: 'divider',
        backgroundColor: 'background.paper',
      }}
    >
      <Table
        size="small"
        aria-label={ariaLabel}
        aria-labelledby={ariaLabelledBy}
        sx={{
          minWidth,
          '& .MuiTableCell-root': {
            verticalAlign: 'middle',
          },
          '& .MuiTableHead-root .MuiTableCell-root': {
            whiteSpace: 'nowrap',
            lineHeight: 1.25,
          },
          '& .MuiTableBody-root .MuiTableRow-root:last-of-type .MuiTableCell-root': {
            borderBottom: 0,
          },
        }}
      >
        {caption ? (
          <Box
            component="caption"
            sx={captionHidden ? visuallyHiddenCaptionSx : visibleCaptionSx}
          >
            {caption}
          </Box>
        ) : null}
        {children}
      </Table>
    </TableContainer>
  );
}

export function DataTableRow({ interactive = false, hover, sx, ...props }: TableRowProps & { interactive?: boolean }) {
  return (
    <TableRow
      {...props}
      hover={hover ?? interactive}
      sx={[
        interactive
          ? {
              '&:focus-within': {
                backgroundColor: 'action.hover',
              },
              '&.Mui-selected': {
                backgroundColor: 'action.selected',
              },
            }
          : {},
        ...(Array.isArray(sx) ? sx : sx ? [sx] : []),
      ]}
    />
  );
}

export function DataTableActionCell({ align = 'right', sx, ...props }: TableCellProps) {
  return (
    <TableCell
      {...props}
      align={align}
      sx={[
        {
          width: '1%',
          whiteSpace: 'nowrap',
        },
        ...(Array.isArray(sx) ? sx : sx ? [sx] : []),
      ]}
    />
  );
}

export function DataTableStateRow({
  colSpan,
  children,
  loading = false,
}: {
  colSpan: number;
  children: ReactNode;
  loading?: boolean;
}) {
  return (
    <TableRow>
      <TableCell colSpan={colSpan} sx={{ py: 3, textAlign: 'center', color: 'text.secondary' }}>
        <Box role={loading ? 'status' : undefined} aria-live={loading ? 'polite' : undefined}>
          {children}
        </Box>
      </TableCell>
    </TableRow>
  );
}

const visibleCaptionSx = {
  captionSide: 'top',
  paddingBlock: 1,
  paddingInline: 1.5,
  textAlign: 'left',
  color: 'text.secondary',
  fontSize: '0.75rem',
  lineHeight: 1.4,
  borderBottom: 1,
  borderColor: 'divider',
} as const;

const visuallyHiddenCaptionSx = {
  position: 'absolute',
  width: 1,
  height: 1,
  padding: 0,
  margin: -1,
  overflow: 'hidden',
  clip: 'rect(0 0 0 0)',
  whiteSpace: 'nowrap',
  border: 0,
} as const;
