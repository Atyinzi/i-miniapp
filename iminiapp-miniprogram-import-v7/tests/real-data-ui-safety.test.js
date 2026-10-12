const assert = require('assert')
const fs = require('fs')
const path = require('path')

const root = path.resolve(__dirname, '..', 'miniprogram')
const mine = fs.readFileSync(path.join(root, 'pages/mine/mine.js'), 'utf8')
  + fs.readFileSync(path.join(root, 'pages/mine/mine.wxml'), 'utf8')
const venues = fs.readFileSync(path.join(root, 'pages/venues/venues.js'), 'utf8')
  + fs.readFileSync(path.join(root, 'pages/venues/venues.wxml'), 'utf8')

for (const residue of ['MEMBER_QR_CELLS', '演示会员码', '2 张可用', '交互演示版']) {
  assert.ok(!mine.includes(residue), `production member UI must not contain ${residue}`)
}
for (const residue of ['price: 368', 'price: 428', 'price: 688', "remaining: 'xx'"]) {
  assert.ok(!venues.includes(residue), `hotel UI must not contain static inventory ${residue}`)
}
assert.ok(venues.includes("rooms: []"))
assert.ok(venues.includes("roomsError: '房型数据暂不可用，请稍后重试'"))
console.log('Real-data UI safety tests passed')
