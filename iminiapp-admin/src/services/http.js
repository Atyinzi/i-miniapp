import axios from 'axios'

const http = axios.create({
  baseURL: import.meta.env.VITE_API_BASE_URL || '/api/v1',
  timeout: 10000
})

http.interceptors.request.use((config) => {
  const token = localStorage.getItem('bf_admin_token')
  if (token) config.headers.Authorization = `Bearer ${token}`
  config.headers['X-Trace-Id'] = `admin-${Date.now()}`
  return config
})

http.interceptors.response.use(
  ({ data }) => {
    const successCodes = ['SUCCESS', 'OK', 200]
    if (data?.code && !successCodes.includes(data.code)) {
      return Promise.reject(new Error(data.message || '请求失败'))
    }
    return data?.data ?? data
  },
  (error) => {
    if (error.response?.status === 401) {
      localStorage.removeItem('bf_admin_token')
      localStorage.removeItem('bf_admin_profile')
      if (window.location.pathname !== '/login') window.location.assign('/login')
    }
    return Promise.reject(new Error(error.response?.data?.message || error.message || '网络异常'))
  }
)

export default http
