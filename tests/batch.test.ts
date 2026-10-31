import assert from 'node:assert/strict';
import test from 'node:test';
import { batchBlockReason, hasPossibleBatchMatches, type BatchCandidate } from '../src/capture/batch';
import type { NotificationCandidateFields } from '../src/capture/native';

const today = '2026-10-31';
const confidence = (overrides: Partial<BatchCandidate['parsed']['confidence']> = {}): BatchCandidate['parsed']['confidence'] => ({
  movement: 'certain', amount: 'certain', nature: 'uncertain', account: 'missing', date: 'certain', category: 'uncertain', ...overrides,
});
const fields = (overrides: Partial<NotificationCandidateFields> = {}): NotificationCandidateFields => ({
  amountCents: 1250, kind: 'expense', effectiveDate: '2026-10-30', description: 'Compra', category: 'A classificar',
  ...overrides,
});
function candidate(overrides: Partial<BatchCandidate> = {}): BatchCandidate {
  return {
    transactionId: null, review: null, financialReview: null, possibleMatches: [], previousSampleId: null,
    relatedReviewExists: false, hasLaterRevision: false,
    parsed: { confidence: confidence(), draft: fields() },
    ...overrides,
  };
}

test('batch eligibility requires a safe positive amount and a valid non-future date', () => {
  assert.equal(batchBlockReason(candidate(), today), null);
  assert.match(batchBlockReason(candidate({ parsed: { confidence: confidence({ movement: 'uncertain', amount: 'uncertain', date: 'missing' }), draft: fields() } }), today) ?? '', /revisão individual/i);
  assert.match(batchBlockReason(candidate({ parsed: { confidence: confidence(), draft: fields({ amountCents: Number.MAX_SAFE_INTEGER + 1 }) } }), today) ?? '', /valor/i);
  assert.match(batchBlockReason(candidate({ parsed: { confidence: confidence(), draft: fields({ amountCents: 0 }) } }), today) ?? '', /valor/i);
  assert.match(batchBlockReason(candidate({ parsed: { confidence: confidence(), draft: fields({ effectiveDate: '2026-02-30' }) } }), today) ?? '', /data/i);
  assert.match(batchBlockReason(candidate({ parsed: { confidence: confidence(), draft: fields({ effectiveDate: '2026-11-01' }) } }), today) ?? '', /data/i);
});

test('uncertain nature can be resolved by an explicit batch choice, but transfer-like nature stays individual', () => {
  assert.equal(batchBlockReason(candidate({ parsed: { confidence: confidence({ nature: 'uncertain' }), draft: fields({ kind: null }) } }), today), null);
  for (const kind of ['transfer', 'other'] as const) {
    assert.match(batchBlockReason(candidate({ parsed: { confidence: confidence(), draft: fields({ kind }) } }), today) ?? '', /individual/i);
  }
  assert.match(batchBlockReason(candidate({ review: { ...fields({ kind: 'transfer' }), status: 'reviewed' } }), today) ?? '', /individual/i);
});

test('a saved human correction supplies the reviewed fields for batch eligibility', () => {
  const reviewed = fields({ kind: 'expense', amountCents: 7800, effectiveDate: '2026-10-28' });
  const value = candidate({
    review: { ...reviewed, status: 'reviewed' },
    financialReview: reviewed,
    parsed: { confidence: confidence({ movement: 'uncertain', amount: 'missing', date: 'missing' }), draft: fields({ kind: 'transfer', amountCents: null }) },
  });
  assert.equal(batchBlockReason(value, today), null);
});

test('possible matches require individual review without claiming equal purchases are duplicates', () => {
  const match = { id: 'existing-1', description: 'Compra igual', amountCents: 1250, effectiveDate: '2026-10-30', kind: 'expense' as const };
  const value = candidate({ possibleMatches: [match] });
  assert.equal(hasPossibleBatchMatches(value), true);
  assert.match(batchBlockReason(value, today) ?? '', /não provam duplicidade/i);
  assert.match(batchBlockReason(candidate({ transactionId: 'existing-1' }), today) ?? '', /vinculado/i);
  assert.match(batchBlockReason(candidate({ hasLaterRevision: true }), today) ?? '', /versão|revisão/i);
});
