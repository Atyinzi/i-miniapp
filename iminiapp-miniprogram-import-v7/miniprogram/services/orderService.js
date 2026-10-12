const { request } = require('../utils/request')
const { formatDateTime, createClientRequestId } = require('../utils/contract')
const config = require('../config/env')
const { settleMemberPoints, reverseMemberPoints } = require('./memberService')

const ORDER_STORAGE_KEY = 'iminiappPurchasedOrders'
const MALL_ORDER_STORAGE_KEY = 'iminiappMallOrders'
const TYPE_LABELS = { HOTEL: '住宿', MEETING: '会议预订', TICKET: '门票', EXPERIENCE: '军事体验', DINING_RESERVATION: '餐饮预订', VEHICLE: '车辆预约', LEISURE_TICKET: '休闲门票', MALL: '商城商品' }
const STATUS_LABELS = {
  PENDING_PAYMENT: '待付款',
  PENDING_USE: '待使用',
  PENDING_REVIEW: '待评价',
  COMPLETED: '已完成',
  REFUNDING: '退款处理中',
  REFUNDED: '退款成功',
  CANCELLED: '已取消'
}
const TICKET_STATUS_LABELS = { UNUSED: '未使用', USED: '已核销', VOID: '已失效', INVALIDATED: '已失效', REFUNDED: '已退款' }

function getStoredOrders() {
  const orders = wx.getStorageSync(ORDER_STORAGE_KEY)
  return Array.isArray(orders) ? orders : []
}

function normalizeMallOrder(order) {
  const items = Array.isArray(order.items) ? order.items : []
  const firstItem = items[0]
  const status = order.status === 'REFUNDED' ? 'REFUNDED' : order.status === 'REFUNDING' ? 'REFUNDING' : 'PENDING_USE'
  return {
    id: order.orderNo,
    type: 'MALL',
    typeLabel: TYPE_LABELS.MALL,
    status,
    statusLabel: status === 'PENDING_USE' ? '待配送' : STATUS_LABELS[status],
    title: firstItem ? firstItem.name : 'i某小程序特色商城订单',
    subtitle: `${items.length || 1} 种商品 · ${order.address || '收货地址待确认'}`,
    useDate: '物流配送',
    amountLabel: `¥${order.amount || '0'}`,
    canRefund: order.status !== 'REFUNDED',
    refundRule: '商品未发货前可提交退款申请，最终结果以服务方审核为准。',
    canShowVerification: false,
    verificationCode: '',
    verificationTickets: [],
    refundId: order.refundId || '',
    createdAt: order.createdAt,
    sourceStorage: MALL_ORDER_STORAGE_KEY
  }
}

function sanitizeMallOrder(order) {
  if (!order || order.type !== 'MALL') return order
  return {
    ...order,
    statusLabel: order.status === 'PENDING_USE' ? '待配送' : order.statusLabel,
    subtitle: String(order.subtitle || '').replace('收货方式待确认', '收货地址待确认'),
    useDate: '物流配送',
    refundRule: '商品未发货前可提交退款申请，最终结果以服务方审核为准。',
    canShowVerification: false,
    verificationCode: '',
    verificationTickets: []
  }
}

function getPurchasedOrders() {
  const orders = getStoredOrders().map(sanitizeMallOrder)
  const mallOrders = wx.getStorageSync(MALL_ORDER_STORAGE_KEY)
  const knownIds = new Set(orders.map(item => item.id))
  const normalizedMallOrders = Array.isArray(mallOrders)
    ? mallOrders.map(normalizeMallOrder).map(sanitizeMallOrder).filter(item => !knownIds.has(item.id))
    : []
  return [...orders, ...normalizedMallOrders]
    .sort((a, b) => String(b.createdAt || '').localeCompare(String(a.createdAt || '')))
}

const REMOTE_TYPE_MAP = {
  HOTEL_ROOM: 'HOTEL',
  HOTEL: 'HOTEL',
  MEETING_BOOKING: 'MEETING',
  SCENIC_TICKET: 'TICKET',
  VEHICLE_RENTAL: 'VEHICLE',
  VEHICLE: 'VEHICLE',
  CULTURAL_STORE: 'MALL',
  CULTURAL_PRODUCT: 'MALL',
  DINING: 'DINING_RESERVATION',
  SPORTS: 'LEISURE_TICKET',
  SPORT_VENUE: 'LEISURE_TICKET'
}

function normalizeRemoteOrder(order) {
  const firstItem = Array.isArray(order.items) ? order.items[0] : null
  const isWeaponExperience = firstItem && /^SCENIC_WEAPON_(TANK|MORTAR|RIFLE|FITNESS)_/.test(firstItem.skuCode || '')
  const type = isWeaponExperience ? 'EXPERIENCE' : (REMOTE_TYPE_MAP[order.orderType] || order.orderType || 'TICKET')
  const fulfillmentType = firstItem && firstItem.fulfillmentType ? firstItem.fulfillmentType : ''
  const status = [
    'PAID', 'READY', 'APPROVED',
    'WAITING_DRIVER', 'ACCEPTED', 'IN_SERVICE',
    'PENDING_ACCEPT', 'DRIVER_ACCEPTED', 'SERVICE_IN_PROGRESS'
  ].includes(order.status) ? 'PENDING_USE' : order.status
  const useDate = firstItem && firstItem.serviceDate
    ? `${firstItem.serviceDate}${firstItem.timeSlot ? ` ${firstItem.timeSlot}` : ''}`
    : ''
  return {
    id: String(order.id),
    orderNo: order.orderNo,
    backendOrderId: order.id,
    type,
    typeLabel: TYPE_LABELS[type] || '文旅订单',
    status,
    backendStatus: order.status,
    statusLabel: type === 'MALL' && status === 'PENDING_USE' ? '待配送' : (STATUS_LABELS[status] || order.status),
    title: firstItem ? (isWeaponExperience ? firstItem.skuName : (firstItem.resourceName || firstItem.skuName)) : (TYPE_LABELS[type] || '文旅订单'),
    subtitle: firstItem ? `${firstItem.skuName || ''} · ${firstItem.quantity}份` : order.orderNo,
    useDate: type === 'MALL' ? '物流配送' : useDate,
    serviceDate: firstItem && firstItem.serviceDate,
    quantity: firstItem ? firstItem.quantity : 1,
    fulfillmentType,
    amountLabel: `¥${(Number(order.payableAmountCent || 0) / 100).toFixed(2)}`,
    canRefund: order.status === 'PAID',
    refundRule: '退款申请提交后由服务方审核，结果以后端订单状态为准。',
    createdAt: order.createdAt,
    canShowVerification: type !== 'MALL' && (
      ['TICKET', 'EXPERIENCE', 'LEISURE_TICKET', 'HOTEL', 'MEETING'].includes(type)
      || (type === 'DINING_RESERVATION' && fulfillmentType === 'DINE_IN')
    ),
    verificationCode: type === 'MALL' ? '' : order.verificationCode,
    verificationTickets: type === 'MALL' ? [] : order.verificationTickets,
    items: order.items || []
  }
}

function normalizeVerificationTicket(ticket) {
  return {
    ...ticket,
    id: String(ticket.verificationId),
    statusLabel: TICKET_STATUS_LABELS[ticket.status] || ticket.status,
    displayCode: ticket.code || '票码生成中',
    qrCodeDataUrl: ticket.qrCodeDataUrl || ''
  }
}

function fetchOrderTickets(orderId) {
  return request({
    url: `/api/v1/app/orders/${orderId}/tickets`,
    mock: () => ({ code: 200, message: 'success', data: [] })
  }).then(tickets => (tickets || []).map(normalizeVerificationTicket))
}

function fetchPurchasedOrders() {
  if (config.useMock) return Promise.resolve(getPurchasedOrders())
  return request({ url: '/api/v1/app/orders', data: { page: 1, pageSize: 100 } })
    .then(page => {
      const orders = (page.items || []).map(normalizeRemoteOrder)
      wx.setStorageSync(ORDER_STORAGE_KEY, orders)
      return orders
    })
}

function savePurchasedOrder(order) {
  const orders = getStoredOrders()
  const status = order.status || 'PENDING_USE'
  const normalized = {
    createdAt: formatDateTime(),
    canRefund: true,
    ...order,
    status,
    typeLabel: TYPE_LABELS[order.type] || '文旅订单',
    statusLabel: STATUS_LABELS[status] || '待使用'
  }
  const index = orders.findIndex(item => item.id === normalized.id)
  if (index >= 0) orders[index] = normalized
  else orders.unshift(normalized)
  wx.setStorageSync(ORDER_STORAGE_KEY, orders)
  return normalized
}

function updatePurchasedOrder(orderId, patch) {
  const orders = getStoredOrders()
  const index = orders.findIndex(item => item.id === orderId)
  if (index < 0) {
    const mallOrders = wx.getStorageSync(MALL_ORDER_STORAGE_KEY)
    const mallIndex = Array.isArray(mallOrders) ? mallOrders.findIndex(item => item.orderNo === orderId) : -1
    if (mallIndex < 0) return null
    const status = patch.status || mallOrders[mallIndex].status
    mallOrders[mallIndex] = { ...mallOrders[mallIndex], ...patch, status }
    wx.setStorageSync(MALL_ORDER_STORAGE_KEY, mallOrders)
    if (['PENDING_REVIEW', 'COMPLETED'].includes(status)) settleMemberPoints(orderId)
    if (['CANCELLED', 'REFUNDED'].includes(status)) reverseMemberPoints(orderId)
    return normalizeMallOrder(mallOrders[mallIndex])
  }
  const status = patch.status || orders[index].status
  orders[index] = {
    ...orders[index],
    ...patch,
    status,
    statusLabel: patch.statusLabel || STATUS_LABELS[status] || orders[index].statusLabel,
    typeLabel: TYPE_LABELS[orders[index].type] || orders[index].typeLabel
  }
  wx.setStorageSync(ORDER_STORAGE_KEY, orders)
  if (['PENDING_REVIEW', 'COMPLETED'].includes(status)) settleMemberPoints(orderId)
  if (['CANCELLED', 'REFUNDED'].includes(status)) reverseMemberPoints(orderId)
  return orders[index]
}

/** POST /api/orders/:orderId/refund；返回真实退款单号和退款状态。 */
function refundPurchasedOrder(orderId, reason = '用户主动申请退款') {
  if (!config.useMock) {
    const clientRequestId = createClientRequestId('refund')
    return request({
      url: `/api/v1/app/orders/${orderId}/refund-applications`,
      method: 'POST',
      data: { reason },
      headers: { 'Idempotency-Key': clientRequestId }
    }).then(result => {
      updatePurchasedOrder(String(orderId), {
        status: 'REFUNDING',
        statusLabel: '退款处理中',
        canRefund: false,
        refundId: String(result.id),
        refundNo: result.refundNo
      })
      return {
        ...result,
        refundId: String(result.id),
        refundStatus: result.status
      }
    })
  }
  return request({
    url: `/api/orders/${orderId}/refund`,
    method: 'POST',
    data: { reason, clientRequestId: createClientRequestId('refund') },
    mock: () => ({
      code: 200,
      message: 'success',
      data: { refundId: `TK${Date.now()}`, orderId, refundStatus: 'REFUNDED', refundedAt: formatDateTime() }
    })
  }).then(result => {
    const orders = getStoredOrders()
    const index = orders.findIndex(item => item.id === orderId)
    if (index >= 0) {
      orders[index] = {
        ...orders[index],
        status: 'REFUNDED',
        statusLabel: STATUS_LABELS.REFUNDED,
        canRefund: false,
        refundId: result.refundId,
        refundedAt: result.refundedAt
      }
      wx.setStorageSync(ORDER_STORAGE_KEY, orders)
    } else {
      const mallOrders = wx.getStorageSync(MALL_ORDER_STORAGE_KEY)
      const mallIndex = Array.isArray(mallOrders) ? mallOrders.findIndex(item => item.orderNo === orderId) : -1
      if (mallIndex >= 0) {
        mallOrders[mallIndex] = {
          ...mallOrders[mallIndex],
          status: 'REFUNDED',
          refundId: result.refundId,
          refundedAt: result.refundedAt
        }
        wx.setStorageSync(MALL_ORDER_STORAGE_KEY, mallOrders)
      }
    }
    reverseMemberPoints(orderId)
    return result
  })
}

module.exports = {
  ORDER_STORAGE_KEY,
  TYPE_LABELS,
  STATUS_LABELS,
  getPurchasedOrders,
  fetchPurchasedOrders,
  fetchOrderTickets,
  savePurchasedOrder,
  updatePurchasedOrder,
  refundPurchasedOrder
}
