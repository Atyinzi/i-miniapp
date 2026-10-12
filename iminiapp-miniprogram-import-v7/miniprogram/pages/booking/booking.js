const {
  VEHICLE_STATUS_FLOW,
  createBooking,
  getVehicleModels,
  getVehicleBookingStatus,
  getVehicleSettlement,
  cancelVehicleBooking
} = require('../../services/bookingService')
const {
  getPurchasedOrders,
  savePurchasedOrder,
  updatePurchasedOrder
} = require('../../services/orderService')
const { createClientRequestId, formatDateTime, yuanToFen, fenToYuan } = require('../../utils/contract')
const { getMemberProfile, calculateMemberPrice, registerPendingMemberPoints } = require('../../services/memberService')
const { commonPlaces } = require('../../config/location')
const { chooseLocation, locationLabel, markerFor } = require('../../services/locationService')
const { fetchLocationPlaces, mergeWithDefaults } = require('../../services/locationCatalogService')
const { payAndConfirmOrder, payAndConfirmVehicleSupplement } = require('../../services/wechatPaymentService')

const VEHICLE_BOOKING_STORAGE_KEY = 'iminiappVehicleBookingProgress'

const PRICE_ITEMS = [
  { key: 'bareMonthly', label: '裸租价（月租价）', unit: '月' },
  { key: 'halfDay', label: '半天价', unit: '半天' },
  { key: 'fullDay', label: '全天价', unit: '全天' },
  { key: 'monthly', label: '月租价', unit: '月' },
  { key: 'overtime', label: '超时', unit: '小时' },
  { key: 'overMileage', label: '超公里', unit: '公里' }
]

const PLAN_ITEMS = [
  { key: 'halfDay', label: '半天用车' },
  { key: 'fullDay', label: '全天用车' },
  { key: 'monthly', label: '月租用车' },
  { key: 'bareMonthly', label: '裸租（月租）' }
]

const VEHICLE_TYPES = [
  { id: 'sedan', name: '小轿车', prices: { bareMonthly: 6500, halfDay: 280, fullDay: 550, monthly: 9800, overtime: 50, overMileage: 2 } },
  { id: 'gl8', name: '商务车（GL8）', prices: { bareMonthly: null, halfDay: 380, fullDay: 760, monthly: 12000, overtime: 50, overMileage: 2 } },
  { id: 'prado', name: '越野车（PRADO）', prices: { bareMonthly: null, halfDay: 400, fullDay: 800, monthly: null, overtime: 50, overMileage: 3 } },
  { id: 'jinbei', name: '中型客车（金杯）', prices: { bareMonthly: null, halfDay: 700, fullDay: 1000, monthly: null, overtime: 60, overMileage: 3 } },
  { id: 'coaster', name: '中型客车（考斯特）', prices: { bareMonthly: null, halfDay: 800, fullDay: 1200, monthly: null, overtime: 60, overMileage: 3 } },
  { id: 'bus35', name: '35座客车（公交）', prices: { bareMonthly: null, halfDay: 600, fullDay: 1000, monthly: null, overtime: 60, overMileage: 3 } },
  { id: 'bus39', name: '39座客车', prices: { bareMonthly: null, halfDay: 800, fullDay: 1200, monthly: null, overtime: 100, overMileage: 4 } },
  { id: 'bus51', name: '51座客车', prices: { bareMonthly: null, halfDay: 1000, fullDay: 1500, monthly: null, overtime: 120, overMileage: 4 } },
  { id: 'hiace12', name: '12座客车（丰田海狮）', prices: { bareMonthly: 1500, halfDay: null, fullDay: null, monthly: null, overtime: null, overMileage: null } }
].map(vehicle => ({
  ...vehicle,
  priceItems: PRICE_ITEMS.map(item => ({ ...item, value: vehicle.prices[item.key] }))
}))

function availablePlans(vehicle) {
  return PLAN_ITEMS
    .filter(item => vehicle.prices[item.key] !== null)
    .map(item => ({ ...item, price: vehicle.prices[item.key] }))
}

Page({
  data: {
    isHotel: false,
    hotelName: '示例宾馆',
    lodgingAreaId: 'hotel',
    roomName: '',
    hotelId: '',
    roomTypeId: '',
    roomPrice: '',
    date: '2026-07-29',
    endDate: '2026-07-30',
    time: '09:00',
    count: 1,
    name: '',
    phone: '',
    guestIdCard: '',
    departure: '',
    destination: '',
    departureLocation: null,
    destinationLocation: null,
    commonPlaces: commonPlaces(),
    carTypes: VEHICLE_TYPES,
    carIndex: 0,
    selectedCar: VEHICLE_TYPES[0],
    availablePlans: availablePlans(VEHICLE_TYPES[0]),
    planIndex: 0,
    pricingRulesVisible: false,
    submitting: false,
    cancelling: false,
    paying: false,
    openedFromOrder: false,
    vehicleOrderId: '',
    vehicleStatusCode: '',
    vehicleStatusLabel: '',
    vehicleStatusUpdatedAt: '',
    vehicleStatusIndex: -1,
    vehicleProgressSteps: VEHICLE_STATUS_FLOW.map(item => ({ ...item, state: 'upcoming', time: '' })),
    vehicleDriver: null,
    vehicleDriverLocation: null,
    vehicleDriverMarkers: [],
    vehicleDriverLocationUpdatedAt: '',
    vehicleSettlement: null,
    vehicleOrderSummary: null,
    memberBenefit: getMemberProfile(),
    memberOriginalPrice: '',
    memberDiscountAmount: '0.00',
    memberPayablePrice: ''
  },

  onLoad(query) {
    const isHotel = query.type === 'hotel'
    const lodgingAreaId = query.area === 'houshan' ? 'houshan' : 'hotel'
    const orderId = query.orderId ? decodeURIComponent(query.orderId) : ''
    this.setData({
      isHotel,
      lodgingAreaId,
      hotelName: lodgingAreaId === 'houshan' ? '后山住宿' : '示例宾馆',
      openedFromOrder: Boolean(orderId),
      hotelId: query.hotelId || '',
      roomTypeId: query.roomTypeId || '',
      roomName: query.room ? decodeURIComponent(query.room) : '',
      roomPrice: query.price || '',
      date: query.checkIn || this.data.date,
      endDate: query.checkOut || this.data.endDate
    }, () => {
      this.refreshMemberPricing()
      if (!isHotel) {
        this.loadLocationPlaces()
        this.loadVehicleModels()
        this.restoreVehicleBooking(orderId)
      }
    })
  },

  onShow() {
    this.refreshMemberPricing()
    if (this.data.vehicleOrderId) this.startVehicleStatusPolling()
  },

  onHide() { this.stopVehicleStatusPolling() },

  onUnload() { this.stopVehicleStatusPolling() },

  refreshMemberPricing() {
    const memberBenefit = getMemberProfile()
    const selectedPlan = this.data.availablePlans[this.data.planIndex]
    const price = this.data.isHotel ? Number(this.data.roomPrice) : selectedPlan && Number(selectedPlan.price)
    if (!price) {
      this.setData({ memberBenefit, memberOriginalPrice: '', memberDiscountAmount: '0.00', memberPayablePrice: '' })
      return
    }
    const pricing = calculateMemberPrice(yuanToFen(price), memberBenefit)
    this.setData({
      memberBenefit,
      memberOriginalPrice: fenToYuan(pricing.originalAmountFen),
      memberDiscountAmount: fenToYuan(pricing.discountAmountFen),
      memberPayablePrice: fenToYuan(pricing.payableAmountFen)
    })
  },

  dateChange(e) {
    this.setData({ date: e.detail.value })
  },

  endDateChange(e) {
    this.setData({ endDate: e.detail.value })
  },

  timeChange(e) {
    this.setData({ time: e.detail.value })
  },

  selectCar(e) {
    const carIndex = Number(e.currentTarget.dataset.index)
    const selectedCar = this.data.carTypes[carIndex]
    this.setData({
      carIndex,
      selectedCar,
      availablePlans: availablePlans(selectedCar),
      planIndex: 0
    }, () => this.refreshMemberPricing())
  },

  planChange(e) {
    this.setData({ planIndex: Number(e.detail.value) }, () => this.refreshMemberPricing())
  },

  togglePricingRules() {
    this.setData({ pricingRulesVisible: !this.data.pricingRulesVisible })
  },

  minus() {
    if (this.data.count > 1) {
      this.setData({ count: this.data.count - 1 })
    }
  },

  plus() {
    if (this.data.isHotel) {
      wx.showToast({ title: '当前仅支持单一实名入住人', icon: 'none' })
      return
    }
    this.setData({ count: this.data.count + 1 })
  },

  nameInput(e) {
    this.setData({ name: e.detail.value })
  },

  phoneInput(e) {
    this.setData({ phone: e.detail.value })
  },
  guestIdCardInput(e) { this.setData({ guestIdCard: String(e.detail.value || '').toUpperCase() }) },

  departureInput(e) { this.setData({ departure: e.detail.value, departureLocation: null }) },
  destinationInput(e) { this.setData({ destination: e.detail.value, destinationLocation: null }) },

  loadLocationPlaces() {
    return fetchLocationPlaces().then(rows => this.setData({ commonPlaces: mergeWithDefaults(rows) })).catch(() => {})
  },

  chooseVehicleLocation(e) {
    const target = e.currentTarget.dataset.target === 'destination' ? 'destination' : 'departure'
    chooseLocation().then(location => {
      this.setData({ [target]: locationLabel(location), [`${target}Location`]: location })
    }).catch(error => {
      if (error.code !== 'LOCATION_CANCELLED') wx.showToast({ title: error.message, icon: 'none' })
    })
  },

  useCommonPlace(e) {
    const target = e.currentTarget.dataset.target === 'destination' ? 'destination' : 'departure'
    const place = this.data.commonPlaces.find(item => item.key === e.currentTarget.dataset.key)
    if (!place) return
    const hasCoordinates = place.latitude != null && place.longitude != null
      && Number.isFinite(Number(place.latitude)) && Number.isFinite(Number(place.longitude))
    this.setData({
      [target]: place.name,
      [`${target}Location`]: hasCoordinates ? {
        name: place.name,
        address: place.address,
        latitude: Number(place.latitude),
        longitude: Number(place.longitude),
        coordinateSystem: 'GCJ-02'
      } : null
    })
  },

  async loadVehicleModels() {
    try {
      const models = await getVehicleModels()
      if (!models || !models.length) return
      const toYuan = value => value == null ? null : Number(fenToYuan(value))
      const carTypes = models.map(model => ({
        id: model.modelId,
        modelCode: model.modelCode,
        name: model.modelName,
        prices: {
          bareMonthly: toYuan(model.bareMonthCent),
          halfDay: toYuan(model.halfDayCent),
          fullDay: toYuan(model.fullDayCent),
          monthly: toYuan(model.monthCent),
          overtime: null,
          overMileage: null
        }
      })).map(vehicle => ({
        ...vehicle,
        priceItems: PRICE_ITEMS.map(item => ({ ...item, value: vehicle.prices[item.key] }))
      }))
      const selectedCar = carTypes[0]
      this.setData({
        carTypes,
        carIndex: 0,
        selectedCar,
        availablePlans: availablePlans(selectedCar),
        planIndex: 0
      }, () => this.refreshMemberPricing())
    } catch (error) {}
  },

  formatStatusTime(value) {
    const date = value ? new Date(value) : new Date()
    return `${String(date.getHours()).padStart(2, '0')}:${String(date.getMinutes()).padStart(2, '0')}`
  },

  applyVehicleStatus(statusCode, updatedAt, driver, settlement, driverLocation) {
    const statusIndex = VEHICLE_STATUS_FLOW.findIndex(item => item.code === statusCode)
    if (statusIndex < 0) return
    const previous = this.data.vehicleProgressSteps
    const statusTime = this.formatStatusTime(updatedAt)
    const vehicleProgressSteps = VEHICLE_STATUS_FLOW.map((item, index) => ({
      ...item,
      state: index < statusIndex ? 'done' : index === statusIndex ? 'current' : 'upcoming',
      time: index === statusIndex ? statusTime : (previous[index] && previous[index].time) || ''
    }))
    const normalizedDriverLocation = driverLocation && Number.isFinite(Number(driverLocation.latitude)) && Number.isFinite(Number(driverLocation.longitude))
      ? { ...driverLocation, latitude: Number(driverLocation.latitude), longitude: Number(driverLocation.longitude) }
      : this.data.vehicleDriverLocation
    this.setData({
      vehicleStatusCode: statusCode,
      vehicleStatusLabel: VEHICLE_STATUS_FLOW[statusIndex].label,
      vehicleStatusUpdatedAt: statusTime,
      vehicleStatusIndex: statusIndex,
      vehicleProgressSteps,
      vehicleDriver: driver || this.data.vehicleDriver,
      vehicleSettlement: settlement || this.data.vehicleSettlement,
      vehicleDriverLocation: normalizedDriverLocation,
      vehicleDriverMarkers: normalizedDriverLocation ? markerFor(normalizedDriverLocation, 1, '司机当前位置') : [],
      vehicleDriverLocationUpdatedAt: normalizedDriverLocation
        ? this.formatStatusTime(normalizedDriverLocation.updatedAt || updatedAt)
        : this.data.vehicleDriverLocationUpdatedAt
    }, () => {
      this.persistVehicleBooking()
      if (this.data.vehicleOrderId) {
        const purchasedStatus = statusCode === 'PENDING_PAYMENT'
          ? 'PENDING_PAYMENT'
          : statusCode === 'SERVICE_COMPLETED' ? 'PENDING_REVIEW' : 'PENDING_USE'
        updatePurchasedOrder(this.data.vehicleOrderId, {
          status: purchasedStatus,
          serviceStatusCode: statusCode,
          serviceStatusLabel: VEHICLE_STATUS_FLOW[statusIndex].label,
          serviceStatusUpdatedAt: updatedAt || formatDateTime(),
          driver: driver || this.data.vehicleDriver,
          settlement: settlement || this.data.vehicleSettlement,
          canRefund: false
        })
      }
    })
  },

  refreshVehicleStatus() {
    if (!this.data.vehicleOrderId) return Promise.resolve()
    return getVehicleBookingStatus(this.data.vehicleOrderId).then(result => {
      if (['PENDING_SUPPLEMENT', 'SERVICE_COMPLETED'].includes(result.statusCode)) {
        return getVehicleSettlement(this.data.vehicleOrderId)
          .catch(() => null)
          .then(settlement => this.applyVehicleStatus(result.statusCode, result.statusUpdatedAt,
            result.driver, settlement || result.settlement, result.driverLocation))
      }
      this.applyVehicleStatus(result.statusCode, result.statusUpdatedAt, result.driver, result.settlement, result.driverLocation)
    }).catch(() => {})
  },

  startVehicleStatusPolling() {
    this.stopVehicleStatusPolling()
    this.refreshVehicleStatus()
    if (!['SERVICE_COMPLETED', 'CANCELLED'].includes(this.data.vehicleStatusCode)) {
      this.vehicleStatusTimer = setInterval(() => this.refreshVehicleStatus(), 60000)
    }
  },

  stopVehicleStatusPolling() {
    if (this.vehicleStatusTimer) clearInterval(this.vehicleStatusTimer)
    this.vehicleStatusTimer = null
  },

  callVehicleDriver() {
    const phone = this.data.vehicleDriver && this.data.vehicleDriver.driverPhone
    if (!phone) {
      wx.showToast({ title: '司机电话等待后端返回', icon: 'none' })
      return
    }
    wx.makePhoneCall({ phoneNumber: phone })
  },

  storedVehicleBookings() {
    if (!wx.getStorageSync) return {}
    const stored = wx.getStorageSync(VEHICLE_BOOKING_STORAGE_KEY)
    if (!stored || typeof stored !== 'object') return {}
    if (stored.orderId) return { [stored.orderId]: stored }
    return stored
  },

  persistVehicleBooking() {
    if (!this.data.vehicleOrderId || !wx.setStorageSync) return
    const bookings = this.storedVehicleBookings()
    bookings[this.data.vehicleOrderId] = {
      orderId: this.data.vehicleOrderId,
      statusCode: this.data.vehicleStatusCode,
      statusUpdatedAt: formatDateTime(),
      progressSteps: this.data.vehicleProgressSteps,
      driver: this.data.vehicleDriver,
      driverLocation: this.data.vehicleDriverLocation,
      settlement: this.data.vehicleSettlement,
      summary: this.data.vehicleOrderSummary
    }
    wx.setStorageSync(VEHICLE_BOOKING_STORAGE_KEY, bookings)
  },

  restoreVehicleBooking(orderId = '') {
    const bookings = this.storedVehicleBookings()
    const stored = orderId
      ? bookings[orderId]
      : Object.values(bookings).sort((a, b) => String(b.statusUpdatedAt).localeCompare(String(a.statusUpdatedAt)))[0]
    if (!stored && orderId) {
      const order = getPurchasedOrders().find(item => item.id === orderId)
      getVehicleBookingStatus(orderId).then(result => {
        const restoredStatus = result.statusCode || (order && order.serviceStatusCode) || 'PENDING_ACCEPT'
        this.setData({
          vehicleOrderId: orderId,
          vehicleDriver: result.driver || (order && order.driver) || null,
          vehicleSettlement: result.settlement || (order && order.settlement) || null,
          vehicleOrderSummary: order && order.vehicleSummary ? order.vehicleSummary : null
        }, () => {
          this.applyVehicleStatus(
            restoredStatus,
            result.statusUpdatedAt || (order && order.serviceStatusUpdatedAt),
            result.driver || (order && order.driver),
            result.settlement || (order && order.settlement),
            result.driverLocation
          )
          this.startVehicleStatusPolling()
        })
      })
      return
    }
    if (!stored || !stored.orderId) return
    this.setData({
      vehicleOrderId: stored.orderId,
      vehicleProgressSteps: Array.isArray(stored.progressSteps) ? stored.progressSteps : this.data.vehicleProgressSteps,
      vehicleDriver: stored.driver || null,
      vehicleDriverLocation: stored.driverLocation || null,
      vehicleDriverMarkers: stored.driverLocation ? markerFor(stored.driverLocation, 1, '司机当前位置') : [],
      vehicleSettlement: stored.settlement || null,
      vehicleOrderSummary: stored.summary || null
    }, () => {
      this.applyVehicleStatus(stored.statusCode || 'PENDING_ACCEPT', stored.statusUpdatedAt, stored.driver, stored.settlement)
      this.startVehicleStatusPolling()
    })
  },

  removeStoredVehicleBooking(orderId) {
    if (!wx.setStorageSync || !orderId) return
    const bookings = this.storedVehicleBookings()
    delete bookings[orderId]
    wx.setStorageSync(VEHICLE_BOOKING_STORAGE_KEY, bookings)
  },

  resetVehicleBooking() {
    this.removeStoredVehicleBooking(this.data.vehicleOrderId)
    this.setData({
      vehicleOrderId: '',
      vehicleStatusCode: '',
      vehicleStatusLabel: '',
      vehicleStatusUpdatedAt: '',
      vehicleStatusIndex: -1,
      vehicleProgressSteps: VEHICLE_STATUS_FLOW.map(item => ({ ...item, state: 'upcoming', time: '' })),
      vehicleDriver: null,
      vehicleDriverLocation: null,
      vehicleDriverMarkers: [],
      vehicleDriverLocationUpdatedAt: '',
      vehicleSettlement: null,
      vehicleOrderSummary: null
    })
  },

  cancelVehicleReservation() {
    if (this.data.paying || this.data.cancelling) return
    if (!['PENDING_PAYMENT', 'PENDING_ACCEPT'].includes(this.data.vehicleStatusCode)) {
      wx.showToast({ title: '司机已接单，请联系客服协商取消', icon: 'none' })
      return
    }
    wx.showModal({
      title: '取消车辆预约',
      content: '当前司机尚未接单，取消后本次预约将立即关闭。确定继续吗？',
      confirmText: '确认取消',
      confirmColor: '#a84e38',
      success: modal => {
        if (!modal.confirm) return
        this.setData({ cancelling: true })
        cancelVehicleBooking(this.data.vehicleOrderId, {
          clientRequestId: createClientRequestId('vehicle_cancel'),
          reason: '用户主动取消',
          currentStatus: this.data.vehicleStatusCode
        }).then(result => {
          const orderId = this.data.vehicleOrderId
          this.setData({ cancelling: false })
          updatePurchasedOrder(orderId, {
            status: 'CANCELLED',
            statusLabel: '已取消',
            serviceStatusCode: 'CANCELLED',
            serviceStatusLabel: '预约已取消',
            cancelledAt: result.cancelledAt,
            cancellationNo: result.cancellationNo,
            canRefund: false
          })
          this.removeStoredVehicleBooking(orderId)
          wx.showModal({
            title: '预约已取消',
            content: `取消单号：${result.cancellationNo}\n可在“我的订单-退款售后”中查看记录。`,
            showCancel: false,
            success: () => {
              if (this.data.openedFromOrder) wx.navigateBack()
              else this.resetVehicleBooking()
            }
          })
        }).catch(() => this.setData({ cancelling: false }))
      }
    })
  },

  finishVehicleBooking() {
    wx.navigateBack()
  },

  payVehicleReservation() {
    if (this.data.vehicleStatusCode !== 'PENDING_PAYMENT' || this.data.paying || this.data.cancelling) return
    const orderId = this.data.vehicleOrderId
    if (!orderId) return
    this.setData({ paying: true })
    wx.showLoading({ title: '支付中' })
    payAndConfirmOrder(orderId).then(result => {
      wx.hideLoading()
      this.setData({ paying: false })
      this.applyVehicleStatus('PAID', result.order.updatedAt || result.order.createdAt, null, null)
      updatePurchasedOrder(orderId, { status: 'PENDING_USE', backendStatus: result.order.status || 'PAID' })
      wx.showToast({ title: '支付成功', icon: 'success' })
      this.refreshVehicleStatus()
    }).catch(error => {
      wx.hideLoading()
      this.setData({ paying: false })
      if (error && error.message === 'WECHAT_PAYMENT_CANCELLED') {
        wx.showToast({ title: '已取消支付', icon: 'none' })
      } else if (error && error.message === 'PAYMENT_CONFIRMATION_PENDING') {
        this.refreshVehicleStatus()
        wx.showModal({ title: '支付结果确认中', content: '请稍后刷新订单，请勿重复付款。', showCancel: false })
      } else if (error && error.message === 'PAYMENT_ORDER_CLOSED') {
        this.stopVehicleStatusPolling()
        this.setData({
          vehicleStatusCode: 'CANCELLED',
          vehicleStatusLabel: '订单已关闭',
          vehicleStatusUpdatedAt: this.formatStatusTime()
        })
        updatePurchasedOrder(orderId, { status: 'CANCELLED', backendStatus: error.orderStatus || 'CANCELLED' })
        this.removeStoredVehicleBooking(orderId)
        wx.showToast({ title: '订单已关闭，无法继续支付', icon: 'none' })
      } else {
        wx.showToast({ title: '支付未完成，请稍后重试', icon: 'none' })
      }
    })
  },

  payVehicleSupplement() {
    if (this.data.vehicleStatusCode !== 'PENDING_SUPPLEMENT' || this.data.paying) return
    const orderId = this.data.vehicleOrderId
    if (!orderId) return
    this.setData({ paying: true })
    wx.showLoading({ title: '补款中' })
    payAndConfirmVehicleSupplement(orderId).then(result => {
      wx.hideLoading()
      this.setData({ paying: false, vehicleSettlement: result.settlement })
      this.applyVehicleStatus('SERVICE_COMPLETED', new Date().toISOString(), null, result.settlement)
      updatePurchasedOrder(orderId, { status: 'COMPLETED', backendStatus: 'COMPLETED', settlement: result.settlement })
      wx.showToast({ title: '补款成功', icon: 'success' })
    }).catch(error => {
      wx.hideLoading()
      this.setData({ paying: false })
      if (error && error.message === 'WECHAT_PAYMENT_CANCELLED') {
        wx.showToast({ title: '已取消支付', icon: 'none' })
      } else if (error && error.message === 'PAYMENT_CONFIRMATION_PENDING') {
        this.refreshVehicleStatus()
        wx.showModal({ title: '支付结果确认中', content: '请稍后刷新结算状态，请勿重复付款。', showCancel: false })
      } else {
        wx.showToast({ title: '补款未完成，请稍后重试', icon: 'none' })
      }
    })
  },

  async submit() {
    if (!this.data.name || this.data.phone.length !== 11) {
      wx.showToast({ title: '请完善联系人和手机号', icon: 'none' })
      return
    }
    if (!this.data.isHotel && (!this.data.departure.trim() || !this.data.destination.trim())) {
      wx.showToast({ title: '请填写上车地点和目的地', icon: 'none' })
      return
    }
    if (this.data.isHotel && !this.data.roomTypeId) {
      wx.showToast({ title: '请返回房型列表重新选择', icon: 'none' })
      return
    }
    if (this.data.isHotel && !/^\d{17}[\dX]$/.test(this.data.guestIdCard)) {
      wx.showToast({ title: '请输入正确的入住人身份证号', icon: 'none' })
      return
    }
    this.setData({ submitting: true })
    try {
      const {
        isHotel, hotelName, lodgingAreaId, roomName, roomTypeId, date, endDate, time, count, name, phone,
        selectedCar, availablePlans, planIndex, departureLocation, destinationLocation
      } = this.data
      const selectedPlan = isHotel ? null : availablePlans[planIndex]
      const booking = await createBooking({
        bookingType: isHotel ? 'hotel' : 'vehicle',
        roomTypeId: isHotel ? roomTypeId : null,
        lodgingAreaId: isHotel ? lodgingAreaId : null,
        lodgingAreaName: isHotel ? hotelName : null,
        checkInDate: `${date} ${isHotel ? '14:00:00' : time + ':00'}`,
        checkOutDate: isHotel ? `${endDate} 12:00:00` : null,
        passengerCount: count,
        contactName: name,
        contactPhone: phone,
        guestIdCard: isHotel ? this.data.guestIdCard : null,
        vehicleType: isHotel ? null : selectedCar.name,
        vehicleModelId: isHotel ? null : selectedCar.id,
        vehicleTypeId: isHotel ? null : selectedCar.id,
        rentalPlan: isHotel ? null : selectedPlan.key,
        departure: isHotel ? null : this.data.departure,
        destination: isHotel ? null : this.data.destination,
        departureLocation: isHotel ? null : departureLocation,
        destinationLocation: isHotel ? null : destinationLocation,
        quotedBasePriceFen: isHotel ? null : yuanToFen(selectedPlan.price),
        memberPayablePriceFen: this.data.memberPayablePrice ? yuanToFen(this.data.memberPayablePrice) : null,
        memberLevelCode: this.data.memberBenefit.levelCode,
        overtimeRateFen: isHotel || selectedCar.prices.overtime === null ? null : yuanToFen(selectedCar.prices.overtime),
        overMileageRateFen: isHotel || selectedCar.prices.overMileage === null ? null : yuanToFen(selectedCar.prices.overMileage),
        pricingVersion: isHotel ? null : '2026-02-28',
        clientRequestId: createClientRequestId('booking')
      })
      this.setData({ submitting: false })
      if (isHotel) {
        savePurchasedOrder({
          id: booking.bookingId,
          type: 'HOTEL',
          title: lodgingAreaId === 'houshan'
            ? `${hotelName} · ${roomName || '住宿预订'}`
            : roomName || '示例宾馆住宿预订',
          subtitle: `${date} 至 ${endDate} · ${count}人入住`,
          useDate: date,
          endDate,
          quantity: count,
          amountLabel: this.data.memberPayablePrice ? `¥${this.data.memberPayablePrice}/晚起` : '房费待确认',
          contactName: name,
          contactPhone: phone,
          verificationCode: booking.bookingId,
          reservationId: booking.hotelReservationId,
          status: booking.statusCode === 'PENDING_PAYMENT' ? 'PENDING_PAYMENT' : 'PENDING_USE',
          refundRule: `入住日前可申请退款，最终退款金额以${hotelName}确认结果为准。`
        })
        if (this.data.memberPayablePrice) registerPendingMemberPoints(yuanToFen(this.data.memberPayablePrice), booking.bookingId, '住宿订单完成获得积分')
        wx.showModal({
          title: '预约提交成功',
          content: '工作人员会尽快与您联系确认。',
          showCancel: false,
          success: () => wx.navigateBack()
        })
        return
      }
      const vehicleSummary = {
        vehicleName: selectedCar.name,
        rentalPlanLabel: selectedPlan.label,
        basePrice: this.data.memberPayablePrice || selectedPlan.price,
        useDate: date,
        useTime: time,
        passengerCount: count
      }
      savePurchasedOrder({
        id: booking.bookingId,
        type: 'VEHICLE',
        title: selectedCar.name,
        subtitle: `${selectedPlan.label} · ${count}人乘车`,
        useDate: date,
        quantity: count,
        amountLabel: `¥${this.data.memberPayablePrice || selectedPlan.price}起`,
        contactName: name,
        contactPhone: phone,
        status: booking.statusCode === 'PENDING_PAYMENT' ? 'PENDING_PAYMENT' : 'PENDING_USE',
        serviceStatusCode: booking.statusCode || 'PENDING_ACCEPT',
        serviceStatusLabel: booking.statusCode === 'PENDING_PAYMENT' ? '等待付款' : '等待司机接单',
        serviceStatusUpdatedAt: booking.statusUpdatedAt || booking.createdAt,
        vehicleSummary,
        canRefund: false,
        refundRule: '司机接单前可取消预约；接单后请联系客服协商。'
      })
      if (this.data.memberPayablePrice) registerPendingMemberPoints(yuanToFen(this.data.memberPayablePrice), booking.bookingId, '车辆服务完成获得积分')
      this.setData({
        vehicleOrderId: booking.bookingId,
        vehicleDriver: booking.driver || null,
        vehicleSettlement: booking.settlement || null,
        vehicleOrderSummary: vehicleSummary
      }, () => {
        this.applyVehicleStatus(
          booking.statusCode || 'PENDING_ACCEPT',
          booking.statusUpdatedAt || booking.createdAt,
          booking.driver,
          booking.settlement
        )
        wx.showModal({
          title: '用车预约已提交',
          content: '订单已进入司机接单流程，可在当前页面查看服务进度。',
          showCancel: false,
          confirmText: '查看进度',
          success: () => {
            if (wx.pageScrollTo) wx.pageScrollTo({ selector: '#vehicle-progress', duration: 300 })
          }
        })
      })
    } catch (error) {
      this.setData({ submitting: false })
    }
  }
})
