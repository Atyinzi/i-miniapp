import http from './http'

export const getDashboardSummary = () => http.get('/admin/dashboard/summary')
