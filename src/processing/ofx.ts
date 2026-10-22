import { validDate } from '../core/dates';
import { parseOfxAmount } from '../core/money';
import type { OfxRow, ParsedOfx } from '../core/types';

const MAX_FILE_CHARS = 100_000;
const MAX_LINES = 200;
const ENTITY_REPLACEMENTS: Record<string, string> = { amp: '&', lt: '<', gt: '>', quot: '"', apos: "'" };

function decodeText(input: string): string {
  return input.replace(/&([A-Za-z]+);/g, (entity, name: string) => {
    const decoded = ENTITY_REPLACEMENTS[name];
    if (decoded === undefined) throw new Error(`Entidade OFX não permitida: &${name};`);
    return decoded;
  }).trim();
}

function tags(input: string, name: string): string[] {
  const escaped = name.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
  const expression = new RegExp(`<${escaped}(?:\\s[^>]*)?>([\\s\\S]*?)(?:<\\/${escaped}\\s*>|(?=<\\/|<[A-Za-z][A-Za-z0-9.]*(?:\\s[^>]*)?>)|$)`, 'gi');
  return [...input.matchAll(expression)].map((match) => decodeText(match[1] ?? ''));
}

function oneTag(input: string, name: string, required = true): string {
  const values = tags(input, name);
  if (values.length === 0) {
    if (!required) return '';
    throw new Error(`Campo OFX ${name} ausente.`);
  }
  if (values.some((value) => value !== values[0])) throw new Error(`Campo OFX ${name} conflitante.`);
  if (required && !values[0]) throw new Error(`Campo OFX ${name} vazio.`);
  return values[0] ?? '';
}

function dateFromOfx(raw: string): string {
  const match = /^(\d{4})(\d{2})(\d{2})(?:\d{6}(?:\.\d+)?(?:\[[^\]]+\])?)?$/.exec(raw.trim());
  if (!match) throw new Error('Data DTPOSTED OFX inválida.');
  const date = `${match[1]}-${match[2]}-${match[3]}`;
  try { return validDate(date); } catch { throw new Error('Data DTPOSTED OFX inválida.'); }
}

function assertAggregateClosures(text: string): void {
  for (const name of ['OFX', 'BANKMSGSRSV1', 'STMTTRNRS', 'STMTRS', 'BANKTRANLIST', 'STMTTRN']) {
    const escaped = name.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
    const opens = [...text.matchAll(new RegExp(`<${escaped}(?:\\s[^>]*)?>`, 'gi'))].length;
    const closes = [...text.matchAll(new RegExp(`<\\/${escaped}\\s*>`, 'gi'))].length;
    if (opens !== closes) throw new Error(`Arquivo OFX parcial: agregado ${name} sem fechamento.`);
  }
}

function extractRows(text: string): OfxRow[] {
  const blocks = [...text.matchAll(/<STMTTRN(?:\s[^>]*)?>([\s\S]*?)<\/STMTTRN\s*>/gi)];
  if (blocks.length === 0) throw new Error('Arquivo OFX sem transações fechadas.');
  return blocks.map((match) => {
    const block = match[1] ?? '';
    const externalId = oneTag(block, 'FITID');
    const effectiveDate = dateFromOfx(oneTag(block, 'DTPOSTED'));
    const amountText = oneTag(block, 'TRNAMT');
    let signedAmountCents: number;
    try { signedAmountCents = parseOfxAmount(amountText); }
    catch { throw new Error('Valor TRNAMT OFX inválido.'); }
    const memo = oneTag(block, 'MEMO', false);
    const name = oneTag(block, 'NAME', false);
    return { externalId, effectiveDate, signedAmountCents, description: memo || name || 'Movimento OFX' };
  });
}

/** Parses a bounded, read-only subset of OFX SGML 1.x and XML 2.x. */
export function parseOfx(text: string): ParsedOfx {
  if (typeof text !== 'string' || text.length === 0) throw new Error('Arquivo OFX vazio.');
  if (text.length > MAX_FILE_CHARS) throw new Error('Arquivo OFX excede o limite de tamanho.');
  if (text.split(/\r?\n/).length > MAX_LINES) throw new Error(`Arquivo OFX excede ${MAX_LINES} linhas.`);
  if (/<!\s*(?:DOCTYPE|ENTITY)\b/i.test(text)) throw new Error('DTD e entidades externas não são permitidos em OFX.');
  if (!/<OFX(?:\s[^>]*)?>/i.test(text) || !/<\/OFX\s*>/i.test(text)) throw new Error('Arquivo OFX parcial: fechamento </OFX> ausente.');
  assertAggregateClosures(text);
  if (/&(?!(?:amp|lt|gt|quot|apos);)[A-Za-z#]/.test(text)) throw new Error('Entidade OFX não permitida.');

  const institution = oneTag(text, 'BANKID');
  const account = oneTag(text, 'ACCTID');
  const currency = oneTag(text, 'CURDEF');
  if (currency !== 'BRL') throw new Error('Apenas arquivos OFX em BRL são aceitos.');
  const rows = extractRows(text);
  const seen = new Map<string, string>();
  const uniqueRows: OfxRow[] = [];
  for (const row of rows) {
    const signature = JSON.stringify([row.effectiveDate, row.signedAmountCents, row.description]);
    const previous = seen.get(row.externalId);
    if (previous !== undefined && previous !== signature) throw new Error(`FITID OFX repetido com dados conflitantes: ${row.externalId}.`);
    if (previous === undefined) {
      seen.set(row.externalId, signature);
      uniqueRows.push(row);
    }
  }
  return { institution, account, currency: 'BRL', rows: uniqueRows };
}
