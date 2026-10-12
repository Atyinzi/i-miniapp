const assert = require('assert')
const path = require('path')

const servicePath = path.resolve(__dirname, '../miniprogram/services/notificationService.js')
const requestPath = path.resolve(__dirname, '../miniprogram/utils/request.js')

async function withService(handler) {
  const calls = []
  require.cache[requestPath] = { exports: { request: options => {
    calls.push(options)
    if (options.url === '/api/v1/app/notification-config') {
      return Promise.resolve({ wechatSubscriptionConfigured: true, wechatTemplates: [
        { eventType: 'ORDER_PAID', templateId: 'tmplA' },
        { eventType: 'ORDER_REFUNDED', templateId: 'tmplA' },
        { eventType: 'ORDER_COMPLETED', templateId: 'tmplB' }
      ] })
    }
    return Promise.resolve([])
  } } }
  delete require.cache[servicePath]
  try { await handler(require(servicePath), calls) }
  finally { delete require.cache[servicePath]; delete require.cache[requestPath] }
}

async function run() {
  await withService(async (service, calls) => {
    await service.saveNotificationPreferences([
      { eventType: '*', channel: service.CHANNELS.WECHAT, enabled: true }
    ])
    assert.strictEqual(calls[0].method, 'PUT')
    assert.strictEqual(calls[0].url, '/api/v1/app/notification-preferences')
  })

  await withService(async service => {
    let ids = null
    global.wx = { requestSubscribeMessage: options => { ids = options.tmplIds; options.success({ tmplA: 'accept' }) } }
    await service.requestWechatSubscription()
    assert.deepStrictEqual(ids, ['tmplA', 'tmplB'])
    delete global.wx
  })

  console.log('notification-service tests passed')
}

run().catch(error => { console.error(error); process.exit(1) })
