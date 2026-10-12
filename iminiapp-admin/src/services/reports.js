import http from './http'

export const getReportOverview = (params) => http.get('/admin/reports/overview', { params })
export const getDailyReport = (params) => http.get('/admin/reports/daily', { params })
export const getResourceSalesReport = (params) => http.get('/admin/reports/resource-sales', { params })
