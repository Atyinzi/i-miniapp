const assert = require('assert')

const envPath = require.resolve('../miniprogram/config/env')
const servicePath = require.resolve('../miniprogram/services/verificationService')
const requestPath = require.resolve('../miniprogram/utils/request')
const sessionPath = require.resolve('../miniprogram/utils/session')

function fresh(runtime) {
  ;[servicePath, requestPath, sessionPath, envPath].forEach(path => delete require.cache[path])
  global.wx = runtime
  return {
    config: require('../miniprogram/config/env'),
    verification: require('../miniprogram/services/verificationService')
  }
}

async function run() {
  const release = fresh({
    getDeviceInfo: () => ({ platform: 'ios' }),
    getAccountInfoSync: () => ({ miniProgram: { envVersion: 'release' } }),
    request: () => { throw new Error('must not call network while entry is disabled') }
  })
  assert.strictEqual(release.verification.isStaffVerificationAvailable(), false,
    'production staff entry must be hidden unless explicitly enabled')
  await assert.rejects(release.verification.loginStaff('user', 'password'),
    error => error.code === 'STAFF_VERIFICATION_UNAVAILABLE')

  const calls = []
  const ordinaryTokenAttempt = fresh({
    getDeviceInfo: () => ({ platform: 'devtools' }),
    getStorageSync: () => 'ordinary-user-token',
    showToast: () => {},
    request: options => {
      calls.push(options)
      if (options.url.endsWith('/admin/auth/login')) {
        options.success({ statusCode: 200, data: { code: 'SUCCESS', data: {
          accessToken: 'ordinary-user-token', expiresAt: '2099-01-01T00:00:00+08:00',
          user: { accountType: 'USER' }
        } } })
      } else if (options.url.endsWith('/admin/auth/me')) {
        options.success({ statusCode: 403, data: {
          code: 403, errorCode: 'AUTH_403_001', message: '无权访问'
        } })
      } else if (options.url.endsWith('/admin/auth/logout')) {
        options.success({ statusCode: 200, data: { code: 'SUCCESS', data: null } })
      }
    }
  })
  ordinaryTokenAttempt.config.staffVerificationEnabled = true
  await assert.rejects(ordinaryTokenAttempt.verification.loginStaff('ordinary', 'NotAStaffPassword'),
    error => Number(error.statusCode) === 403)
  assert.ok(calls.some(call => call.url.endsWith('/admin/auth/me')))
  assert.ok(calls.some(call => call.url.endsWith('/admin/auth/logout')),
    'a token rejected by /admin/auth/me should be revoked')
  await assert.rejects(ordinaryTokenAttempt.verification.ensureStaffSession(),
    error => error.code === 'STAFF_LOGIN_REQUIRED')
}

run().then(() => console.log('Staff verification security tests passed')).catch(error => {
  console.error(error)
  process.exitCode = 1
})
