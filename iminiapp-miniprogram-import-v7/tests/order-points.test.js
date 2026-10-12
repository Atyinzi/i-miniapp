const assert = require('assert')

const storage = {
  iminiappMemberProfile: {
    points: 0,
    growthPoints: 0,
    levelCode: 'NORMAL',
    validUntil: null,
    rewardedOrderIds: [],
    pendingPointOrders: [],
    pointRecords: []
  }
}

global.wx = {
  getAccountInfoSync: () => ({ miniProgram: { envVersion: 'develop' } }),
  getStorageSync: key => storage[key] || '',
  setStorageSync: (key, value) => { storage[key] = value },
  showToast: () => {}
}

const { registerPendingMemberPoints, getMemberProfile } = require('../miniprogram/services/memberService')
const { savePurchasedOrder, updatePurchasedOrder, refundPurchasedOrder } = require('../miniprogram/services/orderService')

async function run() {
  registerPendingMemberPoints(10000, 'ORDER-FLOW-1', '订单完成获得积分')
  savePurchasedOrder({ id: 'ORDER-FLOW-1', type: 'HOTEL', title: '测试订单', status: 'PENDING_USE' })
  assert.strictEqual(getMemberProfile().points, 0, '付款后不得立即发放积分')
  assert.strictEqual(getMemberProfile().pendingPointOrders.length, 1)

  updatePurchasedOrder('ORDER-FLOW-1', { status: 'PENDING_REVIEW' })
  assert.strictEqual(getMemberProfile().points, 100, '订单完成后应发放积分')
  assert.strictEqual(getMemberProfile().pendingPointOrders.length, 0)

  await refundPurchasedOrder('ORDER-FLOW-1')
  assert.strictEqual(getMemberProfile().points, 0, '完成后退款应扣回积分')
  assert.strictEqual(getMemberProfile().growthPoints, 0)
}

run().then(() => console.log('Order point settlement tests passed')).catch(error => {
  console.error(error)
  process.exitCode = 1
})
