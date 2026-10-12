const assert = require('assert')

const storage = {}
global.wx = {
  getAccountInfoSync: () => ({ miniProgram: { envVersion: 'develop' } }),
  getStorageSync: key => storage[key] || '',
  setStorageSync: (key, value) => { storage[key] = value },
  showToast: () => {}
}

const dateTimePattern = /^\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2}$/

async function run() {
  const booking = require('../miniprogram/services/bookingService')
  const dining = require('../miniprogram/services/diningService')
  const leisure = require('../miniprogram/services/leisureService')
  const weaponCity = require('../miniprogram/services/weaponCityService')
  const mall = require('../miniprogram/services/mallService')
  const orders = require('../miniprogram/services/orderService')

  const bookingResult = await booking.createBooking({ bookingType: 'hotel' })
  assert.match(bookingResult.createdAt, dateTimePattern)
  assert.strictEqual(bookingResult.statusCode, 'BOOKED')

  const deliveryResult = await dining.createDeliveryOrder({ payableAmountFen: 4100 })
  assert.match(deliveryResult.createdAt, dateTimePattern)
  assert.strictEqual(deliveryResult.status, 'PENDING_PAYMENT')

  const leisureResult = await leisure.createLeisureTicketOrder({ visitDate: '2026-08-03', admissionSlot: '全天' })
  assert.match(leisureResult.createdAt, dateTimePattern)
  assert.strictEqual(leisureResult.statusCode, 'PAID')

  const ticketResult = await weaponCity.createTicketOrder({ visitDate: '2026-08-03' })
  assert.strictEqual(ticketResult.statusCode, 'PAID')
  assert.match(ticketResult.createdAt, dateTimePattern)

  const experienceResult = await weaponCity.createExperienceBooking({ experienceDate: '2026-08-03', timeSlot: '09:00-10:00' })
  assert.strictEqual(experienceResult.statusCode, 'BOOKED')
  assert.match(experienceResult.createdAt, dateTimePattern)

  const mallResult = await mall.createMallOrder({ payableAmountFen: 2990 })
  assert.strictEqual(mallResult.payableAmountFen, 2990)
  assert.match(mallResult.createdAt, dateTimePattern)

  const refundResult = await orders.refundPurchasedOrder('ORDER_TEST')
  assert.strictEqual(refundResult.refundStatus, 'REFUNDED')
  assert.match(refundResult.refundedAt, dateTimePattern)
}

run().then(() => console.log('Service contract tests passed')).catch(error => {
  console.error(error)
  process.exitCode = 1
})
