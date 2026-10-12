/** 前后端契约公共工具：时间、金额和幂等键只在这里生成。 */
function pad(value) {
  return String(value).padStart(2, '0')
}

function formatDateTime(value = new Date()) {
  const date = value instanceof Date ? value : new Date(value)
  if (Number.isNaN(date.getTime())) throw new Error('INVALID_DATE_TIME')
  return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())} ${pad(date.getHours())}:${pad(date.getMinutes())}:${pad(date.getSeconds())}`
}

function createClientRequestId(prefix = 'wx') {
  const random = Math.random().toString(36).slice(2, 10)
  return `${prefix}_${Date.now()}_${random}`
}

function yuanToFen(value) {
  const amount = Number(value)
  if (!Number.isFinite(amount)) throw new Error('INVALID_AMOUNT')
  return Math.round(amount * 100)
}

function fenToYuan(value) {
  const amount = Number(value)
  if (!Number.isInteger(amount)) throw new Error('INVALID_AMOUNT_FEN')
  return (amount / 100).toFixed(2)
}

module.exports = { formatDateTime, createClientRequestId, yuanToFen, fenToYuan }
