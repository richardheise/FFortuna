import { validDate } from '../core/dates';
import type { DemoScenario, ObservedEvent } from '../core/types';

const marker = 'ffortuna.synthetic.v1';

function atNoon(date: string): string {
  validDate(date);
  return `${date}T12:00:00.000Z`;
}

export function makeDemoEvent(scenario: DemoScenario, date: string, id: string): ObservedEvent {
  const timestamp = atNoon(date);
  const payload = scenario === 'review'
    ? { marker, amountCents: null, kind: null, effectiveDate: date, effectiveAt: null, description: 'Café fictício', category: 'A classificar', accountRef: 'demo-main', externalId: `demo-${id}` }
    : { marker, amountCents: 1990, kind: 'expense', effectiveDate: date, effectiveAt: null, description: 'Café fictício', category: 'Alimentação', accountRef: 'demo-main', externalId: `demo-${id}` };
  return {
    id, source: 'simulation', sourceKey: `ffortuna:synthetic:${id}`, revision: 1,
    capturedAt: timestamp, publishedAt: timestamp, payload: JSON.stringify(payload),
  };
}

export function makeUpdatedEvent(original: ObservedEvent, id: string): ObservedEvent {
  const parsed: unknown = JSON.parse(original.payload);
  if (typeof parsed !== 'object' || parsed === null || Array.isArray(parsed) || (parsed as Record<string, unknown>).marker !== marker) {
    throw new Error('Evento original não é uma fixture sintética FFortuna.');
  }
  const payload: Record<string, unknown> = { ...(parsed as Record<string, unknown>), amountCents: 2990 };
  const effectiveDate = payload.effectiveDate;
  if (typeof effectiveDate !== 'string') throw new Error('Evento original não tem effectiveDate válida.');
  const timestamp = atNoon(effectiveDate);
  return {
    id, source: 'simulation', sourceKey: original.sourceKey, revision: original.revision + 1,
    capturedAt: timestamp, publishedAt: timestamp, payload: JSON.stringify(payload),
  };
}

function shiftDate(date: string, days: number): string {
  validDate(date);
  const shifted = new Date(`${date}T00:00:00.000Z`);
  shifted.setUTCDate(shifted.getUTCDate() + days);
  const result = shifted.toISOString().slice(0, 10);
  return validDate(result);
}

function row(fitid: string, date: string, amount: string, memo: string): string {
  return `<STMTTRN>\n<TRNTYPE>${amount.startsWith('-') ? 'DEBIT' : 'CREDIT'}\n<DTPOSTED>${date.replaceAll('-', '')}120000\n<TRNAMT>${amount}\n<FITID>${fitid}\n<MEMO>${memo}\n</STMTTRN>`;
}

function fitid(base: string, referenceDate: string): string {
  return `${base}-${referenceDate.replaceAll('-', '')}`;
}

export function getDemoOfx(variant: 'current' | 'overlap' | 'history', referenceDate: string): { name: string; text: string } {
  validDate(referenceDate);
  const current = [
    row(fitid('FF-DEMO-INCOME-001', referenceDate), referenceDate, '500.00', 'FFortuna sintético: renda fictícia'),
    row(fitid('FF-DEMO-EXPENSE-001', referenceDate), referenceDate, '-50.00', 'FFortuna sintético: despesa fictícia'),
  ];
  const rows = variant === 'history'
    ? [row(fitid('FF-DEMO-HISTORY-INCOME-001', referenceDate), shiftDate(referenceDate, -2), '500.00', 'FFortuna sintético: renda histórica fictícia'), row(fitid('FF-DEMO-HISTORY-EXPENSE-001', referenceDate), shiftDate(referenceDate, -1), '-300.00', 'FFortuna sintético: despesa histórica fictícia')]
    : variant === 'overlap'
      ? [...current, row(fitid('FF-DEMO-EXPENSE-002', referenceDate), referenceDate, '-25.00', 'FFortuna sintético: nova despesa fictícia')]
      : current;
  const text = [
    'OFXHEADER:100', 'DATA:OFXSGML', 'VERSION:102', 'SECURITY:NONE', 'ENCODING:UTF-8', 'CHARSET:UTF-8', 'COMPRESSION:NONE', 'OLDFILEUID:NONE', 'NEWFILEUID:NONE',
    '<OFX>', '<BANKMSGSRSV1>', '<STMTTRNRS>', '<STMTRS>', '<CURDEF>BRL', '<BANKACCTFROM>', '<BANKID>DEMO', '<ACCTID>FICTICIA', '</BANKACCTFROM>', '<BANKTRANLIST>', ...rows, '</BANKTRANLIST>', '</STMTRS>', '</STMTTRNRS>', '</BANKMSGSRSV1>', '</OFX>',
  ].join('\n');
  return { name: `FFortuna sintético ${variant} ${referenceDate}.ofx`, text };
}
