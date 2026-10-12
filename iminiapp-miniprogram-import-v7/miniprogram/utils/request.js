const config = require('../config/env')

function isSuccess(response) {
  return response && (response.code === 200 || response.code === 'SUCCESS')
}

/** 全局响应契约：{ code, message, data, traceId }。错误仅在这里统一处理。 */
function request({ url, method = 'GET', data, headers = {}, mock, auth = true, retry = true }) {
  if (config.useMock && mock) return Promise.resolve(mock()).then(intercept)
  if (!config.backendReady) {
    if (typeof wx !== 'undefined' && wx.showToast) {
      wx.showToast({ title: '服务地址尚未配置，请联系管理员', icon: 'none' })
    }
    const configurationError = new Error(config.configurationError || 'API_BASE_URL_NOT_CONFIGURED')
    configurationError.code = config.configurationError || 'API_BASE_URL_NOT_CONFIGURED'
    return Promise.reject(configurationError)
  }
  const session = auth ? require('./session') : null
  const ready = session ? session.ensureSession() : Promise.resolve()
  return ready.then(() => send({ url, method, data, headers, mock, auth, retry }))
}

function send(options) {
  const { url, method, data, headers, auth, retry } = options
  return new Promise((resolve, reject) => {
    const token = wx.getStorageSync('token')
    wx.request({
      url: `${config.baseUrl}${url}`,
      method,
      data,
      header: {
        'Content-Type': 'application/json',
        ...(auth && token ? { Authorization: `Bearer ${token}` } : {}),
        ...headers
      },
      success: ({ statusCode, data: body }) => {
        if (statusCode === 401 && auth && retry) {
          const session = require('./session')
          session.clearSession()
          session.ensureSession()
            .then(() => send({ ...options, retry: false }))
            .then(resolve, reject)
          return
        }
        try {
          if (statusCode < 200 || statusCode >= 300) throw responseError(body, statusCode)
          resolve(intercept(body))
        } catch (error) {
          reject(error)
        }
      },
      fail: error => {
        wx.showToast({ title: '网络连接失败，请稍后重试', icon: 'none' })
        const networkError = new Error('NETWORK_ERROR')
        networkError.cause = error
        reject(networkError)
      }
    })
  })
}

function responseError(response, statusCode) {
  const message = response && response.message ? response.message : `请求失败（${statusCode}）`
  const error = new Error(message)
  error.code = response && response.code !== undefined ? response.code : statusCode
  error.traceId = response && response.traceId
  error.statusCode = statusCode
  if (typeof wx !== 'undefined' && wx.showToast) wx.showToast({ title: message, icon: 'none' })
  return error
}

function intercept(response) {
  if (!response || response.code === undefined) throw new Error('INVALID_RESPONSE_CONTRACT')
  if (!isSuccess(response)) {
    const message = response.message || '请求失败'
    wx.showToast({ title: message, icon: 'none' })
    const error = new Error(message)
    error.code = response.code
    error.errorCode = response.errorCode || 'UNKNOWN_BUSINESS_ERROR'
    error.detail = response.detail === undefined ? null : response.detail
    error.traceId = response.traceId || null
    throw error
  }
  return response.data
}
module.exports = { request, intercept, isSuccess, responseError }
