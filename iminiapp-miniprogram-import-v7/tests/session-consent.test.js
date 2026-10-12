const assert = require('assert')

const storage = {}
const calls = []
global.wx = {
  getDeviceInfo: () => ({ platform: 'devtools' }),
  getSystemInfoSync: () => ({ platform: 'devtools' }),
  getStorageSync: key => storage[key] || '',
  setStorageSync: (key, value) => { storage[key] = value },
  removeStorageSync: key => { delete storage[key] },
  showToast: () => {},
  login: options => options.success({ code: 'wx-consent-code' }),
  request: options => {
    calls.push(options)
    options.success({
      statusCode: 200,
      data: {
        code: 'SUCCESS',
        message: '登录成功',
        data: { accessToken: 'wechat_token', user: { displayName: options.data.displayName } }
      }
    })
  }
}

const { ensureSession, resumeSession, hasSession } = require('../miniprogram/utils/session')

async function run() {
  await assert.rejects(ensureSession(), error => error.code === 'LOGIN_REQUIRED')
  assert.strictEqual(calls.length, 0, '未授权时不应调用微信登录接口')

  await resumeSession({ displayName: '授权用户', avatarUrl: 'https://example.com/avatar.png' })
  assert.strictEqual(hasSession(), true)
  assert.strictEqual(calls.length, 1)
  assert.strictEqual(calls[0].data.displayName, '授权用户')
  assert.strictEqual(calls[0].data.avatarUrl, 'https://example.com/avatar.png')
  console.log('Session consent tests passed')
}

run().catch(error => {
  console.error(error)
  process.exitCode = 1
})
