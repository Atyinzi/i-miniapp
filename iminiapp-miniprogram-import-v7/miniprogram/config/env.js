/** Environment switching is centralized here. Secrets must never be bundled in mini-program code. */
const ENV = Object.freeze({
  development: Object.freeze({
    baseUrl: 'http://127.0.0.1:8080',
    useMock: false,
    authMode: 'wechat',
    enableDevelopmentTools: false,
    staffVerificationEnabled: true
  }),
  test: Object.freeze({
    baseUrl: '',
    useMock: false,
    authMode: 'wechat',
    enableDevelopmentTools: false,
    staffVerificationEnabled: false
  }),
  production: Object.freeze({
    baseUrl: '',
    useMock: false,
    authMode: 'wechat',
    enableDevelopmentTools: false,
    // Enable only for a dedicated staff release after the production API is configured.
    staffVerificationEnabled: false
  })
})

function runtimeApi() {
  return typeof wx === 'undefined' ? null : wx
}

function resolveEnvironment(runtime = runtimeApi()) {
  try {
    const deviceInfo = runtime && runtime.getDeviceInfo ? runtime.getDeviceInfo() : null
    const systemInfo = runtime && runtime.getSystemInfoSync ? runtime.getSystemInfoSync() : null
    const platform = String((deviceInfo && deviceInfo.platform) || (systemInfo && systemInfo.platform) || '').toLowerCase()
    if (platform === 'devtools') return 'development'
    const accountInfo = runtime && runtime.getAccountInfoSync ? runtime.getAccountInfoSync() : null
    const envVersion = accountInfo && accountInfo.miniProgram ? accountInfo.miniProgram.envVersion : 'develop'
    if (envVersion === 'release') return 'production'
    if (envVersion === 'trial') return 'test'
  } catch (error) {}
  return 'development'
}

function isPlaceholderBackend(baseUrl) {
  return /\.example\.com(?=\/|$)/i.test(String(baseUrl || ''))
}

function isSafeRemoteBaseUrl(baseUrl) {
  const normalized = String(baseUrl || '').trim().toLowerCase()
  if (!normalized.startsWith('https://') || isPlaceholderBackend(normalized)) return false
  return !/^https:\/\/(localhost|127\.0\.0\.1|0\.0\.0\.0)(:\d+)?(?=\/|$)/.test(normalized)
}

const runtime = runtimeApi()
const environment = resolveEnvironment(runtime)
const selected = ENV[environment]
const usesPlaceholderBackend = isPlaceholderBackend(selected.baseUrl)
const lacksRequestRuntime = !runtime || typeof runtime.request !== 'function'
const isProduction = environment === 'production'
const isRemoteBuild = environment === 'production' || environment === 'test'
const backendReady = !isRemoteBuild || isSafeRemoteBaseUrl(selected.baseUrl)
const productionReady = !isProduction || backendReady

module.exports = {
  ...selected,
  environment,
  // Trial and release builds must never fall back to fixtures. Only local/Node development may use them.
  useMock: environment === 'development' ? (selected.useMock || lacksRequestRuntime) : false,
  usesPlaceholderBackend,
  backendReady,
  productionReady,
  configurationError: backendReady ? ''
    : (isProduction ? 'PRODUCTION_API_BASE_URL_NOT_CONFIGURED' : 'TEST_API_BASE_URL_NOT_CONFIGURED')
}
