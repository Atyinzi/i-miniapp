/**
 * 地图业务配置。
 *
 * 正式坐标必须由公司提供并确认是 GCJ-02 坐标后，将 configured 改为 true。
 * 未确认的地点仍可作为车队常用地点快捷文本使用，但不会启动错误导航。
 */
const COORDINATE_SYSTEM = 'gcj02'

const PLACES = {
  northHotel: {
    code: 'NORTH_HOTEL',
    name: '示例宾馆',
    address: '包头市青山区i某小程序园区',
    latitude: null,
    longitude: null,
    configured: false
  },
  weaponCity: {
    code: 'WEAPON_CITY',
    name: '示例景区',
    address: '内蒙古自治区包头市青山区兵工路',
    latitude: null,
    longitude: null,
    configured: false
  },
  houshanLodging: {
    code: 'HOUSHAN_LODGING',
    name: '后山住宿',
    address: '正式地址待公司确认',
    latitude: null,
    longitude: null,
    configured: false
  },
  sportsVenue: {
    code: 'SPORTS_VENUE',
    name: 'i某小程序体育馆',
    address: '正式地址待公司确认',
    latitude: null,
    longitude: null,
    configured: false
  },
  bathVenue: {
    code: 'BATH_VENUE',
    name: 'i某小程序浴都',
    address: '正式地址待公司确认',
    latitude: null,
    longitude: null,
    configured: false
  }
}

const COMMON_PLACE_KEYS = ['northHotel', 'weaponCity', 'houshanLodging', 'sportsVenue', 'bathVenue']

function commonPlaces() {
  return COMMON_PLACE_KEYS.map(key => ({ ...PLACES[key], key }))
}

module.exports = { COORDINATE_SYSTEM, PLACES, commonPlaces }
