const { request } = require('../utils/request')

function getHotels() {
  return request({ url: '/api/v1/app/hotels' })
}

function getHotelRoomTypes(hotelId, checkInDate, checkOutDate, roomCount = 1) {
  return request({
    url: `/api/v1/app/hotels/${hotelId}/room-types`,
    data: { checkInDate, checkOutDate, roomCount }
  })
}

function quoteHotelBooking(roomTypeId, checkInDate, checkOutDate, roomCount = 1) {
  return request({
    url: '/api/v1/app/hotel/quote',
    method: 'POST',
    data: { roomTypeId, checkInDate, checkOutDate, roomCount }
  })
}

module.exports = { getHotels, getHotelRoomTypes, quoteHotelBooking }
