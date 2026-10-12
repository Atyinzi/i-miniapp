import http from './http'

export const getPlatformSettings = () => http.get('/admin/settings/platform')
export const updatePlatformSetting = (key, value) => http.put(`/admin/settings/platform/${encodeURIComponent(key)}`, { value })
export const getPermissions = () => http.get('/admin/settings/permissions')
export const getRoles = () => http.get('/admin/settings/roles')
export const updateRolePermissions = (id, permissionIds) => http.put(`/admin/settings/roles/${id}/permissions`, { permissionIds })
export const getAuditLogs = (params) => http.get('/admin/settings/audit-logs', { params })
export const getAdminAccounts = () => http.get('/admin/admin-accounts')
export const createAdminAccount = (data) => http.post('/admin/admin-accounts', data)
export const updateAdminAccountStatus = (id, status) => http.patch(`/admin/admin-accounts/${id}/status`, { status })
export const resetAdminPassword = (id, password) => http.put(`/admin/admin-accounts/${id}/password`, { password })
export const updateAdminAccountRoles = (id, roles) => http.put(`/admin/admin-accounts/${id}/roles`, { roles })
