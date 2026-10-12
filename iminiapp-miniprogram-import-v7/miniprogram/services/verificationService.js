const { request } = require('../utils/request')
const config = require('../config/env')

const REQUIRED_PERMISSION = 'verification.use'
const STATUS_LABELS = {
  UNUSED: '未使用',
  USED: '已核销',
  VOID: '已失效',
  INVALIDATED: '已失效'
}

// The staff token exists only in this JavaScript runtime. It is never written to Storage or Page.data.
let staffAccessToken = ''
let staffSession = null
let staffLoginPromise = null

function isStaffVerificationAvailable() {
  return config.staffVerificationEnabled === true &&
    config.useMock !== true &&
    config.usesPlaceholderBackend !== true &&
    Boolean(config.baseUrl)
}

function loginStaff(username, password) {
  if (!isStaffVerificationAvailable()) return Promise.reject(staffError('STAFF_VERIFICATION_UNAVAILABLE'))
  const normalizedUsername = String(username || '').trim()
  const rawPassword = String(password || '')
  if (!normalizedUsername || !rawPassword || normalizedUsername.length > 64 || rawPassword.length > 128) {
    return Promise.reject(staffError('ADMIN_CREDENTIALS_REQUIRED'))
  }
  if (staffLoginPromise) return staffLoginPromise

  clearStaffSession()
  staffLoginPromise = request({
    url: '/api/v1/admin/auth/login',
    method: 'POST',
    auth: false,
    data: { username: normalizedUsername, password: rawPassword }
  }).then(login => {
    const candidateToken = String((login && login.accessToken) || '')
    if (!candidateToken) throw staffError('STAFF_LOGIN_INVALID_RESPONSE')
    return validateStaffToken(candidateToken).then(principal => {
      staffAccessToken = candidateToken
      staffSession = publicSession(login, principal)
      return { ...staffSession, roles: staffSession.roles.slice(), permissions: staffSession.permissions.slice() }
    }).catch(error => revokeToken(candidateToken).then(() => { throw error }))
  }).finally(() => { staffLoginPromise = null })

  return staffLoginPromise
}

function ensureStaffSession() {
  if (!isStaffVerificationAvailable()) return Promise.reject(staffError('STAFF_VERIFICATION_UNAVAILABLE'))
  if (staffLoginPromise) return staffLoginPromise
  if (!staffAccessToken || !staffSession) return Promise.reject(staffError('STAFF_LOGIN_REQUIRED'))
  if (hasExpired(staffSession.expiresAt)) {
    clearStaffSession()
    return Promise.reject(staffError('STAFF_SESSION_EXPIRED'))
  }

  const token = staffAccessToken
  return validateStaffToken(token).then(principal => {
    if (token !== staffAccessToken || !staffSession) throw staffError('STAFF_SESSION_EXPIRED')
    staffSession = publicSession(staffSession, principal)
    return { ...staffSession, roles: staffSession.roles.slice(), permissions: staffSession.permissions.slice() }
  }).catch(error => {
    if (isAuthenticationError(error) || error.code === 'STAFF_PERMISSION_REQUIRED') clearStaffSession()
    throw isAuthenticationError(error) ? staffError('STAFF_SESSION_EXPIRED') : error
  })
}

function logoutStaff() {
  const token = staffAccessToken
  clearStaffSession()
  if (!token) return Promise.resolve({ serverRevoked: true })
  return request({
    url: '/api/v1/admin/auth/logout',
    method: 'POST',
    auth: false,
    headers: authorization(token)
  }).then(() => ({ serverRevoked: true }), () => ({ serverRevoked: false }))
}

function clearStaffSession() {
  staffAccessToken = ''
  staffSession = null
  staffLoginPromise = null
}

function validateStaffToken(token) {
  return request({
    url: '/api/v1/admin/auth/me',
    method: 'GET',
    auth: false,
    headers: authorization(token)
  }).then(principal => {
    if (!principal || principal.accountType !== 'ADMIN' ||
        !Array.isArray(principal.permissions) || !principal.permissions.includes(REQUIRED_PERMISSION)) {
      throw staffError('STAFF_PERMISSION_REQUIRED')
    }
    return principal
  })
}

function revokeToken(token) {
  return request({
    url: '/api/v1/admin/auth/logout',
    method: 'POST',
    auth: false,
    headers: authorization(token)
  }).then(() => undefined, () => undefined)
}

function staffRequest(options) {
  if (!isStaffVerificationAvailable()) return Promise.reject(staffError('STAFF_VERIFICATION_UNAVAILABLE'))
  if (!staffAccessToken || !staffSession) return Promise.reject(staffError('STAFF_LOGIN_REQUIRED'))
  if (hasExpired(staffSession.expiresAt)) {
    clearStaffSession()
    return Promise.reject(staffError('STAFF_SESSION_EXPIRED'))
  }

  const token = staffAccessToken
  return request({
    ...options,
    auth: false,
    headers: { ...(options.headers || {}), ...authorization(token) }
  }).catch(error => {
    if (isAuthenticationError(error)) {
      clearStaffSession()
      throw staffError('STAFF_SESSION_EXPIRED')
    }
    if (Number(error && error.statusCode) === 403 || (error && error.errorCode) === 'AUTH_403_001') {
      clearStaffSession()
      throw staffError('STAFF_PERMISSION_REQUIRED')
    }
    throw error
  })
}

function publicSession(login, principal) {
  return {
    displayName: principal.displayName || '现场核销员',
    accountType: principal.accountType,
    roles: Array.isArray(principal.roles) ? principal.roles.slice() : [],
    permissions: Array.isArray(principal.permissions) ? principal.permissions.slice() : [],
    expiresAt: login && login.expiresAt ? login.expiresAt : (staffSession && staffSession.expiresAt)
  }
}

function authorization(token) {
  return { Authorization: `Bearer ${token}` }
}

function hasExpired(expiresAt) {
  if (!expiresAt) return false
  const timestamp = Date.parse(expiresAt)
  return Number.isFinite(timestamp) && timestamp <= Date.now()
}

function isAuthenticationError(error) {
  return Boolean(error) && (Number(error.statusCode) === 401 || Number(error.code) === 401 ||
    error.errorCode === 'AUTH_401_001')
}

function isStaffSessionError(error) {
  return Boolean(error) && ['STAFF_LOGIN_REQUIRED', 'STAFF_SESSION_EXPIRED', 'STAFF_PERMISSION_REQUIRED']
    .includes(error.code)
}

function consumeTicketCode(code, deviceNo = '') {
  const normalizedCode = String(code || '').trim().toUpperCase()
  if (!normalizedCode) return Promise.reject(validationError('请输入电子票核销码'))
  return staffRequest({
    url: '/api/v1/admin/verifications/consume',
    method: 'POST',
    data: { code: normalizedCode, deviceNo: String(deviceNo || '').trim().slice(0, 64) || undefined }
  }).then(normalizeTicket)
}

function normalizeTicket(ticket) {
  return {
    ...ticket,
    verificationId: ticket && ticket.verificationId != null ? String(ticket.verificationId) : '',
    orderId: ticket && ticket.orderId != null ? String(ticket.orderId) : '',
    statusLabel: STATUS_LABELS[ticket && ticket.status] || (ticket && ticket.status) || '核销成功',
    verifiedAtLabel: formatDateTime(ticket && ticket.verifiedAt)
  }
}

function extractTicketCode(scanResult) {
  let value = String(scanResult || '').trim()
  if (!value) return ''
  try {
    const parsed = JSON.parse(value)
    value = String(parsed.code || parsed.ticketCode || parsed.verificationCode || value).trim()
  } catch (error) {}
  const queryMatch = value.match(/[?&#](?:code|ticketCode|verificationCode)=([^&#]+)/i)
  if (queryMatch) {
    try { value = decodeURIComponent(queryMatch[1]) } catch (error) { value = queryMatch[1] }
  }
  return value.trim().toUpperCase()
}

function getDeviceNo() {
  try {
    const info = wx.getDeviceInfo ? wx.getDeviceInfo() : (wx.getSystemInfoSync ? wx.getSystemInfoSync() : {})
    return ['MINIPROGRAM', info.platform, info.model].filter(Boolean).join('-').slice(0, 64)
  } catch (error) {
    return 'MINIPROGRAM'
  }
}

function describeVerificationError(error) {
  if (!error) return '操作失败，请重试'
  if (error.code === 'STAFF_VERIFICATION_UNAVAILABLE') return '当前版本未启用工作人员核销入口'
  if (error.code === 'ADMIN_CREDENTIALS_REQUIRED') return '请输入后台用户名和密码'
  if (error.code === 'STAFF_LOGIN_REQUIRED') return '请先使用工作人员账号登录'
  if (error.code === 'STAFF_SESSION_EXPIRED') return '工作人员登录已过期，请重新登录'
  if (error.code === 'STAFF_PERMISSION_REQUIRED') return '该后台账号没有核销权限'
  if (error.code === 'VALIDATION_ERROR') return error.message
  if (error.errorCode === 'VERIFICATION_409_001' || Number(error.statusCode) === 409) {
    return `${error.message || '电子票已核销或已失效'}，请勿重复放行`
  }
  if (error.errorCode === 'SYSTEM_404_001' || Number(error.statusCode) === 404) {
    return error.message || '未找到可核销的电子票，请核对票码'
  }
  if (error.errorCode === 'AUTH_403_001' || Number(error.statusCode) === 403) return '当前账号没有核销权限'
  if (isAuthenticationError(error)) return '用户名或密码错误，或登录已经过期'
  if (error.message === 'NETWORK_ERROR') return '无法连接核销服务，请检查网络后重试'
  return error.message || '操作失败，请重试'
}

function staffError(code) {
  const error = new Error(code)
  error.code = code
  return error
}

function validationError(message) {
  const error = new Error(message)
  error.code = 'VALIDATION_ERROR'
  return error
}

function formatDateTime(value) {
  if (!value) return ''
  return String(value).replace('T', ' ').replace(/\.\d+.*$/, '').slice(0, 19)
}

module.exports = {
  isStaffVerificationAvailable,
  loginStaff,
  ensureStaffSession,
  logoutStaff,
  clearStaffSession,
  consumeTicketCode,
  extractTicketCode,
  getDeviceNo,
  describeVerificationError,
  isStaffSessionError
}
