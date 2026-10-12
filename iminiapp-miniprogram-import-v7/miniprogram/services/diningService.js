const { request } = require('../utils/request')
const { formatDateTime, createClientRequestId } = require('../utils/contract')

/**
 * 配送状态契约。后端、轮询接口或 WebSocket 推送均应返回 statusCode，
 * 页面只通过该稳定编码更新展示，避免依赖中文状态文字。
 */
const DELIVERY_STATUS_FLOW = [
  { code: 'PENDING_PAYMENT', label: '待支付', description: '订单已创建，请完成支付' },
  { code: 'PAID', label: '待接单', description: '支付成功，等待后厨接单' },
  { code: 'PREPARING', label: '制作中', description: '后厨已接单，正在制作餐品' },
  { code: 'READY', label: '已出餐', description: '餐品已备好，等待配送员取餐' },
  { code: 'DELIVERING', label: '配送中', description: '配送员已取餐，正在送往指定地点' },
  { code: 'COMPLETED', label: '已送达', description: '餐品已送达，本次配送完成' }
]

function fetchDiningStores() {
  return request({
    url: '/api/v1/dining/catalog/stores',
    mock: () => ({ code: 200, message: 'success', data: [] })
  })
}

function fetchDiningTables({ storeId, date, time, diningMinutes = 120, guestCount = 1 }) {
  return request({
    url: '/api/v1/dining/catalog/tables',
    data: { storeId, date, time, diningMinutes, guestCount },
    mock: () => ({ code: 200, message: 'success', data: [] })
  })
}

function fetchDiningCategories(storeId) {
  return request({
    url: '/api/v1/dining/catalog/categories',
    data: { storeId },
    mock: () => ({ code: 200, message: 'success', data: [] })
  })
}

function fetchDiningDishes(storeId) {
  return request({
    url: '/api/v1/dining/catalog/dishes',
    data: { storeId },
    mock: () => ({ code: 200, message: 'success', data: [] })
  })
}

function createDiningReservation(payload) {
  const { clientRequestId, ...data } = payload
  return request({
    url: '/api/v1/dining/reservations',
    method: 'POST',
    data,
    headers: { 'Idempotency-Key': clientRequestId || createClientRequestId('dining_reservation') },
    mock: () => ({
      code: 200,
      message: 'success',
      data: {
        id: Date.now(), reservationNo: `DR${Date.now()}`, orderId: Date.now(),
        status: 'PENDING_PAYMENT', totalAmountCent: 5000, createdAt: formatDateTime()
      }
    })
  })
}

function cancelDiningReservation(reservationId) {
  return request({
    url: `/api/v1/dining/reservations/${reservationId}/cancel`,
    method: 'POST',
    mock: () => ({ code: 200, message: 'success', data: { id: reservationId, status: 'CANCELLED' } })
  })
}

/** POST /api/dining/delivery/orders；金额字段均使用分，clientRequestId 用于幂等。 */
function createDeliveryOrder(payload) {
  const { clientRequestId, ...data } = payload
  return request({
    url: '/api/v1/dining/reservations',
    method: 'POST',
    data,
    headers: { 'Idempotency-Key': clientRequestId || createClientRequestId('dining_delivery') },
    mock: () => {
      const now = new Date()
      const stamp = [
        now.getFullYear(),
        String(now.getMonth() + 1).padStart(2, '0'),
        String(now.getDate()).padStart(2, '0'),
        String(now.getHours()).padStart(2, '0'),
        String(now.getMinutes()).padStart(2, '0'),
        String(now.getSeconds()).padStart(2, '0')
      ].join('')
      return {
        code: 200,
        message: 'success',
        data: {
          id: Date.now(), orderId: Date.now(), reservationNo: `PS${stamp}`,
          status: 'PENDING_PAYMENT', totalAmountCent: 0,
          createdAt: formatDateTime(now),
          statusUpdatedAt: formatDateTime(now),
          estimatedMinutes: 25
        }
      }
    }
  })
}

function getDeliveryOrder(orderId) {
  return request({
    url: `/api/v1/dining/reservations/${orderId}`,
    mock: () => ({ code: 200, message: 'success', data: { id: orderId, status: 'PENDING_PAYMENT', createdAt: formatDateTime() } })
  })
}

/** POST /api/dining/delivery/orders/:orderId/cancel；仅待接单状态允许取消。 */
function cancelDeliveryOrder(orderId, payload = {}) {
  return request({
    url: `/api/v1/dining/reservations/${orderId}/cancel`,
    method: 'POST',
    data: {
      clientRequestId: payload.clientRequestId || createClientRequestId('delivery_cancel'),
      reason: payload.reason || '用户主动取消配送订单'
    },
    mock: () => ({
      code: 200,
      message: 'success',
      data: { orderId, cancelled: true, refundStatus: 'FULL_REFUND' }
    })
  })
}

module.exports = {
  DELIVERY_STATUS_FLOW,
  fetchDiningStores,
  fetchDiningTables,
  fetchDiningCategories,
  fetchDiningDishes,
  createDiningReservation,
  cancelDiningReservation,
  createDeliveryOrder,
  getDeliveryOrder,
  cancelDeliveryOrder
}
