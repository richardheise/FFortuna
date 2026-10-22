export const MAX_CENTS = Number.MAX_SAFE_INTEGER;

export function cents(value: number, nonnegative = false): number {
  if (!Number.isSafeInteger(value) || (nonnegative && value < 0)) {
    throw new Error('Valor monetário fora do limite seguro.');
  }
  return value;
}

export function addCents(...values: number[]): number {
  const result = values.reduce((total, value) => total + BigInt(cents(value)), 0n);
  if (result > BigInt(MAX_CENTS) || result < -BigInt(MAX_CENTS)) {
    throw new Error('A soma ultrapassa o limite monetário seguro.');
  }
  return Number(result);
}

export function parseBRL(input: string): number {
  const text = input.trim().replace(/^R\$\s*/, '');
  if (!/^(?:\d+|\d{1,3}(?:\.\d{3})+)(?:,\d{1,2})?$/.test(text)) {
    throw new Error('Informe um valor como 123,45, sem sinal negativo.');
  }
  const [whole = '', fraction = ''] = text.replace(/\./g, '').split(',');
  const result = BigInt(whole) * 100n + BigInt(fraction.padEnd(2, '0'));
  if (result > BigInt(MAX_CENTS)) throw new Error('Valor monetário fora do limite seguro.');
  return Number(result);
}

/** Bank file decimals use a separate grammar; never guess the user's locale. */
export function parseOfxAmount(input: string): number {
  if (!/^-?\d+(?:\.\d{1,2})?$/.test(input)) throw new Error('Valor OFX inválido.');
  const negative = input.startsWith('-');
  const [whole = '', fraction = ''] = input.replace(/^-/, '').split('.');
  const magnitude = BigInt(whole) * 100n + BigInt(fraction.padEnd(2, '0'));
  if (magnitude > BigInt(MAX_CENTS)) throw new Error('Valor OFX fora do limite seguro.');
  return Number(negative ? -magnitude : magnitude);
}

export function moneyInput(value: number): string {
  const amount = BigInt(cents(value, true));
  return `${amount / 100n},${String(amount % 100n).padStart(2, '0')}`;
}

export function formatBRL(value: number): string {
  const amount = BigInt(cents(value));
  const absolute = amount < 0n ? -amount : amount;
  const whole = String(absolute / 100n).replace(/\B(?=(\d{3})+(?!\d))/g, '.');
  return `${amount < 0n ? '−' : ''}R$ ${whole},${String(absolute % 100n).padStart(2, '0')}`;
}
