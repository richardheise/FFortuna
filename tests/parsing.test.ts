import assert from 'node:assert/strict';
import test from 'node:test';
import { makeDemoEvent, getDemoOfx } from '../src/demo/scenarios';
import { parseOfx } from '../src/processing/ofx';
import { parseEvent } from '../src/processing/parser';
import type { ObservedEvent } from '../src/core/types';

const baseEvent = (payload: unknown, source: ObservedEvent['source'] = 'simulation'): ObservedEvent => ({
  id: 'test-event', source, sourceKey: 'test-source-key', revision: 1,
  capturedAt: '2026-10-21T12:00:00.000Z', publishedAt: '2026-10-21T12:00:00.000Z', payload: JSON.stringify(payload),
});

const sgml = (rows: string, currency = 'BRL'): string => [
  'OFXHEADER:100', '<OFX>', '<BANKMSGSRSV1>', '<STMTTRNRS>', '<STMTRS>', `<CURDEF>${currency}`, '<BANKACCTFROM>', '<BANKID>DEMO', '<ACCTID>FICTICIA', '</BANKACCTFROM>', '<BANKTRANLIST>', rows, '</BANKTRANLIST>', '</STMTRS>', '</STMTTRNRS>', '</BANKMSGSRSV1>', '</OFX>',
].join('\n');
const sgmlRow = (id: string, amount: string, date = '20261021'): string => `<STMTTRN>\n<DTPOSTED>${date}\n<TRNAMT>${amount}\n<FITID>${id}\n<MEMO>Café &amp; pão\n</STMTTRN>`;

test('parseOfx lê sintaxe SGML 1.x, decimais e texto com entidades padrão', () => {
  const parsed = parseOfx(sgml(sgmlRow('fit-1', '-12.34')));
  assert.equal(parsed.institution, 'DEMO');
  assert.equal(parsed.account, 'FICTICIA');
  assert.equal(parsed.rows[0]?.signedAmountCents, -1234);
  assert.equal(parsed.rows[0]?.description, 'Café & pão');
});

test('parseOfx lê sintaxe XML 2.x', () => {
  const xml = `<?xml version="1.0"?><OFX><BANKMSGSRSV1><STMTTRNRS><STMTRS><CURDEF>BRL</CURDEF><BANKACCTFROM><BANKID>DEMO</BANKID><ACCTID>FICTICIA</ACCTID></BANKACCTFROM><BANKTRANLIST><STMTTRN><DTPOSTED>20261021</DTPOSTED><TRNAMT>123.4</TRNAMT><FITID>xml-1</FITID><NAME>Entrada</NAME></STMTTRN></BANKTRANLIST></STMTRS></STMTTRNRS></BANKMSGSRSV1></OFX>`;
  assert.equal(parseOfx(xml).rows[0]?.signedAmountCents, 12340);
});

test('OFX demo IDs são estáveis em overlaps do mesmo dia e mudam com a referência', () => {
  const currentIds = parseOfx(getDemoOfx('current', '2026-10-21').text).rows.map((row) => row.externalId);
  const repeatedIds = parseOfx(getDemoOfx('current', '2026-10-21').text).rows.map((row) => row.externalId);
  const overlapIds = parseOfx(getDemoOfx('overlap', '2026-10-21').text).rows.map((row) => row.externalId);
  const nextDateIds = parseOfx(getDemoOfx('current', '2026-10-22').text).rows.map((row) => row.externalId);
  assert.deepEqual(repeatedIds, currentIds);
  assert.deepEqual(overlapIds.slice(0, 2), currentIds);
  assert.ok(currentIds.every((id, index) => id !== nextDateIds[index]));
});

test('parseOfx valida moeda, ids, datas e valores, e recusa conteúdo parcial e arquivos grandes', () => {
  assert.throws(() => parseOfx(sgml(sgmlRow('x', '1.001'))), /Valor TRNAMT/);
  assert.throws(() => parseOfx(sgml(sgmlRow('x', '1.00', '20260230'))), /Data DTPOSTED/);
  assert.throws(() => parseOfx(sgml(sgmlRow('x', '1.00'), 'USD')), /BRL/);
  assert.throws(() => parseOfx(sgml(sgmlRow('', '1.00'))), /FITID/);
  assert.throws(() => parseOfx(sgml(`${sgmlRow('same', '1.00')}\n${sgmlRow('same', '-1.00')}`)), /conflitantes/);
  assert.throws(() => parseOfx('<OFX>'), /parcial/);
  assert.throws(() => parseOfx(sgml(`${sgmlRow('closed', '1.00')}\n<STMTTRN>\n<DTPOSTED>20261021`)), /parcial/);
  assert.throws(() => parseOfx(`${sgml(sgmlRow('x', '1.00'))}\n${'\n'.repeat(201)}`), /linhas/);
  assert.throws(() => parseOfx(sgml(sgmlRow('x', '1.00')).replace('<OFX>', '<!DOCTYPE OFX><OFX>')), /DTD/);
});

test('parseEvent só aceita envelopes marcados e mantém dados incompletos para revisão', () => {
  const safe = parseEvent(makeDemoEvent('safe', '2026-10-21', 'safe-id'));
  assert.equal(safe.parserVersion, 'synthetic-v1');
  assert.equal(safe.confidence.movement, 'certain');
  assert.equal(safe.confidence.category, 'certain');
  assert.equal(safe.draft.amountCents, 1990);
  assert.equal(safe.fieldEvidence.amountCents?.sourceField, 'amountCents');

  const review = parseEvent(makeDemoEvent('review', '2026-10-21', 'review-id'));
  assert.equal(review.draft.amountCents, null);
  assert.equal(review.draft.kind, null);
  assert.equal(review.confidence.amount, 'missing');
  assert.equal(review.confidence.nature, 'missing');
  assert.equal(review.confidence.movement, 'uncertain');
  assert.match(review.reason, /revisar/);

  const unmarked = parseEvent(baseEvent({ amountCents: 100, kind: 'income' }));
  assert.match(unmarked.reason, /sem marcador/);
  assert.equal(unmarked.draft.amountCents, null);
});

test('parseEvent never automates an unsafe amount, nature, date, or account', () => {
  const fixture = { marker: 'ffortuna.synthetic.v1', amountCents: 0, kind: 'transfer', effectiveDate: '2026-10-21', effectiveAt: null, description: 'Movimento', category: '', accountRef: 'unknown' };
  const parsed = parseEvent(baseEvent(fixture));
  assert.equal(parsed.confidence.movement, 'uncertain');
  assert.equal(parsed.confidence.amount, 'uncertain');
  assert.equal(parsed.confidence.nature, 'uncertain');
  assert.equal(parsed.confidence.date, 'certain');
  assert.equal(parsed.confidence.account, 'uncertain');
  assert.match(parsed.reason, /segurança/);
  const invalidDate = parseEvent(baseEvent({ ...fixture, effectiveDate: '2026-02-30' }));
  assert.match(invalidDate.reason, /effectiveDate é inválida/);
  assert.equal(parseEvent(baseEvent({ ...fixture, effectiveDate: '2026-10-21', amountCents: 100, kind: 'expense', accountRef: 'demo-main' })).confidence.movement, 'certain');
});

test('OFX sign suggests nature while confidence and account identify required review', () => {
  const imported = parseOfx(getDemoOfx('current', '2026-10-21').text);
  const parsed = parseEvent(baseEvent({ marker: 'ffortuna.ofx-row.v1', ...imported.rows[0], institution: imported.institution, account: imported.account }, 'ofx'));
  assert.equal(parsed.parserVersion, 'ofx-v1');
  assert.equal(parsed.draft.kind, 'income');
  assert.equal(parsed.confidence.nature, 'uncertain');
  assert.equal(parsed.confidence.account, 'certain');
  assert.equal(parsed.confidence.movement, 'uncertain');
  const otherAccount = parseEvent(baseEvent({ marker: 'ffortuna.ofx-row.v1', externalId: 'id', effectiveDate: '2026-10-21', signedAmountCents: -50, description: 'x', institution: 'BANK', account: '123' }, 'ofx'));
  assert.equal(otherAccount.draft.kind, 'expense');
  assert.equal(otherAccount.confidence.nature, 'uncertain');
  assert.equal(otherAccount.confidence.account, 'uncertain');
});
