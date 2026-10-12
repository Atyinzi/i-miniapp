const { request } = require('../utils/request')
const { commonPlaces } = require('../config/location')

function fetchLocationPlaces(type) {
  return request({
    url: '/api/v1/app/location-places',
    data: type ? { type } : {},
    mock: () => ({ code: 200, message: 'success', data: [] })
  })
}

function mergeWithDefaults(rows) {
  const byCode = new Map((rows || []).map(item => [item.placeCode, item]))
  const defaults = commonPlaces().map(item => {
    const remote = byCode.get(item.code)
    return remote ? {
      ...item,
      id: remote.id,
      name: remote.name,
      address: remote.address,
      latitude: remote.latitude,
      longitude: remote.longitude,
      configured: Boolean(remote.configured),
      placeType: remote.placeType,
      coordinateSystem: remote.coordinateSystem || 'GCJ-02'
    } : item
  })
  const known = new Set(defaults.map(item => item.code))
  const extra = (rows || []).filter(item => !known.has(item.placeCode)).map(item => ({
    key: item.placeCode.toLowerCase(), code: item.placeCode, name: item.name, address: item.address,
    latitude: item.latitude, longitude: item.longitude, configured: Boolean(item.configured),
    placeType: item.placeType, coordinateSystem: item.coordinateSystem || 'GCJ-02'
  }))
  return defaults.concat(extra)
}

module.exports = { fetchLocationPlaces, mergeWithDefaults }
