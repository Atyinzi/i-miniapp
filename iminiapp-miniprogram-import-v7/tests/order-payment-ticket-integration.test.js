const assert = require('assert')

const storage = {}
storage.iminiappWechatLoginConsent = true
storage.iminiappPurchasedOrders = [{
  id: 'legacy-mall-order', type: 'MALL', status: 'PENDING_USE', statusLabel: '待配送',
  subtitle: '1 种商品 · 物流配送', useDate: '物流配送',
  refundRule: '商品未发货前可申请退款', verificationCode: ''
}]
const calls = []
global.wx = {
  getDeviceInfo: () => ({ platform: 'devtools' }),
  getSystemInfoSync: () => ({ platform: 'devtools' }),
  getStorageSync: key => storage[key] || '',
  setStorageSync: (key, value) => { storage[key] = value },
  removeStorageSync: key => { delete storage[key] },
  showToast: () => {},
  login: ({ success }) => success({ code: 'order-login-code' }),
  request: options => {
    calls.push(options)
    let data = null
    if (options.url.endsWith('/api/v1/app/auth/wechat-login')) {
      data = { accessToken: 'payment_ticket_token', user: { displayName: '支付出票测试用户' } }
    } else if (options.url.endsWith('/api/v1/app/orders') && options.method === 'GET') {
      data = {
        items: [{
          id: 31, orderNo: 'BFH-WEAPON-31', orderType: 'SCENIC_TICKET', status: 'PENDING_PAYMENT',
          payableAmountCent: 12800, createdAt: '2026-08-09T14:00:00',
          items: [{
            id: 41, skuId: 11, skuCode: 'SCENIC_WEAPON_TANK_001', skuName: '坦克试乘',
            resourceName: '示例景区', resourceType: 'SCENIC_TICKET', quantity: 2,
            serviceDate: '2026-08-10', timeSlot: '09:00-10:00'
          }]
        }, {
          id: 32, orderNo: 'BFH-VEHICLE-32', orderType: 'VEHICLE_RENTAL', status: 'WAITING_DRIVER',
          payableAmountCent: 55000, createdAt: '2026-08-09T14:02:00',
          items: [{ id: 42, skuName: '轿车全天用车', resourceName: '北方车队', quantity: 1 }]
        }], page: 1, pageSize: 100, total: 2, totalPages: 1
      }
    } else if (options.url.endsWith('/api/v1/app/orders/31/tickets')) {
      data = [
        { verificationId: 61, orderId: 31, orderItemId: 41, ticketNo: 1, resourceName: '示例景区', resourceType: 'SCENIC_TICKET', code: 'BFHV-ONE', status: 'UNUSED', serviceDate: '2026-08-10' },
        { verificationId: 62, orderId: 31, orderItemId: 41, ticketNo: 2, resourceName: '示例景区', resourceType: 'SCENIC_TICKET', code: 'BFHV-TWO', status: 'VOID', serviceDate: '2026-08-10' }
      ]
    }
    options.success({ statusCode: 200, data: { code: 'SUCCESS', message: '操作成功', data } })
  }
}

async function run() {
  const orders = require('../miniprogram/services/orderService')
  const cachedMall = orders.getPurchasedOrders()[0]
  assert.strictEqual(cachedMall.statusLabel, '待配送')
  assert.strictEqual(cachedMall.useDate, '物流配送')
  assert.strictEqual(cachedMall.canShowVerification, false)
  assert.strictEqual(cachedMall.verificationCode, '')
  assert.ok(!cachedMall.refundRule.includes('核销'))
  const list = await orders.fetchPurchasedOrders()
  assert.strictEqual(list[0].type, 'EXPERIENCE')
  assert.strictEqual(list[0].title, '坦克试乘')
  assert.strictEqual(list[0].useDate, '2026-08-10 09:00-10:00')
  assert.strictEqual(list[0].canShowVerification, true)
  assert.strictEqual(list[1].type, 'VEHICLE')
  assert.strictEqual(list[1].status, 'PENDING_USE')

  const tickets = await orders.fetchOrderTickets(31)
  assert.strictEqual(tickets.length, 2)
  assert.strictEqual(tickets[0].statusLabel, '未使用')
  assert.strictEqual(tickets[1].statusLabel, '已失效')

  assert.ok(!calls.some(call => call.url.endsWith('/orders/31/mock-pay')))
  const ticketCall = calls.find(call => call.url.endsWith('/orders/31/tickets'))
  assert.strictEqual(ticketCall.method, 'GET')
  assert.strictEqual(ticketCall.header.Authorization, 'Bearer payment_ticket_token')
}

run().then(() => console.log('Order payment and ticket integration tests passed')).catch(error => {
  console.error(error)
  process.exitCode = 1
})
