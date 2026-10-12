<script setup>
import { computed, onMounted, reactive, ref } from 'vue'
import {
  appendCulturalTracking, approveCulturalAfterSale, autoReceiveCulturalOrders,
  batchShipCulturalOrders, getCulturalAfterSale,
  getCulturalAfterSales, getCulturalCarriers, getCulturalFulfillmentOrder,
  getCulturalFulfillmentOrders, prepareCulturalOrder, receiveCulturalAfterSaleReturn,
  rejectCulturalAfterSale, retryCulturalAfterSaleRefund, shipCulturalOrder, updateCulturalShipment
} from '../services/culturalStore'

const view = ref('orders')
const orders = ref([])
const orderTotal = ref(0)
const orderPages = ref(0)
const orderPage = ref(1)
const afterSales = ref([])
const afterSaleTotal = ref(0)
const afterSalePages = ref(0)
const afterSalePage = ref(1)
const carriers = ref([])
const selected = ref([])
const loading = ref(false)
const error = ref('')
const notice = ref('')
const modal = ref('')
const form = reactive({})
const orderFilters = reactive({ keyword: '', status: '', pageSize: 20 })
const afterSaleFilters = reactive({ status: '', type: '', pageSize: 20 })

const orderStatusText = {
  PENDING_PAYMENT: '待支付', PAID: '待备货', PREPARING: '备货中', SHIPPED: '已发货',
  COMPLETED: '已完成', CANCELLED: '已取消', REFUNDING: '退款中', REFUNDED: '已退款'
}
const shipmentStatusText = {
  PENDING: '待发货', SHIPPED: '已发货', IN_TRANSIT: '运输中', DELIVERED: '已签收',
  EXCEPTION: '物流异常', CANCELLED: '已取消'
}
const afterSaleStatusText = {
  PENDING_REVIEW: '待审核', WAITING_RETURN: '待买家退货', RETURNED: '待商家签收',
  REFUNDING: '等待微信退款结果', REFUND_FAILED: '渠道退款失败', COMPLETED: '已完成', REJECTED: '已拒绝', CANCELLED: '已取消'
}
const afterSaleTypeText = { REFUND_ONLY: '仅退款', RETURN_REFUND: '退货退款' }
const trackingEventText = {
  PICKED_UP: '已揽收', IN_TRANSIT: '运输中', OUT_FOR_DELIVERY: '派送中',
  DELIVERED: '已签收', EXCEPTION: '物流异常', SHIPPED: '已发货'
}
const summary = computed(() => ({
  paid: orders.value.filter(item => item.status === 'PAID').length,
  preparing: orders.value.filter(item => item.status === 'PREPARING').length,
  shipping: orders.value.filter(item => ['SHIPPED', 'IN_TRANSIT', 'EXCEPTION'].includes(item.shipmentStatus)).length,
  afterSalePending: afterSales.value.filter(item => ['PENDING_REVIEW', 'RETURNED', 'REFUNDING'].includes(item.status)).length
}))
const enabledCarriers = computed(() => carriers.value.filter(item => item.enabled))
const selectedEligible = computed(() => orders.value.filter(item => selected.value.includes(item.orderId) && ['PAID', 'PREPARING'].includes(item.status)))

async function run(action) {
  try { loading.value = true; error.value = ''; notice.value = ''; await action() }
  catch (e) { error.value = e.message }
  finally { loading.value = false }
}
async function loadOrders(targetPage = orderPage.value) {
  orderPage.value = targetPage
  const data = await getCulturalFulfillmentOrders({
    keyword: orderFilters.keyword || undefined, status: orderFilters.status || undefined,
    page: orderPage.value, pageSize: orderFilters.pageSize
  })
  orders.value = data?.items || []; orderTotal.value = Number(data?.total || 0)
  orderPages.value = Number(data?.totalPages || 0); selected.value = []
}
async function loadAfterSales(targetPage = afterSalePage.value) {
  afterSalePage.value = targetPage
  const data = await getCulturalAfterSales({
    status: afterSaleFilters.status || undefined, type: afterSaleFilters.type || undefined,
    page: afterSalePage.value, pageSize: afterSaleFilters.pageSize
  })
  afterSales.value = data?.items || []; afterSaleTotal.value = Number(data?.total || 0)
  afterSalePages.value = Number(data?.totalPages || 0)
}
async function initialLoad() {
  await run(async () => {
    const [carrierData] = await Promise.all([getCulturalCarriers(), loadOrders(1), loadAfterSales(1)])
    carriers.value = carrierData || []
  })
}
async function switchView(value) {
  view.value = value
  if (value === 'orders' && !orders.value.length) await run(() => loadOrders(1))
  if (value === 'after-sales' && !afterSales.value.length) await run(() => loadAfterSales(1))
}
function resetForm(values) { Object.keys(form).forEach(key => delete form[key]); Object.assign(form, values) }
function key(prefix) { return `${prefix}-${Date.now()}-${Math.random().toString(36).slice(2, 9)}` }
function defaultCarrier() { return enabledCarriers.value[0]?.carrierCode || '' }
function shipmentPayload() {
  if (!form.carrierCode || !form.trackingNo?.trim()) throw new Error('物流公司和物流单号不能为空')
  if (!Number.isInteger(Number(form.autoReceiveDays)) || Number(form.autoReceiveDays) < 1 || Number(form.autoReceiveDays) > 30) throw new Error('自动收货天数必须为1—30的整数')
  return { carrierCode: form.carrierCode, trackingNo: form.trackingNo.trim(), autoReceiveDays: Number(form.autoReceiveDays), remark: form.remark || null }
}
async function openOrder(item) {
  await run(async () => { resetForm({ detail: await getCulturalFulfillmentOrder(item.orderId) }); modal.value = 'order-detail' })
}
function openPrepare(item) { resetForm({ order: item, remark: '' }); modal.value = 'prepare' }
async function openShip(item, edit = false) {
  if (edit) {
    await run(async () => {
      const detail = await getCulturalFulfillmentOrder(item.orderId)
      resetForm({ order: item, edit, carrierCode: detail.shipment?.carrierCode || defaultCarrier(), trackingNo: detail.shipment?.trackingNo || item.trackingNo || '', autoReceiveDays: 7, remark: '' })
      modal.value = 'ship'
    })
    return
  }
  resetForm({ order: item, edit, carrierCode: defaultCarrier(), trackingNo: '', autoReceiveDays: 7, remark: '' })
  modal.value = 'ship'
}
function openBatchShip() {
  if (!selectedEligible.value.length) { error.value = '请先选择待备货或备货中的订单'; return }
  resetForm({ items: selectedEligible.value.map(item => ({ orderId: item.orderId, orderNo: item.orderNo, carrierCode: defaultCarrier(), trackingNo: '', autoReceiveDays: 7, remark: '' })) })
  modal.value = 'batch-ship'
}
function openTracking(item) {
  resetForm({ order: item, eventCode: 'IN_TRANSIT', eventDescription: '', location: '', occurredAt: localDateTime(new Date()), source: 'MANUAL' })
  modal.value = 'tracking'
}
async function submitPrepare() {
  await run(async () => { await prepareCulturalOrder(form.order.orderId, { remark: form.remark || null }, key('prepare')); notice.value = '订单已进入备货状态'; modal.value = ''; await loadOrders(orderPage.value) })
}
async function submitShip() {
  let payload
  try { payload = shipmentPayload() } catch (e) { error.value = e.message; return }
  await run(async () => {
    if (form.edit) await updateCulturalShipment(form.order.orderId, payload, key('waybill'))
    else await shipCulturalOrder(form.order.orderId, payload, key('ship'))
    notice.value = form.edit ? '运单信息已更新' : '订单发货成功'; modal.value = ''; await loadOrders(orderPage.value)
  })
}
async function submitBatchShip() {
  if (!form.items?.length || form.items.length > 100) { error.value = '批量发货需要1—100条订单'; return }
  if (form.items.some(item => !item.carrierCode || !item.trackingNo?.trim())) { error.value = '每条订单都必须填写物流公司和物流单号'; return }
  const numbers = form.items.map(item => item.trackingNo.trim())
  if (new Set(numbers).size !== numbers.length) { error.value = '本批次物流单号不能重复'; return }
  await run(async () => {
    await batchShipCulturalOrders({ items: form.items.map(item => ({ ...item, trackingNo: item.trackingNo.trim(), autoReceiveDays: Number(item.autoReceiveDays), remark: item.remark || null })) }, key('batch-ship'))
    notice.value = `批量发货成功，共 ${form.items.length} 单`; modal.value = ''; await loadOrders(orderPage.value)
  })
}
async function submitTracking() {
  if (!form.eventDescription?.trim()) { error.value = '物流事件说明不能为空'; return }
  await run(async () => {
    await appendCulturalTracking(form.order.orderId, { eventCode: form.eventCode, eventDescription: form.eventDescription.trim(), location: form.location || null, occurredAt: form.occurredAt, source: form.source, externalEventId: null }, key('tracking'))
    notice.value = '物流轨迹已更新'; modal.value = ''; await loadOrders(orderPage.value)
  })
}
async function runAutoReceive() {
  await run(async () => { const result = await autoReceiveCulturalOrders(); notice.value = `自动收货扫描完成，本次完成 ${result?.completedCount || 0} 单`; await loadOrders(orderPage.value) })
}
async function openAfterSale(item) {
  await run(async () => { resetForm({ detail: await getCulturalAfterSale(item.id) }); modal.value = 'after-sale-detail' })
}
function openReview(item, action) {
  resetForm({ case: item, action, comment: '', returnAddress: '' }); modal.value = 'after-sale-action'
}
async function submitAfterSaleAction() {
  if (form.action === 'reject' && !form.comment?.trim()) { error.value = '拒绝原因不能为空'; return }
  if (form.action === 'approve' && form.case.type === 'RETURN_REFUND' && !form.returnAddress?.trim()) { error.value = '退货退款审核通过时必须填写退货地址'; return }
  await run(async () => {
    const actionKey = key(`after-sale-${form.action}`)
    if (form.action === 'approve') await approveCulturalAfterSale(form.case.id, { comment: form.comment || null, returnAddress: form.returnAddress || null }, actionKey)
    if (form.action === 'reject') await rejectCulturalAfterSale(form.case.id, { comment: form.comment.trim() }, actionKey)
    if (form.action === 'receive') await receiveCulturalAfterSaleReturn(form.case.id, { comment: form.comment || null }, actionKey)
    if (form.action === 'retry') await retryCulturalAfterSaleRefund(form.case.id, { comment: form.comment || null }, actionKey)
    notice.value = actionLabel(form.action) + '成功'; modal.value = ''; await Promise.all([loadAfterSales(afterSalePage.value), loadOrders(orderPage.value)])
  })
}
function actionLabel(action) { return { approve: '审核通过', reject: '拒绝申请', receive: '退货签收', retry: '重新发起退款' }[action] || action }
function toggleAll(event) { selected.value = event.target.checked ? orders.value.filter(item => ['PAID', 'PREPARING'].includes(item.status)).map(item => item.orderId) : [] }
function money(cent) { return `¥${(Number(cent || 0) / 100).toFixed(2)}` }
function dateTime(value) { return value ? String(value).replace('T', ' ').slice(0, 16) : '-' }
function localDateTime(date) { return new Date(date.getTime() - date.getTimezoneOffset() * 60000).toISOString().slice(0, 16) }
function badgeClass(status) { return ['COMPLETED', 'DELIVERED', 'ACTIVE'].includes(status) ? '' : ['PAID', 'PREPARING', 'PENDING_REVIEW', 'WAITING_RETURN', 'RETURNED', 'REFUNDING'].includes(status) ? 'draft' : 'inactive' }
function fullAddress(delivery) { return delivery ? `${delivery.province || ''}${delivery.city || ''}${delivery.district || ''}${delivery.detailAddress || ''}` : '-' }

onMounted(initialLoad)
</script>

<template>
  <div class="fulfillment-panel">
    <div class="fulfillment-summary"><article><span>当前页待备货</span><strong>{{ summary.paid }}</strong><small>已付款订单</small></article><article><span>当前页备货中</span><strong>{{ summary.preparing }}</strong><small>等待录入运单</small></article><article><span>当前页在途</span><strong>{{ summary.shipping }}</strong><small>含物流异常</small></article><article :class="{warning:summary.afterSalePending}"><span>当前页待处理售后</span><strong>{{ summary.afterSalePending }}</strong><small>审核、签收或退款</small></article></div>
    <p v-if="error" class="error cultural-message">{{ error }}</p><p v-if="notice" class="success cultural-message">{{ notice }}</p>
    <div class="content-tabs inventory-tabs"><button :class="{active:view==='orders'}" @click="switchView('orders')">订单履约</button><button :class="{active:view==='after-sales'}" @click="switchView('after-sales')">退款与售后</button></div>

    <template v-if="view==='orders'">
      <div class="toolbar fulfillment-toolbar"><div><h3>文创订单工作台</h3><p>处理备货、发货、运单和物流轨迹。</p></div><div class="actions"><button :disabled="loading" @click="runAutoReceive">执行自动收货扫描</button><button class="primary" :disabled="!selectedEligible.length||loading" @click="openBatchShip">批量发货（{{ selectedEligible.length }}）</button></div></div>
      <div class="filters panel cultural-filters"><input v-model.trim="orderFilters.keyword" placeholder="订单号、用户、收件人、手机号或运单号" @keyup.enter="loadOrders(1)" /><select v-model="orderFilters.status"><option value="">全部订单状态</option><option v-for="(label,status) in orderStatusText" :key="status" :value="status">{{ label }}</option></select><button class="primary" :disabled="loading" @click="loadOrders(1)">查询</button></div>
      <div class="panel table-wrap"><table><thead><tr><th><input type="checkbox" :checked="selectedEligible.length>0&&selectedEligible.length===orders.filter(item=>['PAID','PREPARING'].includes(item.status)).length" @change="toggleAll" /></th><th>订单/用户</th><th>金额</th><th>收件信息</th><th>履约状态</th><th>创建时间</th><th>操作</th></tr></thead><tbody><tr v-if="!orders.length"><td colspan="7">暂无符合条件的文创订单。</td></tr><tr v-for="item in orders" :key="item.orderId"><td><input v-model="selected" type="checkbox" :value="item.orderId" :disabled="!['PAID','PREPARING'].includes(item.status)" /></td><td><b>{{ item.orderNo }}</b><small>{{ item.nickname || '用户' }} · #{{ item.userId }}</small></td><td><b>{{ money(item.payableAmountCent) }}</b><small v-if="item.discountAmountCent">优惠 {{ money(item.discountAmountCent) }}</small></td><td>{{ item.receiverName }} {{ item.receiverMobile }}<small>{{ item.fullAddress }}</small></td><td><span class="badge" :class="badgeClass(item.status)">{{ orderStatusText[item.status] || item.status }}</span><small>{{ shipmentStatusText[item.shipmentStatus] || item.shipmentStatus || '尚无物流' }}<template v-if="item.trackingNo"> · {{ item.carrierName }} {{ item.trackingNo }}</template></small></td><td>{{ dateTime(item.createdAt) }}</td><td><div class="actions fulfillment-actions"><button @click="openOrder(item)">详情</button><button v-if="item.status==='PAID'" class="primary" @click="openPrepare(item)">开始备货</button><button v-if="['PAID','PREPARING'].includes(item.status)" class="primary" @click="openShip(item)">发货</button><button v-if="['SHIPPED','IN_TRANSIT','EXCEPTION'].includes(item.shipmentStatus)" @click="openShip(item,true)">改运单</button><button v-if="['SHIPPED','IN_TRANSIT','EXCEPTION'].includes(item.shipmentStatus)" @click="openTracking(item)">物流更新</button></div></td></tr></tbody></table><footer class="pagination"><span>共 {{ orderTotal }} 条，第 {{ orderPage }}/{{ orderPages || 1 }} 页</span><div><button :disabled="orderPage<=1||loading" @click="loadOrders(orderPage-1)">上一页</button><button :disabled="orderPage>=orderPages||loading" @click="loadOrders(orderPage+1)">下一页</button></div></footer></div>
    </template>

    <template v-else>
      <div class="toolbar fulfillment-toolbar"><div><h3>退款与售后工作台</h3><p>审核申请、签收退货；退款提交后由微信支付回调自动确认结果。</p></div></div>
      <div class="filters panel cultural-filters"><select v-model="afterSaleFilters.type"><option value="">全部售后类型</option><option value="REFUND_ONLY">仅退款</option><option value="RETURN_REFUND">退货退款</option></select><select v-model="afterSaleFilters.status"><option value="">全部售后状态</option><option v-for="(label,status) in afterSaleStatusText" :key="status" :value="status">{{ label }}</option></select><button class="primary" :disabled="loading" @click="loadAfterSales(1)">查询</button></div>
      <div class="panel table-wrap"><table><thead><tr><th>售后单/订单</th><th>类型与原因</th><th>退款金额</th><th>退货物流</th><th>状态</th><th>申请时间</th><th>操作</th></tr></thead><tbody><tr v-if="!afterSales.length"><td colspan="7">暂无售后申请。</td></tr><tr v-for="item in afterSales" :key="item.id"><td><b>{{ item.afterSaleNo }}</b><small>{{ item.orderNo }} · #{{ item.orderId }}</small></td><td>{{ afterSaleTypeText[item.type] || item.type }}<small>{{ item.reason }}</small></td><td><b>{{ money(item.refundAmountCent) }}</b><small v-if="item.refundStatus">退款状态：{{ item.refundStatus }}</small></td><td><template v-if="item.returnShipment">{{ item.returnShipment.carrierName }}<small>{{ item.returnShipment.trackingNo }}</small></template><span v-else>-</span></td><td><span class="badge" :class="badgeClass(item.status)">{{ afterSaleStatusText[item.status] || item.status }}</span><small v-if="item.status==='REFUNDING'">请等待渠道异步结果，不能手工标记成功</small></td><td>{{ dateTime(item.createdAt) }}</td><td><div class="actions fulfillment-actions"><button @click="openAfterSale(item)">详情</button><button v-if="item.status==='PENDING_REVIEW'" class="primary" @click="openReview(item,'approve')">通过</button><button v-if="item.status==='PENDING_REVIEW'" class="danger" @click="openReview(item,'reject')">拒绝</button><button v-if="item.status==='RETURNED'" class="primary" @click="openReview(item,'receive')">签收退货</button><button v-if="item.status==='REFUND_FAILED'" class="primary" @click="openReview(item,'retry')">重新退款</button></div></td></tr></tbody></table><footer class="pagination"><span>共 {{ afterSaleTotal }} 条，第 {{ afterSalePage }}/{{ afterSalePages || 1 }} 页</span><div><button :disabled="afterSalePage<=1||loading" @click="loadAfterSales(afterSalePage-1)">上一页</button><button :disabled="afterSalePage>=afterSalePages||loading" @click="loadAfterSales(afterSalePage+1)">下一页</button></div></footer></div>
    </template>

    <div v-if="modal" class="modal-backdrop" @click.self="modal=''">
      <section v-if="modal==='prepare'" class="modal panel"><div class="modal-header"><div><h3>开始备货</h3><p>{{ form.order.orderNo }}</p></div><button class="text-button" @click="modal=''">×</button></div><label>备货备注<textarea v-model="form.remark" rows="3" placeholder="可选" /></label><div class="modal-actions"><button @click="modal=''">取消</button><button class="primary" :disabled="loading" @click="submitPrepare">确认备货</button></div></section>

      <section v-if="modal==='ship'" class="modal panel"><div class="modal-header"><div><h3>{{ form.edit?'修改运单':'订单发货' }}</h3><p>{{ form.order.orderNo }}</p></div><button class="text-button" @click="modal=''">×</button></div><label>物流公司<select v-model="form.carrierCode"><option value="">请选择</option><option v-for="item in enabledCarriers" :key="item.id" :value="item.carrierCode">{{ item.carrierName }}（{{ item.carrierCode }}）</option></select></label><label>物流单号<input v-model.trim="form.trackingNo" maxlength="64" /></label><label>自动收货天数<input v-model.number="form.autoReceiveDays" type="number" min="1" max="30" /></label><label>备注<textarea v-model="form.remark" rows="2" /></label><div class="modal-actions"><button @click="modal=''">取消</button><button class="primary" :disabled="loading" @click="submitShip">{{ form.edit?'保存运单':'确认发货' }}</button></div></section>

      <section v-if="modal==='batch-ship'" class="modal modal-wide panel fulfillment-modal"><div class="modal-header"><div><h3>批量发货</h3><p>本次共 {{ form.items.length }} 单，提交后整批处理。</p></div><button class="text-button" @click="modal=''">×</button></div><div class="batch-ship-head"><span>订单</span><span>物流公司</span><span>物流单号</span><span>自动收货</span><span>备注</span></div><div v-for="item in form.items" :key="item.orderId" class="batch-ship-row"><b>{{ item.orderNo }}</b><select v-model="item.carrierCode"><option v-for="carrier in enabledCarriers" :key="carrier.id" :value="carrier.carrierCode">{{ carrier.carrierName }}</option></select><input v-model.trim="item.trackingNo" placeholder="物流单号" /><input v-model.number="item.autoReceiveDays" type="number" min="1" max="30" /><input v-model.trim="item.remark" placeholder="备注" /></div><div class="modal-actions"><button @click="modal=''">取消</button><button class="primary" :disabled="loading" @click="submitBatchShip">确认批量发货</button></div></section>

      <section v-if="modal==='tracking'" class="modal panel"><div class="modal-header"><div><h3>更新物流轨迹</h3><p>{{ form.order.orderNo }} · {{ form.order.trackingNo }}</p></div><button class="text-button" @click="modal=''">×</button></div><label>物流事件<select v-model="form.eventCode"><option value="PICKED_UP">已揽收</option><option value="IN_TRANSIT">运输中</option><option value="OUT_FOR_DELIVERY">派送中</option><option value="DELIVERED">已签收</option><option value="EXCEPTION">物流异常</option></select></label><label>事件说明<textarea v-model="form.eventDescription" rows="3" /></label><label>地点<input v-model.trim="form.location" /></label><label>发生时间<input v-model="form.occurredAt" type="datetime-local" /></label><div class="modal-actions"><button @click="modal=''">取消</button><button class="primary" :disabled="loading" @click="submitTracking">保存轨迹</button></div></section>

      <section v-if="modal==='order-detail'" class="modal modal-wide panel fulfillment-modal"><div class="modal-header"><div><h3>订单详情</h3><p>{{ form.detail.order.orderNo }}</p></div><button class="text-button" @click="modal=''">×</button></div><div class="detail-grid"><article><span>订单状态</span><b>{{ orderStatusText[form.detail.order.status] || form.detail.order.status }}</b></article><article><span>应付金额</span><b>{{ money(form.detail.order.payableAmountCent) }}</b></article><article><span>实付金额</span><b>{{ money(form.detail.order.paidAmountCent) }}</b></article><article><span>创建时间</span><b>{{ dateTime(form.detail.order.createdAt) }}</b></article></div><div class="subsection"><h4>收件地址快照</h4><p>{{ form.detail.delivery.receiverName }} {{ form.detail.delivery.receiverMobile }}</p><p>{{ fullAddress(form.detail.delivery) }} <template v-if="form.detail.delivery.postalCode">（{{ form.detail.delivery.postalCode }}）</template></p></div><div class="subsection"><h4>商品明细</h4><div class="panel table-wrap"><table><thead><tr><th>商品/SKU</th><th>单价</th><th>数量</th><th>小计</th></tr></thead><tbody><tr v-for="item in form.detail.order.items" :key="item.id"><td>{{ item.resourceName }}<small>{{ item.skuName }} · {{ item.skuCode }}</small></td><td>{{ money(item.unitPriceCent) }}</td><td>{{ item.quantity }}</td><td><b>{{ money(item.amountCent) }}</b></td></tr></tbody></table></div></div><div v-if="form.detail.shipment" class="subsection"><h4>物流信息</h4><p>{{ form.detail.shipment.carrierName }} {{ form.detail.shipment.trackingNo }} · {{ shipmentStatusText[form.detail.shipment.status] || form.detail.shipment.status }}</p><p v-if="form.detail.shipment.exceptionFlag" class="error">{{ form.detail.shipment.exceptionReason }}</p><ol class="tracking-list"><li v-for="event in form.detail.shipment.trackingEvents" :key="event.id"><b>{{ trackingEventText[event.eventCode] || event.eventCode }}</b><span>{{ event.eventDescription }}<small>{{ event.location || '-' }} · {{ dateTime(event.occurredAt) }}</small></span></li></ol></div></section>

      <section v-if="modal==='after-sale-action'" class="modal panel"><div class="modal-header"><div><h3>{{ actionLabel(form.action) }}</h3><p>{{ form.case.afterSaleNo }} · {{ afterSaleTypeText[form.case.type] }}</p></div><button class="text-button" @click="modal=''">×</button></div><p class="review-tip">退款金额：{{ money(form.case.refundAmountCent) }}。该操作提交后将推进售后状态。</p><label v-if="form.action==='approve'&&form.case.type==='RETURN_REFUND'">退货地址<textarea v-model="form.returnAddress" rows="3" /></label><label>{{ form.action==='reject'?'拒绝原因':'处理备注' }}<textarea v-model="form.comment" rows="3" /></label><div class="modal-actions"><button @click="modal=''">取消</button><button :class="form.action==='reject'?'danger':'primary'" :disabled="loading" @click="submitAfterSaleAction">确认{{ actionLabel(form.action) }}</button></div></section>

      <section v-if="modal==='after-sale-detail'" class="modal modal-wide panel fulfillment-modal"><div class="modal-header"><div><h3>售后详情</h3><p>{{ form.detail.afterSaleNo }} · 订单 {{ form.detail.orderNo }}</p></div><button class="text-button" @click="modal=''">×</button></div><div class="detail-grid"><article><span>售后类型</span><b>{{ afterSaleTypeText[form.detail.type] || form.detail.type }}</b></article><article><span>当前状态</span><b>{{ afterSaleStatusText[form.detail.status] || form.detail.status }}</b></article><article><span>退款金额</span><b>{{ money(form.detail.refundAmountCent) }}</b></article><article><span>申请时间</span><b>{{ dateTime(form.detail.createdAt) }}</b></article></div><div class="subsection"><h4>申请原因</h4><p>{{ form.detail.reason }}</p><div v-if="form.detail.evidenceUrls?.length" class="evidence-list"><a v-for="url in form.detail.evidenceUrls" :key="url" :href="url" target="_blank" rel="noreferrer">查看凭证</a></div></div><div v-if="form.detail.reviewComment||form.detail.returnAddress" class="subsection"><h4>审核信息</h4><p v-if="form.detail.reviewComment">{{ form.detail.reviewComment }}</p><p v-if="form.detail.returnAddress">退货地址：{{ form.detail.returnAddress }}</p></div><div v-if="form.detail.returnShipment" class="subsection"><h4>退货物流</h4><p>{{ form.detail.returnShipment.carrierName }} {{ form.detail.returnShipment.trackingNo }}</p><p>寄出：{{ dateTime(form.detail.returnShipment.shippedAt) }} · 签收：{{ dateTime(form.detail.returnShipment.receivedAt) }}</p></div><div class="subsection"><h4>状态流水</h4><ol class="tracking-list"><li v-for="log in form.detail.logs" :key="log.id"><b>{{ actionLabel(log.action) }}</b><span>{{ afterSaleStatusText[log.fromStatus] || log.fromStatus || '创建' }} → {{ afterSaleStatusText[log.toStatus] || log.toStatus }}<small>{{ log.note || '-' }} · {{ dateTime(log.createdAt) }}</small></span></li></ol></div></section>
    </div>
  </div>
</template>
