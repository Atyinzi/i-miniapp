const { COORDINATE_SYSTEM } = require('../config/location')

function normalizeLocation(value) {
  if (!value) return null
  if (value.latitude == null || value.longitude == null || value.latitude === '' || value.longitude === '') return null
  const latitude = Number(value.latitude)
  const longitude = Number(value.longitude)
  if (!Number.isFinite(latitude) || !Number.isFinite(longitude)) return null
  if (latitude < -90 || latitude > 90 || longitude < -180 || longitude > 180) return null
  return {
    name: String(value.name || '').trim(),
    address: String(value.address || '').trim(),
    latitude,
    longitude,
    coordinateSystem: 'GCJ-02'
  }
}

function locationLabel(value) {
  if (!value) return ''
  return String(value.name || value.address || '').trim()
}

function chooseLocation() {
  if (!wx.chooseLocation) return Promise.reject(Object.assign(new Error('当前微信版本不支持地图选点'), { code: 'UNSUPPORTED' }))
  return new Promise((resolve, reject) => {
    wx.chooseLocation({
      success(result) {
        const location = normalizeLocation(result)
        if (!location) {
          reject(Object.assign(new Error('未取得有效的地图坐标'), { code: 'INVALID_LOCATION' }))
          return
        }
        resolve(location)
      },
      fail(error) {
        const message = String(error && error.errMsg || '')
        const denied = /auth deny|auth denied|authorize:fail|permission/i.test(message)
        reject(Object.assign(new Error(denied ? '定位权限未开启，可继续手动填写地点' : '未选择地图地点'), {
          code: denied ? 'LOCATION_DENIED' : 'LOCATION_CANCELLED',
          cause: error
        }))
      }
    })
  })
}

function getCurrentLocation() {
  if (!wx.getLocation) return Promise.reject(Object.assign(new Error('当前微信版本不支持定位'), { code: 'UNSUPPORTED' }))
  return new Promise((resolve, reject) => wx.getLocation({
    type: COORDINATE_SYSTEM,
    isHighAccuracy: true,
    success(result) { resolve(normalizeLocation(result)) },
    fail(error) {
      reject(Object.assign(new Error('无法获取当前位置，可继续手动填写'), { code: 'LOCATION_DENIED', cause: error }))
    }
  }))
}

function openLocation(place) {
  const location = normalizeLocation(place)
  if (!location || place.configured === false) {
    wx.showToast({ title: '正式地址和坐标待配置', icon: 'none' })
    return Promise.resolve(false)
  }
  return new Promise((resolve, reject) => wx.openLocation({
    latitude: location.latitude,
    longitude: location.longitude,
    name: location.name,
    address: location.address,
    scale: 16,
    success() { resolve(true) },
    fail(error) { reject(error) }
  }))
}

function markerFor(place, id = 1, title) {
  const location = normalizeLocation(place)
  if (!location) return []
  return [{
    id,
    latitude: location.latitude,
    longitude: location.longitude,
    title: title || location.name,
    width: 28,
    height: 36,
    callout: { content: title || location.name, display: 'ALWAYS', padding: 6, borderRadius: 6 }
  }]
}

module.exports = { normalizeLocation, locationLabel, chooseLocation, getCurrentLocation, openLocation, markerFor }
