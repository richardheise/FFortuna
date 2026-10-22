// query-string 7 expects a CommonJS function and legacy plus-to-space decoding.
const decode = require('decode-uri-component-fixed').default;
module.exports = function decodeUriComponentCompat(input) {
  return decode(typeof input === 'string' ? input.replace(/\+/g, ' ') : input);
};
