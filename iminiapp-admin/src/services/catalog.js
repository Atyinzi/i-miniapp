import http from './http'

export const getResources = (params) => http.get('/admin/resources', { params })
export const createResource = (payload) => http.post('/admin/resources', payload)
export const updateResource = (id, payload) => http.put(`/admin/resources/${id}`, payload)
export const updateResourceStatus = (id, status) => http.patch(`/admin/resources/${id}/status`, { status })
export const getResourceSkus = (id) => http.get(`/admin/resources/${id}/skus`)
export const createSku = (resourceId, payload) => http.post(`/admin/resources/${resourceId}/skus`, payload)
export const updateSku = (resourceId, skuId, payload) => http.put(`/admin/resources/${resourceId}/skus/${skuId}`, payload)
export const getResourceInventories = (id) => http.get(`/admin/resources/${id}/inventories`)
export const setupInventory = (resourceId, payload) => http.post(`/admin/resources/${resourceId}/inventories`, payload)
export const adjustInventory = (id, payload) => http.post(`/admin/inventories/${id}/adjust`, payload, {
  headers: { 'Idempotency-Key': `admin-${Date.now()}-${Math.random().toString(36).slice(2, 8)}` }
})
