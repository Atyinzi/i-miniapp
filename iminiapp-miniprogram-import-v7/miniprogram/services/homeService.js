const { request } = require('../utils/request')

/** 正常运行环境只读取后端；下方 Mock 仅供无 wx.request 的 Node 契约测试。 */
function getHome() {
  if (!require('../config/env').useMock) {
    return Promise.all([
      request({ url: '/api/v1/app/content/articles', data: { page: 1, pageSize: 10 }, auth: false }),
      request({ url: '/api/v1/app/content/banners', auth: false })
    ]).then(([articlePage, banners]) => ({
      notices: (articlePage.items || []).map(item => item.title),
      banners: banners || [],
      quickItems: defaultQuickItems()
    }))
  }
  return request({ url: '/api/home', mock: () => ({ code: 200, message: 'success', data: {
    notices: ['i某小程序产业服务平台服务功能持续完善', '示例宾馆会议与住宿预约服务焕新', '车队预约车型及服务流程说明'],
    quickItems: defaultQuickItems()
  } }) })
}

function defaultQuickItems() {
  return [
    { name: '客房', desc: '安心入住', icon: '⌂', color: 'sand', url: '/pages/venues/venues' },
    { name: '餐饮', desc: '两地餐饮', icon: '♨', color: 'red', url: '/pages/dining/dining' },
    { name: '景区', desc: '文化探索', icon: '⚑', color: 'green', url: '/pages/weapon-city/weapon-city' },
    { name: '出行', desc: '便捷出行', icon: '▰', color: 'blue', url: '/pages/booking/booking' },
    { name: '商城', desc: 'i某小程序好物', icon: '✦', color: 'gold', url: '/pages/mall/mall' }
  ]
}
module.exports = { getHome }
