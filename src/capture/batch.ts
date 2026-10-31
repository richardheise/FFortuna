import type { NotificationCandidate, NotificationCandidateFields } from './native';

export type BatchCandidate = Pick<NotificationCandidate,
  'transactionId' | 'review' | 'financialReview' | 'possibleMatches' | 'previousSampleId' | 'relatedReviewExists' | 'hasLaterRevision'
> & { parsed: Pick<NotificationCandidate['parsed'], 'confidence' | 'draft'> };

export function batchFields(candidate: BatchCandidate): NotificationCandidateFields {
  return candidate.financialReview ?? (candidate.review?.status === 'reviewed' ? candidate.review : null) ?? candidate.parsed.draft;
}

function validDate(value: string): boolean {
  if (!/^\d{4}-\d{2}-\d{2}$/.test(value)) return false;
  const date = new Date(`${value}T00:00:00.000Z`);
  return !Number.isNaN(date.getTime()) && date.toISOString().slice(0, 10) === value;
}

export function batchBlockReason(candidate: BatchCandidate, today: string): string | null {
  const fields = batchFields(candidate);
  if (candidate.transactionId) return 'Já está vinculado a um lançamento. Confira individualmente.';
  if (candidate.review?.status === 'dismissed') return 'Esta pendência foi descartada. Reabra a revisão individual se necessário.';
  if (candidate.previousSampleId || candidate.relatedReviewExists || candidate.hasLaterRevision) return 'Há outra versão ou revisão relacionada. Confira individualmente.';
  if (hasPossibleBatchMatches(candidate)) return 'Há possível lançamento correspondente. Confira individualmente; valor e data iguais não provam duplicidade.';
  if (candidate.review?.status !== 'reviewed' && (
    candidate.parsed.confidence.movement !== 'certain' || candidate.parsed.confidence.amount !== 'certain' || candidate.parsed.confidence.date !== 'certain'
  )) return 'Valor, data ou movimento ainda precisam de revisão individual.';
  if (fields.kind === 'transfer' || fields.kind === 'other') return 'Transferências, aportes, resgates, faturas, estornos e reembolsos precisam de revisão individual.';
  if (fields.amountCents === null || !Number.isSafeInteger(fields.amountCents) || fields.amountCents <= 0) return 'O valor precisa ser conferido individualmente.';
  if (!fields.effectiveDate || !validDate(fields.effectiveDate) || fields.effectiveDate > today) return 'A data precisa ser conferida individualmente.';
  return null;
}

/** Possible matches are a warning, never proof that two equal purchases are duplicates. */
export function hasPossibleBatchMatches(candidate: BatchCandidate): boolean {
  return (candidate.possibleMatches?.length ?? 0) > 0;
}
