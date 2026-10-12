const { getMemberProfile, updateMemberProfile } = require('../../services/memberService')
const { authorizePhone, fetchProfile, updateProfile } = require('../../services/wechatProfileService')
const { hasSession, resumeSession } = require('../../utils/session')

Page({
  data: { loggedIn: false, loginLoading: false, avatarUrl: '', avatarText: '未', nickname: '', mobile: '', phoneLoading: false },

  onLoad() {
    const loggedIn = hasSession()
    const member = getMemberProfile()
    this.setData({
      loggedIn,
      avatarUrl: loggedIn ? (member.avatarUrl || '') : '',
      avatarText: loggedIn ? String(member.nickname || '北').slice(0, 1) : '微',
      nickname: loggedIn ? (member.nickname || '') : '',
      mobile: member.mobile || ''
    })
    if (!loggedIn) return
    this.loadProfile(member)
  },

  loadProfile(member = getMemberProfile()) {
    fetchProfile().then(profile => {
      const updated = updateMemberProfile({
        nickname: profile.nickname || member.nickname,
        avatarUrl: profile.avatarUrl || member.avatarUrl,
        mobile: profile.mobile || member.mobile
      })
      this.setData({
        avatarUrl: updated.avatarUrl || '',
        avatarText: String(updated.nickname || '北').slice(0, 1),
        nickname: updated.nickname || '',
        mobile: updated.mobile || ''
      })
    }).catch(() => {})
  },

  authorizeWechatLogin() {
    if (this.data.loginLoading) return
    this.setData({ loginLoading: true })
    resumeSession({ displayName: '微信用户', avatarUrl: '' })
      .then(() => {
        const member = updateMemberProfile({ nickname: '微信用户', avatarUrl: '' })
        this.setData({
          loggedIn: true,
          loginLoading: false,
          nickname: member.nickname,
          avatarUrl: member.avatarUrl,
          avatarText: String(member.nickname || '微').slice(0, 1)
        })
        this.loadProfile(member)
        wx.showToast({ title: '登录成功，请绑定手机号', icon: 'success' })
      })
      .catch(error => {
        this.setData({ loginLoading: false })
        const detail = error && (error.errMsg || error.message)
        wx.showModal({
          title: '微信登录失败',
          content: detail || '未能完成微信登录，请稍后重试',
          showCancel: false,
          confirmText: '知道了'
        })
      })
  },

  chooseAvatar(e) {
    const avatarUrl = e.detail && e.detail.avatarUrl
    if (avatarUrl) this.setData({ avatarUrl })
  },

  nicknameInput(e) {
    this.setData({ nickname: e.detail.value })
  },

  authorizePhone(e) {
    if (!this.data.loggedIn) {
      wx.showToast({ title: '请先授权微信登录', icon: 'none' })
      return
    }
    const detail = e.detail || {}
    if (detail.errMsg !== 'getPhoneNumber:ok' || !detail.code) {
      wx.showToast({ title: '未授权手机号', icon: 'none' })
      return
    }
    this.setData({ phoneLoading: true })
    authorizePhone(detail.code).then(result => {
      this.setData({ mobile: result.purePhoneNumber || result.phoneNumber || '', phoneLoading: false })
      wx.showToast({ title: '手机号已授权', icon: 'success' })
    }).catch(error => {
      this.setData({ phoneLoading: false })
      const code = error.code ? `\n错误码：${error.code}` : ''
      const traceId = error.traceId ? `\n追踪编号：${error.traceId}` : ''
      wx.showModal({
        title: '手机号授权失败',
        content: `${error.message || '手机号授权失败'}${code}${traceId}`,
        showCancel: false,
        confirmText: '知道了'
      })
    })
  },

  saveProfile() {
    if (!this.data.loggedIn) {
      wx.showToast({ title: '请先授权微信登录', icon: 'none' })
      return
    }
    const nickname = String(this.data.nickname || '').trim()
    if (!nickname) {
      wx.showToast({ title: '请填写昵称', icon: 'none' })
      return
    }
    updateProfile({ nickname, avatarUrl: this.data.avatarUrl }).then(profile => {
      updateMemberProfile({
        nickname: profile.nickname,
        avatarUrl: profile.avatarUrl,
        mobile: profile.mobile || this.data.mobile
      })
      wx.showToast({ title: '资料已保存', icon: 'success' })
      setTimeout(() => wx.navigateBack(), 500)
    }).catch(error => {
      wx.showToast({ title: error.message || '保存失败', icon: 'none' })
    })
  }
})
