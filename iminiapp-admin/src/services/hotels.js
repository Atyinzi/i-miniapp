import http from './http'

export const fetchHotelBookings = (params = {}) => http.get('/admin/hotels/operations/bookings', { params })
export const fetchVacantHotelRooms = () => http.get('/admin/hotels/rooms', { params: { status: 'VACANT' } })
export const checkInHotelByCode = (data) => http.post('/admin/hotels/operations/check-in/by-code', data, {
  headers: { 'Idempotency-Key': `hotel-checkin-${Date.now()}-${Math.random().toString(16).slice(2)}` }
})
