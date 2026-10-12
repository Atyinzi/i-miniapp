const { request } = require('../utils/request')

function fetchEmployeeProfile() {
  return request({
    url: '/api/v1/app/vehicle-employees/me',
    mock: () => ({ code: 200, message: 'success', data: { employee: false } })
  })
}

function bindEmployee(employeeNo) {
  return request({
    url: '/api/v1/app/vehicle-employees/bind',
    method: 'POST',
    data: { employeeNo: String(employeeNo || '').trim() }
  })
}

function fetchInternalVehicleApplications() {
  return request({
    url: '/api/v1/app/vehicle-applications',
    mock: () => ({ code: 200, message: 'success', data: [] })
  })
}

function createInternalVehicleApplication(payload) {
  return request({
    url: '/api/v1/app/vehicle-applications',
    method: 'POST',
    headers: { 'Idempotency-Key': `vehicle-internal-${Date.now()}` },
    data: payload
  })
}

function cancelInternalVehicleApplication(applicationId) {
  return request({
    url: `/api/v1/app/vehicle-applications/${encodeURIComponent(applicationId)}/cancel`,
    method: 'POST'
  })
}

module.exports = {
  fetchEmployeeProfile,
  bindEmployee,
  fetchInternalVehicleApplications,
  createInternalVehicleApplication,
  cancelInternalVehicleApplication
}
