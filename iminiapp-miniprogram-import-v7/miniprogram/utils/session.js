const config = require('../config/env')

let loginPromise = null
const MANUAL_LOGOUT_KEY = 'iminiappManualLogout'
const LOGIN_CONSENT_KEY = 'iminiappWechatLoginConsent'
const WECHAT_PROFILE_KEY = 'iminiappWechatAuthorizedProfile'

function clearSession() {
  if (typeof wx === 'undefined') return
  if (wx.removeStorageSync) {
    wx.removeStorageSync('token')
    wx.removeStorageSync('user')
  } else if (wx.setStorageSync) {
    wx.setStorageSync('token', '')
    wx.setStorageSync('user', '')
  }
}

function markLoggedOut() {
  clearSession()
  if (typeof wx !== 'undefined' && wx.setStorageSync) wx.setStorageSync(MANUAL_LOGOUT_KEY, true)
}

function isManuallyLoggedOut() {
  return typeof wx !== 'undefined' && wx.getStorageSync && wx.getStorageSync(MANUAL_LOGOUT_KEY) === true
}

function hasSession() {
  return typeof wx !== 'undefined' && wx.getStorageSync && Boolean(wx.getStorageSync('token'))
}

function hasLoginConsent() {
  return typeof wx !== 'undefined' && wx.getStorageSync && wx.getStorageSync(LOGIN_CONSENT_KEY) === true
}

function resumeSession(profile) {
  if (typeof wx !== 'undefined' && wx.removeStorageSync) wx.removeStorageSync(MANUAL_LOGOUT_KEY)
  if (typeof wx !== 'undefined' && wx.setStorageSync) {
    wx.setStorageSync(LOGIN_CONSENT_KEY, true)
    if (profile) wx.setStorageSync(WECHAT_PROFILE_KEY, profile)
  }
  return ensureSession()
}

function parseLoginResponse(statusCode, body) {
  if (statusCode < 200 || statusCode >= 300 || !body || !['SUCCESS', 200].includes(body.code)) {
    const error = new Error((body && body.message) || `登录失败（${statusCode}）`)
    error.code = body && body.code
    error.statusCode = statusCode
    throw error
  }
  if (!body.data || !body.data.accessToken) throw new Error('LOGIN_TOKEN_MISSING')
  wx.setStorageSync('token', body.data.accessToken)
  wx.setStorageSync('user', body.data.user || null)
  return body.data
}

function postLogin(path, data) {
  return new Promise((resolve, reject) => {
    wx.request({
      url: `${config.baseUrl}${path}`,
      method: 'POST',
      data,
      header: { 'Content-Type': 'application/json' },
      success: ({ statusCode, data: body }) => {
        try { resolve(parseLoginResponse(statusCode, body)) } catch (error) { reject(error) }
      },
      fail: reject
    })
  })
}

function wechatLogin(profile) {
  return new Promise((resolve, reject) => {
    wx.login({
      success: ({ code }) => {
        if (!code) return reject(new Error('WECHAT_LOGIN_CODE_MISSING'))
        const storedUser = wx.getStorageSync('user') || {}
        const authorizedProfile = profile || wx.getStorageSync(WECHAT_PROFILE_KEY) || {}
        postLogin('/api/v1/app/auth/wechat-login', {
          code,
          displayName: authorizedProfile.displayName || authorizedProfile.nickName || storedUser.displayName || '微信用户',
          avatarUrl: authorizedProfile.avatarUrl || ''
        }).then(resolve, reject)
      },
      fail: reject
    })
  })
}

function ensureSession() {
  if (isManuallyLoggedOut()) {
    const error = new Error('请先登录')
    error.code = 'MANUAL_LOGOUT'
    return Promise.reject(error)
  }
  let token = wx.getStorageSync('token')
  // 升级到真实微信登录后，自动移除旧版本遗留的 mock_ 本地令牌。
  if (token && config.authMode === 'wechat' && typeof wx.login === 'function' && String(token).startsWith('mock_')) {
    clearSession()
    token = ''
  }
  if (token) return Promise.resolve({ accessToken: token, user: wx.getStorageSync('user') || null })
  if (!hasLoginConsent()) {
    const error = new Error('请先授权微信登录')
    error.code = 'LOGIN_REQUIRED'
    return Promise.reject(error)
  }
  if (config.useMock) {
    const mockSession = { accessToken: 'mock_consented_session', user: { displayName: '微信用户' } }
    wx.setStorageSync('token', mockSession.accessToken)
    wx.setStorageSync('user', mockSession.user)
    return Promise.resolve(mockSession)
  }
  if (!loginPromise) {
    if (typeof wx.login !== 'function') {
      const error = new Error('WECHAT_LOGIN_UNAVAILABLE')
      error.code = 'WECHAT_LOGIN_UNAVAILABLE'
      return Promise.reject(error)
    }
    loginPromise = wechatLogin()
      .catch(error => {
        wx.showToast({ title: error.message || '登录失败', icon: 'none' })
        throw error
      })
      .finally(() => { loginPromise = null })
  }
  return loginPromise
}

module.exports = {
  LOGIN_CONSENT_KEY, WECHAT_PROFILE_KEY,
  ensureSession, clearSession, markLoggedOut, isManuallyLoggedOut,
  hasSession, hasLoginConsent, resumeSession, wechatLogin, parseLoginResponse
}
