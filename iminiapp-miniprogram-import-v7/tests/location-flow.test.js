const assert = require('assert')
const fs = require('fs')
const path = require('path')

const toastCalls = []
global.wx = { showToast: value => toastCalls.push(value) }

const location = require('../miniprogram/services/locationService')
const config = require('../miniprogram/config/location')

assert.deepStrictEqual(location.normalizeLocation({
  name: '测试地点', address: '测试地址', longitude: 109.9, latitude: 40.6
}), {
  name: '测试地点', address: '测试地址', longitude: 109.9, latitude: 40.6, coordinateSystem: 'GCJ-02'
})
assert.strictEqual(location.normalizeLocation({ longitude: 181, latitude: 40.6 }), null)
assert.strictEqual(location.normalizeLocation({ longitude: null, latitude: null }), null)
assert.deepStrictEqual(location.markerFor(config.PLACES.northHotel), [])
assert.ok(config.commonPlaces().some(item => item.name === '示例宾馆'))
assert.ok(config.commonPlaces().some(item => item.name === '示例景区'))

const appJson = JSON.parse(fs.readFileSync(path.join(__dirname, '../miniprogram/app.json'), 'utf8'))
assert.ok(appJson.requiredPrivateInfos.includes('getLocation'))
assert.ok(appJson.requiredPrivateInfos.includes('chooseLocation'))
assert.ok(appJson.permission['scope.userLocation'])

const booking = fs.readFileSync(path.join(__dirname, '../miniprogram/pages/booking/booking.js'), 'utf8')
const bookingView = fs.readFileSync(path.join(__dirname, '../miniprogram/pages/booking/booking.wxml'), 'utf8')
assert.ok(booking.includes('setInterval(() => this.refreshVehicleStatus(), 60000)'))
assert.ok(bookingView.includes('chooseVehicleLocation'))
assert.ok(bookingView.includes('driver-location-map'))

const dining = fs.readFileSync(path.join(__dirname, '../miniprogram/pages/dining/dining.js'), 'utf8')
assert.ok(dining.includes("addresses:['示例宾馆客房区','示例宾馆会议区','示例宾馆大堂及公共区域']"))
assert.ok(dining.includes('请选择内部区域并填写房号或会议室号'))

console.log('Location flow tests passed')
