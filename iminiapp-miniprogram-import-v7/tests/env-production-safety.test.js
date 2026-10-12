const assert = require('assert')

const envPath = require.resolve('../miniprogram/config/env')

function loadWith(runtime) {
  delete require.cache[envPath]
  if (runtime === undefined) delete global.wx
  else global.wx = runtime
  return require(envPath)
}

const release = loadWith({
  getDeviceInfo: () => ({ platform: 'ios' }),
  getAccountInfoSync: () => ({ miniProgram: { envVersion: 'release' } }),
  request: () => {}
})
assert.strictEqual(release.environment, 'production')
assert.strictEqual(release.baseUrl, '', 'release must not bundle a placeholder or temporary API host')
assert.strictEqual(release.useMock, false, 'release must never fall back to mock data')
assert.strictEqual(release.productionReady, false)
assert.strictEqual(release.configurationError, 'PRODUCTION_API_BASE_URL_NOT_CONFIGURED')

const trial = loadWith({
  getDeviceInfo: () => ({ platform: 'ios' }),
  getAccountInfoSync: () => ({ miniProgram: { envVersion: 'trial' } }),
  request: () => {}
})
assert.strictEqual(trial.environment, 'test')
assert.strictEqual(trial.baseUrl, '', 'trial must wait for an explicitly configured API host')
assert.strictEqual(trial.useMock, false, 'trial must never fall back to fixtures')
assert.strictEqual(trial.backendReady, false)
assert.strictEqual(trial.configurationError, 'TEST_API_BASE_URL_NOT_CONFIGURED')

const devtools = loadWith({
  getDeviceInfo: () => ({ platform: 'devtools' }),
  getAccountInfoSync: () => ({ miniProgram: { envVersion: 'develop' } }),
  request: () => {}
})
assert.strictEqual(devtools.environment, 'development')
assert.strictEqual(devtools.baseUrl, 'http://127.0.0.1:8080')
assert.strictEqual(devtools.useMock, false)

const nodeContractRuntime = loadWith(undefined)
assert.strictEqual(nodeContractRuntime.environment, 'development')
assert.strictEqual(nodeContractRuntime.useMock, true)

delete require.cache[envPath]
delete global.wx
console.log('Environment production safety tests passed')
