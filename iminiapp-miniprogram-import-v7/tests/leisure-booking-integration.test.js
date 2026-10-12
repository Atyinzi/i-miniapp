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
  login: ({ success }) => success({ code: 'leisure-login-code' }),
  request: options => {
    calls.push(options)
    let data = null
    if (options.url.endsWith('/api/v1/app/auth/wechat-login')) {
      data = { accessToken: 'leisure_token', user: { displayName: '场馆测试用户' } }
    } else if (options.url.endsWith('/sports/venues')) {
      data = [{ id: 1, venueCode: 'IMINIAPP_SPORTS', courts: [{ id: 2 }] }]
    } else if (options.url.includes('/availability')) {
      data = [{ courtId: 2, startTime: '08:00:00', endTime: '10:00:00', priceCent: 8000, available: true }]
    } else if (options.url.endsWith('/sports/bookings') && options.method === 'POST') {
      data = { bookingId: 3, orderId: 4, orderNo: 'BFH-SPORT-4', orderStatus: 'PENDING_PAYMENT', bookingStatus: 'HELD' }
    } else if (options.url.endsWith('/bookings/4/cancel')) {
      data = { bookingId: 3, orderId: 4, orderStatus: 'CANCELLED', bookingStatus: 'CANCELLED' }
    }
    options.success({ statusCode: 200, data: { code: 'SUCCESS', message: '操作成功', data } })
  }
}

async function run() {
  const leisure = require('../miniprogram/services/leisureService')
  assert.strictEqual((await leisure.fetchLeisureVenues())[0].venueCode, 'IMINIAPP_SPORTS')
  assert.strictEqual((await leisure.fetchLeisureAvailability(2, '2026-08-10'))[0].priceCent, 8000)
  const booking = await leisure.createLeisureBooking({
    clientRequestId: 'leisure-test-key', courtId: 2, bookingDate: '2026-08-10',
    startTime: '08:00:00', endTime: '10:00:00', remark: '接口测试'
  })
  assert.strictEqual(booking.bookingStatus, 'HELD')
  await leisure.cancelLeisureBooking(booking.orderId)

  const createCall = calls.find(call => call.url.endsWith('/sports/bookings') && call.method === 'POST')
  assert.strictEqual(createCall.header.Authorization, 'Bearer leisure_token')
  assert.strictEqual(createCall.header['Idempotency-Key'], 'leisure-test-key')
  assert.strictEqual(createCall.data.clientRequestId, undefined)
  assert.strictEqual(createCall.data.courtId, 2)
  assert.ok(calls.some(call => call.url.endsWith('/bookings/4/cancel') && call.method === 'POST'))
}

run().then(() => console.log('Leisure booking integration tests passed')).catch(error => {
  console.error(error)
  process.exitCode = 1
})
