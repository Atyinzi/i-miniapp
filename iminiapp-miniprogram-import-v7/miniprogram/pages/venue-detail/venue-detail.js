const { PLACES } = require('../../config/location')
const { openLocation, markerFor } = require('../../services/locationService')
const { fetchLocationPlaces, mergeWithDefaults } = require('../../services/locationCatalogService')

const meetings = [
  {
    name: '小型洽谈室',
    capacity: 8,
    equipment: '投影仪 · 白板 · 茶水服务',
    price: '¥380 /半天',
    time: '09:00–18:00 可订',
    style: 'meeting-warm',
    mark: '洽'
  },
  {
    name: '商务会议室',
    capacity: 18,
    equipment: '投影仪 · 音响 · 麦克风 · 白板',
    price: '¥680 /半天',
    time: '09:00–18:00 可订',
    style: 'meeting-green',
    mark: '商'
  },
  {
    name: '中型报告厅',
    capacity: 40,
    equipment: '高清投影 · 专业音响 · 双麦克风',
    price: '¥1,280 /半天',
    time: '09:00–18:00 可订',
    style: 'meeting-blue',
    mark: '报'
  },
  {
    name: '多功能厅1',
    capacity: 80,
    equipment: 'LED屏 · 专业音响 · 麦克风 · 茶水服务',
    price: '¥1,800 /半天',
    time: '09:00–18:00 可订',
    style: 'meeting-red',
    mark: '多'
  }
]

Page({
  data: {
    statusBarHeight: 20,
    navHeight: 64,
    contentTop: 76,
    shareRight: 104,
    sharePanelVisible: false,
    venueLocation: PLACES.northHotel,
    venueLocationMarkers: markerFor(PLACES.northHotel, 1),
    activeMeetingFilter: 0,
    meetingFilters: [
      { label: '全部', min: 0, max: 9999 },
      { label: '10人内', min: 0, max: 10 },
      { label: '10–20人', min: 11, max: 20 },
      { label: '20–50人', min: 21, max: 50 },
      { label: '50人以上', min: 51, max: 9999 }
    ],
    meetings,
    visibleMeetings: meetings
  },

  onLoad() {
    const windowInfo = wx.getWindowInfo ? wx.getWindowInfo() : wx.getSystemInfoSync()
    const menuButton = wx.getMenuButtonBoundingClientRect()
    const statusBarHeight = windowInfo.statusBarHeight || 20
    const navBarHeight = (menuButton.top - statusBarHeight) * 2 + menuButton.height
    const navHeight = statusBarHeight + navBarHeight
    const shareRight = windowInfo.windowWidth - menuButton.left + 8
    this.setData({
      statusBarHeight,
      navHeight,
      contentTop: navHeight + 12,
      shareRight
    })
    wx.showShareMenu({
      menus: ['shareAppMessage', 'shareTimeline']
    })
    fetchLocationPlaces('VENUE').then(rows => {
      const place = mergeWithDefaults(rows).find(item => item.code === 'NORTH_HOTEL')
      if (place) this.setData({ venueLocation: place, venueLocationMarkers: markerFor(place, 1) })
    }).catch(() => {})
  },

  backHome() {
    wx.switchTab({ url: '/pages/index/index' })
  },

  openSharePanel() { this.setData({ sharePanelVisible: true }) },
  closeSharePanel() { this.setData({ sharePanelVisible: false }) },
  preventShareTap() {},
  changeMeetingFilter(e) {
    const activeMeetingFilter = Number(e.currentTarget.dataset.index)
    const filter = this.data.meetingFilters[activeMeetingFilter]
    const visibleMeetings = this.data.meetings.filter(
      item => item.capacity >= filter.min && item.capacity <= filter.max
    )
    this.setData({ activeMeetingFilter, visibleMeetings })
  },

  openMeeting(e) {
    const meeting = this.data.visibleMeetings[Number(e.currentTarget.dataset.index)]
    wx.showModal({
      title: meeting.name,
      content: `可容纳 ${meeting.capacity} 人\n${meeting.equipment}\n${meeting.price}\n${meeting.time}`,
      confirmText: '预约咨询',
      success: result => {
        if (result.confirm) {
          wx.navigateTo({ url: '/pages/booking/booking?type=hotel' })
        }
      }
    })
  },

  toBooking() {
    wx.navigateTo({ url: '/pages/booking/booking?type=hotel' })
  },

  navigateToVenue() {
    openLocation(this.data.venueLocation).catch(() => wx.showToast({ title: '暂时无法打开地图', icon: 'none' }))
  },

  onShareAppMessage() {
    return {
      title: '示例宾馆｜会议、住宿、餐饮一站式服务',
      path: '/pages/venue-detail/venue-detail'
    }
  },

  onShareTimeline() {
    return {
      title: '示例宾馆｜会议、住宿、餐饮一站式服务',
      query: ''
    }
  }
})
