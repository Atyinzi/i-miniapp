const assert = require('assert')
const fs = require('fs')
const path = require('path')

const root = path.join(__dirname, '..')
const miniRoot = path.join(root, 'miniprogram')

function source(relativePath) {
  return fs.readFileSync(path.join(root, relativePath), 'utf8')
}

function filesBelow(directory) {
  return fs.readdirSync(directory, { withFileTypes: true }).flatMap(entry => {
    const target = path.join(directory, entry.name)
    return entry.isDirectory() ? filesBelow(target) : [target]
  })
}

const runtimeFiles = filesBelow(miniRoot).filter(file => /\.(js|wxml|json)$/.test(file))
const runtimeSource = runtimeFiles.map(file => fs.readFileSync(file, 'utf8')).join('\n')

// Fake cross-platform sharing is gone; native WeChat sharing remains.
assert.ok(!runtimeSource.includes('createQQShareTask'))
assert.ok(!runtimeSource.includes('shareToQQ'))
assert.ok(!runtimeSource.includes('QQ 好友'))
assert.ok(!runtimeSource.includes('模拟分享'))
assert.ok(!fs.existsSync(path.join(miniRoot, 'services', 'shareService.js')))

for (const page of ['leisure', 'mall', 'notices', 'venue-detail', 'venues', 'weapon-city']) {
  const js = source(`miniprogram/pages/${page}/${page}.js`)
  const wxml = source(`miniprogram/pages/${page}/${page}.wxml`)
  assert.ok(js.includes('onShareAppMessage'), `${page} must retain native share payload`)
  assert.ok(wxml.includes('open-type="share"'), `${page} must retain native WeChat share button`)
}

// No runtime code may call a development payment endpoint.
assert.ok(!runtimeSource.includes('/mock-pay'))
assert.ok(!runtimeSource.includes('/mock-supplement-pay'))
assert.ok(!runtimeSource.includes('/api/members/me/recharges'))
assert.ok(!runtimeSource.includes('/api/members/me/points'))
const wechatPayment = source('miniprogram/services/wechatPaymentService.js')
assert.ok(wechatPayment.includes('/wechat-pay/jsapi-prepay'))
assert.ok(wechatPayment.includes('wx.requestPayment'))

// Simulated member expiry/recharge and placeholder customer data are absent.
const mineJs = source('miniprogram/pages/mine/mine.js')
const mineWxml = source('miniprogram/pages/mine/mine.wxml')
assert.ok(!mineJs.includes('simulateMemberExpiry'))
assert.ok(!mineJs.includes('rechargeMemberBalance'))
assert.ok(!mineWxml.includes("activePanel==='recharge'"))
assert.ok(!mineWxml.includes("activePanel==='balance'"))
assert.ok(!runtimeSource.includes('XXX-XXXXXXX'))
assert.ok(!source('miniprogram/pages/booking/booking.wxml').includes('测试数据'))

const envPath = require.resolve('../miniprogram/config/env')
function loadEnv(envVersion) {
  delete require.cache[envPath]
  global.wx = {
    getDeviceInfo: () => ({ platform: 'ios' }),
    getAccountInfoSync: () => ({ miniProgram: { envVersion } }),
    request: () => {}
  }
  return require(envPath)
}

assert.strictEqual(loadEnv('trial').enableDevelopmentTools, false)
const releaseEnv = loadEnv('release')
assert.strictEqual(releaseEnv.enableDevelopmentTools, false)
assert.strictEqual(releaseEnv.baseUrl, '')
assert.strictEqual(releaseEnv.useMock, false)
assert.ok(!runtimeSource.includes('api.example.com'))
delete require.cache[envPath]
delete global.wx

console.log('Production UI mock safety tests passed')
