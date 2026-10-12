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
  login: ({ success }) => success({ code: 'weapon-login-code' }),
  request: options => {
    calls.push(options)
    let data = null
    if (options.url.endsWith('/api/v1/app/auth/wechat-login')) {
      data = { accessToken: 'weapon_token', user: { displayName: '兵器城测试用户' } }
    } else if (options.url.includes('/api/v1/app/scenic-spots?') || options.url.endsWith('/api/v1/app/scenic-spots')) {
      data = { items: [{ id: 7, name: '示例景区' }], page: 1, pageSize: 20, total: 1, totalPages: 1 }
    } else if (options.url.includes('/scenic-spots/7/tickets')) {
      data = [{ skuId: 11, skuCode: 'SCENIC_WEAPON_TANK_001', name: '坦克试乘', timeSlot: '09:00-10:00', priceCent: 12800, available: true }]
    } else if (options.url.endsWith('/api/v1/app/orders') && options.method === 'POST') {
      data = { id: 21, orderNo: 'BFH-WEAPON-21', orderType: 'SCENIC_TICKET', status: 'PENDING_PAYMENT', payableAmountCent: 12800 }
    } else if (options.url.endsWith('/api/v1/app/orders/21/cancel')) {
      data = { id: 21, status: 'CANCELLED' }
    }
    options.success({ statusCode: 200, data: { code: 'SUCCESS', message: '操作成功', data } })
  }
}

async function run() {
  const weaponCity = require('../miniprogram/services/weaponCityService')
  const spotPage = await weaponCity.fetchWeaponCitySpot()
  assert.strictEqual(spotPage.items[0].name, '示例景区')
  const tickets = await weaponCity.fetchWeaponCityTickets(7, '2026-08-10')
  assert.strictEqual(tickets[0].skuCode, 'SCENIC_WEAPON_TANK_001')

  const participant = { name: '测试游客', mobile: '13800000000', idType: 'ID_CARD', idNo: '110101199001011234' }
  const order = await weaponCity.createWeaponCityOrder({
    clientRequestId: 'weapon-test-key',
    items: [{ skuId: 11, quantity: 1, serviceDate: '2026-08-10', timeSlot: '09:00-10:00', businessData: { visitors: [participant] } }],
    remark: '兵器城接口测试'
  })
  assert.strictEqual(order.status, 'PENDING_PAYMENT')
  await weaponCity.cancelWeaponCityOrder(order.id)

  const createCall = calls.find(call => call.url.endsWith('/api/v1/app/orders') && call.method === 'POST')
  assert.strictEqual(createCall.header.Authorization, 'Bearer weapon_token')
  assert.strictEqual(createCall.header['Idempotency-Key'], 'weapon-test-key')
  assert.strictEqual(createCall.data.orderType, 'SCENIC_TICKET')
  assert.strictEqual(createCall.data.clientRequestId, undefined)
  assert.deepStrictEqual(createCall.data.items[0].businessData.visitors[0], participant)
  assert.ok(calls.some(call => call.url.endsWith('/api/v1/app/orders/21/cancel') && call.method === 'POST'))
}

run().then(() => console.log('Weapon city integration tests passed')).catch(error => {
  console.error(error)
  process.exitCode = 1
})
