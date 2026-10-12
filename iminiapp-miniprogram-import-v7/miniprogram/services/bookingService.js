const { request } = require('../utils/request')
const config = require('../config/env')
const { formatDateTime, createClientRequestId } = require('../utils/contract')

/**
 * 车辆服务状态契约。用户端轮询、WebSocket 推送及司机端状态写入均使用 code，
 * 页面不依赖中文文案判断业务状态。
 */
const VEHICLE_STATUS_FLOW = [
  { code: 'PENDING_PAYMENT', label: '等待付款', description: '订单已经创建，请在有效期内完成支付', actionLabel: '' },
  { code: 'PAID', label: '等待调度', description: '订单已支付，正在安排车辆和司机', actionLabel: '' },
  { code: 'PENDING_ACCEPT', label: '等待司机接单', description: '调度已分配车辆和司机，等待司机确认', actionLabel: '' },
  { code: 'DRIVER_ACCEPTED', label: '司机已接单', description: '司机信息已确认，请留意电话或服务消息', actionLabel: '' },
  { code: 'SERVICE_IN_PROGRESS', label: '服务进行中', description: '车辆已经出发，本次服务正在进行', actionLabel: '' },
  { code: 'PENDING_SUPPLEMENT', label: '等待补款', description: '服务费用已核算，请完成车辆结算补款', actionLabel: '' },
  { code: 'SERVICE_COMPLETED', label: '服务已结束', description: '司机已结束服务，费用等待最终核算', actionLabel: '' }
]

/** POST /api/bookings；clientRequestId 用于后端幂等，金额字段保留分单位。 */
function createBooking(payload) {
  const requestPayload = {
    ...payload,
    clientRequestId: payload.clientRequestId || createClientRequestId('booking')
  }
  const isHotel = payload.bookingType === 'hotel'
  const clientRequestId = requestPayload.clientRequestId
  const isRealVehicle = !isHotel && !config.useMock
  const url = isHotel
    ? '/api/v1/app/hotel/bookings'
    : isRealVehicle ? '/api/v1/app/vehicle-rentals' : '/api/bookings'
  const data = isHotel ? {
    roomTypeId: Number(payload.roomTypeId),
    checkInDate: String(payload.checkInDate || '').slice(0, 10),
    checkOutDate: String(payload.checkOutDate || '').slice(0, 10),
    roomCount: 1,
    guestCount: payload.passengerCount,
    contactName: payload.contactName,
    contactMobile: payload.contactPhone,
    estimatedArrivalTime: '14:00:00',
    specialRequests: '',
    remark: payload.lodgingAreaName || '',
    guests: Array.from({ length: payload.passengerCount }, (_, index) => ({
      name: index === 0 ? payload.contactName : `同行人${index + 1}`,
      mobile: index === 0 ? payload.contactPhone : '',
      idType: 'ID_CARD',
      idNo: payload.guestIdCard,
      roomIndex: 1
    }))
  } : isRealVehicle ? {
    modelId: Number(payload.vehicleModelId),
    rentalMode: ({ halfDay: 'HALF_DAY', fullDay: 'FULL_DAY', monthly: 'MONTH', bareMonthly: 'BARE_MONTH' })[payload.rentalPlan],
    startAt: toLocalDateTime(payload.checkInDate),
    endAt: vehicleEndAt(payload.checkInDate, payload.rentalPlan),
    departure: payload.departure,
    destination: payload.destination,
    departureLongitude: payload.departureLocation && payload.departureLocation.longitude,
    departureLatitude: payload.departureLocation && payload.departureLocation.latitude,
    destinationLongitude: payload.destinationLocation && payload.destinationLocation.longitude,
    destinationLatitude: payload.destinationLocation && payload.destinationLocation.latitude,
    coordinateSystem: 'GCJ-02',
    passengerCount: payload.passengerCount,
    estimatedKm: null,
    remark: `${payload.contactName} ${payload.contactPhone}`
  } : requestPayload
  return request({
    url,
    method: 'POST',
    data,
    headers: isHotel || isRealVehicle ? { 'Idempotency-Key': clientRequestId } : {},
    mock: () => {
      const createdAt = formatDateTime()
      return {
        code: 200,
        message: 'success',
        data: {
          bookingId: `BZ${Date.now()}`,
          createdAt,
          statusCode: payload.bookingType === 'vehicle' ? 'PENDING_ACCEPT' : 'BOOKED',
          statusUpdatedAt: createdAt,
          driver: null,
          settlement: null
        }
      }
    }
  }).then(result => isHotel && !config.useMock ? {
    ...result,
    bookingId: String(result.orderId),
    hotelReservationId: String(result.reservationId),
    statusCode: result.orderStatus,
    createdAt: result.createdAt
  } : isRealVehicle ? {
    ...result,
    bookingId: String(result.orderId),
    statusCode: result.status,
    statusUpdatedAt: result.createdAt,
    createdAt: result.createdAt
  } : result)
}

function toLocalDateTime(value) {
  return String(value || '').replace(' ', 'T').slice(0, 19)
}

function vehicleEndAt(startValue, rentalPlan) {
  const start = new Date(toLocalDateTime(startValue))
  const hours = rentalPlan === 'halfDay' ? 4 : rentalPlan === 'fullDay' ? 8 : 24 * 30
  start.setHours(start.getHours() + hours)
  const pad = value => String(value).padStart(2, '0')
  return `${start.getFullYear()}-${pad(start.getMonth() + 1)}-${pad(start.getDate())}T${pad(start.getHours())}:${pad(start.getMinutes())}:${pad(start.getSeconds())}`
}

function getVehicleModels() {
  return request({
    url: '/api/v1/app/vehicle-rentals/models',
    mock: () => ({ code: 200, message: 'success', data: [] })
  })
}

/** GET /api/bookings/:bookingId/status；供轮询或页面恢复时读取权威状态。 */
function getVehicleBookingStatus(bookingId) {
  if (!config.useMock) {
    return request({ url: `/api/v1/app/vehicle-rentals/${bookingId}/status` }).then(status => ({
      bookingId: String(status.orderId),
      statusCode: status.statusCode,
      statusUpdatedAt: status.statusUpdatedAt,
      driver: status.driverId ? {
        driverId: String(status.driverId), driverName: status.driverName,
        driverPhone: status.driverPhone, driverPhoneMasked: status.driverPhoneMasked,
        vehiclePlate: status.vehiclePlate, isMock: false
      } : null,
      settlement: null,
      driverLocation: status.currentLongitude != null && status.currentLatitude != null ? {
        longitude: Number(status.currentLongitude),
        latitude: Number(status.currentLatitude),
        coordinateSystem: status.coordinateSystem || 'GCJ-02',
        updatedAt: status.locationUpdatedAt
      } : null
    }))
  }
  return request({
    url: `/api/bookings/${bookingId}/status`,
    method: 'GET',
    mock: () => ({
      code: 200,
      message: 'success',
      data: {
        bookingId,
        statusCode: 'PENDING_ACCEPT',
        statusUpdatedAt: formatDateTime(),
        driver: null,
        settlement: null
      }
    })
  })
}

function getVehicleSettlement(bookingId) {
  if (config.useMock) return Promise.resolve(null)
  return request({ url: `/api/v1/app/vehicle-rentals/${encodeURIComponent(bookingId)}/settlement` })
}

/** POST /api/bookings/:bookingId/cancel；当前规则仅允许司机接单前取消。 */
function cancelVehicleBooking(bookingId, payload) {
  if (!['PENDING_PAYMENT', 'PENDING_ACCEPT'].includes(payload.currentStatus)) {
    return Promise.reject(new Error('VEHICLE_BOOKING_ALREADY_ACCEPTED'))
  }
  const requestPayload = {
    ...payload,
    clientRequestId: payload.clientRequestId || createClientRequestId('vehicle_cancel'),
    reason: payload.reason || '用户主动取消车辆预约'
  }
  return request({
    url: config.useMock ? `/api/bookings/${bookingId}/cancel` : `/api/v1/app/orders/${bookingId}/cancel`,
    method: 'POST',
    data: requestPayload,
    mock: () => ({
      code: 200,
      message: 'success',
      data: {
        bookingId,
        statusCode: 'CANCELLED',
        cancelledAt: formatDateTime(),
        cancellationNo: `QX${Date.now()}`,
        refundStatus: 'NOT_APPLICABLE'
      }
    })
  }).then(result => config.useMock ? result : {
    bookingId: String(result.id),
    statusCode: result.status,
    cancelledAt: result.cancelledAt,
    cancellationNo: result.orderNo,
    refundStatus: 'NOT_APPLICABLE'
  })
}

module.exports = {
  VEHICLE_STATUS_FLOW,
  createBooking,
  getVehicleModels,
  getVehicleBookingStatus,
  getVehicleSettlement,
  cancelVehicleBooking
}
