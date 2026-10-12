<script setup>
import { computed, onMounted, reactive, ref, watch } from 'vue'
import {
  createScenicGuide, createScenicSpot, createScenicTicket, getPackageComponents,
  getScenicGuides, getScenicSpot, getScenicSpots, getScenicSummary, getScenicTickets,
  getScenicVerifications, setupScenicInventory, updatePackageComponents,
  updateScenicGuide, updateScenicSpot, updateScenicTicket
} from '../services/scenic'

const tab = ref('tickets')
const spots = ref([])
const spotId = ref('')
const tickets = ref([])
const guides = ref([])
const summary = ref(null)
const records = ref([])
const loading = ref(false)
const error = ref('')
const notice = ref('')
const modal = ref('')
const form = reactive({})
const today = localDate(new Date())
const startDate = ref(localDate(new Date(Date.now() - 29 * 86400000)))
const endDate = ref(today)
const selectedSpot = computed(() => spots.value.find(item => String(item.id) === String(spotId.value)))
const normalTickets = computed(() => tickets.value.filter(item => item.ticketType !== 'PACKAGE' && item.status === 'ACTIVE'))
const reportCards = computed(() => summary.value ? [
  ['支付订单', summary.value.paidOrderCount, '按游玩日期统计'],
  ['售出票数', summary.value.soldTicketCount, '已支付订单票数'],
  ['销售金额', money(summary.value.salesAmountCent), summary.value.currency || 'CNY'],
  ['签发电子票', summary.value.issuedTicketCount, '未作废票数'],
  ['已入园人数', summary.value.verifiedTicketCount, '实际核销票数'],
  ['核销率', `${Number(summary.value.verificationRate || 0).toFixed(2)}%`, '入园转化指标']
] : [])
const ticketTypeText = { ADULT: '成人票', CHILD: '儿童票', PACKAGE: '项目套票', SERVICE: '服务票', OTHER: '其他票' }
const guideTypeText = { MAP: '景区地图', ATTRACTION: '景点介绍', AUDIO: '语音导览', STRATEGY: '游玩攻略' }

async function loadSpots() {
  await run(async () => {
    spots.value = await getScenicSpots() || []
    if (!spotId.value && spots.value.length) spotId.value = spots.value[0].id
  })
}
async function loadCurrent() {
  if (!spotId.value) return
  await run(async () => {
    const [ticketData, guideData] = await Promise.all([getScenicTickets(spotId.value), getScenicGuides(spotId.value)])
    tickets.value = ticketData || []
    guides.value = guideData || []
  })
}
async function loadReport() {
  if (!spotId.value) return
  await run(async () => {
    const params = { startDate: startDate.value, endDate: endDate.value }
    const [summaryData, recordData] = await Promise.all([
      getScenicSummary(spotId.value, params), getScenicVerifications(spotId.value, { ...params, page: 1, pageSize: 100 })
    ])
    summary.value = summaryData
    records.value = recordData?.items || recordData?.records || []
  })
}
async function run(action) {
  try { loading.value = true; error.value = ''; await action() }
  catch (e) { error.value = e.message }
  finally { loading.value = false }
}
function resetForm(values) { Object.keys(form).forEach(key => delete form[key]); Object.assign(form, values) }
async function openSpot(item) {
  if (item) {
    await run(async () => { resetForm(await getScenicSpot(item.id)); modal.value = 'spot' })
  } else {
    resetForm({ siteCode: '', name: '', categoryCode: 'CULTURAL_SCENIC', description: '', coverUrl: '', address: '', longitude: '', latitude: '', servicePhone: '', introduction: '', openingHours: '09:00-17:00', recommendedDurationMinutes: 120, status: 'DRAFT' })
    modal.value = 'spot'
  }
}
function openTicket(item) {
  resetForm(item ? { ...item, priceYuan: item.priceCent / 100 } : { skuCode: '', name: '', ticketType: 'ADULT', priceYuan: 0, status: 'DRAFT', audienceRule: '', usageRule: '', refundRule: '未核销前可申请退款', entryNotice: '', validDays: 1, maxPerOrder: 5, realNameRequired: true, idCardRequired: true })
  modal.value = 'ticket'
}
function openInventory(item) { resetForm({ item, businessDate: today, timeSlot: '09:00-12:00', totalQuantity: 200, priceYuan: item.priceCent / 100 }); modal.value = 'inventory' }
async function openPackage(item) {
  await run(async () => {
    const data = await getPackageComponents(item.skuId)
    resetForm({ item, components: (data.components || []).map(v => ({ skuId: v.skuId, quantity: v.quantity })) })
    while (form.components.length < 2) form.components.push({ skuId: '', quantity: 1 })
    modal.value = 'package'
  })
}
function openGuide(item) {
  resetForm(item ? { ...item } : { contentType: 'MAP', title: '', summary: '', coverUrl: '', contentUrl: '', contentText: '', durationSeconds: '', sortOrder: 0, status: 'DRAFT' })
  modal.value = 'guide'
}
async function submit() {
  await run(async () => {
    if (modal.value === 'spot') {
      const payload = { ...form, longitude: numberOrNull(form.longitude), latitude: numberOrNull(form.latitude), recommendedDurationMinutes: Number(form.recommendedDurationMinutes) }
      form.id ? await updateScenicSpot(form.id, payload) : await createScenicSpot(payload)
      await loadSpots()
    } else if (modal.value === 'ticket') {
      const payload = { ...form, priceCent: Math.round(Number(form.priceYuan) * 100), validDays: Number(form.validDays), maxPerOrder: Number(form.maxPerOrder) }
      form.skuId ? await updateScenicTicket(form.skuId, payload) : await createScenicTicket(spotId.value, payload)
      await loadCurrent()
    } else if (modal.value === 'inventory') {
      await setupScenicInventory(form.item.skuId, form.businessDate, form.timeSlot, { totalQuantity: Number(form.totalQuantity), priceCent: Math.round(Number(form.priceYuan) * 100) })
    } else if (modal.value === 'package') {
      await updatePackageComponents(form.item.skuId, form.components.filter(v => v.skuId).map(v => ({ skuId: Number(v.skuId), quantity: Number(v.quantity) })))
    } else if (modal.value === 'guide') {
      const payload = { ...form, durationSeconds: numberOrNull(form.durationSeconds), sortOrder: Number(form.sortOrder) }
      form.id ? await updateScenicGuide(form.id, payload) : await createScenicGuide(spotId.value, payload)
      await loadCurrent()
    }
    notice.value = '保存成功，数据已刷新'; modal.value = ''
  })
}
function money(cent) { return `¥${(Number(cent || 0) / 100).toFixed(2)}` }
function localDate(date) { return new Date(date.getTime() - date.getTimezoneOffset() * 60000).toISOString().slice(0, 10) }
function numberOrNull(value) { return value === '' || value == null ? null : Number(value) }
function statusClass(status) { return status === 'ACTIVE' ? '' : status === 'DRAFT' ? 'draft' : 'inactive' }
function dateTime(value) { return value ? String(value).replace('T', ' ').slice(0, 16) : '-' }

watch(spotId, async () => { summary.value = null; await loadCurrent(); if (tab.value === 'report') await loadReport() })
watch(tab, value => { if (value === 'report') loadReport() })
onMounted(loadSpots)
</script>

<template>
  <div>
    <div class="toolbar"><div><h2>景区票务运营</h2><p>门票、项目套票、预约限流、导览内容与运营分析统一管理。</p></div><button class="primary" @click="openSpot()">新增景区</button></div>
    <p v-if="error" class="error scenic-message">{{ error }}</p><p v-if="notice" class="success scenic-message">{{ notice }}</p>
    <div class="scenic-context panel"><label>当前景区<select v-model="spotId"><option value="">请选择景区</option><option v-for="item in spots" :key="item.id" :value="item.id">{{ item.name }}（{{ item.status }}）</option></select></label><div v-if="selectedSpot"><b>{{ selectedSpot.siteCode }}</b><span>{{ selectedSpot.categoryCode || '未分类' }} · #{{ selectedSpot.id }}</span></div><button v-if="selectedSpot" @click="openSpot(selectedSpot)">编辑景区</button></div>
    <div class="content-tabs scenic-tabs"><button :class="{active:tab==='tickets'}" @click="tab='tickets'">门票与限流</button><button :class="{active:tab==='guides'}" @click="tab='guides'">导览服务</button><button :class="{active:tab==='report'}" @click="tab='report'">运营分析</button></div>

    <section v-if="tab==='tickets'">
      <div class="toolbar"><div><h3>门票及项目套票</h3><p>成人票、儿童票和套票使用同一库存与规则模型。</p></div><button :disabled="!spotId" class="primary" @click="openTicket()">新增票种</button></div>
      <div class="panel table-wrap"><table><thead><tr><th>票种</th><th>类型</th><th>价格</th><th>购票规则</th><th>状态</th><th>操作</th></tr></thead><tbody><tr v-if="!tickets.length"><td colspan="6">当前景区暂无票种。</td></tr><tr v-for="item in tickets" :key="item.skuId"><td><b>{{ item.name }}</b><small>{{ item.skuCode }} · #{{ item.skuId }}</small></td><td>{{ ticketTypeText[item.ticketType] || item.ticketType }}</td><td><b>{{ money(item.priceCent) }}</b></td><td>{{ item.audienceRule || '-' }}<small>限购 {{ item.maxPerOrder || '-' }} · {{ item.realNameRequired ? '实名制' : '非实名' }}</small></td><td><span class="badge" :class="statusClass(item.status)">{{ item.status }}</span></td><td><div class="actions"><button @click="openTicket(item)">编辑</button><button @click="openInventory(item)">时段/库存</button><button v-if="item.ticketType==='PACKAGE'" class="primary" @click="openPackage(item)">套票项目</button></div></td></tr></tbody></table></div>
    </section>

    <section v-if="tab==='guides'">
      <div class="toolbar"><div><h3>导览内容</h3><p>管理地图、景点介绍、语音文件和游玩攻略；仅 ACTIVE 内容对用户可见。</p></div><button :disabled="!spotId" class="primary" @click="openGuide()">新增导览</button></div>
      <div class="scenic-guide-grid"><article v-for="item in guides" :key="item.id" class="panel guide-card"><div><span class="badge" :class="statusClass(item.status)">{{ guideTypeText[item.contentType] }}</span><small>{{ item.status }}</small></div><h3>{{ item.title }}</h3><p>{{ item.summary || '暂无摘要' }}</p><footer><small>排序 {{ item.sortOrder }} · {{ dateTime(item.updatedAt) }}</small><button @click="openGuide(item)">编辑</button></footer></article><div v-if="!guides.length" class="panel empty-report">当前景区暂无导览内容</div></div>
    </section>

    <section v-if="tab==='report'">
      <div class="report-filter panel"><label>开始日期<input v-model="startDate" type="date" /></label><label>结束日期<input v-model="endDate" type="date" /></label><button class="primary" :disabled="!spotId" @click="loadReport">生成分析</button></div>
      <div class="report-stats"><article v-for="item in reportCards" :key="item[0]"><span>{{ item[0] }}</span><strong>{{ item[1] }}</strong><small>{{ item[2] }}</small></article></div>
      <div class="panel table-wrap"><div class="report-table-title"><h3>入园核销记录</h3><p>不展示游客姓名、手机号、身份证号及核销码。</p></div><table><thead><tr><th>订单</th><th>门票/景区</th><th>游玩日期</th><th>核销时间</th><th>核销员</th><th>渠道</th></tr></thead><tbody><tr v-if="!records.length"><td colspan="6">所选日期暂无核销记录。</td></tr><tr v-for="item in records" :key="item.verificationId"><td>{{ item.orderNo }}<small>#{{ item.orderId }}</small></td><td>{{ item.ticketName }}<small>{{ item.resourceName }}</small></td><td>{{ item.serviceDate }}</td><td>{{ dateTime(item.verifiedAt) }}</td><td>{{ item.verifierName || '-' }}</td><td>{{ item.verificationChannel === 'ID_CARD' ? '身份证' : '二维码' }}</td></tr></tbody></table></div>
    </section>

    <div v-if="modal" class="modal-backdrop" @click.self="modal='' "><section class="modal panel scenic-modal"><div class="modal-header"><h3>{{ {spot:'景区资料',ticket:'门票类型与规则',inventory:'预约时段与限流',package:'项目套票组成',guide:'导览内容'}[modal] }}</h3><button class="text-button" @click="modal=''">×</button></div>
      <template v-if="modal==='spot'"><div class="scenic-form-grid"><label v-if="!form.id">景区编码<input v-model.trim="form.siteCode" placeholder="SCENIC_CODE" /></label><label>景区名称<input v-model.trim="form.name" /></label><label>分类编码<input v-model.trim="form.categoryCode" /></label><label>状态<select v-model="form.status"><option value="DRAFT">草稿</option><option value="ACTIVE">上架</option><option value="INACTIVE">下架</option></select></label><label class="span-two">地址<input v-model.trim="form.address" /></label><label>经度<input v-model="form.longitude" type="number" step="0.000001" /></label><label>纬度<input v-model="form.latitude" type="number" step="0.000001" /></label><label>客服电话<input v-model.trim="form.servicePhone" /></label><label>营业时间<input v-model.trim="form.openingHours" /></label><label>建议游玩分钟<input v-model="form.recommendedDurationMinutes" type="number" min="1" /></label><label>封面地址<input v-model.trim="form.coverUrl" /></label><label class="span-two">简介<textarea v-model="form.description" rows="2" /></label><label class="span-two">详细介绍<textarea v-model="form.introduction" rows="4" /></label></div></template>
      <template v-if="modal==='ticket'"><div class="scenic-form-grid"><label v-if="!form.skuId">票种编码<input v-model.trim="form.skuCode" /></label><label>票种名称<input v-model.trim="form.name" /></label><label>类型<select v-model="form.ticketType" :disabled="Boolean(form.skuId)"><option v-for="(label,key) in ticketTypeText" :key="key" :value="key">{{ label }}</option></select></label><label>价格（元）<input v-model="form.priceYuan" type="number" min="0" step="0.01" /></label><label>状态<select v-model="form.status"><option value="DRAFT">草稿</option><option value="ACTIVE">上架</option><option value="INACTIVE">下架</option></select></label><label>有效天数<input v-model="form.validDays" type="number" min="1" /></label><label>单次限购<input v-model="form.maxPerOrder" type="number" min="1" /></label><label class="check-label"><input v-model="form.realNameRequired" type="checkbox" />实名购票</label><label class="check-label"><input v-model="form.idCardRequired" type="checkbox" />必须身份证</label><label class="span-two">适用人群<textarea v-model="form.audienceRule" rows="2" /></label><label class="span-two">使用规则<textarea v-model="form.usageRule" rows="2" /></label><label class="span-two">退款规则<textarea v-model="form.refundRule" rows="2" /></label><label class="span-two">入园须知<textarea v-model="form.entryNotice" rows="2" /></label></div></template>
      <template v-if="modal==='inventory'"><p class="review-tip">{{ form.item?.name }}：总容量不得低于当前已预订数量。</p><label>预约日期<input v-model="form.businessDate" type="date" /></label><label>预约时段<input v-model.trim="form.timeSlot" placeholder="09:00-12:00，留空表示全天" /></label><label>总容量<input v-model="form.totalQuantity" type="number" min="0" /></label><label>当日价格（元）<input v-model="form.priceYuan" type="number" min="0" step="0.01" /></label></template>
      <template v-if="modal==='package'"><p class="review-tip">套票需配置 2—10 个有效普通门票项目；各项目支付后生成独立电子票。</p><div v-for="(component,index) in form.components" :key="index" class="package-row"><select v-model="component.skuId"><option value="">请选择普通票</option><option v-for="item in normalTickets" :key="item.skuId" :value="item.skuId">{{ item.name }}（{{ item.skuCode }}）</option></select><input v-model="component.quantity" type="number" min="1" max="10" /><button v-if="form.components.length>2" @click="form.components.splice(index,1)">删除</button></div><button v-if="form.components.length<10" @click="form.components.push({skuId:'',quantity:1})">增加项目</button></template>
      <template v-if="modal==='guide'"><div class="scenic-form-grid"><label>内容类型<select v-model="form.contentType"><option v-for="(label,key) in guideTypeText" :key="key" :value="key">{{ label }}</option></select></label><label>状态<select v-model="form.status"><option value="DRAFT">草稿</option><option value="ACTIVE">发布</option><option value="INACTIVE">下架</option></select></label><label class="span-two">标题<input v-model.trim="form.title" /></label><label class="span-two">摘要<textarea v-model="form.summary" rows="2" /></label><label>封面地址<input v-model.trim="form.coverUrl" /></label><label>媒体/地图地址<input v-model.trim="form.contentUrl" /></label><label>音频时长（秒）<input v-model="form.durationSeconds" type="number" min="0" /></label><label>排序<input v-model="form.sortOrder" type="number" /></label><label class="span-two">正文/攻略<textarea v-model="form.contentText" rows="6" /></label></div></template>
      <div class="modal-actions"><button @click="modal=''">取消</button><button class="primary" :disabled="loading" @click="submit">保存</button></div>
    </section></div>
  </div>
</template>
