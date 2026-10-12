import http from './http'

export const getUsers = (params) => http.get('/admin/users', { params })
export const getUserDetail = (id) => http.get(`/admin/users/${id}`)
export const updateUserStatus = (id, data) => http.patch(`/admin/users/${id}/status`, data)
