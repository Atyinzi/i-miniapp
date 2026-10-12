<script setup>
import { computed, onMounted, reactive, ref } from 'vue'
import CulturalMarketingPanel from './CulturalMarketingPanel.vue'
import CulturalFulfillmentPanel from './CulturalFulfillmentPanel.vue'
import CulturalReportPanel from './CulturalReportPanel.vue'
import {
  createCulturalCategory, createCulturalProduct, createCulturalSku, getCulturalCategories,
  getCulturalProduct, getCulturalProducts, updateCulturalCategory, updateCulturalProduct,
  updateCulturalProductStatus, updateCulturalSku, getCulturalStocks, adjustCulturalStock,
  batchAdjustCulturalStock, getCulturalStockLogs
} from '../services/culturalStore'

const tab = ref('products')
const categories = ref([])
const products = ref([])
const total = ref(0)
const totalPages = ref(0)
const page = ref(1)
const loading = ref(false)
const error = ref('')
const notice = ref('')
const modal = ref('')
const form = reactive({})
const filters = reactive({ keyword: '', categoryId: '', status: '', pageSize: 20 })
const stocks = ref([])
const stockTotal = ref(0)
const stockTotalPages = ref(0)
const stockPage = ref(1)
const stockLogs = ref([])
const logTotal = ref(0)
const logTotalPages = ref(0)
const logPage = ref(1)
const inventoryView = ref('stocks')
const stockFilters = reactive({ keyword: '', lowStock: false, pageSize: 20, logSkuId: '' })
const enabledCategories = computed(() => categories.value.filter(item => item.status === 'ACTIVE'))
const currentStockStats = computed(() => ({
  onHand: stocks.value.reduce((sum, item) => sum + Number(item.onHandQuantity || 0), 0),
  locked: stocks.value.reduce((sum, item) => sum + Number(item.lockedQuantity || 0), 0),
  available: stocks.value.reduce((sum, item) => sum + Number(item.availableQuantity || 0), 0),
  low: stocks.value.filter(item => item.lowStock).length
}))
const statusText = { ACTIVE: '已上架', DRAFT: '草稿', INACTIVE: '已下架' }
const stockTypeText = { INBOUND: '采购入库', OUTBOUND: '业务出库', ADJUSTMENT: '盘点调整', RETURN: '退货入库', DAMAGE: '报损出库', SALE_DEDUCT: '销售扣减' }

async function run(action) {
  try { loading.value = true; error.value = ''; await action() }
  catch (e) { error.value = e.message }
  finally { loading.value = false }
}
async function loadCategories() { categories.value = await getCulturalCategories() || [] }
async function loadProducts(targetPage = page.value) {
  page.value = targetPage
  const data = await getCulturalProducts({
    keyword: filters.keyword || undefined,
    categoryId: filters.categoryId || undefined,
    status: filters.status || undefined,
    page: page.value,
    pageSize: filters.pageSize
  })
  products.value = data?.items || []
  total.value = Number(data?.total || 0)
  totalPages.value = Number(data?.totalPages || 0)
}
async function refresh() { await run(async () => { await Promise.all([loadCategories(), loadProducts(1)]) }) }
async function loadStocks(targetPage = stockPage.value) {
  stockPage.value = targetPage
  const data = await getCulturalStocks({ keyword: stockFilters.keyword || undefined, lowStock: stockFilters.lowStock || undefined, page: stockPage.value, pageSize: stockFilters.pageSize })
  stocks.value = data?.items || []; stockTotal.value = Number(data?.total || 0); stockTotalPages.value = Number(data?.totalPages || 0)
}
async function loadLogs(targetPage = logPage.value) {
  logPage.value = targetPage
  const data = await getCulturalStockLogs({ skuId: stockFilters.logSkuId || undefined, page: logPage.value, pageSize: stockFilters.pageSize })
  stockLogs.value = data?.items || []; logTotal.value = Number(data?.total || 0); logTotalPages.value = Number(data?.totalPages || 0)
}
async function switchTab(value) {
  tab.value = value
  if (value === 'inventory' && !stocks.value.length) await run(() => loadStocks(1))
}
async function switchInventoryView(value) {
  inventoryView.value = value
  await run(() => value === 'stocks' ? loadStocks(stockPage.value) : loadLogs(1))
}
function resetForm(values) { Object.keys(form).forEach(key => delete form[key]); Object.assign(form, values) }

function openCategory(item) {
  resetForm(item ? { ...item } : { categoryCode: '', name: '', parentId: null, sortOrder: 0, status: 'ACTIVE' })
  modal.value = 'category'
}
async function openProduct(item) {
  await run(async () => {
    if (item) {
      const detail = await getCulturalProduct(item.id)
      resetForm({
        ...detail,
        marketPriceYuan: centToYuan(detail.marketPriceCent),
        images: (detail.images || []).map(image => ({ ...image })),
        skus: detail.skus || []
      })
    } else {
      resetForm({
        productCode: '', name: '', categoryId: enabledCategories.value[0]?.id || '', description: '',
        coverUrl: '', detailHtml: '', marketPriceYuan: '', weightGram: '', status: 'DRAFT', images: [],
        skus: [{ skuCode: '', name: '标准款', priceYuan: '', status: 'ACTIVE', attributes: '{}', initialStock: 0, warningThreshold: 10 }]
      })
    }
    modal.value = 'product'
  })
}
function addImage() { form.images.push({ imageUrl: '', imageType: 'GALLERY', sortOrder: form.images.length }) }
function addInitialSku() { form.skus.push({ skuCode: '', name: '', priceYuan: '', status: 'ACTIVE', attributes: '{}', initialStock: 0, warningThreshold: 10 }) }
function openSku(item) {
  resetForm(item ? {
    productId: form.id, skuId: item.skuId, skuCode: item.skuCode, name: item.name,
    priceYuan: centToYuan(item.priceCent), status: item.status, attributes: item.attributes || '{}',
    warningThreshold: item.warningThreshold, onHandQuantity: item.onHandQuantity,
    lockedQuantity: item.lockedQuantity, availableQuantity: item.availableQuantity
  } : {
    productId: form.id, skuCode: '', name: '', priceYuan: '', status: 'ACTIVE', attributes: '{}',
    initialStock: 0, warningThreshold: 10
  })
  modal.value = 'sku'
}
function openAdjustment(item) {
  resetForm({ stock: item, changeType: 'INBOUND', quantity: 1, referenceNo: '', remark: '' })
  modal.value = 'stock-adjustment'
}
function openBatchAdjustment() {
  resetForm({ items: [{ skuId: '', changeType: 'INBOUND', quantity: 1, referenceNo: '', remark: '' }] })
  modal.value = 'stock-batch'
}
function addBatchRow() { if (form.items.length < 200) form.items.push({ skuId: '', changeType: 'INBOUND', quantity: 1, referenceNo: '', remark: '' }) }
function signedDelta(type, quantity) {
  const value = Number(quantity)
  if (['OUTBOUND', 'DAMAGE'].includes(type)) return -Math.abs(value)
  if (['INBOUND', 'RETURN'].includes(type)) return Math.abs(value)
  return value
}

async function submitCategory() {
  if (!form.categoryCode || !form.name) { error.value = '分类编码和名称不能为空'; return }
  await run(async () => {
    const payload = { categoryCode: form.categoryCode, name: form.name, parentId: numberOrNull(form.parentId), sortOrder: Number(form.sortOrder || 0), status: form.status }
    form.id ? await updateCulturalCategory(form.id, payload) : await createCulturalCategory(payload)
    await loadCategories(); notice.value = '分类保存成功'; modal.value = ''
  })
}
async function submitProduct() {
  if (!form.productCode || !form.name || !form.categoryId) { error.value = '商品编码、名称和分类不能为空'; return }
  if (!form.id && (!form.skus?.length || form.skus.some(item => !item.skuCode || !item.name || item.priceYuan === ''))) {
    error.value = '新商品至少需要一个填写完整的SKU'; return
  }
  await run(async () => {
    const payload = {
      productCode: form.productCode, name: form.name, categoryId: Number(form.categoryId),
      description: form.description || null, coverUrl: form.coverUrl || null, detailHtml: form.detailHtml || null,
      marketPriceCent: yuanToCentOrNull(form.marketPriceYuan), weightGram: numberOrNull(form.weightGram), status: form.status,
      images: (form.images || []).filter(item => item.imageUrl).map((item, index) => ({ imageUrl: item.imageUrl, imageType: item.imageType || 'GALLERY', sortOrder: Number(item.sortOrder ?? index) })),
      skus: form.id ? [] : form.skus.map(item => ({
        skuCode: item.skuCode, name: item.name, priceCent: yuanToCent(item.priceYuan), status: item.status,
        attributes: item.attributes || '{}', initialStock: Number(item.initialStock || 0), warningThreshold: Number(item.warningThreshold || 0)
      }))
    }
    if (form.id) await updateCulturalProduct(form.id, payload)
    else await createCulturalProduct(payload)
    await loadProducts(form.id ? page.value : 1); notice.value = '商品保存成功'; modal.value = ''
  })
}
async function submitSku() {
  if (!form.name || form.priceYuan === '' || (!form.skuId && !form.skuCode)) { error.value = 'SKU编码、名称和价格不能为空'; return }
  await run(async () => {
    const common = { name: form.name, priceCent: yuanToCent(form.priceYuan), status: form.status, attributes: form.attributes || '{}', warningThreshold: Number(form.warningThreshold || 0) }
    form.skuId ? await updateCulturalSku(form.skuId, common) : await createCulturalSku(form.productId, { ...common, skuCode: form.skuCode, initialStock: Number(form.initialStock || 0) })
    notice.value = 'SKU保存成功'; modal.value = ''; await loadProducts(page.value)
  })
}
async function submitAdjustment() {
  const delta = signedDelta(form.changeType, form.quantity)
  if (!Number.isInteger(delta) || delta === 0) { error.value = '库存变更数量必须为非零整数'; return }
  await run(async () => {
    await adjustCulturalStock(form.stock.skuId, { quantityDelta: delta, changeType: form.changeType, referenceNo: form.referenceNo || null, remark: form.remark || null }, idempotencyKey('stock'))
    await loadStocks(stockPage.value); notice.value = '库存调整成功'; modal.value = ''
  })
}
async function submitBatchAdjustment() {
  const rows = (form.items || []).filter(item => item.skuId)
  if (!rows.length || rows.some(item => !Number.isInteger(Number(item.quantity)) || Number(item.quantity) === 0)) { error.value = '请至少填写一条SKU及非零整数数量'; return }
  if (new Set(rows.map(item => String(item.skuId))).size !== rows.length) { error.value = '同一批次不能重复选择SKU'; return }
  await run(async () => {
    await batchAdjustCulturalStock({ items: rows.map(item => ({ skuId: Number(item.skuId), quantityDelta: signedDelta(item.changeType, item.quantity), changeType: item.changeType, referenceNo: item.referenceNo || null, remark: item.remark || null })) }, idempotencyKey('stock-batch'))
    await loadStocks(1); notice.value = `批量库存调整成功，共 ${rows.length} 条`; modal.value = ''
  })
}
async function changeStatus(item) {
  const next = item.status === 'ACTIVE' ? 'INACTIVE' : 'ACTIVE'
  await run(async () => { await updateCulturalProductStatus(item.id, next); await loadProducts(page.value); notice.value = next === 'ACTIVE' ? '商品已上架' : '商品已下架' })
}
async function inspectSkus(item) {
  await run(async () => {
    const detail = await getCulturalProduct(item.id)
    resetForm({ ...detail, marketPriceYuan: centToYuan(detail.marketPriceCent), images: detail.images || [], skus: detail.skus || [] })
    modal.value = 'sku-list'
  })
}
function money(cent) { return cent == null ? '-' : `¥${(Number(cent) / 100).toFixed(2)}` }
function centToYuan(cent) { return cent == null ? '' : (Number(cent) / 100).toFixed(2) }
function yuanToCent(value) { return Math.round(Number(value || 0) * 100) }
function yuanToCentOrNull(value) { return value === '' || value == null ? null : yuanToCent(value) }
function numberOrNull(value) { return value === '' || value == null ? null : Number(value) }
function dateTime(value) { return value ? String(value).replace('T', ' ').slice(0, 16) : '-' }
function statusClass(status) { return status === 'ACTIVE' ? '' : status === 'DRAFT' ? 'draft' : 'inactive' }
function idempotencyKey(prefix) { return `${prefix}-${Date.now()}-${Math.random().toString(36).slice(2, 9)}` }

onMounted(refresh)
</script>

<template>
  <div>
    <div class="toolbar"><div><h2>文创商城</h2><p>商品、库存、营销、订单履约、售后与运营数据统一管理。</p></div><button v-if="tab==='products'" class="primary" @click="openProduct()">新增商品</button><button v-else-if="tab==='categories'" class="primary" @click="openCategory()">新增分类</button><button v-else-if="tab==='inventory'" class="primary" @click="openBatchAdjustment">批量出入库</button></div>
    <p v-if="error" class="error cultural-message">{{ error }}</p><p v-if="notice" class="success cultural-message">{{ notice }}</p>
    <div class="content-tabs"><button :class="{active:tab==='products'}" @click="switchTab('products')">商品管理</button><button :class="{active:tab==='categories'}" @click="switchTab('categories')">分类管理</button><button :class="{active:tab==='inventory'}" @click="switchTab('inventory')">库存管理</button><button :class="{active:tab==='marketing'}" @click="switchTab('marketing')">营销管理</button><button :class="{active:tab==='fulfillment'}" @click="switchTab('fulfillment')">订单与售后</button><button :class="{active:tab==='reports'}" @click="switchTab('reports')">运营报表</button></div>

    <section v-if="tab==='products'">
      <div class="filters panel cultural-filters"><input v-model.trim="filters.keyword" placeholder="商品名称或编码" @keyup.enter="loadProducts(1)" /><select v-model="filters.categoryId"><option value="">全部分类</option><option v-for="item in categories" :key="item.id" :value="item.id">{{ item.name }}</option></select><select v-model="filters.status"><option value="">全部状态</option><option value="ACTIVE">已上架</option><option value="DRAFT">草稿</option><option value="INACTIVE">已下架</option></select><button class="primary" :disabled="loading" @click="loadProducts(1)">查询</button></div>
      <div class="panel table-wrap"><table><thead><tr><th>商品</th><th>分类</th><th>起售价</th><th>SKU/库存</th><th>状态</th><th>更新时间</th><th>操作</th></tr></thead><tbody><tr v-if="!products.length"><td colspan="7">暂无符合条件的文创商品。</td></tr><tr v-for="item in products" :key="item.id"><td><div class="product-cell"><img v-if="item.coverUrl" :src="item.coverUrl" alt="" /><span v-else>创</span><div><b>{{ item.name }}</b><small>{{ item.productCode }} · #{{ item.id }}</small></div></div></td><td>{{ item.categoryName }}</td><td><b>{{ money(item.minimumPriceCent) }}</b></td><td>{{ item.skuCount }} 个 SKU<small>可售 {{ item.totalAvailable }}</small></td><td><span class="badge" :class="statusClass(item.status)">{{ statusText[item.status] || item.status }}</span></td><td>{{ dateTime(item.updatedAt) }}</td><td><div class="actions cultural-actions"><button @click="openProduct(item)">编辑</button><button @click="inspectSkus(item)">SKU</button><button :class="item.status==='ACTIVE'?'danger':'primary'" @click="changeStatus(item)">{{ item.status==='ACTIVE'?'下架':'上架' }}</button></div></td></tr></tbody></table><footer class="pagination"><span>共 {{ total }} 条，第 {{ page }}/{{ totalPages || 1 }} 页</span><div><button :disabled="page<=1||loading" @click="loadProducts(page-1)">上一页</button><button :disabled="page>=totalPages||loading" @click="loadProducts(page+1)">下一页</button></div></footer></div>
    </section>

    <section v-if="tab==='categories'">
      <div class="panel table-wrap"><table><thead><tr><th>分类</th><th>编码</th><th>上级分类</th><th>排序</th><th>商品数量</th><th>状态</th><th>操作</th></tr></thead><tbody><tr v-if="!categories.length"><td colspan="7">暂无商品分类。</td></tr><tr v-for="item in categories" :key="item.id"><td><b>{{ item.name }}</b><small>#{{ item.id }}</small></td><td>{{ item.categoryCode }}</td><td>{{ categories.find(v=>v.id===item.parentId)?.name || '-' }}</td><td>{{ item.sortOrder }}</td><td>{{ item.productCount }}</td><td><span class="badge" :class="statusClass(item.status)">{{ statusText[item.status] || item.status }}</span></td><td><button @click="openCategory(item)">编辑</button></td></tr></tbody></table></div>
    </section>

    <section v-if="tab==='inventory'">
      <div class="inventory-summary"><article><span>当前页现货</span><strong>{{ currentStockStats.onHand }}</strong><small>实体库存总量</small></article><article><span>当前页锁定</span><strong>{{ currentStockStats.locked }}</strong><small>待支付订单占用</small></article><article><span>当前页可售</span><strong>{{ currentStockStats.available }}</strong><small>允许继续销售</small></article><article :class="{warning:currentStockStats.low}"><span>当前页预警SKU</span><strong>{{ currentStockStats.low }}</strong><small>可售库存≤预警值</small></article></div>
      <div class="content-tabs inventory-tabs"><button :class="{active:inventoryView==='stocks'}" @click="switchInventoryView('stocks')">库存看板</button><button :class="{active:inventoryView==='logs'}" @click="switchInventoryView('logs')">库存流水</button></div>
      <template v-if="inventoryView==='stocks'">
        <div class="filters panel cultural-filters"><input v-model.trim="stockFilters.keyword" placeholder="商品、SKU名称或编码" @keyup.enter="loadStocks(1)" /><label class="check-label inventory-check"><input v-model="stockFilters.lowStock" type="checkbox" />仅看库存预警</label><button class="primary" :disabled="loading" @click="loadStocks(1)">查询</button></div>
        <div class="panel table-wrap"><table><thead><tr><th>商品/SKU</th><th>现货</th><th>锁定</th><th>可售</th><th>预警阈值</th><th>版本/更新时间</th><th>操作</th></tr></thead><tbody><tr v-if="!stocks.length"><td colspan="7">暂无符合条件的库存记录。</td></tr><tr v-for="item in stocks" :key="item.skuId" :class="{'low-stock-row':item.lowStock}"><td><b>{{ item.productName }}</b><small>{{ item.skuName }} · {{ item.skuCode }} · #{{ item.skuId }}</small></td><td><b>{{ item.onHandQuantity }}</b></td><td>{{ item.lockedQuantity }}</td><td><b>{{ item.availableQuantity }}</b></td><td><span :class="{'stock-warning':item.lowStock}">{{ item.warningThreshold }}{{ item.lowStock?' · 库存不足':'' }}</span></td><td>v{{ item.version }}<small>{{ dateTime(item.updatedAt) }}</small></td><td><div class="actions"><button class="primary" @click="openAdjustment(item)">调整库存</button><button @click="stockFilters.logSkuId=item.skuId;switchInventoryView('logs')">查看流水</button></div></td></tr></tbody></table><footer class="pagination"><span>共 {{ stockTotal }} 条，第 {{ stockPage }}/{{ stockTotalPages || 1 }} 页</span><div><button :disabled="stockPage<=1||loading" @click="loadStocks(stockPage-1)">上一页</button><button :disabled="stockPage>=stockTotalPages||loading" @click="loadStocks(stockPage+1)">下一页</button></div></footer></div>
      </template>
      <template v-else>
        <div class="filters panel cultural-filters"><select v-model="stockFilters.logSkuId"><option value="">全部SKU</option><option v-for="item in stocks" :key="item.skuId" :value="item.skuId">{{ item.productName }} / {{ item.skuCode }}</option></select><button class="primary" :disabled="loading" @click="loadLogs(1)">查询流水</button><button v-if="stockFilters.logSkuId" @click="stockFilters.logSkuId='';loadLogs(1)">清除SKU筛选</button></div>
        <div class="panel table-wrap"><table><thead><tr><th>时间</th><th>SKU</th><th>类型</th><th>变更数量</th><th>变更后库存</th><th>业务单号</th><th>备注/操作人</th></tr></thead><tbody><tr v-if="!stockLogs.length"><td colspan="7">暂无库存流水。</td></tr><tr v-for="item in stockLogs" :key="item.id"><td>{{ dateTime(item.createdAt) }}</td><td>{{ item.skuCode }}<small>#{{ item.skuId }}</small></td><td><span class="badge" :class="item.quantityDelta<0?'inactive':''">{{ stockTypeText[item.changeType] || item.changeType }}</span></td><td><b :class="item.quantityDelta>0?'stock-positive':'stock-negative'">{{ item.quantityDelta>0?'+':'' }}{{ item.quantityDelta }}</b></td><td>现货 {{ item.onHandAfter }}<small>可售 {{ item.availableAfter }}</small></td><td>{{ item.referenceNo || '-' }}</td><td>{{ item.remark || '-' }}<small>操作人 #{{ item.operatorId || '-' }}</small></td></tr></tbody></table><footer class="pagination"><span>共 {{ logTotal }} 条，第 {{ logPage }}/{{ logTotalPages || 1 }} 页</span><div><button :disabled="logPage<=1||loading" @click="loadLogs(logPage-1)">上一页</button><button :disabled="logPage>=logTotalPages||loading" @click="loadLogs(logPage+1)">下一页</button></div></footer></div>
      </template>
    </section>

    <section v-if="tab==='marketing'"><CulturalMarketingPanel /></section>
    <section v-if="tab==='fulfillment'"><CulturalFulfillmentPanel /></section>
    <section v-if="tab==='reports'"><CulturalReportPanel /></section>

    <div v-if="modal" class="modal-backdrop" @click.self="modal=''">
      <section v-if="modal==='category'" class="modal panel"><div class="modal-header"><div><h3>{{ form.id ? '编辑分类' : '新增分类' }}</h3><p>停用分类后用户端不再展示。</p></div><button class="text-button" @click="modal=''">×</button></div><label>分类编码<input v-model.trim="form.categoryCode" :disabled="Boolean(form.id)" /></label><label>分类名称<input v-model.trim="form.name" /></label><label>上级分类<select v-model="form.parentId"><option :value="null">无上级分类</option><option v-for="item in categories.filter(v=>v.id!==form.id)" :key="item.id" :value="item.id">{{ item.name }}</option></select></label><label>排序<input v-model="form.sortOrder" type="number" /></label><label>状态<select v-model="form.status"><option value="ACTIVE">启用</option><option value="INACTIVE">停用</option></select></label><div class="modal-actions"><button @click="modal=''">取消</button><button class="primary" :disabled="loading" @click="submitCategory">保存</button></div></section>

      <section v-if="modal==='product'" class="modal modal-wide panel cultural-modal"><div class="modal-header"><div><h3>{{ form.id ? '编辑商品' : '新增商品' }}</h3><p>商品资料与图片在此保存；已有SKU通过SKU管理单独维护。</p></div><button class="text-button" @click="modal=''">×</button></div><div class="cultural-form-grid"><label>商品编码<input v-model.trim="form.productCode" /></label><label>商品名称<input v-model.trim="form.name" /></label><label>商品分类<select v-model="form.categoryId"><option value="">请选择</option><option v-for="item in enabledCategories" :key="item.id" :value="item.id">{{ item.name }}</option></select></label><label>状态<select v-model="form.status"><option value="DRAFT">草稿</option><option value="ACTIVE">上架</option><option value="INACTIVE">下架</option></select></label><label>市场价（元）<input v-model="form.marketPriceYuan" type="number" min="0" step="0.01" /></label><label>重量（克）<input v-model="form.weightGram" type="number" min="0" /></label><label class="span-two">封面地址<input v-model.trim="form.coverUrl" placeholder="https://..." /></label><label class="span-two">商品简介<textarea v-model="form.description" rows="2" /></label><label class="span-two">详情HTML<textarea v-model="form.detailHtml" rows="5" placeholder="可填写商品详情HTML" /></label></div>
        <div class="subsection"><div class="subsection-title"><h4>商品图片</h4><button @click="addImage">增加图片</button></div><div v-if="!form.images?.length" class="cultural-empty">未配置轮播图片</div><div v-for="(image,index) in form.images" :key="index" class="image-row"><input v-model.trim="image.imageUrl" placeholder="图片地址" /><select v-model="image.imageType"><option value="COVER">封面</option><option value="GALLERY">轮播图</option><option value="DETAIL">详情图</option></select><input v-model="image.sortOrder" type="number" /><button @click="form.images.splice(index,1)">删除</button></div></div>
        <div v-if="!form.id" class="subsection"><div class="subsection-title"><h4>初始SKU</h4><button @click="addInitialSku">增加SKU</button></div><div v-for="(sku,index) in form.skus" :key="index" class="initial-sku"><input v-model.trim="sku.skuCode" placeholder="SKU编码" /><input v-model.trim="sku.name" placeholder="SKU名称" /><input v-model="sku.priceYuan" type="number" min="0" step="0.01" placeholder="售价（元）" /><input v-model="sku.initialStock" type="number" min="0" placeholder="初始库存" /><input v-model="sku.warningThreshold" type="number" min="0" placeholder="预警值" /><button :disabled="form.skus.length===1" @click="form.skus.splice(index,1)">删除</button></div></div>
        <div class="modal-actions"><button @click="modal=''">取消</button><button class="primary" :disabled="loading" @click="submitProduct">保存商品</button></div></section>

      <section v-if="modal==='sku-list'" class="modal modal-wide panel"><div class="modal-header"><div><h3>{{ form.name }} · SKU管理</h3><p>库存数量将在第二阶段库存管理页面调整。</p></div><button class="text-button" @click="modal=''">×</button></div><div class="panel table-wrap"><table><thead><tr><th>SKU</th><th>售价</th><th>库存</th><th>预警</th><th>状态</th><th>操作</th></tr></thead><tbody><tr v-for="item in form.skus" :key="item.skuId"><td><b>{{ item.name }}</b><small>{{ item.skuCode }} · #{{ item.skuId }}</small></td><td>{{ money(item.priceCent) }}</td><td>现货 {{ item.onHandQuantity }}<small>锁定 {{ item.lockedQuantity }} / 可售 {{ item.availableQuantity }}</small></td><td><span :class="{'stock-warning':item.lowStock}">{{ item.warningThreshold }}{{ item.lowStock?'（不足）':'' }}</span></td><td><span class="badge" :class="statusClass(item.status)">{{ statusText[item.status] || item.status }}</span></td><td><button @click="openSku(item)">编辑</button></td></tr></tbody></table></div><button class="primary" @click="openSku()">新增SKU</button></section>

      <section v-if="modal==='sku'" class="modal panel"><div class="modal-header"><div><h3>{{ form.skuId ? '编辑SKU' : '新增SKU' }}</h3><p>SKU编码创建后不可修改。</p></div><button class="text-button" @click="modal=''">×</button></div><label>SKU编码<input v-model.trim="form.skuCode" :disabled="Boolean(form.skuId)" /></label><label>SKU名称<input v-model.trim="form.name" /></label><label>售价（元）<input v-model="form.priceYuan" type="number" min="0" step="0.01" /></label><label v-if="!form.skuId">初始库存<input v-model="form.initialStock" type="number" min="0" /></label><label>低库存预警值<input v-model="form.warningThreshold" type="number" min="0" /></label><label>状态<select v-model="form.status"><option value="ACTIVE">启用</option><option value="INACTIVE">停用</option></select></label><label>规格属性JSON<textarea v-model="form.attributes" rows="3" /></label><p v-if="form.skuId" class="review-tip">当前库存：现货 {{ form.onHandQuantity }}，锁定 {{ form.lockedQuantity }}，可售 {{ form.availableQuantity }}</p><div class="modal-actions"><button @click="modal=''">取消</button><button class="primary" :disabled="loading" @click="submitSku">保存SKU</button></div></section>

      <section v-if="modal==='stock-adjustment'" class="modal panel"><div class="modal-header"><div><h3>调整库存</h3><p>{{ form.stock.productName }} / {{ form.stock.skuName }}（{{ form.stock.skuCode }}）</p></div><button class="text-button" @click="modal=''">×</button></div><div class="inventory-current"><span>现货 <b>{{ form.stock.onHandQuantity }}</b></span><span>锁定 <b>{{ form.stock.lockedQuantity }}</b></span><span>可售 <b>{{ form.stock.availableQuantity }}</b></span></div><label>变更类型<select v-model="form.changeType"><option value="INBOUND">采购入库</option><option value="OUTBOUND">业务出库</option><option value="RETURN">退货入库</option><option value="DAMAGE">报损出库</option><option value="ADJUSTMENT">盘点调整</option></select></label><label>数量<input v-model.number="form.quantity" type="number" step="1" :placeholder="form.changeType==='ADJUSTMENT'?'盘盈正数，盘亏负数':'请输入正整数'" /></label><p class="review-tip">入库和退货填写正数；出库和报损页面会自动转换为负数；盘点调整需直接填写正数或负数。锁定库存不会被库存调整修改。</p><label>业务单号<input v-model.trim="form.referenceNo" placeholder="采购单、出库单或盘点单号" /></label><label>备注<textarea v-model="form.remark" rows="3" /></label><div class="modal-actions"><button @click="modal=''">取消</button><button class="primary" :disabled="loading" @click="submitAdjustment">确认调整</button></div></section>

      <section v-if="modal==='stock-batch'" class="modal modal-wide panel batch-stock-modal"><div class="modal-header"><div><h3>批量库存调整</h3><p>一次支持1—200条；任意一条失败时整批回滚。</p></div><button class="text-button" @click="modal=''">×</button></div><div class="batch-stock-head"><span>SKU</span><span>类型</span><span>数量</span><span>业务单号</span><span>备注</span><span></span></div><div v-for="(item,index) in form.items" :key="index" class="batch-stock-row"><select v-model="item.skuId"><option value="">请选择SKU</option><option v-for="stock in stocks" :key="stock.skuId" :value="stock.skuId">{{ stock.productName }} / {{ stock.skuCode }}（可售{{ stock.availableQuantity }}）</option></select><select v-model="item.changeType"><option value="INBOUND">采购入库</option><option value="OUTBOUND">业务出库</option><option value="RETURN">退货入库</option><option value="DAMAGE">报损出库</option><option value="ADJUSTMENT">盘点调整</option></select><input v-model.number="item.quantity" type="number" step="1" /><input v-model.trim="item.referenceNo" placeholder="业务单号" /><input v-model.trim="item.remark" placeholder="备注" /><button :disabled="form.items.length===1" @click="form.items.splice(index,1)">删除</button></div><button :disabled="form.items.length>=200" @click="addBatchRow">增加一行</button><p class="review-tip">批量下拉展示当前库存页SKU；如需其他SKU，请先在库存看板搜索后再打开批量操作。</p><div class="modal-actions"><button @click="modal=''">取消</button><button class="primary" :disabled="loading" @click="submitBatchAdjustment">提交整批</button></div></section>
    </div>
  </div>
</template>
