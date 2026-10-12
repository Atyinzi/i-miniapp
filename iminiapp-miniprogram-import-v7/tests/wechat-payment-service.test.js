const assert = require('assert')

const calls = []
const storage = { token: 'wechat-pay-token', iminiappWechatLoginConsent: true }
global.wx = {
  getDeviceInfo: () => ({ platform: 'devtools' }),
  getSystemInfoSync: () => ({ platform: 'devtools' }),
  getStorageSync: key => storage[key] || '',
  setStorageSync: (key, value) => { storage[key] = value },
  removeStorageSync: key => { delete storage[key] },
  showToast: () => {},
  request: options => {
    calls.push(options)
    options.success({
      statusCode: 200,
      data: {
        code: 'SUCCESS', message: 'success', data: {
          outTradeNo: 'WXBFH31', timeStamp: '1786500000', nonceStr: 'nonce-1',
          packageValue: 'prepay_id=wx-prepay-1', signType: 'RSA', paySign: 'signed-value'
        }
      }
    })
  },
  requestPayment: options => {
    calls.push(options)
    options.success({ errMsg: 'requestPayment:ok' })
  }
}

async function run() {
  const payment = require('../miniprogram/services/wechatPaymentService')
  const result = await payment.payOrder(31, 'fixed-payment-key')

  const prepayCall = calls.find(call => call.url && call.url.endsWith('/orders/31/wechat-pay/jsapi-prepay'))
  assert.ok(prepayCall)
  assert.strictEqual(prepayCall.method, 'POST')
  assert.strictEqual(prepayCall.header.Authorization, 'Bearer wechat-pay-token')
  assert.strictEqual(prepayCall.header['Idempotency-Key'], 'fixed-payment-key')

  const wxPayCall = calls.find(call => call.package === 'prepay_id=wx-prepay-1')
  assert.ok(wxPayCall)
  assert.strictEqual(wxPayCall.timeStamp, '1786500000')
  assert.strictEqual(wxPayCall.signType, 'RSA')
  assert.strictEqual(result.acceptedByClient, true)
  assert.strictEqual(result.outTradeNo, 'WXBFH31')

  await payment.createJsapiPrepay(31, 'different-key-must-not-replace-active-attempt')
  const sameOrderPrepayCalls = calls.filter(call => call.url && call.url.endsWith('/orders/31/wechat-pay/jsapi-prepay'))
  assert.strictEqual(sameOrderPrepayCalls.length, 2)
  assert.strictEqual(sameOrderPrepayCalls[0].header['Idempotency-Key'], sameOrderPrepayCalls[1].header['Idempotency-Key'])

  global.wx.requestPayment = options => options.fail({ errMsg: 'requestPayment:fail cancel' })
  await assert.rejects(payment.invokeWechatPayment({
    outTradeNo: 'WXBFH31', timeStamp: '1', nonceStr: 'n', packageValue: 'prepay_id=x', paySign: 's'
  }), error => error.message === 'WECHAT_PAYMENT_CANCELLED')

  const serverStates = ['PENDING_PAYMENT', 'PAID']
  global.wx.request = options => {
    calls.push(options)
    options.success({
      statusCode: 200,
      data: { code: 'SUCCESS', message: 'success', data: { id: 31, status: serverStates.shift() } }
    })
  }
  const paidOrder = await payment.waitForServerPayment(31, { maxAttempts: 2, intervalMs: 0 })
  assert.strictEqual(paidOrder.status, 'PAID')
  assert.strictEqual(serverStates.length, 0)

  global.wx.request = options => options.success({
    statusCode: 200,
    data: { code: 'SUCCESS', message: 'success', data: { id: 31, status: 'PENDING_PAYMENT' } }
  })
  await assert.rejects(
    payment.waitForServerPayment(31, { maxAttempts: 1, intervalMs: 0 }),
    error => error.message === 'PAYMENT_CONFIRMATION_PENDING'
  )

  payment.clearPaymentAttempt(41)
  const retryCalls = []
  let prepayAttempt = 0
  global.wx.request = options => {
    retryCalls.push(options)
    prepayAttempt += 1
    if (prepayAttempt === 1) {
      options.fail({ errMsg: 'request:fail timeout' })
      return
    }
    options.success({
      statusCode: 200,
      data: {
        code: 'SUCCESS', message: 'success', data: {
          outTradeNo: 'WXBFH41', timeStamp: '1786500000', nonceStr: 'nonce-41',
          packageValue: 'prepay_id=wx-prepay-41', signType: 'RSA', paySign: 'signed-41'
        }
      }
    })
  }
  await assert.rejects(payment.createJsapiPrepay(41), error => error.message === 'NETWORK_ERROR')
  await payment.createJsapiPrepay(41)
  assert.strictEqual(retryCalls.length, 2)
  assert.strictEqual(
    retryCalls[0].header['Idempotency-Key'],
    retryCalls[1].header['Idempotency-Key'],
    'network retries for one order must resume the same payment attempt'
  )

  const firstClosedKey = retryCalls[1].header['Idempotency-Key']
  global.wx.request = options => options.success({
    statusCode: 200,
    data: { code: 'SUCCESS', message: 'success', data: { id: 41, status: 'CANCELLED' } }
  })
  await assert.rejects(
    payment.waitForServerPayment(41, { maxAttempts: 1, intervalMs: 0 }),
    error => error.message === 'PAYMENT_ORDER_CLOSED' && error.orderStatus === 'CANCELLED'
  )
  const afterCloseCalls = []
  global.wx.request = options => {
    afterCloseCalls.push(options)
    options.success({
      statusCode: 200,
      data: {
        code: 'SUCCESS', message: 'success', data: {
          outTradeNo: 'WXBFH41-NEW', timeStamp: '1786500001', nonceStr: 'nonce-new',
          packageValue: 'prepay_id=wx-prepay-new', signType: 'RSA', paySign: 'signed-new'
        }
      }
    })
  }
  await payment.createJsapiPrepay(41)
  assert.notStrictEqual(afterCloseCalls[0].header['Idempotency-Key'], firstClosedKey)

  const acceptedStatuses = ['APPROVED', 'WAITING_DRIVER', 'ACCEPTED']
  for (const status of acceptedStatuses) {
    global.wx.request = options => options.success({
      statusCode: 200,
      data: { code: 'SUCCESS', message: 'success', data: { id: 51, status } }
    })
    const confirmed = await payment.waitForServerPayment(51, { maxAttempts: 1, intervalMs: 0 })
    assert.strictEqual(confirmed.status, status)
  }

  const supplementCalls = []
  let settlementPoll = 0
  global.wx.requestPayment = options => options.success({ errMsg: 'requestPayment:ok' })
  global.wx.request = options => {
    supplementCalls.push(options)
    if (options.url.includes('/settlement/wechat-pay/jsapi-prepay')) {
      options.success({ statusCode: 200, data: { code: 'SUCCESS', data: {
        outTradeNo: 'WXVS1', timeStamp: '1', nonceStr: 'n',
        packageValue: 'prepay_id=supplement', signType: 'RSA', paySign: 's'
      } } })
      return
    }
    settlementPoll += 1
    options.success({ statusCode: 200, data: { code: 'SUCCESS', data: {
      id: 9, status: settlementPoll > 1 ? 'SETTLED' : 'PENDING_SUPPLEMENT'
    } } })
  }
  const supplement = await payment.payAndConfirmVehicleSupplement(88, 'supplement-key', {
    maxAttempts: 2, intervalMs: 0
  })
  assert.strictEqual(supplement.settlement.status, 'SETTLED')
  const supplementPrepay = supplementCalls.find(call => call.url.includes('/vehicle-rentals/88/settlement/wechat-pay/jsapi-prepay'))
  assert.ok(supplementPrepay)
  assert.strictEqual(supplementPrepay.header['Idempotency-Key'], 'supplement-key')
}

run().then(() => console.log('WeChat payment service tests passed')).catch(error => {
  console.error(error)
  process.exitCode = 1
})
