const { request } = require('../utils/request')
const { formatDateTime, createClientRequestId } = require('../utils/contract')

const BASE_URL = '/api/v1/app/cultural-store'

function fetchMallCategories() {
  return request({
    url: `${BASE_URL}/categories`,
    auth: false,
    mock: () => ({ code: 200, message: 'success', data: [] })
  })
}

function fetchMallProducts(params = {}) {
  return request({
    url: `${BASE_URL}/products`,
    data: { page: 1, pageSize: 50, ...params },
    auth: false,
    mock: () => ({ code: 200, message: 'success', data: { items: [], page: 1, pageSize: 50, total: 0 } })
  })
}

function fetchMallProductDetail(productId) {
  return request({
    url: `${BASE_URL}/products/${productId}`,
    auth: false,
    mock: () => ({ code: 200, message: 'success', data: null })
  })
}

function fetchMallCart() {
  return request({
    url: `${BASE_URL}/cart`,
    mock: () => ({ code: 200, message: 'success', data: [] })
  })
}

function putMallCartItem({ skuId, quantity, selected = true }) {
  return request({
    url: `${BASE_URL}/cart/items`,
    method: 'PUT',
    data: { skuId, quantity, selected },
    mock: () => ({ code: 200, message: 'success', data: { skuId, quantity, selected } })
  })
}

function removeMallCartItem(skuId) {
  return request({
    url: `${BASE_URL}/cart/items/${skuId}`,
    method: 'DELETE',
    mock: () => ({ code: 200, message: 'success', data: null })
  })
}

function fetchMallAddresses() {
  return request({
    url: `${BASE_URL}/addresses`,
    mock: () => ({ code: 200, message: 'success', data: [] })
  })
}

function createMallAddress(payload) {
  return request({
    url: `${BASE_URL}/addresses`,
    method: 'POST',
    data: payload,
    mock: () => ({ code: 200, message: 'success', data: { id: Date.now(), ...payload } })
  })
}

/** 商品价格、优惠和库存全部以后端结算结果为准。 */
function createMallOrder(payload) {
  return request({
    url: `${BASE_URL}/checkout`,
    method: 'POST',
    data: {
      addressId: payload.addressId,
      items: (payload.items || []).map(item => ({ skuId: item.skuId, quantity: item.quantity })),
      remark: payload.remark || ''
    },
    headers: { 'Idempotency-Key': payload.clientRequestId || createClientRequestId('mall_order') },
    mock: () => payload.addressId ? ({
      code: 200, message: 'success', data: {
        order: {
          id: Date.now(), orderNo: `SC${Date.now()}`, status: 'PENDING_PAYMENT',
          payableAmountCent: payload.payableAmountFen || 0, createdAt: formatDateTime()
        }, delivery: { addressId: payload.addressId }
      }
    }) : ({
      code: 200, message: 'success', data: {
        orderId: `SC${Date.now()}`, statusCode: 'PENDING_PAYMENT',
        payableAmountFen: payload.payableAmountFen, createdAt: formatDateTime()
      }
    })
  })
}

function cancelMallOrder(orderId) {
  return request({
    url: `${BASE_URL}/orders/${orderId}/cancel`,
    method: 'POST',
    mock: () => ({ code: 200, message: 'success', data: { order: { id: orderId, status: 'CANCELLED' } } })
  })
}

module.exports = {
  fetchMallCategories,
  fetchMallProducts,
  fetchMallProductDetail,
  fetchMallCart,
  putMallCartItem,
  removeMallCartItem,
  fetchMallAddresses,
  createMallAddress,
  createMallOrder,
  cancelMallOrder
}
