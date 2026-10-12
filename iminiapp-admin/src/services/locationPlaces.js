import http from './http'

const root = '/admin/location-places'

export const getLocationPlaces = () => http.get(root)
export const createLocationPlace = (data) => http.post(root, data)
export const updateLocationPlace = (id, data) => http.put(`${root}/${id}`, data)
