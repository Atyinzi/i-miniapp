const { fetchLeisureVenues, fetchLeisureAvailability, createLeisureBooking } = require('../../services/leisureService')
const { createClientRequestId } = require('../../utils/contract')
const { getMemberProfile } = require('../../services/memberService')
const { PLACES } = require('../../config/location')
const { openLocation, markerFor } = require('../../services/locationService')
const { fetchLocationPlaces, mergeWithDefaults } = require('../../services/locationCatalogService')

function dateValue(date) {
  return `${date.getFullYear()}-${String(date.getMonth() + 1).padStart(2, '0')}-${String(date.getDate()).padStart(2, '0')}`
}

function buildDates() {
  const weekdays = ['周日', '周一', '周二', '周三', '周四', '周五', '周六']
  return Array.from({ length: 7 }, (_, index) => {
    const date = new Date()
    date.setDate(date.getDate() + index)
    return {
      value: dateValue(date),
      day: `${date.getMonth() + 1}.${date.getDate()}`,
      week: index === 0 ? '今天' : weekdays[date.getDay()]
    }
  })
}

const venuePresentation = [
  {
    id: 'bath', venueCode: 'IMINIAPP_BATH',
    name: '浴都门票',
    shortName: '浴都',
    symbol: '浴',
    theme: 'bath',
    price: '58',
    unit: '元/次',
    audience: '适用人群不限',
    description: '基础洗浴服务，放松身心，感受i某小程序专属休闲体验。',
    rules: ['当日有效', '单次入场', '包含基础洗浴服务'],
    notice: '需携带本人有效身份证，禁止携带易燃易爆物品。',
    image: '/assets/images/leisure/swimming-pool.jpg',
    imageAlt: '游泳馆室内泳池实景',
    slots: []
  },
  {
    id: 'sports', venueCode: 'IMINIAPP_SPORTS',
    name: '体育馆门票',
    shortName: '体育馆',
    symbol: '体',
    theme: 'sports',
    price: '80',
    unit: '元/场',
    audience: '适用人群不限',
    description: '篮球、羽毛球等指定运动场地，分时段预约入场。',
    rules: ['当日有效', '单次入场', '可使用指定场地'],
    notice: '请遵守体育馆管理规定，并穿着运动鞋入场。',
    image: '/assets/images/leisure/sports-arena.jpg',
    imageAlt: '体育馆室内篮球场实景',
    slots: []
  }
]

Page({
  data: {
    statusBarHeight: 20,
    navHeight: 64,
    contentTop: 76,
    shareRight: 104,
    sharePanelVisible: false,
    dates: [],
    activeDate: '',
    tickets: [],
    venuePreviewVisible: false,
    venuePreviewTitle: '',
    venuePreviewImages: [],
    venuePreviewCurrent: '',
    orderVisible: false,
    selectedTicket: null,
    slotIndex: 0,
    quantity: 1,
    buyerName: '',
    buyerPhone: '',
    submitting: false,
    resultVisible: false,
    orderResult: null,
    memberBenefit: getMemberProfile()
  },

  onShow() {
    this.setData({ memberBenefit: getMemberProfile() })
  },

  async onLoad() {
    const windowInfo = wx.getWindowInfo ? wx.getWindowInfo() : wx.getSystemInfoSync()
    const menuButton = wx.getMenuButtonBoundingClientRect()
    const statusBarHeight = windowInfo.statusBarHeight || 20
    const navBarHeight = (menuButton.top - statusBarHeight) * 2 + menuButton.height
    const navHeight = statusBarHeight + navBarHeight
    const dates = buildDates()
    this.setData({
      statusBarHeight,
      navHeight,
      contentTop: navHeight + 12,
      shareRight: windowInfo.windowWidth - menuButton.left + 8,
      dates,
      activeDate: dates[0].value
    })
    await this.loadTickets(dates[0].value)
    wx.showShareMenu({ menus: ['shareAppMessage', 'shareTimeline'] })
  },

  async loadTickets(date) {
    try {
      const [venues, locationRows] = await Promise.all([fetchLeisureVenues(), fetchLocationPlaces('VENUE').catch(() => [])])
      const locationPlaces = mergeWithDefaults(locationRows)
      const tickets = await Promise.all((venues || []).map(async venue => {
        const presentation = venuePresentation.find(item => item.venueCode === venue.venueCode) || {}
        const court = (venue.courts || [])[0]
        const availability = court ? await fetchLeisureAvailability(court.id, date) : []
        const slotRecords = (availability || []).filter(item => item.available)
        const slots = slotRecords.map(item => `${String(item.startTime).slice(0, 5)}-${String(item.endTime).slice(0, 5)} · ¥${(Number(item.priceCent) / 100).toFixed(2)}`)
        const minimumPrice = slotRecords.length ? Math.min(...slotRecords.map(item => Number(item.priceCent))) : Number(court && court.basePriceCent || 0)
        const locationCode = venue.venueCode === 'IMINIAPP_BATH' ? 'BATH_VENUE' : 'SPORTS_VENUE'
        const location = locationPlaces.find(item => item.code === locationCode)
          || (venue.venueCode === 'IMINIAPP_BATH' ? PLACES.bathVenue : PLACES.sportsVenue)
        return {
          ...presentation,
          id: String(venue.id), legacyId: presentation.id || '', venueId: venue.id, venueCode: venue.venueCode,
          courtId: court && court.id, name: venue.name, description: venue.description || presentation.description || '',
          price: (minimumPrice / 100).toFixed(2).replace(/\.00$/, ''),
          unit: presentation.unit || '元/场', slots, slotRecords,
          location,
          locationMarkers: markerFor(location, Number(venue.id))
        }
      }))
      this.setData({ tickets })
    } catch (error) {
      this.setData({ tickets: [] })
    }
  },

  backHome() {
    wx.switchTab({ url: '/pages/index/index' })
  },

  openSharePanel() { this.setData({ sharePanelVisible: true }) },
  closeSharePanel() { this.setData({ sharePanelVisible: false }) },
  preventShareTap() {},
  async selectDate(e) {
    const activeDate = e.currentTarget.dataset.date
    this.setData({ activeDate })
    await this.loadTickets(activeDate)
  },

  previewVenue(e) {
    const current = e.currentTarget.dataset.src
    const ticket = this.data.tickets.find(item => item.image === current)
    if (!ticket || !current) {
      wx.showToast({ title: '场馆图片暂不可用', icon: 'none' })
      return
    }
    this.setData({
      venuePreviewVisible: true,
      venuePreviewTitle: ticket.name,
      venuePreviewImages: this.data.tickets.map(item => item.image),
      venuePreviewCurrent: current
    })
  },

  navigateToVenue(e) {
    const ticket = this.data.tickets.find(item => item.venueCode === e.currentTarget.dataset.code)
    if (ticket) openLocation(ticket.location).catch(() => wx.showToast({ title: '暂时无法打开地图', icon: 'none' }))
  },

  switchVenuePreview(e) {
    this.setData({
      venuePreviewCurrent: e.currentTarget.dataset.src,
      venuePreviewTitle: e.currentTarget.dataset.title
    })
  },

  closeVenuePreview() {
    this.setData({
      venuePreviewVisible: false,
      venuePreviewTitle: '',
      venuePreviewImages: [],
      venuePreviewCurrent: ''
    })
  },

  openOrder(e) {
    const selectedTicket = this.data.tickets.find(item => item.id === String(e.currentTarget.dataset.id))
    if (!selectedTicket) return
    if (!selectedTicket.slotRecords.length) {
      wx.showToast({ title: '所选日期暂无可订时段', icon: 'none' })
      return
    }
    this.setData({
      selectedTicket,
      slotIndex: 0,
      quantity: 1,
      orderVisible: true
    })
  },

  closeOrder() {
    if (this.data.submitting) return
    this.setData({ orderVisible: false })
  },

  slotChange(e) {
    this.setData({ slotIndex: Number(e.detail.value) })
  },

  adjustQuantity(e) {
    this.setData({ quantity: 1 })
  },

  fieldInput(e) {
    this.setData({ [e.currentTarget.dataset.field]: e.detail.value })
  },

  validateBuyer() {
    if (!this.data.buyerName.trim()) {
      wx.showToast({ title: '请填写入场人姓名', icon: 'none' })
      return false
    }
    if (!/^1\d{10}$/.test(this.data.buyerPhone.trim())) {
      wx.showToast({ title: '请填写正确手机号', icon: 'none' })
      return false
    }
    return true
  },

  submitOrder() {
    if (!this.validateBuyer()) return
    const ticket = this.data.selectedTicket
    const slot = ticket.slotRecords[this.data.slotIndex]
    const admissionSlot = ticket.slots[this.data.slotIndex]
    this.setData({ submitting: true })
    createLeisureBooking({
      clientRequestId: createClientRequestId('leisure_booking'),
      courtId: ticket.courtId,
      bookingDate: this.data.activeDate,
      startTime: slot.startTime,
      endTime: slot.endTime,
      remark: `微信小程序休闲场馆预订；联系人：${this.data.buyerName.trim()}；电话：${this.data.buyerPhone.trim()}`
    }).then(async result => {
      await this.loadTickets(this.data.activeDate)
      this.setData({
        submitting: false,
        orderVisible: false,
        resultVisible: true,
        orderResult: {
          ...result,
          electronicTicketCode: result.orderNo,
          visitDate: result.bookingDate,
          admissionSlot,
          statusLabel: '待支付'
        }
      })
    }).catch(() => this.setData({ submitting: false }))
  },

  closeResult() {
    this.setData({ resultVisible: false })
  },

  viewOrders() {
    this.setData({ resultVisible: false })
    wx.switchTab({ url: '/pages/mine/mine' })
  },

  stopTap() {
  },

  onShareAppMessage() {
    return { title: 'i某小程序休闲场馆｜浴都与体育馆线上购票', path: '/pages/leisure/leisure' }
  },

  onShareTimeline() {
    return { title: 'i某小程序休闲场馆｜浴都与体育馆线上购票', query: '' }
  }
})
