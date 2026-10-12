import http from './http'

export const getScenicSpots = () => http.get('/admin/scenic-spots')
export const getScenicSpot = (id) => http.get(`/admin/scenic-spots/${id}`)
export const createScenicSpot = (data) => http.post('/admin/scenic-spots', data)
export const updateScenicSpot = (id, data) => http.put(`/admin/scenic-spots/${id}`, data)
export const getScenicTickets = (id) => http.get(`/admin/scenic-spots/${id}/tickets`)
export const createScenicTicket = (id, data) => http.post(`/admin/scenic-spots/${id}/tickets`, data)
export const updateScenicTicket = (id, data) => http.put(`/admin/scenic-tickets/${id}`, data)
export const setupScenicInventory = (id, date, timeSlot, data) => http.put(
  `/admin/scenic-tickets/${id}/inventories/${date}`, data,
  { params: { timeSlot }, headers: { 'Idempotency-Key': crypto.randomUUID() } }
)
export const getPackageComponents = (id) => http.get(`/admin/scenic-packages/${id}/components`)
export const updatePackageComponents = (id, components) => http.put(`/admin/scenic-packages/${id}/components`, { components })
export const getScenicGuides = (id) => http.get(`/admin/scenic-spots/${id}/guides`)
export const createScenicGuide = (id, data) => http.post(`/admin/scenic-spots/${id}/guides`, data)
export const updateScenicGuide = (id, data) => http.put(`/admin/scenic-guides/${id}`, data)
export const getScenicSummary = (id, params) => http.get(`/admin/scenic-spots/${id}/operations/summary`, { params })
export const getScenicVerifications = (id, params) => http.get(`/admin/scenic-spots/${id}/operations/verification-records`, { params })
