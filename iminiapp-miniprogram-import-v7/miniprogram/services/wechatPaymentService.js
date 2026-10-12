const { request } = require('../utils/request')
const { createClientRequestId } = require('../utils/contract')

// Keep one idempotency key per order for the lifetime of this JavaScript session.
// The key is not a credential and is deliberately not written to wx storage. Keeping
// it across network/cashier failures lets a retry resume the same channel prepay
// transaction instead of accidentally opening a second one.
const attemptKeys = new Map()

function orderKey(orderId) {
  const normalized = String(orderId === undefined || orderId === null ? '' : orderId).trim()
  if (!normalized) throw new Error('ORDER_ID_REQUIRED')
  return normalized
}

function paymentAttemptKey(orderId, suppliedKey) {
  const key = orderKey(orderId)
  if (!attemptKeys.has(key)) {
    attemptKeys.set(key, suppliedKey || createClientRequestId('wechat_payment'))
  }
  return attemptKeys.get(key)
}

function clearPaymentAttempt(orderId) {
  attemptKeys.delete(orderKey(orderId))
}

function createJsapiPrepay(orderId, clientRequestId) {
  let normalizedOrderId
  let idempotencyKey
  try {
    normalizedOrderId = orderKey(orderId)
    idempotencyKey = paymentAttemptKey(normalizedOrderId, clientRequestId)
  } catch (error) {
    return Promise.reject(error)
  }
  return request({
    url: `/api/v1/app/orders/${encodeURIComponent(normalizedOrderId)}/wechat-pay/jsapi-prepay`,
    method: 'POST',
    headers: {
      'Idempotency-Key': idempotencyKey
    }
  })
}

function createVehicleSupplementPrepay(orderId, clientRequestId) {
  let normalizedOrderId
  let idempotencyKey
  try {
    normalizedOrderId = orderKey(orderId)
    idempotencyKey = paymentAttemptKey(`vehicle-supplement:${normalizedOrderId}`, clientRequestId)
  } catch (error) {
    return Promise.reject(error)
  }
  return request({
    url: `/api/v1/app/vehicle-rentals/${encodeURIComponent(normalizedOrderId)}/settlement/wechat-pay/jsapi-prepay`,
    method: 'POST',
    headers: { 'Idempotency-Key': idempotencyKey }
  })
}

function invokeWechatPayment(prepay) {
  if (!prepay || !prepay.timeStamp || !prepay.nonceStr || !prepay.packageValue || !prepay.paySign) {
    return Promise.reject(new Error('INVALID_WECHAT_PREPAY_PARAMETERS'))
  }
  if (typeof wx === 'undefined' || typeof wx.requestPayment !== 'function') {
    return Promise.reject(new Error('WECHAT_PAYMENT_API_UNAVAILABLE'))
  }
  return new Promise((resolve, reject) => {
    wx.requestPayment({
      timeStamp: String(prepay.timeStamp),
      nonceStr: prepay.nonceStr,
      package: prepay.packageValue,
      signType: prepay.signType || 'RSA',
      paySign: prepay.paySign,
      success: result => resolve({
        acceptedByClient: true,
        outTradeNo: prepay.outTradeNo,
        clientResult: result
      }),
      fail: result => {
        const message = String((result && result.errMsg) || '')
        const error = new Error(/cancel/i.test(message) ? 'WECHAT_PAYMENT_CANCELLED' : 'WECHAT_PAYMENT_FAILED')
        error.cause = result
        reject(error)
      }
    })
  })
}

/**
 * A successful wx.requestPayment result is not treated as the final order state.
 * The caller must refresh the order until the signed server callback changes it to PAID.
 */
function payOrder(orderId, clientRequestId) {
  return createJsapiPrepay(orderId, clientRequestId).then(invokeWechatPayment)
}

const FINAL_PAID_STATUSES = new Set([
  'PAID', 'READY', 'APPROVED', 'WAITING_DRIVER', 'ACCEPTED', 'IN_SERVICE', 'COMPLETED'
])
const FINAL_CLOSED_STATUSES = new Set(['CANCELLED', 'REFUNDED', 'CLOSED', 'EXPIRED'])

function fetchOrderPaymentState(orderId) {
  let normalizedOrderId
  try {
    normalizedOrderId = orderKey(orderId)
  } catch (error) {
    return Promise.reject(error)
  }
  return request({ url: `/api/v1/app/orders/${encodeURIComponent(normalizedOrderId)}` })
}

function delay(milliseconds) {
  return new Promise(resolve => setTimeout(resolve, Math.max(0, milliseconds)))
}

/**
 * WeChat's client success callback only means the cashier accepted the payment.
 * The signed server callback is the source of truth, so poll the owned order until
 * the backend has committed a paid (or later) state.
 */
function waitForServerPayment(orderId, options = {}) {
  const maxAttempts = Number.isInteger(options.maxAttempts) ? Math.max(1, options.maxAttempts) : 15
  const intervalMs = Number.isFinite(options.intervalMs) ? Math.max(0, options.intervalMs) : 1200

  function poll(attempt) {
    return fetchOrderPaymentState(orderId).then(order => {
      const status = String((order && order.status) || '').toUpperCase()
      if (FINAL_PAID_STATUSES.has(status)) {
        clearPaymentAttempt(orderId)
        return order
      }
      if (FINAL_CLOSED_STATUSES.has(status)) {
        clearPaymentAttempt(orderId)
        const error = new Error('PAYMENT_ORDER_CLOSED')
        error.orderStatus = status
        throw error
      }
      if (attempt >= maxAttempts) {
        const error = new Error('PAYMENT_CONFIRMATION_PENDING')
        error.orderStatus = status
        throw error
      }
      return delay(intervalMs).then(() => poll(attempt + 1))
    })
  }

  return poll(1)
}

function payAndConfirmOrder(orderId, clientRequestId, options) {
  return payOrder(orderId, clientRequestId)
    .then(clientResult => waitForServerPayment(orderId, options)
      .then(order => ({ clientResult, order })))
}

function waitForVehicleSupplement(orderId, options = {}) {
  const maxAttempts = Number.isInteger(options.maxAttempts) ? Math.max(1, options.maxAttempts) : 15
  const intervalMs = Number.isFinite(options.intervalMs) ? Math.max(0, options.intervalMs) : 1200
  function poll(attempt) {
    return request({ url: `/api/v1/app/vehicle-rentals/${encodeURIComponent(orderKey(orderId))}/settlement` })
      .then(settlement => {
        const status = String((settlement && settlement.status) || '').toUpperCase()
        if (status === 'SETTLED') {
          clearPaymentAttempt(`vehicle-supplement:${orderId}`)
          return settlement
        }
        if (attempt >= maxAttempts) throw new Error('PAYMENT_CONFIRMATION_PENDING')
        return delay(intervalMs).then(() => poll(attempt + 1))
      })
  }
  return poll(1)
}

function payAndConfirmVehicleSupplement(orderId, clientRequestId, options) {
  return createVehicleSupplementPrepay(orderId, clientRequestId)
    .then(invokeWechatPayment)
    .then(clientResult => waitForVehicleSupplement(orderId, options)
      .then(settlement => ({ clientResult, settlement })))
}

module.exports = {
  createJsapiPrepay,
  invokeWechatPayment,
  payOrder,
  fetchOrderPaymentState,
  waitForServerPayment,
  payAndConfirmOrder,
  createVehicleSupplementPrepay,
  waitForVehicleSupplement,
  payAndConfirmVehicleSupplement,
  clearPaymentAttempt
}
