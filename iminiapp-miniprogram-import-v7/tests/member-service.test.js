const assert = require('assert')

const storage = {}
global.wx = {
  getAccountInfoSync: () => ({ miniProgram: { envVersion: 'develop' } }),
  getStorageSync: key => storage[key] || '',
  setStorageSync: (key, value) => { storage[key] = value },
  showToast: () => {}
}

const {
  MEMBER_LEVELS,
  normalizeMember,
  getMemberProfile,
  registerPendingMemberPoints,
  settleMemberPoints,
  reverseMemberPoints,
  calculateMemberPrice,
  toMemberApiData
} = require('../miniprogram/services/memberService')

assert.strictEqual(MEMBER_LEVELS.length, 6)
assert.strictEqual(MEMBER_LEVELS[MEMBER_LEVELS.length - 1].discountRate, 0.7)

const migratedBalance = normalizeMember({ balance: '300.00' }, new Date(2026, 7, 4))
assert.strictEqual(migratedBalance.balanceFen, 30000)
assert.strictEqual(migratedBalance.balanceLabel, '300.00')
assert.strictEqual(Object.prototype.hasOwnProperty.call(migratedBalance, 'balance'), false)
const memberApiData = toMemberApiData(migratedBalance)
assert.strictEqual(memberApiData.balanceFen, 30000)
assert.strictEqual(Object.prototype.hasOwnProperty.call(memberApiData, 'balanceLabel'), false)

const upgraded = normalizeMember({ points: 1700, growthPoints: 1700, levelCode: 'NORMAL' }, new Date(2026, 7, 4))
assert.strictEqual(upgraded.levelCode, 'GOLD')
assert.strictEqual(upgraded.validUntil, '2027-08-04')

const expired = normalizeMember({
  points: 5000,
  growthPoints: 5000,
  levelCode: 'PLATINUM',
  validUntil: '2026-08-03'
}, new Date(2026, 7, 4))
assert.strictEqual(expired.levelCode, 'NORMAL')
assert.strictEqual(expired.growthPoints, 0)
assert.strictEqual(expired.points, 5000)

storage.iminiappMemberProfile = { points: 1490, growthPoints: 1490, levelCode: 'SILVER', validUntil: '2027-01-01' }
const before = getMemberProfile()
assert.strictEqual(before.levelCode, 'SILVER')
const paid = registerPendingMemberPoints(2000, 'ORDER-POINT-1')
assert.strictEqual(paid.points, 1490)
assert.strictEqual(paid.growthPoints, 1490)
assert.strictEqual(paid.pendingPointOrders.length, 1)
assert.strictEqual(paid.levelCode, 'SILVER')
const after = settleMemberPoints('ORDER-POINT-1')
assert.strictEqual(after.points, 1510)
assert.strictEqual(after.levelCode, 'GOLD')
const duplicate = settleMemberPoints('ORDER-POINT-1')
assert.strictEqual(duplicate.points, 1510)
const reversed = reverseMemberPoints('ORDER-POINT-1')
assert.strictEqual(reversed.points, 1490)
assert.strictEqual(reversed.growthPoints, 1490)
assert.strictEqual(reversed.levelCode, 'SILVER')

const pendingRefund = registerPendingMemberPoints(5000, 'ORDER-POINT-2')
assert.strictEqual(pendingRefund.points, 1490)
const cancelled = reverseMemberPoints('ORDER-POINT-2')
assert.strictEqual(cancelled.points, 1490)
assert.strictEqual(cancelled.pendingPointOrders.length, 0)

const blackGoldPrice = calculateMemberPrice(10000, normalizeMember({ points: 10000, growthPoints: 10000, levelCode: 'BLACK_GOLD', validUntil: '2027-08-04' }))
assert.strictEqual(blackGoldPrice.payableAmountFen, 7000)
assert.strictEqual(blackGoldPrice.discountAmountFen, 3000)

console.log('Member service tests passed')
