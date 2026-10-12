const { getHotels, getHotelRoomTypes } = require('../../services/hotelService')
const { fenToYuan } = require('../../utils/contract')

const meetings = []

function toDateValue(date) {
  const year = date.getFullYear()
  const month = String(date.getMonth() + 1).padStart(2, '0')
  const day = String(date.getDate()).padStart(2, '0')
  return `${year}-${month}-${day}`
}

function toDateLabel(value) {
  const [, month, day] = value.split('-')
  return `${Number(month)}月${Number(day)}日`
}

function nextDate(value) {
  const [year, month, day] = value.split('-').map(Number)
  const date = new Date(year, month - 1, day)
  date.setDate(date.getDate() + 1)
  return toDateValue(date)
}

Page({
  data: {
    statusBarHeight: 20,
    navHeight: 64,
    contentTop: 76,
    shareRight: 104,
    sharePanelVisible: false,
    isHoushan: false,
    venueName: '示例宾馆',
    heroLabel: 'NORTH HOTEL · SINCE 1953',
    heroDescription: '藏在工业记忆里的温暖居所',
    introTitle: '示例宾馆',
    introDescription: 'i某小程序指定接待酒店 · 距兵器城 800 米',
    houshanImage: '/assets/images/houshan/mountain-twin-room.jpg',
    activeMeetingFilter: 0,
    meetingPreviewVisible: false,
    meetingPreviewTitle: '',
    meetingPreviewImages: [],
    meetingPreviewCurrent: '',
    minDate: '',
    checkOutMin: '',
    checkInDate: '',
    checkOutDate: '',
    checkInLabel: '',
    checkOutLabel: '',
    meetingFilters: [
      { label: '全部', min: 0, max: 9999 },
      { label: '10人内', min: 0, max: 10 },
      { label: '10-20人', min: 11, max: 20 },
      { label: '20-50人', min: 21, max: 50 },
      { label: '50人以上', min: 51, max: 9999 }
    ],
    meetings,
    visibleMeetings: meetings,
    rooms: [],
    roomsLoading: false,
    roomsError: ''
  },

  onLoad(options = {}) {
    const isHoushan = options.area === 'houshan'
    const windowInfo = wx.getWindowInfo ? wx.getWindowInfo() : wx.getSystemInfoSync()
    const menuButton = wx.getMenuButtonBoundingClientRect()
    const statusBarHeight = windowInfo.statusBarHeight || 20
    const navBarHeight = (menuButton.top - statusBarHeight) * 2 + menuButton.height
    const navHeight = statusBarHeight + navBarHeight
    const checkInDate = toDateValue(new Date())
    const checkOutDate = nextDate(checkInDate)
    this.setData({
      isHoushan,
      venueName: isHoushan ? '后山住宿' : '示例宾馆',
      heroLabel: isHoushan ? 'BACK MOUNTAIN RETREAT' : 'NORTH HOTEL · SINCE 1953',
      heroDescription: isHoushan ? '静谧山居，舒适住宿' : '藏在工业记忆里的温暖居所',
      introTitle: isHoushan ? 'i某小程序后山专属住宿' : '示例宾馆',
      introDescription: isHoushan
        ? '住宿 · 会议 · 餐饮预订一站式服务'
        : 'i某小程序指定接待酒店 · 距兵器城 800 米',
      statusBarHeight,
      navHeight,
      contentTop: navHeight + 12,
      shareRight: windowInfo.windowWidth - menuButton.left + 8,
      minDate: checkInDate,
      checkOutMin: checkOutDate,
      checkInDate,
      checkOutDate,
      checkInLabel: toDateLabel(checkInDate),
      checkOutLabel: toDateLabel(checkOutDate)
    })
    wx.showShareMenu({ menus: ['shareAppMessage', 'shareTimeline'] })
    this.loadHotelRooms()
  },

  async loadHotelRooms() {
    this.setData({ roomsLoading: true, roomsError: '', rooms: [] })
    try {
      const hotels = await getHotels()
      const hotel = (hotels || []).find(item => this.data.isHoushan
        ? /后山|山居/.test(item.name || '')
        : /示例宾馆/.test(item.name || '')) || (!this.data.isHoushan && hotels && hotels[0])
      if (!hotel) {
        this.setData({ roomsLoading: false, roomsError: '暂无已发布的住宿信息' })
        return
      }
      const roomTypes = await getHotelRoomTypes(
        hotel.id,
        this.data.checkInDate,
        this.data.checkOutDate,
        1
      )
      const rooms = (roomTypes || []).map((room, index) => ({
        id: room.id,
        hotelId: hotel.id,
        skuId: room.skuId,
        name: room.name,
        bed: room.bedType || '床型待确认',
        size: room.areaSqm == null ? '--' : String(room.areaSqm),
        price: room.nightlyFromPriceCent == null ? null : Number(fenToYuan(room.nightlyFromPriceCent)),
        status: Number(room.minimumAvailableQuantity || 0) > 0 ? '可订' : '满房',
        remaining: room.minimumAvailableQuantity == null ? '--' : String(room.minimumAvailableQuantity),
        images: (room.images || []).map(item => item.imageUrl).filter(Boolean)
          .concat(room.coverUrl ? [room.coverUrl] : [])
          .filter((value, imageIndex, values) => values.indexOf(value) === imageIndex)
      }))
      this.setData({
        venueName: hotel.name || this.data.venueName,
        introTitle: hotel.name || this.data.introTitle,
        introDescription: hotel.description || this.data.introDescription,
        rooms,
        roomsLoading: false,
        roomsError: rooms.length ? '' : '所选日期暂无可订房型'
      })
    } catch (error) {
      this.setData({ rooms: [], roomsLoading: false, roomsError: '房型数据暂不可用，请稍后重试' })
    }
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

  previewHoushanImage() {
    const image = this.data.houshanImage
    if (!image) {
      wx.showToast({ title: '后山住宿图片暂不可用', icon: 'none' })
      return
    }
    this.setData({
      meetingPreviewVisible: true,
      meetingPreviewTitle: '后山住宿',
      meetingPreviewImages: [image],
      meetingPreviewCurrent: image
    })
  },

  openMeeting(e) {
    const meeting = this.data.visibleMeetings[Number(e.currentTarget.dataset.index)]
    wx.showModal({
      title: meeting.name,
      content: `可容纳 ${meeting.capacity} 人\n${meeting.equipment}\n${meeting.price}\n${meeting.time}`,
      confirmText: '预约咨询',
      success: result => {
        if (result.confirm) {
          const area = this.data.isHoushan ? '&area=houshan' : ''
          wx.navigateTo({ url: `/pages/booking/booking?type=hotel${area}` })
        }
      }
    })
  },

  previewMeetingImages(e) {
    const index = Number(e.currentTarget.dataset.index)
    const meeting = this.data.visibleMeetings[index]
    if (!meeting || !meeting.images || !meeting.images.length) {
      wx.showToast({ title: '图片暂不可用', icon: 'none' })
      return
    }
    this.setData({
      meetingPreviewVisible: true,
      meetingPreviewTitle: meeting.name,
      meetingPreviewImages: meeting.images,
      meetingPreviewCurrent: e.currentTarget.dataset.url || meeting.images[0]
    })
  },

  switchMeetingPreview(e) {
    this.setData({ meetingPreviewCurrent: e.currentTarget.dataset.url })
  },

  closeMeetingPreview() {
    this.setData({
      meetingPreviewVisible: false,
      meetingPreviewImages: [],
      meetingPreviewCurrent: ''
    })
  },

  preventTap() {
  },

  checkInDateChange(e) {
    const checkInDate = e.detail.value
    const checkOutDate = checkInDate >= this.data.checkOutDate
      ? nextDate(checkInDate)
      : this.data.checkOutDate
    this.setData({
      checkInDate,
      checkOutDate,
      checkOutMin: nextDate(checkInDate),
      checkInLabel: toDateLabel(checkInDate),
      checkOutLabel: toDateLabel(checkOutDate)
    }, () => this.loadHotelRooms())
  },

  checkOutDateChange(e) {
    const checkOutDate = e.detail.value
    if (checkOutDate <= this.data.checkInDate) {
      wx.showToast({ title: '离店日期须晚于入住日期', icon: 'none' })
      return
    }
    this.setData({
      checkOutDate,
      checkOutLabel: toDateLabel(checkOutDate)
    }, () => this.loadHotelRooms())
  },

  previewRoomImages(e) {
    const room = this.data.rooms[Number(e.currentTarget.dataset.index)]
    if (!room || !room.images || !room.images.length) {
      wx.showToast({ title: '房型图片暂不可用', icon: 'none' })
      return
    }
    this.setData({
      meetingPreviewVisible: true,
      meetingPreviewTitle: room.name,
      meetingPreviewImages: room.images,
      meetingPreviewCurrent: e.currentTarget.dataset.url || room.images[0]
    })
  },

  bookRoom(e) {
    const room = this.data.rooms[Number(e.currentTarget.dataset.index)]
    if (!room) {
      wx.showToast({ title: '房型信息暂不可用', icon: 'none' })
      return
    }
    if (!room.id) {
      wx.showToast({ title: '房型数据尚未从后端加载', icon: 'none' })
      return
    }
    if (room.status !== '可订') {
      wx.showToast({ title: '所选日期该房型暂无库存', icon: 'none' })
      return
    }
    wx.navigateTo({
      url: `/pages/booking/booking?type=hotel&area=${this.data.isHoushan ? 'houshan' : 'hotel'}&hotelId=${room.hotelId}&roomTypeId=${room.id}&room=${encodeURIComponent(room.name)}&price=${room.price || ''}&checkIn=${this.data.checkInDate}&checkOut=${this.data.checkOutDate}`
    })
  },

  openDiningReservation() {
    const area = this.data.isHoushan ? '&area=houshan' : '&area=hotel'
    wx.navigateTo({ url: `/pages/dining/dining?tab=reservation${area}` })
  },

  openDiningDelivery() {
    if (this.data.isHoushan) {
      wx.showToast({ title: '后山住宿暂不提供餐饮配送', icon: 'none' })
      return
    }
    wx.navigateTo({ url: '/pages/dining/dining?tab=delivery&area=hotel' })
  },

  onShareAppMessage() {
    return {
      title: `${this.data.venueName}｜会议、住宿、餐饮预订一站式服务`,
      path: `/pages/venues/venues${this.data.isHoushan ? '?area=houshan' : ''}`
    }
  },

  onShareTimeline() {
    return {
      title: `${this.data.venueName}｜会议、住宿、餐饮预订一站式服务`,
      query: this.data.isHoushan ? 'area=houshan' : ''
    }
  }
})
