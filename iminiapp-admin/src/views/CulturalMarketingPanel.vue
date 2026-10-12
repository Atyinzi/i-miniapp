<script setup>
import { computed, onMounted, reactive, ref } from 'vue'
import TargetEditor from './CulturalTargetEditor.vue'
import {
  createCulturalCoupon, createCulturalPromotion, getCulturalCategories, getCulturalCoupons,
  getCulturalProducts, getCulturalPromotions, issueCulturalCoupon, updateCulturalCoupon,
  updateCulturalPromotion
} from '../services/culturalStore'

const view = ref('promotions')
const promotions = ref([])
const coupons = ref([])
const categories = ref([])
const products = ref([])
const loading = ref(false)
const error = ref('')
const notice = ref('')
const modal = ref('')
const form = reactive({})
const typeText = { FULL_REDUCTION: '满减', DISCOUNT: '折扣' }
const statusText = { ACTIVE: '进行中', DRAFT: '草稿', INACTIVE: '已停用' }
const targetTypeText = { ALL: '全场商品', CATEGORY: '指定分类', PRODUCT: '指定商品', SKU: '指定SKU' }
const summary = computed(() => ({
  activePromotions: promotions.value.filter(item => item.status === 'ACTIVE').length,
  couponTemplates: coupons.value.length,
  issued: coupons.value.reduce((sum, item) => sum + Number(item.issuedCount || 0), 0),
  used: coupons.value.reduce((sum, item) => sum + Number(item.usedCount || 0), 0)
}))

async function run(action) {
  try { loading.value = true; error.value = ''; await action() }
  catch (e) { error.value = e.message }
  finally { loading.value = false }
}
async function load() {
  await run(async () => {
    const [promotionData, couponData, categoryData, productData] = await Promise.all([
      getCulturalPromotions(), getCulturalCoupons(), getCulturalCategories(), getCulturalProducts({ page: 1, pageSize: 100 })
    ])
    promotions.value = promotionData || []; coupons.value = couponData || []
    categories.value = categoryData || []; products.value = productData?.items || []
  })
}
function resetForm(values) { Object.keys(form).forEach(key => delete form[key]); Object.assign(form, values) }
function defaultTimes() {
  const start = new Date(); const end = new Date(start.getTime() + 30 * 86400000)
  return { startAt: localDateTime(start), endAt: localDateTime(end) }
}
function mapTargets(targets) { return (targets?.length ? targets : [{ targetType: 'ALL', targetId: null }]).map(item => ({ ...item })) }
function openPromotion(item) {
  const times = defaultTimes()
  resetForm(item ? {
    ...item, thresholdYuan: centToYuan(item.thresholdAmountCent), reductionYuan: centToYuan(item.reductionAmountCent),
    discountValue: item.discountRate == null ? '' : Number(item.discountRate) * 10,
    startAt: inputDateTime(item.startAt), endAt: inputDateTime(item.endAt), targets: mapTargets(item.targets)
  } : {
    promotionCode: '', name: '', promotionType: 'FULL_REDUCTION', thresholdYuan: 100,
    reductionYuan: 10, discountValue: 8.5, priority: 0, exclusive: false,
    startAt: times.startAt, endAt: times.endAt, status: 'DRAFT', targets: [{ targetType: 'ALL', targetId: null }]
  })
  modal.value = 'promotion'
}
function openCoupon(item) {
  const times = defaultTimes()
  resetForm(item ? {
    ...item, thresholdYuan: centToYuan(item.thresholdAmountCent), discountYuan: centToYuan(item.discountAmountCent),
    discountValue: item.discountRate == null ? '' : Number(item.discountRate) * 10,
    startAt: inputDateTime(item.startAt), endAt: inputDateTime(item.endAt), targets: mapTargets(item.targets),
    economicLocked: Number(item.issuedCount || 0) > 0
  } : {
    couponCode: '', name: '', couponType: 'FULL_REDUCTION', thresholdYuan: 100,
    discountYuan: 10, discountValue: 8.5, issueTotal: 1000, perUserLimit: 1, stackable: false,
    startAt: times.startAt, endAt: times.endAt, status: 'DRAFT', targets: [{ targetType: 'ALL', targetId: null }], economicLocked: false
  })
  modal.value = 'coupon'
}
function openIssue(item) { resetForm({ coupon: item, userId: '', quantity: 1 }); modal.value = 'issue' }
function addTarget() { if (form.targets.length < 20) form.targets.push({ targetType: 'CATEGORY', targetId: '' }) }
function normalizeTargets() {
  const rows = (form.targets || []).filter(item => item.targetType)
  if (rows.some(item => item.targetType === 'ALL')) return [{ targetType: 'ALL', targetId: null }]
  if (!rows.length || rows.some(item => !item.targetId)) throw new Error('指定范围必须选择目标ID')
  const normalized = rows.map(item => ({ targetType: item.targetType, targetId: Number(item.targetId) }))
  const keys = normalized.map(item => `${item.targetType}:${item.targetId}`)
  if (new Set(keys).size !== keys.length) throw new Error('适用范围不能重复')
  return normalized
}
function validateRule(kind, threshold, amount, rate) {
  if (!kind || Number(threshold) < 0) throw new Error('优惠类型和使用门槛不合法')
  if (kind === 'FULL_REDUCTION' && Number(amount) <= 0) throw new Error('满减金额必须大于0')
  if (kind === 'DISCOUNT' && (Number(rate) <= 0 || Number(rate) >= 10)) throw new Error('折扣必须大于0折且小于10折')
  if (!form.startAt || !form.endAt || form.endAt <= form.startAt) throw new Error('结束时间必须晚于开始时间')
}
async function submitPromotion() {
  try { validateRule(form.promotionType, form.thresholdYuan, form.reductionYuan, form.discountValue) }
  catch (e) { error.value = e.message; return }
  await run(async () => {
    const payload = {
      promotionCode: form.promotionCode, name: form.name, promotionType: form.promotionType,
      thresholdAmountCent: yuanToCent(form.thresholdYuan),
      reductionAmountCent: form.promotionType === 'FULL_REDUCTION' ? yuanToCent(form.reductionYuan) : null,
      discountRate: form.promotionType === 'DISCOUNT' ? Number(form.discountValue) / 10 : null,
      priority: Number(form.priority || 0), exclusive: Boolean(form.exclusive), startAt: form.startAt,
      endAt: form.endAt, status: form.status, targets: normalizeTargets()
    }
    form.id ? await updateCulturalPromotion(form.id, payload) : await createCulturalPromotion(payload)
    promotions.value = await getCulturalPromotions() || []; notice.value = '促销活动保存成功'; modal.value = ''
  })
}
async function submitCoupon() {
  try { validateRule(form.couponType, form.thresholdYuan, form.discountYuan, form.discountValue) }
  catch (e) { error.value = e.message; return }
  if (Number(form.perUserLimit) < 1 || Number(form.perUserLimit) > 100 || (form.issueTotal !== '' && Number(form.issueTotal) < 1)) { error.value = '发行总量或每人限领数量不合法'; return }
  await run(async () => {
    const payload = {
      couponCode: form.couponCode, name: form.name, couponType: form.couponType,
      thresholdAmountCent: yuanToCent(form.thresholdYuan),
      discountAmountCent: form.couponType === 'FULL_REDUCTION' ? yuanToCent(form.discountYuan) : null,
      discountRate: form.couponType === 'DISCOUNT' ? Number(form.discountValue) / 10 : null,
      issueTotal: form.issueTotal === '' || form.issueTotal == null ? null : Number(form.issueTotal),
      perUserLimit: Number(form.perUserLimit), stackable: Boolean(form.stackable), startAt: form.startAt,
      endAt: form.endAt, status: form.status, targets: normalizeTargets()
    }
    form.id ? await updateCulturalCoupon(form.id, payload) : await createCulturalCoupon(payload)
    coupons.value = await getCulturalCoupons() || []; notice.value = '优惠券模板保存成功'; modal.value = ''
  })
}
async function submitIssue() {
  if (!Number.isInteger(Number(form.userId)) || Number(form.userId) < 1 || !Number.isInteger(Number(form.quantity)) || Number(form.quantity) < 1) { error.value = '用户ID和发放数量必须为正整数'; return }
  await run(async () => {
    const result = await issueCulturalCoupon(form.coupon.id, { userId: Number(form.userId), quantity: Number(form.quantity) })
    coupons.value = await getCulturalCoupons() || []; notice.value = `优惠券发放成功，共 ${result?.length || form.quantity} 张`; modal.value = ''
  })
}
function ruleText(item) { return item.promotionType === 'DISCOUNT' || item.couponType === 'DISCOUNT' ? `${Number(item.discountRate) * 10}折` : `减${money(item.reductionAmountCent ?? item.discountAmountCent)}` }
function targetText(targets) { const values = targets || []; if (!values.length || values.some(item => item.targetType === 'ALL')) return '全场商品'; return values.map(item => `${targetTypeText[item.targetType]} #${item.targetId}`).join('、') }
function money(cent) { return `¥${(Number(cent || 0) / 100).toFixed(2)}` }
function centToYuan(cent) { return cent == null ? '' : Number(cent) / 100 }
function yuanToCent(value) { return Math.round(Number(value || 0) * 100) }
function inputDateTime(value) { return value ? String(value).slice(0, 16) : '' }
function localDateTime(date) { return new Date(date.getTime() - date.getTimezoneOffset() * 60000).toISOString().slice(0, 16) }
function dateTime(value) { return value ? String(value).replace('T', ' ').slice(0, 16) : '-' }
function statusClass(status) { return status === 'ACTIVE' ? '' : status === 'DRAFT' ? 'draft' : 'inactive' }
function targetOptions(type) { return type === 'CATEGORY' ? categories.value : type === 'PRODUCT' ? products.value : [] }
function targetLabel(item, type) { return type === 'CATEGORY' ? `${item.name}（${item.categoryCode}）` : `${item.name}（${item.productCode}）` }

onMounted(load)
</script>

<template>
  <div class="marketing-panel">
    <div class="marketing-summary"><article><span>进行中活动</span><strong>{{ summary.activePromotions }}</strong><small>当前ACTIVE促销</small></article><article><span>优惠券模板</span><strong>{{ summary.couponTemplates }}</strong><small>全部文创券模板</small></article><article><span>累计发券</span><strong>{{ summary.issued }}</strong><small>已发到用户账户</small></article><article><span>累计用券</span><strong>{{ summary.used }}</strong><small>已核销优惠券</small></article></div>
    <p v-if="error" class="error cultural-message">{{ error }}</p><p v-if="notice" class="success cultural-message">{{ notice }}</p>
    <div class="content-tabs inventory-tabs"><button :class="{active:view==='promotions'}" @click="view='promotions'">满减与折扣</button><button :class="{active:view==='coupons'}" @click="view='coupons'">优惠券</button></div>
    <template v-if="view==='promotions'">
      <div class="toolbar marketing-toolbar"><div><h3>促销活动</h3><p>有效活动冲突时，后端选择优惠最大且优先级最高的一项。</p></div><button class="primary" @click="openPromotion()">新增活动</button></div>
      <div class="panel table-wrap"><table><thead><tr><th>活动</th><th>优惠规则</th><th>适用范围</th><th>优先级/互斥</th><th>有效时间</th><th>状态</th><th>操作</th></tr></thead><tbody><tr v-if="!promotions.length"><td colspan="7">暂无促销活动。</td></tr><tr v-for="item in promotions" :key="item.id"><td><b>{{ item.name }}</b><small>{{ item.promotionCode }} · #{{ item.id }}</small></td><td>满 {{ money(item.thresholdAmountCent) }}<small>{{ typeText[item.promotionType] }} · {{ ruleText(item) }}</small></td><td class="marketing-target">{{ targetText(item.targets) }}</td><td>{{ item.priority }}<small>{{ item.exclusive ? '排他活动' : '允许叠加券' }}</small></td><td>{{ dateTime(item.startAt) }}<small>至 {{ dateTime(item.endAt) }}</small></td><td><span class="badge" :class="statusClass(item.status)">{{ statusText[item.status] || item.status }}</span></td><td><button @click="openPromotion(item)">编辑</button></td></tr></tbody></table></div>
    </template>
    <template v-else>
      <div class="toolbar marketing-toolbar"><div><h3>优惠券模板</h3><p>已发放模板不能修改金额、折扣、门槛、总量和适用范围。</p></div><button class="primary" @click="openCoupon()">新增优惠券</button></div>
      <div class="panel table-wrap"><table><thead><tr><th>优惠券</th><th>优惠规则</th><th>发行/使用</th><th>叠加规则</th><th>有效时间</th><th>状态</th><th>操作</th></tr></thead><tbody><tr v-if="!coupons.length"><td colspan="7">暂无优惠券模板。</td></tr><tr v-for="item in coupons" :key="item.id"><td><b>{{ item.name }}</b><small>{{ item.couponCode }} · #{{ item.id }}</small></td><td>满 {{ money(item.thresholdAmountCent) }}<small>{{ typeText[item.couponType] }} · {{ ruleText(item) }}</small></td><td>{{ item.issuedCount }}/{{ item.issueTotal ?? '不限' }}<small>已使用 {{ item.usedCount }} · 每人限 {{ item.perUserLimit }}</small></td><td>{{ item.stackable ? '可与活动叠加' : '不可叠加' }}<small>{{ targetText(item.targets) }}</small></td><td>{{ dateTime(item.startAt) }}<small>至 {{ dateTime(item.endAt) }}</small></td><td><span class="badge" :class="statusClass(item.status)">{{ statusText[item.status] || item.status }}</span></td><td><div class="actions"><button @click="openCoupon(item)">编辑</button><button class="primary" :disabled="item.status!=='ACTIVE'" @click="openIssue(item)">发券</button></div></td></tr></tbody></table></div>
    </template>

    <div v-if="modal" class="modal-backdrop" @click.self="modal=''">
      <section v-if="modal==='promotion'" class="modal modal-wide panel marketing-modal"><div class="modal-header"><div><h3>{{ form.id?'编辑促销活动':'新增促销活动' }}</h3><p>金额单位在页面使用元，提交时自动换算为分。</p></div><button class="text-button" @click="modal=''">×</button></div><div class="marketing-form-grid"><label>活动编码<input v-model.trim="form.promotionCode" /></label><label>活动名称<input v-model.trim="form.name" /></label><label>优惠类型<select v-model="form.promotionType"><option value="FULL_REDUCTION">满减</option><option value="DISCOUNT">折扣</option></select></label><label>使用门槛（元）<input v-model.number="form.thresholdYuan" type="number" min="0" step="0.01" /></label><label v-if="form.promotionType==='FULL_REDUCTION'">减免金额（元）<input v-model.number="form.reductionYuan" type="number" min="0.01" step="0.01" /></label><label v-else>折扣（如8.5折）<input v-model.number="form.discountValue" type="number" min="0.1" max="9.9" step="0.1" /></label><label>优先级<input v-model.number="form.priority" type="number" /></label><label>开始时间<input v-model="form.startAt" type="datetime-local" /></label><label>结束时间<input v-model="form.endAt" type="datetime-local" /></label><label>状态<select v-model="form.status"><option value="DRAFT">草稿</option><option value="ACTIVE">启用</option><option value="INACTIVE">停用</option></select></label><label class="check-label"><input v-model="form.exclusive" type="checkbox" />排他活动（不可叠加优惠券）</label></div><TargetEditor :targets="form.targets" :categories="categories" :products="products" @add="addTarget" /><div class="modal-actions"><button @click="modal=''">取消</button><button class="primary" :disabled="loading" @click="submitPromotion">保存活动</button></div></section>

      <section v-if="modal==='coupon'" class="modal modal-wide panel marketing-modal"><div class="modal-header"><div><h3>{{ form.id?'编辑优惠券':'新增优惠券' }}</h3><p v-if="form.economicLocked">该模板已发放，经济规则和范围已锁定，仅可调整名称、时间和状态。</p></div><button class="text-button" @click="modal=''">×</button></div><div class="marketing-form-grid"><label>优惠券编码<input v-model.trim="form.couponCode" :disabled="form.economicLocked" /></label><label>优惠券名称<input v-model.trim="form.name" /></label><label>优惠类型<select v-model="form.couponType" :disabled="form.economicLocked"><option value="FULL_REDUCTION">满减券</option><option value="DISCOUNT">折扣券</option></select></label><label>使用门槛（元）<input v-model.number="form.thresholdYuan" type="number" min="0" step="0.01" :disabled="form.economicLocked" /></label><label v-if="form.couponType==='FULL_REDUCTION'">优惠金额（元）<input v-model.number="form.discountYuan" type="number" min="0.01" step="0.01" :disabled="form.economicLocked" /></label><label v-else>折扣（如8.5折）<input v-model.number="form.discountValue" type="number" min="0.1" max="9.9" step="0.1" :disabled="form.economicLocked" /></label><label>发行总量<input v-model.number="form.issueTotal" type="number" min="1" placeholder="留空表示不限" :disabled="form.economicLocked" /></label><label>每人限领<input v-model.number="form.perUserLimit" type="number" min="1" max="100" :disabled="form.economicLocked" /></label><label>开始时间<input v-model="form.startAt" type="datetime-local" /></label><label>结束时间<input v-model="form.endAt" type="datetime-local" /></label><label>状态<select v-model="form.status"><option value="DRAFT">草稿</option><option value="ACTIVE">启用</option><option value="INACTIVE">停用</option></select></label><label class="check-label"><input v-model="form.stackable" type="checkbox" :disabled="form.economicLocked" />允许与非排他活动叠加</label></div><TargetEditor :targets="form.targets" :categories="categories" :products="products" :disabled="form.economicLocked" @add="addTarget" /><div class="modal-actions"><button @click="modal=''">取消</button><button class="primary" :disabled="loading" @click="submitCoupon">保存优惠券</button></div></section>

      <section v-if="modal==='issue'" class="modal panel"><div class="modal-header"><div><h3>向指定用户发券</h3><p>{{ form.coupon.name }}（{{ form.coupon.couponCode }}）</p></div><button class="text-button" @click="modal=''">×</button></div><p class="review-tip">请输入平台用户ID。系统会校验发行总量和该用户限领数量。</p><label>用户ID<input v-model.number="form.userId" type="number" min="1" /></label><label>发放数量<input v-model.number="form.quantity" type="number" min="1" max="100" /></label><div class="modal-actions"><button @click="modal=''">取消</button><button class="primary" :disabled="loading" @click="submitIssue">确认发券</button></div></section>
    </div>
  </div>
</template>
