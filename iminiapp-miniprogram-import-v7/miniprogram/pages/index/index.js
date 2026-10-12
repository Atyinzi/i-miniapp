const { getHome } = require('../../services/homeService')
const { hasSession } = require('../../utils/session')

Page({
  data: { keyword: '', unread: true, activeQuick: -1, activeCore: -1, currentSlide: 0, bannerCount: [0, 1, 2, 3], noticeIndex: 0, notices: [], allServicesVisible: false, allServices: [
    { name: '客房预订', desc: '示例宾馆住宿', icon: '⌂', color: 'sand', url: '/pages/venues/venues' },
    { name: '餐饮服务', desc: '预订与场内配送', icon: '♨', color: 'red', url: '/pages/dining/dining' },
    { name: '示例景区', desc: '门票与军事体验', icon: '⚑', color: 'green', url: '/pages/weapon-city/weapon-city' },
    { name: '车辆预约', desc: '多车型用车服务', icon: '▰', color: 'blue', url: '/pages/booking/booking' },
    { name: '休闲场馆', desc: '浴都与体育馆', icon: '◌', color: 'orange', url: '/pages/leisure/leisure' },
    { name: '后山住宿', desc: '山居住宿体验', icon: '山', color: 'teal', url: '/pages/venues/venues?area=houshan' },
    { name: '特色商城', desc: 'i某小程序文创好物', icon: '✦', color: 'gold', url: '/pages/mall/mall' }
  ], coreItems: [
    { name: '示例宾馆', desc: '预约入住', image: '/assets/images/core-icons/hotel.svg', color: 'sand', url: '/pages/venues/venues' },
    { name: '示例景区', desc: '文化参观', image: '/assets/images/core-icons/weapon-city.svg', color: 'red', url: '/pages/weapon-city/weapon-city' },
    { name: '车队预约', desc: '安心出行', image: '/assets/images/core-icons/fleet.svg', color: 'blue', url: '/pages/booking/booking' },
    { name: '休闲场馆', desc: '浴都 · 体育馆', image: '/assets/images/core-icons/leisure.svg', color: 'green', url: '/pages/leisure/leisure' },
    { name: '后山住宿', desc: '山居体验', image: '/assets/images/core-icons/houshan.svg', color: 'gold', url: '/pages/venues/venues?area=houshan' }
  ], quickItems: [
    { name: '客房', desc: '安心入住', icon: '⌂', color: 'sand', url: '/pages/venues/venues' }, { name: '餐饮', desc: '两地餐饮', icon: '♨', color: 'red', url: '/pages/dining/dining' }, { name: '景区', desc: '文化探索', icon: '⚑', color: 'green', url: '/pages/weapon-city/weapon-city' }, { name: '出行', desc: '便捷出行', icon: '▰', color: 'blue', url: '/pages/booking/booking' }, { name: '商城', desc: 'i某小程序好物', icon: '✦', color: 'gold', url: '/pages/mall/mall' }
  ] },
  async onLoad() {
    await this.loadHome()
  },
  onShow() {
    if (hasSession() && this.homeFailed) this.loadHome()
  },
  async loadHome() {
    try {
      const data = await getHome()
      const quickItems = (data.quickItems || []).map(item => item.name === '餐饮'
        ? { ...item, desc: '两地餐饮', url: '/pages/dining/dining' }
        : item)
      this.setData({
        notices: data.notices && data.notices.length ? data.notices : ['暂无最新公告'],
        quickItems,
        bannerCount: data.banners && data.banners.length ? data.banners.map((_, index) => index) : this.data.bannerCount
      })
      this.homeFailed = false
    } catch (error) {
      this.homeFailed = true
      this.setData({ notices: [error.code === 'LOGIN_REQUIRED' ? '登录后可加载最新服务信息' : '服务连接失败，请稍后下拉重试'] })
    }
  },
  navigateService(url){const path=String(url||'').split('?')[0];if(['/pages/experience/experience','/pages/mall/mall','/pages/mine/mine','/pages/index/index'].includes(path))wx.switchTab({url:path});else wx.navigateTo({url})},
  showAllServices(){this.setData({allServicesVisible:true})},
  closeAllServices(){this.setData({allServicesVisible:false})},
  allServiceTap(e){const url=e.currentTarget.dataset.url;this.setData({allServicesVisible:false},()=>this.navigateService(url))},
  preventServicePanelTap(){},
  quickPress(e){this.setData({activeQuick:e.currentTarget.dataset.index})}, quickRelease(){this.setData({activeQuick:-1})}, quickTap(e) {this.navigateService(e.currentTarget.dataset.url)}, corePress(e){this.setData({activeCore:e.currentTarget.dataset.index})}, coreRelease(){this.setData({activeCore:-1})}, coreTap(e){this.navigateService(e.currentTarget.dataset.url)}, goVenue() { wx.navigateTo({ url: '/pages/venues/venues' }) }, goWeaponCity(){wx.navigateTo({url:'/pages/weapon-city/weapon-city'})}, goMall() { wx.switchTab({ url: '/pages/mall/mall' }) }, goHoushan(){wx.navigateTo({url:'/pages/venues/venues?area=houshan'})}, goMine(){wx.switchTab({url:'/pages/mine/mine'})}, goHome(){wx.pageScrollTo({scrollTop:0,duration:300})}, keywordInput(e){this.setData({keyword:e.detail.value})}, search(){const keyword=this.data.keyword.trim();wx.showToast({title:keyword ? `正在搜索：${keyword}` : '请输入搜索内容',icon:'none'})}, messageTap(){this.setData({unread:false});wx.showToast({title:'暂无新的消息',icon:'none'})}, chooseSlide(e){this.setData({currentSlide:e.currentTarget.dataset.index})}, slideChange(e){this.setData({currentSlide:e.detail.current})}, noticeChange(e){this.setData({noticeIndex:e.detail.current})}, nextNotice(){if(!this.data.notices.length)return;this.setData({noticeIndex:(this.data.noticeIndex+1)%this.data.notices.length})}, openInformation(){wx.navigateTo({url:'/pages/notices/notices'})}, openNotice(){if(this.data.notices.length)wx.navigateTo({url:`/pages/notices/notices?index=${this.data.noticeIndex}`})}
})
