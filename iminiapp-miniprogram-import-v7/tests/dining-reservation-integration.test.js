const assert = require('assert')

const storage = {}
storage.iminiappWechatLoginConsent = true
const calls = []
global.wx = {
  getDeviceInfo: () => ({ platform: 'devtools' }),
  getSystemInfoSync: () => ({ platform: 'devtools' }),
  getStorageSync: key => storage[key] || '',
  setStorageSync: (key, value) => { storage[key] = value },
  removeStorageSync: key => { delete storage[key] },
  showToast: () => {},
  login: ({ success }) => success({ code: 'dining-login-code' }),
  request: options => {
    calls.push(options)
    let data = null
    if (options.url.endsWith('/api/v1/app/auth/wechat-login')) {
      data = { accessToken: 'dining_token', user: { displayName: '餐饮测试用户' } }
    } else if (options.url.endsWith('/catalog/stores')) {
      data = [{ id: 1, storeCode: 'NORTH_HOTEL_DINING' }]
    } else if (options.url.endsWith('/catalog/tables')) {
      data = [{ id: 2, name: 'i某小程序雅间', capacity: 6 }]
    } else if (options.url.endsWith('/catalog/categories')) {
      data = [{ id: 3, name: '热菜' }]
    } else if (options.url.endsWith('/catalog/dishes')) {
      data = [{ id: 4, name: '锅包肉', salePriceCent: 5800 }]
    } else if (options.url.endsWith('/dining/reservations') && options.method === 'POST') {
      data = { id: 5, orderId: 6, status: 'PENDING_PAYMENT', totalAmountCent: 10800 }
    } else if (options.url.endsWith('/reservations/5/cancel')) {
      data = { id: 5, status: 'CANCELLED' }
    }
    options.success({ statusCode: 200, data: { code: 'SUCCESS', message: '操作成功', data } })
  }
}

async function run() {
  const dining = require('../miniprogram/services/diningService')
  assert.strictEqual((await dining.fetchDiningStores()).length, 1)
  assert.strictEqual((await dining.fetchDiningTables({ storeId: 1, date: '2026-08-10', time: '11:30' }))[0].capacity, 6)
  assert.strictEqual((await dining.fetchDiningCategories(1))[0].name, '热菜')
  assert.strictEqual((await dining.fetchDiningDishes(1))[0].salePriceCent, 5800)
  const reservation = await dining.createDiningReservation({
    clientRequestId: 'dining-test-key', storeId: 1, tableId: 2,
    fulfillmentType: 'DINE_IN', reservationDate: '2026-08-10', reservationTime: '11:30',
    diningMinutes: 120, guestCount: 6, contactName: '测试用户', contactMobile: '13800000000',
    items: [{ itemType: 'DISH', itemId: 4, quantity: 1 }]
  })
  assert.strictEqual(reservation.status, 'PENDING_PAYMENT')
  await dining.cancelDiningReservation(reservation.id)

  const createCall = calls.find(call => call.url.endsWith('/dining/reservations') && call.method === 'POST')
  assert.strictEqual(createCall.header.Authorization, 'Bearer dining_token')
  assert.strictEqual(createCall.header['Idempotency-Key'], 'dining-test-key')
  assert.strictEqual(createCall.data.clientRequestId, undefined)
  assert.deepStrictEqual(createCall.data.items, [{ itemType: 'DISH', itemId: 4, quantity: 1 }])
  const tableCall = calls.find(call => call.url.endsWith('/catalog/tables'))
  assert.strictEqual(tableCall.data.time, '11:30')
}

run().then(() => console.log('Dining reservation integration tests passed')).catch(error => {
  console.error(error)
  process.exitCode = 1
})
