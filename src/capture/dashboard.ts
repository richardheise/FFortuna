import type { FinanceSnapshot, FinanceTransaction } from './native';

type DashboardInput = Pick<FinanceSnapshot, 'incomeCents' | 'expenseCents' | 'byCategory' | 'transactions' | 'month'>;
type DashboardCategory = { key: string; categoryId: string | null; name: string; amountCents: number; shareBps: number };
type DashboardComparison = { kind: 'income' | 'expense'; label: string; amountCents: number; widthBps: number };
type Dashboard = { resultCents: number; categories: DashboardCategory[]; comparison: DashboardComparison[]; recent: FinanceTransaction[] };

const MAX_CENTS = BigInt(Number.MAX_SAFE_INTEGER);
const BASIS_POINTS = 10_000n;

function requireCents(value: number, allowZero: boolean, label: string): bigint {
  if (!Number.isSafeInteger(value) || value < (allowZero ? 0 : 1)) throw new Error(`INVALID_${label.toUpperCase()}_CENTS`);
  return BigInt(value);
}

export function categoryKey(categoryId: string | null, name: string): string {
  return categoryId === null ? `label:${name}` : `id:${categoryId}`;
}

function allocateShares(rows: Array<{ amount: bigint }>, total: bigint): number[] {
  if (total === 0n) return rows.map(() => 0);
  const shares = rows.map(({ amount }, index) => {
    const scaled = amount * BASIS_POINTS;
    return { index, basisPoints: scaled / total, remainder: scaled % total };
  });
  const allocated = shares.reduce((sum, row) => sum + row.basisPoints, 0n);
  const remainderCount = Number(BASIS_POINTS - allocated);
  shares.sort((left, right) => {
    if (left.remainder === right.remainder) return left.index - right.index;
    return left.remainder > right.remainder ? -1 : 1;
  });
  for (let index = 0; index < remainderCount; index++) {
    const share = shares[index];
    if (!share) throw new Error('INVALID_CATEGORY_SHARES');
    share.basisPoints += 1n;
  }
  shares.sort((left, right) => left.index - right.index);
  return shares.map(row => Number(row.basisPoints));
}

function normalizeSearch(value: string): string {
  return value.normalize('NFD').replace(/\p{M}/gu, '').toLocaleLowerCase('pt-BR');
}

export function buildDashboard(snapshot: DashboardInput): Dashboard {
  const income = requireCents(snapshot.incomeCents, true, 'income');
  const expense = requireCents(snapshot.expenseCents, true, 'expense');
  if (typeof snapshot.month !== 'string' || !Array.isArray(snapshot.byCategory) || !Array.isArray(snapshot.transactions)) {
    throw new Error('INVALID_FINANCE_SNAPSHOT');
  }

  const seenKeys = new Set<string>();
  const rawCategories = snapshot.byCategory.map((row, sourceIndex) => {
    if ((row.categoryId !== null && typeof row.categoryId !== 'string') || typeof row.name !== 'string') {
      throw new Error('INVALID_FINANCE_CATEGORY');
    }
    const amount = requireCents(row.amountCents, true, 'category');
    const key = categoryKey(row.categoryId, row.name);
    if (seenKeys.has(key)) throw new Error('INVALID_FINANCE_CATEGORY');
    seenKeys.add(key);
    return { key, categoryId: row.categoryId, name: row.name, amount, sourceIndex };
  });
  const categoryTotal = rawCategories.reduce((sum, row) => sum + row.amount, 0n);
  if (categoryTotal !== expense) throw new Error('CATEGORY_TOTAL_MISMATCH');

  const sortedCategories = [...rawCategories].sort((left, right) => {
    if (left.amount === right.amount) return left.sourceIndex - right.sourceIndex;
    return left.amount > right.amount ? -1 : 1;
  });
  const shares = allocateShares(sortedCategories, expense);
  const categories = sortedCategories.map((row, index) => {
    const shareBps = shares[index];
    if (shareBps === undefined) throw new Error('INVALID_CATEGORY_SHARES');
    return { key: row.key, categoryId: row.categoryId, name: row.name, amountCents: Number(row.amount), shareBps };
  });

  const comparisonMax = income > expense ? income : expense;
  const comparison: DashboardComparison[] = [
    { kind: 'income', label: 'Receitas', amountCents: Number(income), widthBps: comparisonMax === 0n ? 0 : Number(income * BASIS_POINTS / comparisonMax) },
    { kind: 'expense', label: 'Despesas', amountCents: Number(expense), widthBps: comparisonMax === 0n ? 0 : Number(expense * BASIS_POINTS / comparisonMax) },
  ];

  const recent = snapshot.transactions.map((row, sourceIndex) => {
    if (row.kind !== 'income' && row.kind !== 'expense') throw new Error('INVALID_FINANCE_TRANSACTION');
    requireCents(row.amountCents, false, 'transaction');
    if (typeof row.effectiveDate !== 'string' || typeof row.description !== 'string' || typeof row.category !== 'string' ||
        (row.categoryId !== null && typeof row.categoryId !== 'string') || !Array.isArray(row.tags) ||
        row.tags.some(tag => typeof tag !== 'string') || typeof row.voided !== 'boolean') {
      throw new Error('INVALID_FINANCE_TRANSACTION');
    }
    return { row, sourceIndex };
  }).filter(({ row }) => !row.voided)
    .sort((left, right) => right.row.effectiveDate.localeCompare(left.row.effectiveDate) || left.sourceIndex - right.sourceIndex)
    .slice(0, 5)
    .map(({ row }) => row);

  const result = income - expense;
  if (result > MAX_CENTS || result < -MAX_CENTS) throw new Error('INVALID_RESULT_CENTS');
  return { resultCents: Number(result), categories, comparison, recent };
}

export function filterTransactions(
  rows: FinanceTransaction[],
  filters: { kind?: 'all' | 'income' | 'expense'; category?: string | null; query?: string } = {},
): FinanceTransaction[] {
  const query = filters.query?.trim() ? normalizeSearch(filters.query.trim()) : '';
  return rows.filter(row => {
    if (filters.kind && filters.kind !== 'all' && row.kind !== filters.kind) return false;
    if (filters.category != null && categoryKey(row.categoryId, row.category) !== filters.category) return false;
    if (!query) return true;
    const searchable = normalizeSearch([row.description, row.category, ...row.tags].join('\n'));
    return searchable.includes(query);
  });
}
