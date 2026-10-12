import http from './http'

export const getRefundApplications = (params) => http.get('/admin/refund-applications', { params })
export const approveRefund = (id, comment) => http.post(`/admin/refund-applications/${id}/approve`, { comment })
export const rejectRefund = (id, comment) => http.post(`/admin/refund-applications/${id}/reject`, { comment })
