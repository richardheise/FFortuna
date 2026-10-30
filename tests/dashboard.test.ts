import assert from 'node:assert/strict';
import test from 'node:test';
import { buildDashboard, categoryKey, filterTransactions } from '../src/capture/dashboard';
import type { FinanceSnapshot, FinanceTransaction } from '../src/capture/native';

function transaction(overrides: Partial<FinanceTransaction> = {}): FinanceTransaction {
  return {
    id: 'tx-1', kind: 'expense', amountCents: 100, effectiveDate: '2026-10-30', description: 'Compra fictícia',
    categoryId: null, category: 'Diversos', tagIds: [], tags: [], sourceEventId: null, voided: false, cancellationReason: null,
    ...overrides,
  };
}

function snapshot(overrides: Partial<Pick<FinanceSnapshot, 'incomeCents' | 'expenseCents' | 'byCategory' | 'transactions' | 'month'>> = {}) {
  return {
    month: '2026-10', incomeCents: 0, expenseCents: 0, byCategory: [], transactions: [], ...overrides,
  };
}

test('dashboard uses native totals, omits voided rows from recent, and sorts categories without mutation', () => {
  const byCategory = [
    { categoryId: null, name: 'Legada', amountCents: 200 },
    { categoryId: 'food-id', name: 'Comida', amountCents: 300 },
  ];
  const transactions = [
    transaction({ id: 'older', effectiveDate: '2026-10-28', amountCents: 200 }),
    transaction({ id: 'voided', effectiveDate: '2026-10-30', amountCents: 999, voided: true, cancellationReason: 'fixture' }),
    transaction({ id: 'newer', effectiveDate: '2026-10-29', amountCents: 300 }),
    transaction({ id: 'income', kind: 'income', effectiveDate: '2026-10-29', amountCents: 1000 }),
  ];
  const result = buildDashboard(snapshot({ incomeCents: 1000, expenseCents: 500, byCategory, transactions }));
  assert.equal(result.resultCents, 500);
  assert.deepEqual(result.categories.map(row => row.name), ['Comida', 'Legada']);
  assert.deepEqual(result.categories.map(row => row.shareBps), [6000, 4000]);
  assert.deepEqual(result.recent.map(row => row.id), ['newer', 'income', 'older']);
  assert.deepEqual(result.comparison.map(row => row.widthBps), [10000, 5000]);
  assert.deepEqual(byCategory.map(row => row.name), ['Legada', 'Comida']);
  assert.deepEqual(transactions.map(row => row.id), ['older', 'voided', 'newer', 'income']);
});

test('empty month has zero net, widths, and category shares', () => {
  const result = buildDashboard(snapshot());
  assert.equal(result.resultCents, 0);
  assert.deepEqual(result.categories, []);
  assert.deepEqual(result.comparison.map(row => row.widthBps), [0, 0]);
  assert.deepEqual(result.recent, []);
});

test('safe-integer boundaries use exact cents and basis-point arithmetic', () => {
  const max = Number.MAX_SAFE_INTEGER;
  const result = buildDashboard(snapshot({
    incomeCents: max,
    expenseCents: max - 1,
    byCategory: [
      { categoryId: 'small', name: 'Pequena', amountCents: 1 },
      { categoryId: 'large', name: 'Grande', amountCents: max - 2 },
    ],
  }));
  assert.equal(result.resultCents, 1);
  assert.deepEqual(result.categories.map(row => row.name), ['Grande', 'Pequena']);
  assert.deepEqual(result.categories.map(row => row.shareBps), [10000, 0]);
  assert.deepEqual(result.comparison.map(row => row.widthBps), [10000, 9999]);
});

test('rejects category data that does not reconcile to expense total', () => {
  assert.throws(() => buildDashboard(snapshot({
    expenseCents: 101,
    byCategory: [{ categoryId: null, name: 'Legada', amountCents: 100 }],
  })), /CATEGORY_TOTAL_MISMATCH/);
});

test('legacy label and catalog id stay distinct even when their display names match', () => {
  const legacy = categoryKey(null, 'Comida');
  const catalog = categoryKey('Comida', 'Comida');
  assert.equal(legacy, 'label:Comida');
  assert.equal(catalog, 'id:Comida');
  assert.notEqual(legacy, catalog);
});

test('history filters ignore accents and case across category and tags, including voided entries', () => {
  const voided = transaction({ id: 'voided', categoryId: 'health', category: 'Saúde', tags: ['Médico'], voided: true });
  const match = transaction({ id: 'match', categoryId: 'health', category: 'Saúde', tags: ['Médico'] });
  const other = transaction({ id: 'other', kind: 'income', category: 'Salário', tags: ['Mensal'] });
  const rows = [voided, match, other];
  const filtered = filterTransactions(rows, { kind: 'expense', category: categoryKey('health', 'Saúde'), query: '  MEDICO  ' });
  assert.deepEqual(filtered.map(row => row.id), ['voided', 'match']);
  assert.deepEqual(filterTransactions(rows, { query: ' cafe ' }), []);
  assert.deepEqual(rows.map(row => row.id), ['voided', 'match', 'other']);
});

test('equal transactions remain independent and recent ordering is stable for same-day rows', () => {
  const rows = [
    transaction({ id: 'same-a', effectiveDate: '2026-10-29' }),
    transaction({ id: 'same-b', effectiveDate: '2026-10-29' }),
    transaction({ id: 'later', effectiveDate: '2026-10-30' }),
    transaction({ id: 'voided', effectiveDate: '2026-10-31', voided: true }),
  ];
  const before = rows.map(row => row.id);
  const result = buildDashboard(snapshot({ expenseCents: 300, byCategory: [{ categoryId: null, name: 'Diversos', amountCents: 300 }], transactions: rows }));
  assert.deepEqual(result.recent.map(row => row.id), ['later', 'same-a', 'same-b']);
  assert.deepEqual(rows.map(row => row.id), before);
  assert.equal(new Set(result.recent.map(row => row.id)).size, 3);
});

test('rejects unsafe summary and nonpositive or unsafe transaction amounts', () => {
  assert.throws(() => buildDashboard(snapshot({ incomeCents: Number.MAX_SAFE_INTEGER + 1 })), /INVALID_INCOME_CENTS/);
  assert.throws(() => buildDashboard(snapshot({ transactions: [transaction({ amountCents: 0 })] })), /INVALID_TRANSACTION_CENTS/);
  assert.throws(() => buildDashboard(snapshot({ transactions: [transaction({ amountCents: Number.MAX_SAFE_INTEGER + 1 })] })), /INVALID_TRANSACTION_CENTS/);
});
