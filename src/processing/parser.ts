import { validDate } from '../core/dates';
import type { Confidence, Draft, ObservedEvent, ParseResult } from '../core/types';

type JsonRecord = Record<string, unknown>;
const syntheticMarker = 'ffortuna.synthetic.v1';
const ofxMarker = 'ffortuna.ofx-row.v1';
const absentDraft = (): Draft => ({
  amountCents: null, kind: null, effectiveDate: null, effectiveAt: null,
  description: '', category: '', reserveId: null, direction: null,
});

function isRecord(value: unknown): value is JsonRecord {
  return typeof value === 'object' && value !== null && !Array.isArray(value);
}

function failed(parserVersion: string, reason: string): ParseResult {
  return {
    parserVersion, draft: absentDraft(),
    confidence: { movement: 'missing', amount: 'missing', nature: 'missing', account: 'missing', date: 'missing', category: 'missing' },
    fieldEvidence: {}, externalId: null, reason,
  };
}

function dateConfidence(date: unknown): Confidence {
  if (typeof date !== 'string' || date.length === 0) return 'missing';
  try { validDate(date); return 'certain'; } catch { return 'uncertain'; }
}

function field(value: unknown, sourceField: string): { value: string; sourceField: string } | null {
  return value === null || value === undefined || value === '' ? null : { value: String(value), sourceField };
}

/** Converts only explicitly marked demo envelopes. Unmarked source text is never guessed. */
export function parseEvent(event: ObservedEvent): ParseResult {
  let payload: unknown;
  try { payload = JSON.parse(event.payload) as unknown; }
  catch { return failed(event.source === 'ofx' ? 'ofx-v1' : 'synthetic-v1', 'Payload não contém JSON válido.'); }
  if (!isRecord(payload)) return failed(event.source === 'ofx' ? 'ofx-v1' : 'synthetic-v1', 'Payload deve ser um objeto JSON.');

  if (payload.marker === syntheticMarker) {
    if (event.source !== 'simulation') return failed('synthetic-v1', 'Envelope sintético recebido de uma fonte incompatível.');
    const knownFields = new Set(['marker', 'amountCents', 'kind', 'effectiveDate', 'effectiveAt', 'description', 'category', 'accountRef', 'externalId']);
    const unexpectedField = Object.keys(payload).find((key) => !knownFields.has(key));
    if (unexpectedField) return failed('synthetic-v1', `Campo sintético não reconhecido: ${unexpectedField}.`);
    const allowedKinds = ['income', 'expense', 'transfer'] as const;
    const amount = payload.amountCents;
    const kind = payload.kind;
    const date = payload.effectiveDate;
    const effectiveAt = payload.effectiveAt;
    const description = payload.description;
    const category = payload.category;
    const accountRef = payload.accountRef;
    const externalId = payload.externalId;
    const errors: string[] = [];
    if (amount !== null && amount !== undefined && (typeof amount !== 'number' || !Number.isSafeInteger(amount))) errors.push('amountCents deve ser inteiro seguro ou null.');
    if (kind !== null && kind !== undefined && (typeof kind !== 'string' || !allowedKinds.includes(kind as typeof allowedKinds[number]))) errors.push('kind deve ser income, expense, transfer ou null.');
    if (date !== null && date !== undefined && typeof date !== 'string') errors.push('effectiveDate deve ser texto ou null.');
    if (typeof date === 'string' && date !== '') {
      try { validDate(date); } catch { errors.push('effectiveDate é inválida.'); }
    }
    if (effectiveAt !== null && effectiveAt !== undefined && typeof effectiveAt !== 'string') errors.push('effectiveAt deve ser texto ou null.');
    if (typeof description !== 'string' || typeof category !== 'string') errors.push('description e category devem ser texto.');
    if (typeof accountRef !== 'string') errors.push('accountRef deve ser texto.');
    if (externalId !== null && externalId !== undefined && typeof externalId !== 'string') errors.push('externalId deve ser texto ou null.');
    if (errors.length) return failed('synthetic-v1', errors.join(' '));

    const cents = typeof amount === 'number' ? amount : null;
    const movementKind = typeof kind === 'string' ? kind as Draft['kind'] : null;
    const movementDate = typeof date === 'string' && date !== '' ? date : null;
    const details = typeof description === 'string' ? description : '';
    const categoryName = typeof category === 'string' ? category : '';
    const amountConfidence: Confidence = cents === null ? 'missing' : cents > 0 ? 'certain' : 'uncertain';
    const natureConfidence: Confidence = movementKind === null ? 'missing' : movementKind === 'income' || movementKind === 'expense' ? 'certain' : 'uncertain';
    const accountConfidence: Confidence = accountRef === 'demo-main' ? 'certain' : accountRef ? 'uncertain' : 'missing';
    const effectiveDateConfidence = dateConfidence(movementDate);
    const categoryConfidence: Confidence = categoryName.trim() ? 'certain' : 'missing';
    const movementConfidence: Confidence = amountConfidence === 'certain' && natureConfidence === 'certain' && accountConfidence === 'certain' && effectiveDateConfidence === 'certain' ? 'certain' : 'uncertain';
    const evidence: ParseResult['fieldEvidence'] = {};
    for (const [key, value, sourceField] of [
      ['amountCents', cents, 'amountCents'], ['kind', movementKind, 'kind'], ['effectiveDate', movementDate, 'effectiveDate'],
      ['effectiveAt', typeof effectiveAt === 'string' ? effectiveAt : null, 'effectiveAt'], ['description', details, 'description'],
      ['category', categoryName, 'category'], ['account', accountRef, 'accountRef'],
    ] as const) {
      const evidenceValue = field(value, sourceField);
      if (evidenceValue) evidence[key] = evidenceValue;
    }
    const validForAutomaticProcessing = movementConfidence === 'certain';
    return {
      parserVersion: 'synthetic-v1',
      draft: { amountCents: cents, kind: movementKind, effectiveDate: movementDate, effectiveAt: typeof effectiveAt === 'string' ? effectiveAt : null, description: details, category: categoryName, reserveId: null, direction: null },
      confidence: { movement: movementConfidence, amount: amountConfidence, nature: natureConfidence, account: accountConfidence, date: effectiveDateConfidence, category: categoryConfidence },
      fieldEvidence: evidence,
      externalId: typeof externalId === 'string' && externalId.length ? externalId : null,
      reason: validForAutomaticProcessing ? 'Evento sintético completo; requer a política de automação do processador.' : 'Dados incompletos ou fora das regras de segurança; revisar antes de confirmar.',
    };
  }

  if (payload.marker === ofxMarker) {
    if (event.source !== 'ofx') return failed('ofx-v1', 'Envelope OFX recebido de uma fonte incompatível.');
    const { externalId, effectiveDate, signedAmountCents, description, institution, account } = payload;
    if (typeof externalId !== 'string' || !externalId.trim()) return failed('ofx-v1', 'externalId OFX ausente.');
    if (typeof effectiveDate !== 'string') return failed('ofx-v1', 'effectiveDate OFX ausente ou inválida.');
    try { validDate(effectiveDate); } catch { return failed('ofx-v1', 'effectiveDate OFX inválida.'); }
    if (typeof signedAmountCents !== 'number' || !Number.isSafeInteger(signedAmountCents)) return failed('ofx-v1', 'signedAmountCents deve ser inteiro seguro.');
    if (typeof description !== 'string') return failed('ofx-v1', 'description OFX deve ser texto.');
    if (typeof institution !== 'string' || typeof account !== 'string') return failed('ofx-v1', 'Banco e conta OFX devem ser texto.');
    const kind = signedAmountCents < 0 ? 'expense' : 'income';
    const amountCents = Math.abs(signedAmountCents);
    const accountConfidence: Confidence = institution === 'DEMO' && account === 'FICTICIA' ? 'certain' : 'uncertain';
    const amountConfidence: Confidence = amountCents > 0 ? 'certain' : 'uncertain';
    const evidence: ParseResult['fieldEvidence'] = {
      amountCents: { value: String(amountCents), sourceField: 'signedAmountCents' },
      kind: { value: kind, sourceField: 'signedAmountCents' },
      effectiveDate: { value: effectiveDate, sourceField: 'effectiveDate' },
      description: { value: description, sourceField: 'description' },
      account: { value: `${institution}/${account}`, sourceField: 'institution+account' },
    };
    const movementConfidence: Confidence = 'uncertain';
    return {
      parserVersion: 'ofx-v1',
      draft: { amountCents, kind, effectiveDate, effectiveAt: null, description, category: '', reserveId: null, direction: null },
      confidence: { movement: movementConfidence, amount: amountConfidence, nature: 'uncertain', account: accountConfidence, date: 'certain', category: 'missing' },
      fieldEvidence: evidence, externalId,
      reason: 'Sinal e conta OFX são sugestões; confirme a natureza e a conta antes de processar.',
    };
  }
  return failed(event.source === 'ofx' ? 'ofx-v1' : 'synthetic-v1', `Payload sem marcador reconhecido (esperado ${event.source === 'ofx' ? ofxMarker : syntheticMarker}).`);
}
