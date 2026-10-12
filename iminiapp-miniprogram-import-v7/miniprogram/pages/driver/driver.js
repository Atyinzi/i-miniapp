const { fetchDriverProfile, fetchDriverTasks, acceptDriverTask, departDriverTask, completeDriverTask, reportDriverLocation } = require('../../services/driverService')
const { getCurrentLocation, markerFor } = require('../../services/locationService')

const STATUS_LABELS = { WAITING_DRIVER: '待接单', ACCEPTED: '已接单', IN_SERVICE: '服务中', COMPLETED: '已完成' }
const BUSINESS_LABELS = { INTERNAL: '内部用车', SOCIAL_RENTAL: '社会租赁' }

Page({
  data: {
    loading: true,
    profile: null,
    tasks: [],
    activeTask: null,
    locationReporting: false,
    currentLocation: null,
    locationMarkers: [],
    locationUpdatedAt: '',
    endMileageKm: '',
    fuelLiter: '',
    completing: false
  },

  onShow() { this.load() },
  onHide() { this.stopLocationReporting() },
  onUnload() { this.stopLocationReporting() },

  async load() {
    this.setData({ loading: true })
    try {
      const profile = await fetchDriverProfile()
      if (!profile || !profile.driver) {
        this.setData({ loading: false, profile: null, tasks: [], activeTask: null })
        return
      }
      const rows = await fetchDriverTasks()
      const tasks = (rows || []).map(item => ({
        ...item,
        statusLabel: STATUS_LABELS[item.dispatchStatus] || item.dispatchStatus,
        businessLabel: BUSINESS_LABELS[item.businessMode] || item.businessMode || '车辆任务'
      }))
      const activeTask = tasks.find(item => ['IN_SERVICE', 'ACCEPTED', 'WAITING_DRIVER'].includes(item.dispatchStatus)) || null
      this.setData({ loading: false, profile, tasks, activeTask })
    } catch (error) {
      this.setData({ loading: false })
    }
  },

  accept(e) { this.runAction(() => acceptDriverTask(e.currentTarget.dataset.id), '接单成功') },
  depart(e) {
    this.runAction(() => departDriverTask(e.currentTarget.dataset.id), '服务已开始').then(ok => {
      if (ok) wx.showModal({ title: '开启位置上报', content: '服务期间可每分钟上报一次位置，离开页面会自动停止。', confirmText: '立即开启', success: result => { if (result.confirm) this.startLocationReporting() } })
    })
  },

  runAction(operation, successTitle) {
    wx.showLoading({ title: '处理中' })
    return operation().then(() => {
      wx.hideLoading()
      wx.showToast({ title: successTitle, icon: 'success' })
      return this.load().then(() => true)
    }).catch(() => { wx.hideLoading(); return false })
  },

  startLocationReporting() {
    if (!this.data.activeTask || this.data.activeTask.dispatchStatus !== 'IN_SERVICE') {
      wx.showToast({ title: '只有服务中的任务可以上报位置', icon: 'none' })
      return
    }
    this.stopLocationReporting()
    this.setData({ locationReporting: true })
    this.reportLocation()
    this.locationTimer = setInterval(() => this.reportLocation(), 60000)
  },

  stopLocationReporting() {
    if (this.locationTimer) clearInterval(this.locationTimer)
    this.locationTimer = null
    if (this.data.locationReporting) this.setData({ locationReporting: false })
  },

  reportLocation() {
    const task = this.data.activeTask
    if (!task || !this.data.locationReporting) return
    getCurrentLocation().then(location => reportDriverLocation(task.dispatchId, location).then(() => {
      const now = new Date()
      this.setData({
        currentLocation: location,
        locationMarkers: markerFor(location, 1, '我的当前位置'),
        locationUpdatedAt: `${String(now.getHours()).padStart(2, '0')}:${String(now.getMinutes()).padStart(2, '0')}`
      })
    })).catch(error => {
      this.stopLocationReporting()
      wx.showToast({ title: error.message || '位置上报失败', icon: 'none' })
    })
  },

  endMileageInput(e) { this.setData({ endMileageKm: e.detail.value }) },
  fuelInput(e) { this.setData({ fuelLiter: e.detail.value }) },
  complete(e) {
    const endMileageKm = Number(this.data.endMileageKm)
    if (!Number.isFinite(endMileageKm) || endMileageKm < 0) {
      wx.showToast({ title: '请填写正确的结束里程', icon: 'none' })
      return
    }
    const fuel = this.data.fuelLiter === '' ? null : Number(this.data.fuelLiter)
    if (fuel != null && (!Number.isFinite(fuel) || fuel < 0)) {
      wx.showToast({ title: '请填写正确的油耗', icon: 'none' })
      return
    }
    wx.showModal({ title: '确认结束服务', content: '结束后将停止位置上报并进入结算流程。', confirmText: '确认结束', success: result => {
      if (!result.confirm) return
      this.setData({ completing: true })
      completeDriverTask(e.currentTarget.dataset.id, { endMileageKm, fuelLiter: fuel, remark: '司机小程序结束服务' })
        .then(() => { this.stopLocationReporting(); wx.showToast({ title: '服务已完成', icon: 'success' }); this.setData({ endMileageKm: '', fuelLiter: '' }); return this.load() })
        .catch(() => {})
        .finally(() => this.setData({ completing: false }))
    } })
  }
})
