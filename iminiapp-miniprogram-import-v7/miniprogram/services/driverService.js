const { request } = require('../utils/request')

function fetchDriverProfile() {
  return request({ url: '/api/v1/app/driver/profile', mock: () => ({ code: 200, message: 'success', data: { driver: false } }) })
}

function fetchDriverTasks() {
  return request({ url: '/api/v1/app/driver/tasks', mock: () => ({ code: 200, message: 'success', data: [] }) })
}

function acceptDriverTask(dispatchId) {
  return request({ url: `/api/v1/app/driver/tasks/${dispatchId}/accept`, method: 'POST' })
}

function departDriverTask(dispatchId) {
  return request({ url: `/api/v1/app/driver/tasks/${dispatchId}/depart`, method: 'POST' })
}

function completeDriverTask(dispatchId, payload) {
  return request({ url: `/api/v1/app/driver/tasks/${dispatchId}/complete`, method: 'POST', data: payload })
}

function reportDriverLocation(dispatchId, location) {
  return request({
    url: `/api/v1/app/driver/tasks/${dispatchId}/locations`,
    method: 'POST',
    data: {
      longitude: location.longitude,
      latitude: location.latitude,
      coordinateSystem: 'GCJ-02',
      mileageKm: null,
      eventAt: null,
      remark: '司机小程序服务期间位置上报'
    }
  })
}

module.exports = { fetchDriverProfile, fetchDriverTasks, acceptDriverTask, departDriverTask, completeDriverTask, reportDriverLocation }
