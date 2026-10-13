import {
  Paper,
  Table,
  TableBody,
  TableCell,
  TableContainer,
  TableHead,
  TableRow,
} from '@mui/material';
import type { ReactNode } from 'react';

export { Table, TableBody, TableCell, TableHead, TableRow };

export function DataTable({ children, minWidth = 720 }: { children: ReactNode; minWidth?: number }) {
  return (
    <TableContainer component={Paper} variant="outlined" sx={{ borderColor: 'divider' }}>
      <Table size="small" sx={{ minWidth }}>{children}</Table>
    </TableContainer>
  );
}
