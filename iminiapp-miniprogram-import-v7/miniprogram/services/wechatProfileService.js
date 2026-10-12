const { request } = require('../utils/request')

function authorizePhone(code) {
  return request({ url: '/api/v1/app/auth/wechat-phone', method: 'POST', data: { code } })
}

function fetchProfile() {
  return request({ url: '/api/v1/app/profile' })
}

function updateProfile(payload) {
  return request({ url: '/api/v1/app/profile', method: 'PUT', data: payload })
}

module.exports = { authorizePhone, fetchProfile, updateProfile }
