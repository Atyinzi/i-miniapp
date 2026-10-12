import http from './http'
export const getOrders = (params) => http.get('/admin/orders', { params })
export const getOrderDetail = (id) => http.get(`/admin/orders/${id}`)
export const expireOverdueOrders = () => http.post('/admin/orders/expire-overdue')
