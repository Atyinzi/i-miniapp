const assert = require('assert')
const fs = require('fs')
const path = require('path')

const root = path.join(__dirname, '..', 'miniprogram')
const session = fs.readFileSync(path.join(root, 'utils', 'session.js'), 'utf8')
const env = fs.readFileSync(path.join(root, 'config', 'env.js'), 'utf8')

assert.ok(!session.includes('/auth/mock-login'), 'shipped session code must use only real WeChat login')
assert.ok(!env.includes('api.example.com'), 'no placeholder production domain may be bundled')

const forbiddenUi = /(演示|模拟支付|测试数据|后端接入后|mock-login)/i
function scan(directory) {
  for (const entry of fs.readdirSync(directory, { withFileTypes: true })) {
    const target = path.join(directory, entry.name)
    if (entry.isDirectory()) scan(target)
    else if (/\.(js|wxml)$/.test(entry.name)) {
      const source = fs.readFileSync(target, 'utf8')
      assert.ok(!forbiddenUi.test(source), `production-visible residue in ${target}`)
    }
  }
}
scan(path.join(root, 'pages'))

console.log('Production residue scan tests passed')
