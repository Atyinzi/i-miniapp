const assert = require('assert')
const { formatDateTime, createClientRequestId, yuanToFen, fenToYuan } = require('../miniprogram/utils/contract')

global.wx = { showToast: () => {} }
const { intercept } = require('../miniprogram/utils/request')

assert.strictEqual(formatDateTime(new Date(2026, 7, 3, 9, 5, 7)), '2026-08-03 09:05:07')
assert.match(createClientRequestId('order'), /^order_\d+_[a-z0-9]+$/)
assert.strictEqual(yuanToFen(29.9), 2990)
assert.strictEqual(yuanToFen(36.8), 3680)
assert.strictEqual(fenToYuan(2990), '29.90')
assert.throws(() => fenToYuan(29.9), /INVALID_AMOUNT_FEN/)
assert.deepStrictEqual(intercept({ code: 'SUCCESS', message: '操作成功', data: { connected: true }, traceId: 'test' }), { connected: true })

let businessError
try {
  intercept({ code: 409, errorCode: 'INVENTORY_409', message: '库存不足', detail: null, data: null })
} catch (error) {
  businessError = error
}
assert.strictEqual(businessError.code, 409)
assert.strictEqual(businessError.errorCode, 'INVENTORY_409')
assert.strictEqual(businessError.detail, null)

console.log('Contract utility tests passed')
