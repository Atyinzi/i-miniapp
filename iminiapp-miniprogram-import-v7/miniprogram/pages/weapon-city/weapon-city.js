const {
  fetchWeaponCitySpot,
  fetchWeaponCitySpotDetail,
  fetchWeaponCityTickets,
  createWeaponCityOrder
} = require('../../services/weaponCityService')
const { createClientRequestId } = require('../../utils/contract')
const { getMemberProfile } = require('../../services/memberService')
const { PLACES } = require('../../config/location')
const { openLocation, markerFor } = require('../../services/locationService')

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

function parseAttributes(value) {
  if (!value) return {}
  if (typeof value === 'object') return value
  try { return JSON.parse(value) } catch (error) { return {} }
}

function formatPrice(priceCent) {
  const value = Number(priceCent || 0) / 100
  return Number.isInteger(value) ? String(value) : value.toFixed(2)
}

function buildCatalog(rows) {
  const tickets = []
  const projectMap = new Map()
  ;(rows || []).forEach(row => {
    const attributes = parseAttributes(row.attributes)
    if (attributes.productKind === 'EXPERIENCE') {
      const key = String(row.skuId)
      if (!projectMap.has(key)) {
        projectMap.set(key, {
          id: key,
          skuId: row.skuId,
          skuCode: row.skuCode,
          name: row.name,
          price: formatPrice(row.priceCent),
          priceCent: row.priceCent,
          unit: '元/人/次',
          duration: attributes.duration || '按预约时段体验',
          audience: attributes.audience || row.audienceRule || '按现场安全要求参与',
          description: attributes.description || row.usageRule || '',
          image: attributes.image || '/assets/images/weapon-city/military-fitness.jpg',
          timeSlots: []
        })
      }
      if (row.timeSlot) {
        projectMap.get(key).timeSlots.push({
          label: row.timeSlot,
          available: Boolean(row.available),
          availableQuantity: Number(row.availableQuantity || 0)
        })
      }
      return
    }
    if (row.timeSlot) return
    tickets.push({
      id: String(row.skuId),
      skuId: row.skuId,
      skuCode: row.skuCode,
      name: row.name,
      price: formatPrice(row.priceCent),
      priceCent: row.priceCent,
      unit: attributes.unit === 'SET' ? '元/套' : '元/张',
      audience: row.audienceRule || '适用规则以现场公示为准',
      rule: row.usageRule || '仅限预约日期使用',
      badge: attributes.badge || '门票',
      maxPerOrder: Number(row.maxPerOrder || 1),
      available: Boolean(row.available),
      availableQuantity: Number(row.availableQuantity || 0)
    })
  })
  const projects = Array.from(projectMap.values()).map(project => ({
    ...project,
    available: project.timeSlots.some(slot => slot.available),
    availableQuantity: project.timeSlots.reduce((sum, slot) => sum + slot.availableQuantity, 0)
  }))
  return { tickets, projects }
}

function visitor(name, idCard, phone) {
  return { name: name.trim(), mobile: phone.trim(), idType: 'ID_CARD', idNo: idCard.trim() }
}

Page({
  data: {
    statusBarHeight: 20, navHeight: 64, contentTop: 76, shareRight: 104, sharePanelVisible: false,
    activeModule: 'tickets', dates: [], activeDate: '', scenicSpotId: null,
    venueLocation: PLACES.weaponCity, venueLocationMarkers: markerFor(PLACES.weaponCity, 1),
    catalogLoading: true, catalogError: '', tickets: [], projects: [],
    ticketOrderVisible: false, selectedTicket: null, ticketQuantity: 1,
    visitorName: '', visitorIdCard: '', visitorPhone: '', ticketSubmitting: false,
    ticketResultVisible: false, ticketResult: null,
    experienceOrderVisible: false, selectedProject: null, timeSlots: [], timeSlotIndex: 0,
    experienceName: '', experienceIdCard: '', experiencePhone: '', experienceSubmitting: false,
    experienceResultVisible: false, experienceResult: null,
    projectPreviewVisible: false, projectPreviewTitle: '', projectPreviewImage: '', memberBenefit: getMemberProfile()
  },

  onShow() { this.setData({ memberBenefit: getMemberProfile() }) },

  onLoad() {
    const windowInfo = wx.getWindowInfo ? wx.getWindowInfo() : wx.getSystemInfoSync()
    const menuButton = wx.getMenuButtonBoundingClientRect()
    const statusBarHeight = windowInfo.statusBarHeight || 20
    const navBarHeight = (menuButton.top - statusBarHeight) * 2 + menuButton.height
    const navHeight = statusBarHeight + navBarHeight
    const dates = buildDates()
    this.setData({
      statusBarHeight, navHeight, contentTop: navHeight + 12,
      shareRight: windowInfo.windowWidth - menuButton.left + 8,
      dates, activeDate: dates[0].value
    })
    wx.showShareMenu({ menus: ['shareAppMessage', 'shareTimeline'] })
    this.loadCatalog(dates[0].value)
  },

  async loadCatalog(serviceDate) {
    this.setData({ catalogLoading: true, catalogError: '' })
    try {
      let scenicSpotId = this.data.scenicSpotId
      if (!scenicSpotId) {
        const page = await fetchWeaponCitySpot()
        const spot = (page.items || []).find(item => item.name === '示例景区') || (page.items || [])[0]
        if (!spot) throw new Error('示例景区尚未配置')
        scenicSpotId = spot.id
        const detail = await fetchWeaponCitySpotDetail(scenicSpotId)
        const locationSource = detail || spot
        const hasCoordinates = locationSource.latitude != null && locationSource.longitude != null
        const venueLocation = {
          ...PLACES.weaponCity,
          name: locationSource.name || PLACES.weaponCity.name,
          address: locationSource.address || PLACES.weaponCity.address,
          latitude: hasCoordinates ? Number(locationSource.latitude) : null,
          longitude: hasCoordinates ? Number(locationSource.longitude) : null,
          configured: hasCoordinates
        }
        this.setData({ venueLocation, venueLocationMarkers: markerFor(venueLocation, 1) })
      }
      const rows = await fetchWeaponCityTickets(scenicSpotId, serviceDate)
      const catalog = buildCatalog(rows)
      this.setData({ scenicSpotId, ...catalog, catalogLoading: false })
    } catch (error) {
      this.setData({ catalogLoading: false, catalogError: error.message || '商品加载失败', tickets: [], projects: [] })
    }
  },

  backHome() { wx.switchTab({ url: '/pages/index/index' }) },
  openSharePanel() { this.setData({ sharePanelVisible: true }) },
  closeSharePanel() { this.setData({ sharePanelVisible: false }) },
  preventShareTap() {},
  switchModule(e) { this.setData({ activeModule: e.currentTarget.dataset.module }) },
  selectDate(e) {
    const activeDate = e.currentTarget.dataset.date
    this.setData({ activeDate })
    this.loadCatalog(activeDate)
  },
  retryCatalog() { this.loadCatalog(this.data.activeDate) },
  navigateToVenue() { openLocation(this.data.venueLocation).catch(() => wx.showToast({ title: '暂时无法打开地图', icon: 'none' })) },
  openTicketOrder(e) {
    const selectedTicket = this.data.tickets.find(item => item.id === e.currentTarget.dataset.id)
    if (!selectedTicket || !selectedTicket.available) return
    this.setData({ selectedTicket, ticketQuantity: 1, ticketOrderVisible: true })
  },
  closeTicketOrder() { this.setData({ ticketOrderVisible: false }) },
  adjustTicketQuantity(e) {
    const limit = Math.min(this.data.selectedTicket.maxPerOrder, this.data.selectedTicket.availableQuantity)
    const quantity = Math.max(1, Math.min(limit, this.data.ticketQuantity + Number(e.currentTarget.dataset.delta)))
    this.setData({ ticketQuantity: quantity })
  },
  ticketFieldInput(e) { this.setData({ [e.currentTarget.dataset.field]: e.detail.value }) },
  validateVisitor(name, idCard, phone) {
    if (!name.trim()) { wx.showToast({ title: '请填写入园人姓名', icon: 'none' }); return false }
    if (idCard.trim().length < 6) { wx.showToast({ title: '请填写有效身份证号', icon: 'none' }); return false }
    if (!/^1\d{10}$/.test(phone.trim())) { wx.showToast({ title: '请填写正确手机号', icon: 'none' }); return false }
    return true
  },
  submitTicketOrder() {
    if (!this.validateVisitor(this.data.visitorName, this.data.visitorIdCard, this.data.visitorPhone)) return
    const itemVisitor = visitor(this.data.visitorName, this.data.visitorIdCard, this.data.visitorPhone)
    this.setData({ ticketSubmitting: true })
    createWeaponCityOrder({
      clientRequestId: createClientRequestId('weapon_ticket'),
      items: [{
        skuId: this.data.selectedTicket.skuId,
        quantity: this.data.ticketQuantity,
        serviceDate: this.data.activeDate,
        timeSlot: '',
        businessData: { visitors: Array.from({ length: this.data.ticketQuantity }, () => ({ ...itemVisitor })) }
      }],
      remark: '示例景区小程序门票订单'
    }).then(result => {
      this.setData({ ticketSubmitting: false, ticketOrderVisible: false, ticketResultVisible: true, ticketResult: result })
      this.loadCatalog(this.data.activeDate)
    }).catch(() => this.setData({ ticketSubmitting: false }))
  },
  closeTicketResult() { this.setData({ ticketResultVisible: false }) },
  goToOrders() {
    const result = this.data.ticketResultVisible ? this.data.ticketResult : this.data.experienceResult
    wx.setStorageSync('iminiappOrderNavigationIntent', {
      status: 'PENDING_PAYMENT',
      orderId: result && String(result.id)
    })
    wx.switchTab({ url: '/pages/mine/mine' })
  },
  openExperienceOrder(e) {
    const selectedProject = this.data.projects.find(item => item.id === e.currentTarget.dataset.id)
    if (!selectedProject) return
    const timeSlots = selectedProject.timeSlots.filter(slot => slot.available).map(slot => slot.label)
    if (!timeSlots.length) { wx.showToast({ title: '当前日期已约满', icon: 'none' }); return }
    this.setData({ selectedProject, experienceOrderVisible: true, timeSlots, timeSlotIndex: 0 })
  },
  closeExperienceOrder() { this.setData({ experienceOrderVisible: false }) },
  timeSlotChange(e) { this.setData({ timeSlotIndex: Number(e.detail.value) }) },
  experienceFieldInput(e) { this.setData({ [e.currentTarget.dataset.field]: e.detail.value }) },
  submitExperienceOrder() {
    if (!this.validateVisitor(this.data.experienceName, this.data.experienceIdCard, this.data.experiencePhone)) return
    const selectedTimeSlot = this.data.timeSlots[this.data.timeSlotIndex]
    this.setData({ experienceSubmitting: true })
    createWeaponCityOrder({
      clientRequestId: createClientRequestId('weapon_experience'),
      items: [{
        skuId: this.data.selectedProject.skuId,
        quantity: 1,
        serviceDate: this.data.activeDate,
        timeSlot: selectedTimeSlot,
        businessData: { visitors: [visitor(this.data.experienceName, this.data.experienceIdCard, this.data.experiencePhone)] }
      }],
      remark: '示例景区小程序军事体验预约'
    }).then(result => {
      this.setData({
        experienceSubmitting: false, experienceOrderVisible: false, experienceResultVisible: true,
        experienceResult: { ...result, serviceDate: this.data.activeDate, timeSlot: selectedTimeSlot }
      })
      this.loadCatalog(this.data.activeDate)
    }).catch(() => this.setData({ experienceSubmitting: false }))
  },
  closeExperienceResult() { this.setData({ experienceResultVisible: false }) },
  previewProjectImage(e) {
    const project = this.data.projects.find(item => item.id === e.currentTarget.dataset.id)
    if (!project) return
    this.setData({ projectPreviewVisible: true, projectPreviewTitle: project.name, projectPreviewImage: project.image })
  },
  closeProjectPreview() { this.setData({ projectPreviewVisible: false, projectPreviewTitle: '', projectPreviewImage: '' }) },
  stopTap() {},
  onShareAppMessage() { return { title: '示例景区｜门票与军事体验预约', path: '/pages/weapon-city/weapon-city' } },
  onShareTimeline() { return { title: '示例景区｜门票与军事体验预约', query: '' } }
})
