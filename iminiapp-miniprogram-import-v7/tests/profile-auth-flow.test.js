const assert = require('assert')
const fs = require('fs')
const path = require('path')

const pageScript = fs.readFileSync(path.join(__dirname, '../miniprogram/pages/profile-auth/profile-auth.js'), 'utf8')
const template = fs.readFileSync(path.join(__dirname, '../miniprogram/pages/profile-auth/profile-auth.wxml'), 'utf8')

assert.strictEqual(pageScript.includes('wx.getUserProfile'), false, '不应继续依赖已调整的 getUserProfile 资料接口')
assert.ok(template.includes('open-type="chooseAvatar"'), '应使用微信头像选择能力')
assert.ok(template.includes('type="nickname"'), '应使用微信昵称快速填写能力')
assert.ok(template.includes('open-type="getPhoneNumber"'), '登录后应提供手机号授权能力')
assert.ok(pageScript.includes("resumeSession({ displayName: '微信用户', avatarUrl: '' })"))
assert.ok(template.indexOf('open-type="getPhoneNumber"') < template.indexOf('open-type="chooseAvatar"'), '登录后应优先引导绑定手机号')

console.log('Profile authorization flow tests passed')
