import http from './http'

export const loginAsAdmin = (username, password) => http.post('/admin/auth/login', { username, password })
export const getCurrentAdmin = () => http.get('/admin/auth/me')
export const logoutAdmin = () => http.post('/admin/auth/logout')
