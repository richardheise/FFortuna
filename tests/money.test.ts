import assert from 'node:assert/strict';
import test from 'node:test';
import { validDate } from '../src/core/dates';
import { cents, parseBRL, parseOfxAmount } from '../src/core/money';

test('BRL input uses comma decimals and validates before converting to cents', () => {
  assert.equal(parseBRL('R$ 1.234,5'), 123450);
  assert.equal(parseBRL('0,01'), 1);
  assert.equal(parseBRL('90071992547409,91'), Number.MAX_SAFE_INTEGER);
  assert.throws(() => parseBRL('12.34'), /valor/i);
  assert.throws(() => parseBRL('-1,00'), /valor/i);
  assert.throws(() => parseBRL('90071992547409,92'), /seguro/i);
});

test('OFX decimal parser accepts signed dot-decimals without locale guessing', () => {
  assert.equal(parseOfxAmount('-1234.5'), -123450);
  assert.equal(parseOfxAmount('0.01'), 1);
  assert.equal(parseOfxAmount('90071992547409.91'), Number.MAX_SAFE_INTEGER);
  assert.throws(() => parseOfxAmount('1,25'));
  assert.throws(() => parseOfxAmount('1.001'));
  assert.throws(() => parseOfxAmount('90071992547409.92'), /seguro/i);
});

test('cent values and calendar dates reject unsafe numbers and impossible days', () => {
  assert.equal(cents(-1), -1);
  assert.throws(() => cents(Number.MAX_SAFE_INTEGER + 1));
  assert.throws(() => cents(-1, true));
  assert.equal(validDate('2024-02-29'), '2024-02-29');
  assert.throws(() => validDate('2026-02-29'));
});
