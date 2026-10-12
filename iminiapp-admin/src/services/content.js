import http from './http'

export const getArticles = (params) => http.get('/admin/articles', { params })
export const createArticle = (data) => http.post('/admin/articles', data)
export const updateArticle = (id, data) => http.put(`/admin/articles/${id}`, data)
export const updateArticleStatus = (id, status) => http.patch(`/admin/articles/${id}/status`, { status })

export const getBanners = () => http.get('/admin/banners')
export const createBanner = (data) => http.post('/admin/banners', data)
export const updateBanner = (id, data) => http.put(`/admin/banners/${id}`, data)
export const updateBannerEnabled = (id, enabled) => http.patch(`/admin/banners/${id}/enabled`, { enabled })
