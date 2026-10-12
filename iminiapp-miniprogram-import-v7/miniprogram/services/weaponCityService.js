const { request } = require('../utils/request')
const { formatDateTime, createClientRequestId } = require('../utils/contract')

function fetchWeaponCitySpot() {
  return request({
    url: '/api/v1/app/scenic-spots',
    data: { keyword: '示例景区', page: 1, pageSize: 20 },
    mock: () => ({ code: 200, message: 'success', data: { items: [], page: 1, pageSize: 20, total: 0, totalPages: 0 } })
  })
}

function fetchWeaponCitySpotDetail(scenicSpotId) {
  return request({
    url: `/api/v1/app/scenic-spots/${scenicSpotId}`,
    mock: () => ({ code: 200, message: 'success', data: null })
  })
}

function fetchWeaponCityTickets(scenicSpotId, serviceDate) {
  return request({
    url: `/api/v1/app/scenic-spots/${scenicSpotId}/tickets`,
    data: { serviceDate },
    mock: () => ({ code: 200, message: 'success', data: [] })
  })
}

function createWeaponCityOrder(payload) {
  const { clientRequestId, ...orderData } = payload
  return request({
    url: '/api/v1/app/orders',
    method: 'POST',
    data: { orderType: 'SCENIC_TICKET', ...orderData },
    headers: { 'Idempotency-Key': clientRequestId || createClientRequestId('weapon_city_order') },
    mock: () => ({
      code: 200,
      message: 'success',
      data: {
        id: Date.now(), orderNo: `BFH-WEAPON-${Date.now()}`, orderType: 'SCENIC_TICKET',
        status: 'PENDING_PAYMENT', totalAmountCent: 0, payableAmountCent: 0,
        paidAmountCent: 0, currency: 'CNY', items: orderData.items || [], createdAt: formatDateTime()
      }
    })
  })
}

function cancelWeaponCityOrder(orderId) {
  return request({
    url: `/api/v1/app/orders/${orderId}/cancel`,
    method: 'POST',
    mock: () => ({ code: 200, message: 'success', data: { id: orderId, status: 'CANCELLED' } })
  })
}

/** POST /api/weapon-city/ticket-orders；价格由后端商品配置返回，页面不硬编码正式售价。 */
function createTicketOrder(payload) {
  return request({
    url: '/api/weapon-city/ticket-orders',
    method: 'POST',
    data: {
      ...payload,
      clientRequestId: payload.clientRequestId || createClientRequestId('weapon_ticket')
    },
    mock: () => ({
      code: 200,
      message: 'success',
      data: {
        orderId: `BQMP${Date.now()}`,
        electronicTicketCode: `BQ-${String(Date.now()).slice(-8)}`,
        statusCode: 'PAID',
        visitDate: payload.visitDate,
        createdAt: formatDateTime()
      }
    })
  })
}

/** POST /api/weapon-city/experience-bookings；体验项目价格和余量以后由后端写入。 */
function createExperienceBooking(payload) {
  return request({
    url: '/api/weapon-city/experience-bookings',
    method: 'POST',
    data: {
      ...payload,
      clientRequestId: payload.clientRequestId || createClientRequestId('weapon_experience')
    },
    mock: () => ({
      code: 200,
      message: 'success',
      data: {
        bookingId: `BQTY${Date.now()}`,
        verificationCode: `TY-${String(Date.now()).slice(-8)}`,
        statusCode: 'BOOKED',
        experienceDate: payload.experienceDate,
        timeSlot: payload.timeSlot,
        createdAt: formatDateTime()
      }
    })
  })
}

module.exports = {
  fetchWeaponCitySpot,
  fetchWeaponCitySpotDetail,
  fetchWeaponCityTickets,
  createWeaponCityOrder,
  cancelWeaponCityOrder,
  createTicketOrder,
  createExperienceBooking
}
