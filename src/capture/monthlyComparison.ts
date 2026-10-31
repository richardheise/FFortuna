import type { FinanceSnapshot } from './native';

export function previousMonth(month: string): string {
  if (!/^\d{4}-(0[1-9]|1[0-2])$/.test(month)) throw new Error('INVALID_MONTH');
  const year = Number(month.slice(0, 4)), index = Number(month.slice(5));
  return index === 1 ? `${String(year - 1).padStart(4, '0')}-12` : `${year}-${String(index - 1).padStart(2, '0')}`;
}
export function compareMonths(current: FinanceSnapshot, previous: FinanceSnapshot, today: string) {
  if (previous.month !== previousMonth(current.month)) throw new Error('INVALID_COMPARISON_MONTH');
  const cut = current.month === today.slice(0, 7) ? Number(today.slice(8, 10)) : 31;
  const total = (snapshot: FinanceSnapshot) => {
    let expense = 0n, income = 0n, unclassified = 0n;
    for (const row of snapshot.transactions) {
      if (row.voided || !row.effectiveDate.startsWith(snapshot.month + '-') || Number(row.effectiveDate.slice(8, 10)) > cut) continue;
      if (!Number.isSafeInteger(row.amountCents) || row.amountCents <= 0) throw new Error('INVALID_COMPARISON_AMOUNT');
      const amount = BigInt(row.amountCents);
      if (row.kind === 'expense' || String(row.kind) === 'card_purchase') { expense += amount; if (!row.categoryId && row.category === 'A classificar') unclassified += amount; }
      if (row.kind === 'income') income += amount;
    }
    for (const value of [expense, income, unclassified]) if (value > BigInt(Number.MAX_SAFE_INTEGER)) throw new Error('AMOUNT_LIMIT');
    return { expense: Number(expense), income: Number(income), unclassified: Number(unclassified) };
  };
  const now = total(current), before = total(previous);
  const difference = BigInt(now.expense) - BigInt(before.expense);
  return { current: now, previous: before, difference: Number(difference), cut, comparable: previous.transactions.some(row => !row.voided) };
}
