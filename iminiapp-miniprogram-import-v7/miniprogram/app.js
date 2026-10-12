App({
  globalData: { cartCount: 0 },
  onLaunch() {
    // 登录必须由用户在授权页主动触发；启动时不再静默创建账号。
  },
  toast(title) { wx.showToast({ title, icon: 'none' }) }
})
