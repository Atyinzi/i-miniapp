const {
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
} = require('../../services/verificationService')

let pendingStaffPassword = ''

Page({
  data: {
    available: isStaffVerificationAvailable(),
    loggedIn: false,
    connectionStatus: 'SIGNED_OUT',
    connectionLabel: '请使用工作人员账号登录',
    operatorName: '',
    username: '',
    hasPassword: false,
    passwordVisible: false,
    loginFormVisible: true,
    loginSubmitting: false,
    ticketCode: '',
    submitting: false,
    lastResult: null,
    lastError: '',
    history: []
  },

  onLoad() {
    if (!this.data.available) {
      this.setData({
        connectionStatus: 'UNAVAILABLE',
        connectionLabel: '当前版本未启用核销入口',
        lastError: '工作人员核销入口须由部署配置显式启用。'
      })
      return
    }
    ensureStaffSession().then(session => this.applySession(session)).catch(error => {
      if (!isStaffSessionError(error)) this.setData({ lastError: describeVerificationError(error) })
    })
  },

  onUnload() {
    pendingStaffPassword = ''
  },

  usernameInput(e) {
    this.setData({ username: String(e.detail.value || '').trim(), lastError: '' })
  },

  passwordInput(e) {
    pendingStaffPassword = String(e.detail.value || '')
    this.setData({ hasPassword: Boolean(pendingStaffPassword), lastError: '' })
  },

  togglePassword() {
    this.setData({ passwordVisible: !this.data.passwordVisible })
  },

  submitStaffLogin() {
    if (!this.data.available || this.data.loginSubmitting) return
    const username = this.data.username
    const password = pendingStaffPassword
    this.setData({ loginSubmitting: true, connectionStatus: 'CONNECTING', connectionLabel: '正在验证工作人员身份', lastError: '' })
    loginStaff(username, password).then(session => {
      this.clearPasswordInput()
      this.applySession(session)
    }).catch(error => {
      clearStaffSession()
      this.clearPasswordInput()
      this.setData({
        loggedIn: false,
        loginSubmitting: false,
        connectionStatus: 'ERROR',
        connectionLabel: '工作人员登录失败',
        lastError: describeVerificationError(error)
      })
    })
  },

  applySession(session) {
    this.setData({
      loggedIn: true,
      loginSubmitting: false,
      connectionStatus: 'READY',
      connectionLabel: '核销服务已连接',
      operatorName: session.displayName || '现场核销员',
      lastError: ''
    })
  },

  clearPasswordInput() {
    pendingStaffPassword = ''
    this.setData({ hasPassword: false, passwordVisible: false, loginFormVisible: false })
    if (wx.nextTick) wx.nextTick(() => this.setData({ loginFormVisible: true }))
    else this.setData({ loginFormVisible: true })
  },

  exitStaffSession() {
    this.clearPasswordInput()
    this.setData({ submitting: true })
    logoutStaff().finally(() => this.setData({
      loggedIn: false,
      submitting: false,
      operatorName: '',
      ticketCode: '',
      lastResult: null,
      lastError: '',
      history: [],
      connectionStatus: 'SIGNED_OUT',
      connectionLabel: '已安全退出，请重新登录'
    }))
  },

  ticketCodeInput(e) {
    this.setData({ ticketCode: String(e.detail.value || '').toUpperCase(), lastError: '' })
  },

  scanAndConsume() {
    if (!this.canSubmit()) return
    if (!wx.scanCode) {
      this.setData({ lastError: '当前微信版本不支持扫码，请手动输入电子票码' })
      return
    }
    wx.scanCode({
      scanType: ['qrCode', 'barCode'],
      success: result => {
        const code = extractTicketCode(result.result)
        if (!code) {
          this.setData({ lastError: '二维码中未识别到电子票码' })
          return
        }
        this.setData({ ticketCode: code })
        this.executeVerification(code, '扫码核销')
      },
      fail: error => {
        if (error && /cancel/i.test(error.errMsg || '')) return
        this.setData({ lastError: '扫码失败，请重新扫描或手动输入票码' })
      }
    })
  },

  submitTicketCode() {
    this.executeVerification(this.data.ticketCode, '票码核销')
  },

  canSubmit() {
    if (!this.data.available || !this.data.loggedIn || this.data.connectionStatus !== 'READY') {
      wx.showToast({ title: '请先使用工作人员账号登录', icon: 'none' })
      return false
    }
    return !this.data.submitting
  },

  executeVerification(credential, methodLabel) {
    if (!this.canSubmit()) return
    const value = String(credential || '').trim()
    this.setData({ submitting: true, lastError: '', lastResult: null })
    wx.showLoading({ title: '正在核销', mask: true })
    consumeTicketCode(value, getDeviceNo()).then(ticket => {
      const result = { ...ticket, methodLabel, credentialLabel: maskCredential(value) }
      this.setData({
        submitting: false,
        lastResult: result,
        lastError: '',
        ticketCode: '',
        history: [historyEntry(true, result), ...this.data.history].slice(0, 20)
      })
      wx.hideLoading()
      wx.showToast({ title: '核销成功', icon: 'success' })
    }).catch(error => {
      const message = describeVerificationError(error)
      const sessionEnded = isStaffSessionError(error)
      const failed = {
        methodLabel,
        credentialLabel: maskCredential(value),
        errorMessage: message,
        verifiedAtLabel: currentTime()
      }
      this.setData({
        loggedIn: sessionEnded ? false : this.data.loggedIn,
        operatorName: sessionEnded ? '' : this.data.operatorName,
        connectionStatus: sessionEnded ? 'SIGNED_OUT' : this.data.connectionStatus,
        connectionLabel: sessionEnded ? '登录已过期，请重新登录' : this.data.connectionLabel,
        submitting: false,
        lastError: message,
        history: [historyEntry(false, failed), ...this.data.history].slice(0, 20)
      })
      wx.hideLoading()
    })
  },

  clearResult() {
    this.setData({ lastResult: null, lastError: '' })
  },

  goBack() {
    wx.navigateBack()
  }
})

function historyEntry(success, data) {
  return {
    id: `${Date.now()}-${Math.random().toString(16).slice(2)}`,
    success,
    statusLabel: success ? '核销成功' : '核销失败',
    methodLabel: data.methodLabel,
    credentialLabel: data.credentialLabel,
    resourceName: data.resourceName || '',
    orderNo: data.orderNo || '',
    errorMessage: data.errorMessage || '',
    verifiedAtLabel: data.verifiedAtLabel || currentTime()
  }
}

function maskCredential(value) {
  const text = String(value || '').trim().toUpperCase()
  if (!text) return '未输入'
  if (text.length <= 10) return text
  return `${text.slice(0, 6)}…${text.slice(-4)}`
}

function currentTime() {
  const date = new Date()
  return `${date.getFullYear()}-${String(date.getMonth() + 1).padStart(2, '0')}-${String(date.getDate()).padStart(2, '0')} ${String(date.getHours()).padStart(2, '0')}:${String(date.getMinutes()).padStart(2, '0')}:${String(date.getSeconds()).padStart(2, '0')}`
}
