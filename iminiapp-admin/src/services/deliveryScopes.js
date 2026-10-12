import http from './http'

const root = '/admin/dining-delivery-scopes'

export const getDiningDeliveryScope = (storeId) => http.get(`${root}/${storeId}`)
export const updateDiningDeliveryScope = (storeId, data) => http.put(`${root}/${storeId}`, data)
