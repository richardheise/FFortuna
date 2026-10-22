import assert from 'node:assert/strict';
import { mkdtempSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import test from 'node:test';
import { makeDemoEvent, makeUpdatedEvent } from '../src/demo/scenarios';
import type { ConfirmImportInput, ImportDecision, ImportRow, ReviewInput } from '../src/core/types';
import { createFinanceService, type FinanceService } from '../src/application/demo-service';
import { migrations } from '../src/persistence/migrations';
import { createTestService, openTestDatabase, testEnvironment } from './helpers/database';

const opening = { operationId: 'opening-1', openingDate: '2026-10-21', balanceCents: 100000, expectedIncomeCents: 300000 };
const review = (overrides: Partial<ReviewInput> = {}): ReviewInput => ({
  amountCents: 1000, kind: 'expense', effectiveDate: '2026-10-21', description: 'Compra confirmada',
  category: 'Casa', notes: '', reserveId: null, direction: null, ...overrides,
});

async function readyService(): Promise<Awaited<ReturnType<typeof createTestService>>> {
  const context = await createTestService();
  await context.service.setup(opening);
  return context;
}

function importReview(row: ImportRow, kind: 'income' | 'expense'): ImportDecision {
  return {
    eventId: row.eventId,
    review: review({
      amountCents: row.draft.amountCents ?? 0, kind,
      effectiveDate: row.draft.effectiveDate ?? '2026-10-21',
      description: row.draft.description, category: kind === 'income' ? 'Renda' : 'Alimentação',
    }),
    linkTransactionId: null, skip: false,
  };
}

let importOperationSequence = 0;
function decisionInput(previewId: string, decisions: ImportDecision[], openingValue: ConfirmImportInput['opening'] = null): ConfirmImportInput {
  return { operationId: `confirm-${previewId}-${++importOperationSequence}`, previewId, decisions, opening: openingValue };
}

test('manual income and expense change cash while expected income remains a forecast', async () => {
  const { db, service } = await readyService();
  try {
    await service.addManual({ ...review({ amountCents: 25000, kind: 'income', description: 'Salário' }), operationId: 'salary' });
    await service.addManual({ ...review({ amountCents: 10000, kind: 'expense', description: 'Mercado' }), operationId: 'groceries' });
    const snapshot = await service.snapshot('2026-10');
    assert.equal(snapshot.balanceCents, 115000);
    assert.equal(snapshot.receivedCents, 25000);
    assert.equal(snapshot.spentCents, 10000);
    assert.equal(snapshot.profile?.expectedIncomeCents, 300000);
  } finally { await db.close(); }
});

test('reprocessing and confirming a safe event leave one posted transaction', async () => {
  const { db, service } = await readyService();
  try {
    const event = makeDemoEvent('safe', '2026-10-21', 'idempotent-event');
    const first = await service.observe(event);
    await service.reprocess(event.id);
    const detail = await service.evidence(first.candidateId);
    if (!detail.transactionId) await service.confirmCandidate(first.candidateId, review({ amountCents: 1990, description: 'Café fictício', category: 'Alimentação' }));
    await service.reprocess(event.id);
    const snapshot = await service.snapshot('2026-10');
    assert.equal(snapshot.transactions.length, 1);
    assert.equal(snapshot.balanceCents, 98010);
  } finally { await db.close(); }
});

test('separate source identities preserve two purchases with the same amount and description', async () => {
  const { db, service } = await readyService();
  try {
    const first = makeDemoEvent('same-purchase', '2026-10-21', 'purchase-a');
    await service.observe(first);
    await service.observe(makeDemoEvent('same-purchase', '2026-10-21', 'purchase-b'));
    const duplicate = makeDemoEvent('same-purchase', '2026-10-21', 'purchase-a-observed-again');
    duplicate.payload = JSON.stringify({
      ...(JSON.parse(duplicate.payload) as Record<string, unknown>),
      externalId: (JSON.parse(first.payload) as Record<string, unknown>).externalId,
    });
    const linked = await service.observe(duplicate);
    assert.equal(linked.status, 'linked');
    const snapshot = await service.snapshot('2026-10');
    assert.equal(snapshot.transactions.length, 2);
    assert.equal(snapshot.spentCents, 3980);
    assert.equal(snapshot.transactions.find((item) => item.id === linked.transactionId)?.sourceCount, 2);
  } finally { await db.close(); }
});

test('concurrent confirmation of one pending candidate produces one transaction', async () => {
  const { db, service } = await readyService();
  try {
    const event = makeDemoEvent('review', '2026-10-21', 'concurrent-confirmation');
    const observed = await service.observe(event);
    const decision = review({ amountCents: 7250, description: 'Confirmação concorrente' });
    const ids = await Promise.all([
      service.confirmCandidate(observed.candidateId, decision),
      service.confirmCandidate(observed.candidateId, decision),
    ]);
    assert.equal(ids[0], ids[1]);
    const snapshot = await service.snapshot('2026-10');
    assert.equal(snapshot.transactions.length, 1);
    assert.equal(snapshot.balanceCents, 92750);
  } finally { await db.close(); }
});

test('a confirmed user correction survives reprocessing of its source event', async () => {
  const { db, service } = await readyService();
  try {
    const event = makeDemoEvent('review', '2026-10-21', 'corrected-event');
    const observed = await service.observe(event);
    const correction = review({ amountCents: 4321, kind: 'expense', description: 'Café corrigido', category: 'Alimentação' });
    const transactionId = await service.confirmCandidate(observed.candidateId, correction);
    await assert.rejects(service.confirmCandidate(observed.candidateId, { ...correction, amountCents: 9999 }), /preservada/);
    await service.reprocess(event.id);
    const evidence = await service.evidence(observed.candidateId);
    assert.equal(evidence.transactionId, transactionId);
    assert.deepEqual(evidence.decision, correction);
    assert.equal((await service.snapshot('2026-10')).spentCents, 4321);
  } finally { await db.close(); }
});

test('a newer conflicting revision does not rewrite an already confirmed transaction', async () => {
  const { db, service } = await readyService();
  try {
    const original = makeDemoEvent('safe', '2026-10-21', 'revision-one');
    const first = await service.observe(original);
    const evidence = await service.evidence(first.candidateId);
    const originalTransaction = evidence.transactionId ?? await service.confirmCandidate(first.candidateId, review({ amountCents: 1990 }));
    const updated = makeUpdatedEvent(original, 'revision-two');
    const result = await service.observe(updated);
    assert.equal(result.status, 'conflict');
    const after = await service.evidence(first.candidateId);
    assert.equal(after.transactionId, originalTransaction);
    assert.equal(after.decision?.amountCents ?? after.parsed.draft.amountCents, 1990);
    assert.equal((await service.snapshot('2026-10')).transactions.length, 1);
  } finally { await db.close(); }
});

test('reserve initial position and corrections preserve cash; deposits and withdrawals are transfers', async () => {
  const { db, service } = await readyService();
  try {
    await service.createReserve({ operationId: 'reserve-create', name: 'Reserva', modality: 'Poupança', date: '2026-10-21', amountCents: 20000 });
    let snapshot = await service.snapshot('2026-10');
    const reserve = snapshot.reserves[0];
    assert.ok(reserve);
    assert.equal(snapshot.balanceCents, 100000);
    assert.equal(snapshot.reservesCents, 20000);
    assert.equal(snapshot.receivedCents, 0);
    assert.equal(snapshot.transactions.length, 0);
    await service.moveReserve({ operationId: 'reserve-deposit', reserveId: reserve.id, direction: 'deposit', amountCents: 5000, date: '2026-10-21' });
    await service.moveReserve({ operationId: 'reserve-withdraw', reserveId: reserve.id, direction: 'withdraw', amountCents: 3000, date: '2026-10-21' });
    await service.updateReserve({ operationId: 'reserve-adjust', reserveId: reserve.id, amountCents: 21000, date: '2026-10-21', note: 'Ajuste de posição existente' });
    snapshot = await service.snapshot('2026-10');
    assert.equal(snapshot.balanceCents, 98000);
    assert.equal(snapshot.reserves[0]?.balanceCents, 21000);
    assert.equal(snapshot.receivedCents, 0);
    assert.equal(snapshot.spentCents, 0);
  } finally { await db.close(); }
});

test('reserve transfer overdraw rejects the whole operation without partial postings', async () => {
  const { db, service } = await readyService();
  try {
    await service.createReserve({ operationId: 'reserve-create', name: 'Reserva', modality: 'Poupança', date: '2026-10-21', amountCents: 5000 });
    const reserveId = (await service.snapshot()).reserves[0]?.id;
    assert.ok(reserveId);
    await assert.rejects(service.moveReserve({ operationId: 'overdraw', reserveId, direction: 'deposit', amountCents: 100001, date: '2026-10-21' }));
    const snapshot = await service.snapshot('2026-10');
    assert.equal(snapshot.balanceCents, 100000);
    assert.equal(snapshot.reserves[0]?.balanceCents, 5000);
    assert.equal(snapshot.transactions.length, 0);
  } finally { await db.close(); }
});

test('commitment payment reduces cash and liability without counting as a new expense', async () => {
  const { db, service } = await readyService();
  try {
    await service.addCommitment({ operationId: 'commitment-add', description: 'Fatura', dueDate: '2026-10-25', amountCents: 30000 });
    let snapshot = await service.snapshot('2026-10');
    const commitment = snapshot.commitments[0];
    assert.ok(commitment);
    assert.equal(snapshot.debtCents, 30000);
    assert.equal(snapshot.spentCents, 0);
    assert.equal(snapshot.transactions.length, 0);
    await service.payCommitment({ operationId: 'commitment-payment', commitmentId: commitment.id, amountCents: 10000, date: '2026-10-21' });
    snapshot = await service.snapshot('2026-10');
    assert.equal(snapshot.balanceCents, 90000);
    assert.equal(snapshot.debtCents, 20000);
    assert.equal(snapshot.commitments[0]?.paidCents, 10000);
    assert.equal(snapshot.spentCents, 0);
  } finally { await db.close(); }
});

test('current and overlapping demo imports require review and create only new rows', async () => {
  const { db, service } = await readyService();
  try {
    const current = await service.stageDemoImport('current');
    assert.equal(current.rows.length, 2);
    assert.equal(current.rows.filter((row) => row.status === 'new').length, 2);
    assert.ok(current.rows.every((row) => row.draft.kind === 'income' || row.draft.kind === 'expense'));
    const undecided = current.rows.map((row) => ({ eventId: row.eventId, review: null, linkTransactionId: null, skip: false }));
    await assert.rejects(service.confirmImport(decisionInput(current.id, undecided)));
    const decisions = current.rows.map((row) => importReview(row, row.draft.kind === 'income' ? 'income' : 'expense'));
    await service.confirmImport(decisionInput(current.id, decisions));
    assert.equal((await service.snapshot('2026-10')).balanceCents, 145000);

    const overlap = await service.stageDemoImport('overlap');
    assert.equal(overlap.rows.length, 3);
    assert.equal(overlap.rows.filter((row) => row.status === 'new').length, 1);
    const overlapDecisions = overlap.rows.map((row) => row.status === 'new'
      ? importReview(row, 'expense')
      : { eventId: row.eventId, review: null, linkTransactionId: row.transactionId, skip: true });
    await service.confirmImport(decisionInput(overlap.id, overlapDecisions));
    const snapshot = await service.snapshot('2026-10');
    assert.equal(snapshot.balanceCents, 142500);
    assert.equal(snapshot.spentCents, 7500);
    assert.equal(snapshot.transactions.length, 3);
  } finally { await db.close(); }
});

test('historical import adjusts opening cash so previously known cash is preserved', async () => {
  const { db, service } = await readyService();
  try {
    const preview = await service.stageDemoImport('history');
    assert.equal(preview.rows.length, 2);
    assert.equal(preview.requiresOpening, true);
    const decisions = preview.rows.map((row) => importReview(row, row.draft.kind === 'income' ? 'income' : 'expense'));
    const before = await service.snapshot('2026-10');
    const beforeLedger = await db.get<{ count: number }>('SELECT COUNT(*) count FROM transactions WHERE posted = 1');
    await assert.rejects(service.confirmImport(decisionInput(preview.id, decisions, { date: preview.earliestDate, balanceCents: 79999, acknowledged: true })), /não confere/);
    const afterRejected = await service.snapshot('2026-10');
    const afterRejectedLedger = await db.get<{ count: number }>('SELECT COUNT(*) count FROM transactions WHERE posted = 1');
    assert.deepEqual(afterRejected.profile, before.profile);
    assert.equal(afterRejected.balanceCents, before.balanceCents);
    assert.equal(afterRejectedLedger?.count, beforeLedger?.count);
    await service.confirmImport(decisionInput(preview.id, decisions, { date: preview.earliestDate, balanceCents: 80000, acknowledged: true }));
    const snapshot = await service.snapshot('2026-10');
    assert.equal(snapshot.balanceCents, 100000);
    assert.equal(snapshot.receivedCents, 50000);
    assert.equal(snapshot.spentCents, 30000);
  } finally { await db.close(); }
});

test('a failure on the second import row rolls back the first posted row', async () => {
  const { db, service } = await readyService();
  try {
    const preview = await service.stageDemoImport('current');
    const first = preview.rows[0];
    const second = preview.rows[1];
    assert.ok(first && second);
    const decisions = [importReview(first, first.draft.kind === 'income' ? 'income' : 'expense'), importReview(second, 'expense')];
    const lateFailure = decisions[1];
    assert.ok(lateFailure?.review);
    lateFailure.review.kind = 'transfer';
    lateFailure.review.reserveId = 'reserve:missing';
    lateFailure.review.direction = 'deposit';
    await assert.rejects(service.confirmImport(decisionInput(preview.id, decisions)));
    const snapshot = await service.snapshot('2026-10');
    assert.equal(snapshot.balanceCents, 100000);
    assert.equal(snapshot.transactions.length, 0);
    assert.ok(snapshot.candidates.every((candidate) => candidate.status === 'pending'));
  } finally { await db.close(); }
});

test('ledger triggers reject unbalanced postings and edits to posted transactions', async () => {
  const { db } = await readyService();
  try {
    await assert.rejects(db.transaction(async (tx) => {
      assert.equal((await tx.get<{ foreign_keys: number }>('PRAGMA foreign_keys'))?.foreign_keys, 1);
      await tx.run("INSERT INTO postings (transaction_id,account_id,amount_cents) VALUES ('missing-transaction','cash',1)");
    }), /FOREIGN KEY/);
    await assert.rejects(db.transaction(async (tx) => {
      await tx.run(`INSERT INTO transactions
        (id,operation_id,kind,effective_date,description,category,amount_cents,notes,candidate_id,created_at)
        VALUES ('unbalanced','unbalanced-op','expense','2026-10-21','Teste','Teste',1,'',NULL,'2026-10-21T12:00:00.000Z')`);
      await tx.run("INSERT INTO postings (transaction_id,account_id,amount_cents) VALUES ('unbalanced','cash',-1)");
      await tx.run("UPDATE transactions SET posted=1 WHERE id='unbalanced'");
    }), /desequilibrado/);
    assert.equal((await db.get<{ count: number }>("SELECT COUNT(*) count FROM transactions WHERE id='unbalanced'"))?.count, 0);
    const openingTransaction = await db.get<{ id: string }>("SELECT id FROM transactions WHERE kind='opening' AND posted=1");
    assert.ok(openingTransaction);
    await assert.rejects(db.run('UPDATE transactions SET posted=0 WHERE id=?', openingTransaction.id), /imutável/);
    await assert.rejects(db.run('DELETE FROM postings WHERE transaction_id=?', openingTransaction.id), /imutável/);
  } finally { await db.close(); }
});

test('aggregate cent limits reject the next posting and leave the prior balance intact', async () => {
  const { db, service } = await createTestService();
  try {
    await service.setup({ ...opening, operationId: 'maximum-opening', balanceCents: Number.MAX_SAFE_INTEGER });
    await assert.rejects(service.addManual({
      ...review({ amountCents: 1, kind: 'income', description: 'Excede limite acumulado' }), operationId: 'overflowing-income',
    }), /ultrapassa o limite/);
    const snapshot = await service.snapshot('2026-10');
    assert.equal(snapshot.balanceCents, Number.MAX_SAFE_INTEGER);
    assert.equal(snapshot.receivedCents, 0);
    assert.equal(snapshot.transactions.length, 0);
  } finally { await db.close(); }
});

test('an auto-post overflow keeps its event so it can be retried after cash changes', async () => {
  const { db, service } = await createTestService();
  try {
    await service.setup({ ...opening, operationId: 'overflow-event-opening', balanceCents: Number.MAX_SAFE_INTEGER });
    const event = {
      id: 'overflow-event', source: 'simulation' as const, sourceKey: 'overflow-event-source', revision: 1,
      capturedAt: '2026-10-21T12:00:00.000Z', publishedAt: '2026-10-21T12:00:00.000Z',
      payload: JSON.stringify({ marker: 'ffortuna.synthetic.v1', amountCents: 1, kind: 'income', effectiveDate: '2026-10-21', effectiveAt: null,
        description: 'Receita que excedia o limite', category: 'Renda', accountRef: 'demo-main' }),
    };
    await assert.rejects(service.observe(event));
    assert.ok(await db.get('SELECT id FROM events WHERE id = ?', event.id));
    await service.addManual({ ...review({ amountCents: 1, kind: 'expense', description: 'Saída que libera limite' }), operationId: 'free-one-cent' });
    const retried = await service.reprocess(event.id);
    assert.equal(retried.status, 'confirmed');
    const snapshot = await service.snapshot('2026-10');
    assert.equal(snapshot.balanceCents, Number.MAX_SAFE_INTEGER);
    assert.equal(snapshot.receivedCents, 1);
    assert.equal(snapshot.spentCents, 1);
  } finally { await db.close(); }
});

test('version 1 database migrates to version 2 without losing stored settings', async () => {
  const directory = mkdtempSync(join(tmpdir(), 'ffortuna-migration-'));
  const path = join(directory, 'finance.sqlite');
  const db = openTestDatabase(path);
  try {
    await db.exec(migrations[0] ?? '');
    await db.exec("INSERT INTO settings(key,value) VALUES ('kept','yes'); PRAGMA user_version = 1;");
  } finally { await db.close(); }
  const reopened = openTestDatabase(path);
  try {
    const service = await createFinanceService(reopened, testEnvironment());
    await service.setup({ ...opening, operationId: 'post-migration-setup' });
    assert.equal((await reopened.get<{ user_version: number }>('PRAGMA user_version'))?.user_version, 2);
    assert.equal((await reopened.get<{ value: string }>("SELECT value FROM settings WHERE key='kept'"))?.value, 'yes');
    assert.equal((await service.snapshot('2026-10')).balanceCents, 100000);
  } finally {
    await reopened.close();
    rmSync(directory, { recursive: true, force: true });
  }
});

test('file-backed SQLite survives closing and reopening the service', async () => {
  const directory = mkdtempSync(join(tmpdir(), 'ffortuna-reopen-'));
  const path = join(directory, 'finance.sqlite');
  const firstDb = openTestDatabase(path);
  try {
    const first = await createFinanceService(firstDb, testEnvironment());
    await first.setup({ ...opening, operationId: 'file-opening' });
    await first.addManual({ ...review({ amountCents: 3456, kind: 'income' }), operationId: 'file-income' });
  } finally { await firstDb.close(); }
  const secondDb = openTestDatabase(path);
  try {
    const second: FinanceService = await createFinanceService(secondDb, testEnvironment());
    const snapshot = await second.snapshot('2026-10');
    assert.equal(snapshot.balanceCents, 103456);
    assert.equal(snapshot.transactions.length, 1);
  } finally {
    await secondDb.close();
    rmSync(directory, { recursive: true, force: true });
  }
});
