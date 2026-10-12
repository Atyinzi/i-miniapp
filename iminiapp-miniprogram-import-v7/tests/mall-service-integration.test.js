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
  login: ({ success }) => success({ code: 'mall-login-code' }),
  request: options => {
    calls.push(options)
    let data = null
    if (options.url.endsWith('/api/v1/app/auth/wechat-login')) {
      data = { accessToken: 'mall_token', user: { displayName: '商城测试用户' } }
    } else if (options.url.endsWith('/categories')) {
      data = [{ id: 1, name: '特色产品' }]
    } else if (options.url.includes('/products/9')) {
      data = { id: 9, productCode: 'MALL_TEST', skus: [] }
    } else if (options.url.includes('/products')) {
      data = { items: [{ id: 9 }], page: 1, pageSize: 50, total: 1 }
    } else if (options.url.endsWith('/cart/items') && options.method === 'PUT') {
      data = { skuId: options.data.skuId, quantity: options.data.quantity }
    } else if (options.url.endsWith('/cart')) {
      data = []
    } else if (options.url.endsWith('/addresses') && options.method === 'POST') {
      data = { id: 3, ...options.data }
    } else if (options.url.endsWith('/addresses')) {
      data = []
    } else if (options.url.endsWith('/checkout')) {
      data = { order: { id: 21, orderNo: 'BFH-MALL-21', status: 'PENDING_PAYMENT', payableAmountCent: 2990 } }
    } else if (options.url.endsWith('/orders/21/cancel')) {
      data = { order: { id: 21, status: 'CANCELLED' } }
    }
    options.success({ statusCode: 200, data: { code: 'SUCCESS', message: '操作成功', data } })
  }
}

async function run() {
  const mall = require('../miniprogram/services/mallService')
  assert.strictEqual((await mall.fetchMallCategories())[0].name, '特色产品')
  assert.strictEqual((await mall.fetchMallProducts()).total, 1)
  assert.strictEqual((await mall.fetchMallProductDetail(9)).productCode, 'MALL_TEST')
  await mall.fetchMallCart()
  await mall.putMallCartItem({ skuId: 11, quantity: 2 })
  await mall.fetchMallAddresses()
  const address = await mall.createMallAddress({ receiverName: '测试用户', receiverMobile: '13800000000' })
  const result = await mall.createMallOrder({
    addressId: address.id,
    items: [{ skuId: 11, quantity: 2, unitPriceFen: 1 }],
    clientRequestId: 'mall-checkout-test'
  })
  await mall.cancelMallOrder(result.order.id)

  const checkout = calls.find(call => call.url.endsWith('/checkout'))
  assert.strictEqual(checkout.method, 'POST')
  assert.strictEqual(checkout.header.Authorization, 'Bearer mall_token')
  assert.strictEqual(checkout.header['Idempotency-Key'], 'mall-checkout-test')
  assert.deepStrictEqual(checkout.data.items, [{ skuId: 11, quantity: 2 }])
  assert.strictEqual(checkout.data.items[0].unitPriceFen, undefined)
  assert.ok(calls.some(call => call.url.endsWith('/orders/21/cancel') && call.method === 'POST'))
}

run().then(() => console.log('Mall service integration tests passed')).catch(error => {
  console.error(error)
  process.exitCode = 1
})
