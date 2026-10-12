const { request } = require('../utils/request')
const { formatDateTime, createClientRequestId } = require('../utils/contract')

function fetchLeisureVenues() {
  return request({
    url: '/api/v1/app/sports/venues',
    mock: () => ({ code: 200, message: 'success', data: [] })
  })
}

function fetchLeisureAvailability(courtId, date) {
  return request({
    url: `/api/v1/app/sports/courts/${courtId}/availability`,
    data: { date },
    mock: () => ({ code: 200, message: 'success', data: [] })
  })
}

function createLeisureBooking(payload) {
  const { clientRequestId, ...data } = payload
  return request({
    url: '/api/v1/app/sports/bookings',
    method: 'POST',
    data,
    headers: { 'Idempotency-Key': clientRequestId || createClientRequestId('leisure_booking') },
    mock: () => ({
      code: 200,
      message: 'success',
      data: {
        bookingId: Date.now(), orderId: Date.now(), orderNo: `XX${Date.now()}`,
        orderStatus: 'PENDING_PAYMENT', bookingStatus: 'HELD', bookingDate: data.bookingDate,
        startTime: data.startTime, endTime: data.endTime, priceCent: 0, createdAt: formatDateTime()
      }
    })
  })
}

function cancelLeisureBooking(orderId) {
  return request({
    url: `/api/v1/app/sports/bookings/${orderId}/cancel`,
    method: 'POST',
    mock: () => ({ code: 200, message: 'success', data: { orderId, orderStatus: 'CANCELLED', bookingStatus: 'CANCELLED' } })
  })
}

/**
 * POST /api/leisure/ticket-orders
 * 金额字段以分为单位；clientRequestId 用于避免重复提交。
 */
function createLeisureTicketOrder(payload) {
  return request({
    url: '/api/leisure/ticket-orders',
    method: 'POST',
    data: {
      ...payload,
      clientRequestId: payload.clientRequestId || createClientRequestId('leisure_ticket')
    },
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
          orderId: `XX${stamp}`,
          electronicTicketCode: `XPH${stamp.slice(-8)}`,
          statusCode: 'PAID',
          visitDate: payload.visitDate,
          admissionSlot: payload.admissionSlot,
          createdAt: formatDateTime(now)
        }
      }
    }
  })
}

module.exports = {
  fetchLeisureVenues,
  fetchLeisureAvailability,
  createLeisureBooking,
  cancelLeisureBooking,
  createLeisureTicketOrder
}
