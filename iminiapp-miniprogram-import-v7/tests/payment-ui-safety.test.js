const assert = require('assert')
const fs = require('fs')
const path = require('path')

function source(relativePath) {
  return fs.readFileSync(path.join(__dirname, '..', relativePath), 'utf8')
}

const mineJs = source('miniprogram/pages/mine/mine.js')
const mineWxml = source('miniprogram/pages/mine/mine.wxml')
const bookingJs = source('miniprogram/pages/booking/booking.js')
const bookingWxml = source('miniprogram/pages/booking/booking.wxml')
const mallWxml = source('miniprogram/pages/mall/mall.wxml')

assert.ok(mineJs.includes('payAndConfirmOrder(orderId)'))
assert.ok(mineJs.includes('selected.backendOrderId || selected.id'))
assert.ok(bookingJs.includes('payAndConfirmOrder(orderId)'))
assert.ok(bookingJs.includes('this.data.paying || this.data.cancelling'))
assert.ok(bookingWxml.includes('disabled="{{paying || cancelling}}"'))
assert.ok(bookingJs.includes("statusCode === 'PENDING_PAYMENT'"))
assert.ok(bookingJs.includes("? 'PENDING_PAYMENT'"))
assert.ok(mineWxml.includes('微信支付'))
assert.ok(bookingWxml.includes('微信支付'))
assert.ok(!mineJs.includes('payPurchasedOrder(orderId)'))
assert.ok(!bookingJs.includes('payVehicleBooking('))
assert.ok(!source('miniprogram/services/orderService.js').includes('/mock-pay'))
assert.ok(!mineWxml.includes('开发环境模拟支付'))
assert.ok(!bookingWxml.includes('开发环境模拟支付'))
assert.ok(!mallWxml.includes('当前仍使用项目内的模拟支付'))
assert.ok(!mineJs.includes("action: 'balance'"))

console.log('Payment UI safety tests passed')
