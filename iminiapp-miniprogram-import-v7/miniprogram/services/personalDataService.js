const { request } = require('../utils/request')

function local(key) {
  const value = typeof wx !== 'undefined' && wx.getStorageSync ? wx.getStorageSync(key) : []
  return Array.isArray(value) ? value : []
}

function fetchAddresses() {
  return request({ url: '/api/v1/app/addresses', mock: () => ({ code: 200, message: 'success', data: local('iminiappMemberAddresses') }) })
}
function saveAddress(id, data) {
  return request({
    url: id ? `/api/v1/app/addresses/${id}` : '/api/v1/app/addresses', method: id ? 'PUT' : 'POST', data,
    mock: () => {
      let items = local('iminiappMemberAddresses').map(item => data.isDefault ? { ...item, isDefault: false } : item)
      const value = { ...data, id: id || `ADDR${Date.now()}` }
      const index = items.findIndex(item => String(item.id) === String(value.id))
      if (index >= 0) items[index] = value; else items.unshift(value)
      if (!items.some(item => item.isDefault) && items.length) items[0].isDefault = true
      return { code: 200, message: 'success', data: items }
    }
  })
}
function setDefaultAddress(id) {
  return request({ url: `/api/v1/app/addresses/${id}/default`, method: 'PUT', mock: () => ({ code: 200, message: 'success', data: local('iminiappMemberAddresses').map(item => ({ ...item, isDefault: String(item.id) === String(id) })) }) })
}
function removeAddress(id) {
  return request({ url: `/api/v1/app/addresses/${id}`, method: 'DELETE', mock: () => ({ code: 200, message: 'success', data: local('iminiappMemberAddresses').filter(item => String(item.id) !== String(id)) }) })
}

function fetchTravelers() {
  return request({ url: '/api/v1/app/travelers', mock: () => ({ code: 200, message: 'success', data: local('iminiappCommonTravelers') }) })
}
function saveTraveler(id, data) {
  return request({
    url: id ? `/api/v1/app/travelers/${id}` : '/api/v1/app/travelers', method: id ? 'PUT' : 'POST', data,
    mock: () => {
      const items = local('iminiappCommonTravelers'); const value = { ...data, id: id || `TRAVELER${Date.now()}` }
      const index = items.findIndex(item => String(item.id) === String(value.id)); if (index >= 0) items[index] = value; else items.unshift(value)
      return { code: 200, message: 'success', data: items }
    }
  })
}
function removeTraveler(id) {
  return request({ url: `/api/v1/app/travelers/${id}`, method: 'DELETE', mock: () => ({ code: 200, message: 'success', data: local('iminiappCommonTravelers').filter(item => String(item.id) !== String(id)) }) })
}

module.exports = { fetchAddresses, saveAddress, setDefaultAddress, removeAddress, fetchTravelers, saveTraveler, removeTraveler }
