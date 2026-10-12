const assert = require('assert')
const fs = require('fs')
const path = require('path')

const page = fs.readFileSync(path.join(__dirname, '../miniprogram/pages/mall/mall.js'), 'utf8')
const service = fs.readFileSync(path.join(__dirname, '../miniprogram/services/mallService.js'), 'utf8')
const template = fs.readFileSync(path.join(__dirname, '../miniprogram/pages/mall/mall.wxml'), 'utf8')

assert.ok(page.includes('(page.items || []).map(mapProductListItem)'), '商城首屏应直接使用列表数据')
assert.strictEqual(/page\.items[\s\S]{0,120}fetchMallProductDetail/.test(page), false, '首屏不得逐商品请求详情')
assert.ok(page.includes('fetchProductOnDemand(productId)'), '详情应按需加载')
assert.ok(page.includes('CATALOG_CACHE_TTL'), '商品目录应具有短时缓存')
assert.ok(template.includes('mall-skeleton-grid'), '商品加载时应显示骨架屏')
assert.ok(template.includes('retryCatalog'), '商品加载失败应允许重试')
assert.ok(page.includes('wx.getMenuButtonBoundingClientRect'), '自定义导航栏应读取微信胶囊位置')
assert.ok(template.match(/padding-right:\{\{menuSafeRight\}\}px/g).length === 3, '商城三个顶部栏都应避开微信胶囊')

const publicRequests = [...service.matchAll(/url: `\$\{BASE_URL\}\/(categories|products(?:\/\$\{productId\})?)`,[\s\S]{0,80}?auth: false/g)]
assert.strictEqual(publicRequests.length, 3, '分类、商品列表和商品详情应允许游客读取')

console.log('Mall loading flow tests passed')
