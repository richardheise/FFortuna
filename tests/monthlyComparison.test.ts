import test from 'node:test';
import assert from 'node:assert/strict';
import { compareMonths, previousMonth } from '../src/capture/monthlyComparison';
import type { FinanceSnapshot } from '../src/capture/native';
const snapshot = (month: string, days: number[], kind = 'expense') => ({ month, transactions: days.map((day, i) => ({ id: String(i), kind, amountCents: 100, effectiveDate: `${month}-${String(day).padStart(2,'0')}`, voided: false, categoryId: null, category: 'A classificar' })) }) as FinanceSnapshot;
test('compares the same elapsed portion and counts uncategorized confirmed expenses', () => {
  const result = compareMonths(snapshot('2026-10', [1, 10, 30]), snapshot('2026-09', [1, 15, 30]), '2026-10-15');
  assert.equal(result.current.expense, 200); assert.equal(result.previous.expense, 200); assert.equal(result.current.unclassified, 200);
});
test('historical months use the whole month; transfers are outside income and consumption', () => {
  const result = compareMonths(snapshot('2026-09', [30], 'transfer'), snapshot('2026-08', [31]), '2026-10-15');
  assert.equal(result.current.expense, 0); assert.equal(result.difference, -100); assert.equal(previousMonth('2026-01'), '2025-12');
});
test('comparison rejects mismatched periods and unsafe amounts', () => {
  assert.throws(() => compareMonths(snapshot('2026-10', []), snapshot('2026-08', []), '2026-10-15'));
  const bad=snapshot('2026-10',[1]); bad.transactions[0]!.amountCents=Number.MAX_SAFE_INTEGER+1;
  assert.throws(() => compareMonths(bad,snapshot('2026-09',[]),'2026-10-15'));
});
