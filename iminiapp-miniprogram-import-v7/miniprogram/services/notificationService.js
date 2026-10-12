const { request } = require('../utils/request')

const CHANNELS = Object.freeze({
  WECHAT: 'WECHAT_SUBSCRIPTION',
  SMS: 'SMS'
})

function fetchNotificationPreferences() {
  return request({ url: '/api/v1/app/notification-preferences' })
}

function saveNotificationPreferences(preferences) {
  return request({
    url: '/api/v1/app/notification-preferences',
    method: 'PUT',
    data: { preferences }
  })
}

function fetchNotificationConfiguration() {
  return request({ url: '/api/v1/app/notification-config' })
}

function requestWechatSubscription() {
  return fetchNotificationConfiguration().then(config => {
    const templateIds = Array.from(new Set((config && config.wechatTemplates || [])
      .map(item => String(item.templateId || '').trim()).filter(Boolean))).slice(0, 3)
    if (!config || !config.wechatSubscriptionConfigured || !templateIds.length) {
      const error = new Error('微信订阅消息模板尚未配置')
      error.code = 'NOTIFICATION_NOT_CONFIGURED'
      throw error
    }
    if (!wx.requestSubscribeMessage) {
      const error = new Error('当前微信版本不支持订阅消息')
      error.code = 'NOT_SUPPORTED'
      throw error
    }
    return new Promise((resolve, reject) => wx.requestSubscribeMessage({
      tmplIds: templateIds,
      success: resolve,
      fail: reject
    }))
  })
}

function preferenceEnabled(preferences, channel) {
  const value = (preferences || []).find(item => item.eventType === '*' && item.channel === channel)
  return !value || value.enabled !== false
}

module.exports = {
  CHANNELS,
  fetchNotificationPreferences,
  saveNotificationPreferences,
  fetchNotificationConfiguration,
  requestWechatSubscription,
  preferenceEnabled
}
