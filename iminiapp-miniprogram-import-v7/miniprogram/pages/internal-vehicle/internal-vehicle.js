const {
  fetchEmployeeProfile, bindEmployee, fetchInternalVehicleApplications,
  createInternalVehicleApplication, cancelInternalVehicleApplication
} = require('../../services/employeeVehicleService')

const STATUS_LABELS = {
  PENDING_APPROVAL: '待审核', APPROVED: '已通过', REJECTED: '已拒绝', CANCELLED: '已取消',
  WAITING_DRIVER: '待司机接单', ACCEPTED: '司机已接单', IN_SERVICE: '行程中', COMPLETED: '已完成'
}

function pad(value) { return String(value).padStart(2, '0') }
function dateValue(value) { return `${value.getFullYear()}-${pad(value.getMonth() + 1)}-${pad(value.getDate())}` }

Page({
  data: {
    loading: true,
    submitting: false,
    profile: null,
    employeeNo: '',
    applications: [],
    form: {
      date: '', startTime: '08:30', endTime: '17:30',
      departure: '', destination: '', passengerCount: '1', estimatedKm: '', purpose: ''
    }
  },

  onLoad() {
    const tomorrow = new Date(Date.now() + 86400000)
    this.setData({ 'form.date': dateValue(tomorrow) })
  },

  onShow() { this.load() },

  async load() {
    this.setData({ loading: true })
    try {
      const profile = await fetchEmployeeProfile()
      if (!profile || !profile.employee) {
        this.setData({ loading: false, profile: null, applications: [] })
        return
      }
      const rows = await fetchInternalVehicleApplications()
      this.setData({ loading: false, profile, applications: this.decorate(rows) })
    } catch (error) {
      this.setData({ loading: false })
    }
  },

  decorate(rows) {
    return (rows || []).map(item => {
      const displayStatus = item.dispatchStatus || item.status
      return {
        ...item,
        statusLabel: STATUS_LABELS[displayStatus] || displayStatus,
        canCancel: item.status === 'PENDING_APPROVAL',
        timeLabel: `${String(item.startAt || '').replace('T', ' ')} 至 ${String(item.endAt || '').replace('T', ' ')}`
      }
    })
  },

  employeeNoInput(e) { this.setData({ employeeNo: e.detail.value }) },
  openPhoneAuthorization() { wx.navigateTo({ url: '/pages/profile-auth/profile-auth?from=internal-vehicle' }) },

  bindIdentity() {
    const employeeNo = String(this.data.employeeNo || '').trim()
    if (!employeeNo) {
      wx.showToast({ title: '请输入员工工号', icon: 'none' })
      return
    }
    this.setData({ submitting: true })
    bindEmployee(employeeNo).then(profile => {
      this.setData({ profile, employeeNo: '' })
      wx.showToast({ title: '员工身份绑定成功', icon: 'success' })
      return this.load()
    }).catch(() => {}).finally(() => this.setData({ submitting: false }))
  },

  dateChange(e) { this.setData({ 'form.date': e.detail.value }) },
  startTimeChange(e) { this.setData({ 'form.startTime': e.detail.value }) },
  endTimeChange(e) { this.setData({ 'form.endTime': e.detail.value }) },
  fieldInput(e) { this.setData({ [`form.${e.currentTarget.dataset.field}`]: e.detail.value }) },

  submitApplication() {
    const form = this.data.form
    const passengerCount = Number(form.passengerCount)
    const estimatedKm = Number(form.estimatedKm)
    if (!form.date || !form.startTime || !form.endTime || !form.departure.trim() || !form.destination.trim() || !form.purpose.trim()) {
      wx.showToast({ title: '请完整填写用车信息', icon: 'none' })
      return
    }
    if (!Number.isInteger(passengerCount) || passengerCount < 1 || passengerCount > 100) {
      wx.showToast({ title: '乘车人数应为1至100人', icon: 'none' })
      return
    }
    if (!Number.isFinite(estimatedKm) || estimatedKm <= 0) {
      wx.showToast({ title: '请填写正确的预计里程', icon: 'none' })
      return
    }
    const startAt = `${form.date}T${form.startTime}:00`
    const endAt = `${form.date}T${form.endTime}:00`
    if (new Date(endAt).getTime() <= new Date(startAt).getTime()) {
      wx.showToast({ title: '结束时间必须晚于开始时间', icon: 'none' })
      return
    }
    this.setData({ submitting: true })
    createInternalVehicleApplication({
      startAt, endAt,
      departure: form.departure.trim(), destination: form.destination.trim(),
      passengerCount, estimatedKm, purpose: form.purpose.trim()
    }).then(result => {
      wx.showModal({ title: '申请已提交', content: `申请单号：${result.applicationNo}\n请等待管理员审核和派车。`, showCancel: false })
      this.setData({ 'form.departure': '', 'form.destination': '', 'form.estimatedKm': '', 'form.purpose': '' })
      return this.load()
    }).catch(() => {}).finally(() => this.setData({ submitting: false }))
  },

  cancelApplication(e) {
    const id = e.currentTarget.dataset.id
    wx.showModal({
      title: '撤销用车申请', content: '只有待审核申请可以撤销，确认继续吗？', confirmText: '确认撤销', confirmColor: '#aa4a32',
      success: result => {
        if (!result.confirm) return
        cancelInternalVehicleApplication(id).then(() => {
          wx.showToast({ title: '申请已撤销', icon: 'success' })
          return this.load()
        }).catch(() => {})
      }
    })
  }
})
