const assert = require('assert')

const storage = { token: 'ordinary-user-token' }
const writes = []
const calls = []

global.wx = {
  getDeviceInfo: () => ({ platform: 'devtools', model: 'verification-test-device' }),
  getSystemInfoSync: () => ({ platform: 'devtools' }),
  getStorageSync: key => storage[key] || '',
  setStorageSync: (key, value) => { writes.push({ key, value }); storage[key] = value },
  removeStorageSync: key => { delete storage[key] },
  showToast: () => {},
  request: options => {
    calls.push(options)
    if (options.url.endsWith('/api/v1/admin/auth/login')) {
      assert.deepStrictEqual(options.data, { username: 'verifier', password: 'StrongPassword!2026' })
      assert.strictEqual(options.header.Authorization, undefined)
      options.success({ statusCode: 200, data: { code: 'SUCCESS', data: {
        accessToken: 'staff-admin-token', tokenType: 'Bearer', expiresAt: '2099-08-10T12:00:00+08:00',
        user: { displayName: '现场核销员', accountType: 'ADMIN', roles: ['VERIFIER'], permissions: ['verification.use'] }
      } } })
      return
    }
    assert.strictEqual(options.header.Authorization, 'Bearer staff-admin-token')
    if (options.url.endsWith('/api/v1/admin/auth/me')) {
      options.success({ statusCode: 200, data: { code: 'SUCCESS', data: {
        userId: '8', displayName: '现场核销员', accountType: 'ADMIN', roles: ['VERIFIER'],
        identityProvider: 'PASSWORD', permissions: ['verification.use']
      } } })
      return
    }
    if (options.url.endsWith('/api/v1/admin/verifications/consume')) {
      if (options.data.code === 'BFHV-USED') {
        options.success({ statusCode: 409, data: {
          code: 409, errorCode: 'VERIFICATION_409_001', message: '该电子票已核销或已失效'
        } })
        return
      }
      options.success({ statusCode: 200, data: { code: 'SUCCESS', data: {
        verificationId: 61, orderId: 31, orderNo: 'BFH-WEAPON-31', orderItemId: 41,
        ticketNo: 1, resourceName: '坦克试乘', resourceType: 'SCENIC_TICKET', code: null,
        status: 'USED', serviceDate: '2026-08-09', verifiedAt: '2026-08-09T15:30:00'
      } } })
      return
    }
    if (options.url.endsWith('/api/v1/admin/auth/logout')) {
      options.success({ statusCode: 200, data: { code: 'SUCCESS', data: null } })
    }
  }
}

async function run() {
  const config = require('../miniprogram/config/env')
  config.staffVerificationEnabled = true
  const verification = require('../miniprogram/services/verificationService')
  assert.strictEqual(verification.isStaffVerificationAvailable(), true)
  assert.strictEqual(verification.extractTicketCode('https://local/verify?code=bfhv-41-1-abcd'), 'BFHV-41-1-ABCD')
  assert.strictEqual(verification.extractTicketCode('{"ticketCode":"bfhv-json"}'), 'BFHV-JSON')

  const staff = await verification.loginStaff('verifier', 'StrongPassword!2026')
  assert.strictEqual(staff.accountType, 'ADMIN')
  assert.deepStrictEqual(staff.permissions, ['verification.use'])
  assert.strictEqual(staff.accessToken, undefined)

  const revalidated = await verification.ensureStaffSession()
  assert.strictEqual(revalidated.displayName, '现场核销员')

  const codeTicket = await verification.consumeTicketCode('bfhv-41-1-abcd', 'TEST-DEVICE')
  assert.strictEqual(codeTicket.status, 'USED')
  assert.strictEqual(codeTicket.statusLabel, '已核销')
  assert.strictEqual(codeTicket.verifiedAtLabel, '2026-08-09 15:30:00')

  let duplicateMessage = ''
  try { await verification.consumeTicketCode('BFHV-USED') } catch (error) {
    duplicateMessage = verification.describeVerificationError(error)
  }
  assert.ok(duplicateMessage.includes('已核销或已失效'))
  assert.ok(duplicateMessage.includes('请勿重复放行'))

  assert.strictEqual(storage.token, 'ordinary-user-token')
  assert.strictEqual(writes.length, 0, 'staff token/password must never be persisted')
  assert.strictEqual(Object.prototype.hasOwnProperty.call(verification, 'staffAccessToken'), false)
  assert.strictEqual(calls.filter(call => call.url.endsWith('/admin/auth/login')).length, 1)
  assert.ok(calls.filter(call => call.url.endsWith('/admin/auth/me')).length >= 2)
  assert.strictEqual(calls.filter(call => call.url.includes('/ops/verifications/')).length, 0)
  assert.strictEqual(calls.filter(call => call.url.endsWith('/admin/verifications/consume')).length, 2)

  const logout = await verification.logoutStaff()
  assert.strictEqual(logout.serverRevoked, true)
  await assert.rejects(verification.ensureStaffSession(), error => error.code === 'STAFF_LOGIN_REQUIRED')
  assert.strictEqual(storage.token, 'ordinary-user-token')
}

run().then(() => console.log('Staff verification integration tests passed')).catch(error => {
  console.error(error)
  process.exitCode = 1
})
