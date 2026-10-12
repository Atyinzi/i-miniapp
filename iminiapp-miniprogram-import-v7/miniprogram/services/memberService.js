const { formatDateTime, createClientRequestId, fenToYuan } = require('../utils/contract')
const { request } = require('../utils/request')
const config = require('../config/env')

const MEMBER_PROFILE_KEY = 'iminiappMemberProfile'
const MEMBER_LEVELS = [
  { code: 'NORMAL', name: '普通会员', badge: '普', minPoints: 0, discountRate: 1, discountLabel: '无折扣' },
  { code: 'SILVER', name: '白银会员', badge: '银', minPoints: 500, discountRate: 0.95, discountLabel: '9.5折' },
  { code: 'GOLD', name: '黄金会员', badge: '金', minPoints: 1500, discountRate: 0.9, discountLabel: '9折' },
  { code: 'PLATINUM', name: '铂金会员', badge: '铂', minPoints: 3000, discountRate: 0.85, discountLabel: '8.5折' },
  { code: 'DIAMOND', name: '钻石会员', badge: '钻', minPoints: 6000, discountRate: 0.8, discountLabel: '8折' },
  { code: 'BLACK_GOLD', name: '黑金会员', badge: '黑', minPoints: 10000, discountRate: 0.7, discountLabel: '7折' }
]

const DEFAULT_MEMBER = {
  nickname: '微信用户', avatarText: '微', avatarUrl: '', memberNo: '登录后生成',
  points: 0, growthPoints: 0, balanceFen: 0, balanceLabel: '0.00', coupons: 0,
  levelCode: 'NORMAL', level: '普通会员', levelBadge: '普',
  discountRate: 1, discountLabel: '无折扣', validUntil: null,
  rewardedOrderIds: [], pendingPointOrders: [], pointRecords: []
}

function dateValue(date) {
  return `${date.getFullYear()}-${String(date.getMonth() + 1).padStart(2, '0')}-${String(date.getDate()).padStart(2, '0')}`
}

function oneYearFrom(date = new Date()) {
  const result = new Date(date)
  result.setFullYear(result.getFullYear() + 1)
  return dateValue(result)
}

function levelForPoints(points) {
  return [...MEMBER_LEVELS].reverse().find(level => points >= level.minPoints) || MEMBER_LEVELS[0]
}

function levelByCode(code) {
  return MEMBER_LEVELS.find(level => level.code === code) || MEMBER_LEVELS[0]
}

function normalizeMember(source = {}, now = new Date()) {
  const member = { ...DEFAULT_MEMBER, ...source }
  delete member.expiredNotice
  delete member.upgradeNotice
  member.points = Math.max(0, Number(member.points) || 0)
  member.growthPoints = Math.max(0, Number(source.growthPoints === undefined ? member.points : source.growthPoints) || 0)
  const legacyBalanceFen = source.balanceFen === undefined && source.balance !== undefined
    ? Math.round(Number(source.balance) * 100)
    : member.balanceFen
  member.balanceFen = Math.max(0, Math.round(Number(legacyBalanceFen) || 0))
  member.balanceLabel = fenToYuan(member.balanceFen)
  delete member.balance
  member.rewardedOrderIds = Array.isArray(member.rewardedOrderIds) ? member.rewardedOrderIds : []
  member.pendingPointOrders = Array.isArray(member.pendingPointOrders) ? member.pendingPointOrders : []
  member.pointRecords = Array.isArray(member.pointRecords) ? member.pointRecords : []

  const today = dateValue(now)
  if (member.levelCode !== 'NORMAL' && member.validUntil && member.validUntil < today) {
    member.levelCode = 'NORMAL'
    member.growthPoints = 0
    member.validUntil = null
    member.expiredNotice = '会员有效期已结束，当前已恢复为普通会员'
  }

  const currentLevel = levelByCode(member.levelCode)
  const qualifiedLevel = levelForPoints(member.growthPoints)
  if (qualifiedLevel.minPoints > currentLevel.minPoints) {
    member.levelCode = qualifiedLevel.code
    member.validUntil = oneYearFrom(now)
    member.upgradeNotice = `恭喜升级为${qualifiedLevel.name}`
  }

  const level = levelByCode(member.levelCode)
  const currentIndex = MEMBER_LEVELS.findIndex(item => item.code === level.code)
  const nextLevel = MEMBER_LEVELS[currentIndex + 1] || null
  const range = nextLevel ? nextLevel.minPoints - level.minPoints : 1
  const progress = nextLevel ? Math.min(100, Math.max(0, (member.growthPoints - level.minPoints) / range * 100)) : 100

  return {
    ...member,
    level: level.name,
    levelBadge: level.badge,
    discountRate: level.discountRate,
    discountLabel: level.discountLabel,
    progress: Math.round(progress),
    nextLevelName: nextLevel ? nextLevel.name : '最高等级',
    pointsToNext: nextLevel ? Math.max(0, nextLevel.minPoints - member.growthPoints) : 0,
    levelHint: nextLevel ? `距${nextLevel.name}还差 ${Math.max(0, nextLevel.minPoints - member.growthPoints)} 成长积分` : '已达到最高会员等级',
    validityLabel: level.code === 'NORMAL' ? '升级后有效期一年' : `有效期至 ${member.validUntil}`
  }
}

function saveMemberProfile(member) {
  const normalized = normalizeMember(member)
  const stored = { ...normalized, serverBacked: !config.useMock }
  delete stored.expiredNotice
  delete stored.upgradeNotice
  if (typeof wx !== 'undefined' && wx.setStorageSync) wx.setStorageSync(MEMBER_PROFILE_KEY, stored)
  return normalized
}

function getMemberProfile() {
  const stored = typeof wx !== 'undefined' && wx.getStorageSync ? wx.getStorageSync(MEMBER_PROFILE_KEY) : null
  const usable = stored && typeof stored === 'object' && (config.useMock || stored.serverBacked === true)
  return saveMemberProfile(usable ? stored : DEFAULT_MEMBER)
}

function fetchMemberProfile() {
  return request({
    url: '/api/members/me',
    method: 'GET',
    mock: () => ({ code: 200, message: 'success', data: toMemberApiData() })
  }).then(saveMemberProfile)
}

function toMemberApiData(member = getMemberProfile()) {
  return {
    nickname: member.nickname,
    avatarText: member.avatarText,
    avatarUrl: member.avatarUrl,
    memberNo: member.memberNo,
    points: member.points,
    growthPoints: member.growthPoints,
    balanceFen: member.balanceFen,
    coupons: member.coupons,
    levelCode: member.levelCode,
    level: member.level,
    levelBadge: member.levelBadge,
    discountRate: member.discountRate,
    discountLabel: member.discountLabel,
    validUntil: member.validUntil,
    rewardedOrderIds: member.rewardedOrderIds,
    pendingPointOrders: member.pendingPointOrders,
    pointRecords: member.pointRecords,
    progress: member.progress,
    nextLevelName: member.nextLevelName,
    pointsToNext: member.pointsToNext,
    levelHint: member.levelHint,
    validityLabel: member.validityLabel,
    ...(member.expiredNotice ? { expiredNotice: member.expiredNotice } : {}),
    ...(member.upgradeNotice ? { upgradeNotice: member.upgradeNotice } : {})
  }
}

function updateMemberProfile(patch) {
  return saveMemberProfile({ ...getMemberProfile(), ...patch })
}

function registerPendingMemberPoints(amountFen, orderId, description = '订单完成后获得积分') {
  if (!config.useMock) return getMemberProfile()
  const member = getMemberProfile()
  if (!orderId || member.rewardedOrderIds.includes(orderId) || member.pendingPointOrders.some(item => item.orderId === orderId)) return member
  const earnedPoints = Math.max(0, Math.floor(Number(amountFen || 0) / 100))
  if (!earnedPoints) return member
  const updated = saveMemberProfile({
    ...member,
    pendingPointOrders: [{ orderId, amountFen: Math.round(Number(amountFen)), points: earnedPoints, description, registeredAt: formatDateTime() }, ...member.pendingPointOrders].slice(0, 100)
  })
  return updated
}

function settleMemberPoints(orderId) {
  if (!config.useMock) return getMemberProfile()
  const member = getMemberProfile()
  if (!orderId || member.rewardedOrderIds.includes(orderId)) return member
  const pending = member.pendingPointOrders.find(item => item.orderId === orderId)
  if (!pending) return member
  const updated = saveMemberProfile({
    ...member,
    points: member.points + pending.points,
    growthPoints: member.growthPoints + pending.points,
    rewardedOrderIds: [orderId, ...member.rewardedOrderIds].slice(0, 100),
    pendingPointOrders: member.pendingPointOrders.filter(item => item.orderId !== orderId),
    pointRecords: [{ orderId, points: pending.points, description: pending.description, createdAt: formatDateTime() }, ...member.pointRecords].slice(0, 30)
  })
  return updated
}

function cancelPendingMemberPoints(orderId) {
  if (!config.useMock) return getMemberProfile()
  const member = getMemberProfile()
  const pending = member.pendingPointOrders.find(item => item.orderId === orderId)
  if (!pending) return member
  const updated = saveMemberProfile({
    ...member,
    pendingPointOrders: member.pendingPointOrders.filter(item => item.orderId !== orderId)
  })
  return updated
}

function reverseMemberPoints(orderId) {
  if (!config.useMock) return getMemberProfile()
  const member = getMemberProfile()
  const record = member.pointRecords.find(item => item.orderId === orderId && item.points > 0)
  if (!record || !member.rewardedOrderIds.includes(orderId)) return cancelPendingMemberPoints(orderId)
  const points = Math.max(0, member.points - record.points)
  const growthPoints = Math.max(0, member.growthPoints - record.points)
  const recalculatedLevel = levelForPoints(growthPoints)
  const pending = {
    orderId,
    amountFen: record.points * 100,
    points: record.points,
    description: `${record.description}退款扣回`
  }
  const updated = saveMemberProfile({
    ...member,
    points,
    growthPoints,
    levelCode: recalculatedLevel.code,
    validUntil: recalculatedLevel.code === 'NORMAL' ? null : member.validUntil,
    rewardedOrderIds: member.rewardedOrderIds.filter(id => id !== orderId),
    pointRecords: [{ orderId: `${orderId}-REVERSAL-${Date.now()}`, relatedOrderId: orderId, points: -record.points, description: pending.description, createdAt: formatDateTime() }, ...member.pointRecords].slice(0, 30)
  })
  return updated
}

function checkMemberUpgrade() {
  const clientRequestId = createClientRequestId('member_upgrade')
  return request({
    url: '/api/members/me/upgrade-check',
    method: 'POST',
    data: { clientRequestId },
    mock: () => ({ code: 200, message: 'success', data: toMemberApiData() })
  }).then(saveMemberProfile)
}

function calculateMemberPrice(amountFen, member = getMemberProfile()) {
  const originalAmountFen = Math.max(0, Math.round(Number(amountFen) || 0))
  const payableAmountFen = Math.round(originalAmountFen * member.discountRate)
  return {
    originalAmountFen,
    discountAmountFen: originalAmountFen - payableAmountFen,
    payableAmountFen,
    discountRate: member.discountRate,
    discountLabel: member.discountLabel,
    level: member.level,
    validUntil: member.validUntil
  }
}

module.exports = {
  MEMBER_PROFILE_KEY,
  MEMBER_LEVELS,
  DEFAULT_MEMBER,
  getMemberProfile,
  fetchMemberProfile,
  updateMemberProfile,
  registerPendingMemberPoints,
  settleMemberPoints,
  cancelPendingMemberPoints,
  reverseMemberPoints,
  checkMemberUpgrade,
  calculateMemberPrice,
  normalizeMember,
  toMemberApiData,
  oneYearFrom
}
