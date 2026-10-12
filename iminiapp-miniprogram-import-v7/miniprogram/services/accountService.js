const { request } = require('../utils/request')
const session = require('../utils/session')

const SENSITIVE_KEYS = [
  'iminiappMemberProfile', 'iminiappPurchasedOrders',
  'iminiappMallOrders', 'iminiappMemberAddresses', 'iminiappCommonTravelers',
  'iminiappMemberPreferences', 'iminiappMemberDebugBackup', 'iminiappOrderNavigationIntent',
  'iminiappSelectedCoupon'
]

function clearSensitiveStorage() {
  if (typeof wx === 'undefined' || !wx.removeStorageSync) return
  Array.from(new Set(SENSITIVE_KEYS)).forEach(key => wx.removeStorageSync(key))
}

function logout() {
  return request({ url: '/api/v1/app/auth/logout', method: 'POST' })
    .finally(() => {
      clearSensitiveStorage()
      session.markLoggedOut()
    })
}

function cancelAccount() {
  return request({
    url: '/api/v1/app/auth/account',
    method: 'DELETE',
    data: { confirmation: 'DELETE' }
  }).then(result => {
    clearSensitiveStorage()
    session.markLoggedOut()
    return result
  })
}

module.exports = { logout, cancelAccount, clearSensitiveStorage }
