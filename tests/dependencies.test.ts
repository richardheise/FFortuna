import assert from 'node:assert/strict';
import { createRequire } from 'node:module';
import { spawnSync } from 'node:child_process';
import test from 'node:test';

const require = createRequire(import.meta.url);
const routerRequire = createRequire(require.resolve('expo-router/package.json'));
const queryPath = routerRequire.resolve('query-string');
const queryRequire = createRequire(queryPath);
const query = routerRequire('query-string') as typeof import('query-string');
const decode = queryRequire('decode-uri-component') as (value: string) => string;

test('router query parser keeps Unicode, repeated parameters and encoding', () => {
  assert.deepEqual({ ...query.parse('descricao=caf%C3%A9&tag=a&tag=b&empty=&flag') }, {
    descricao: 'café', tag: ['a', 'b'], empty: '', flag: null,
  });
  assert.equal(query.stringify({ descricao: 'café fictício', tag: ['a', 'b'] }, { sort: false }),
    'descricao=caf%C3%A9%20fict%C3%ADcio&tag=a&tag=b');
});

test('router decoder exposes the CommonJS function API and tolerates malformed escapes', () => {
  assert.equal(typeof decode, 'function');
  assert.equal(query.parse('description=caf%C3%A9+fict%C3%ADcio').description, 'café fictício');
  assert.equal(query.parse('symbol=%2B').symbol, '+');
  assert.equal(decode('caf%C3%A9+fict%C3%ADcio'), 'café fictício');
  assert.equal(decode('%E0%A4%A'), '%E0%A4%A');
  assert.equal(decode('%'), '%');
  assert.throws(() => decode(null as unknown as string), TypeError);
});

test('long malformed route queries finish within a separate process deadline', () => {
  // A separate process lets the deadline kill a recursive decoder regression.
  const result = spawnSync(process.execPath, ['-e', `
    const assert = require('node:assert/strict');
    const query = require(process.argv[1]);
    const malformed = '%E0%A4%A'.repeat(2000);
    assert.equal(query.parse('description=' + malformed).description, malformed);
    process.stdout.write('ok');
  `, queryPath], { encoding: 'utf8', timeout: 5000 });
  assert.equal(result.error, undefined, result.error?.message);
  assert.equal(result.status, 0, result.stderr);
  assert.equal(result.stdout, 'ok');
});
