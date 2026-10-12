const assert = require('assert')

const storage = {}
storage.iminiappWechatLoginConsent = true
const calls = []
global.wx = {
  getDeviceInfo: () => ({ platform: 'devtools' }),
  getSystemInfoSync: () => ({ platform: 'devtools' }),
  getStorageSync: key => storage[key] || '',
  setStorageSync: (key, value) => { storage[key] = value },
  removeStorageSync: key => { delete storage[key] },
  showToast: () => {},
  login: ({ success }) => success({ code: 'request-login-code' }),
  request: options => {
    calls.push(options)
    if (options.url.endsWith('/api/v1/app/auth/wechat-login')) {
      options.success({
        statusCode: 200,
        data: { code: 'SUCCESS', message: '模拟登录成功', data: { accessToken: 'mock_test', user: { displayName: '联调用户' } } }
      })
      return
    }
    options.success({ statusCode: 200, data: { code: 'SUCCESS', message: '操作成功', data: { ok: true } } })
  }
}

const { request } = require('../miniprogram/utils/request')

request({
  url: '/api/v1/app/orders',
  method: 'POST',
  data: {},
  headers: { 'Idempotency-Key': 'request-test-001' }
}).then(result => {
  assert.deepStrictEqual(result, { ok: true })
  assert.strictEqual(calls.length, 2)
  assert.ok(calls[0].url.endsWith('/api/v1/app/auth/wechat-login'))
  assert.strictEqual(calls[1].header.Authorization, 'Bearer mock_test')
  assert.strictEqual(calls[1].header['Idempotency-Key'], 'request-test-001')
  console.log('Request integration tests passed')
}).catch(error => {
  console.error(error)
  process.exitCode = 1
})
