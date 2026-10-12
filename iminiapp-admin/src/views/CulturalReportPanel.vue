<script setup>
import { computed, onMounted, reactive, ref } from 'vue'
import {
  exportCulturalSalesReport, getCulturalInventoryReport,
  getCulturalReportDashboard, getCulturalStockMovements
} from '../services/culturalStore'

const view = ref('sales')
const endDate = ref(localDate(new Date()))
const startDate = ref(localDate(new Date(Date.now() - 29 * 86400000)))
const dashboard = ref(null)
const movements = ref([])
const inventory = ref([])
const inventoryPage = ref(1)
const inventoryTotal = ref(0)
const inventoryPages = ref(0)
const inventoryFilters = reactive({ keyword: '', lowStockOnly: false, pageSize: 20 })
const salesMetric = ref('netSalesCent')
const stockMetric = ref('netChangeQuantity')
const loading = ref(false)
const exporting = ref(false)
const error = ref('')
const notice = ref('')

const overview = computed(() => dashboard.value?.overview || {})
const trend = computed(() => dashboard.value?.salesTrend || [])
const topProducts = computed(() => dashboard.value?.topProducts || [])
const afterSale = computed(() => dashboard.value?.afterSaleSummary || {})
const stats = computed(() => [
  { label: '支付订单', value: `${overview.value.paidOrderCount ?? 0} 单`, hint: `创建 ${overview.value.createdOrderCount ?? 0} / 完成 ${overview.value.completedOrderCount ?? 0}` },
  { label: '销售件数', value: `${overview.value.soldQuantity ?? 0} 件`, hint: '成功支付订单商品数量' },
  { label: '销售总额', value: money(overview.value.grossSalesCent), hint: `优惠 ${money(overview.value.discountCent)}` },
  { label: '成功退款', value: money(overview.value.refundAmountCent), hint: `${overview.value.refundOrderCount ?? 0} 单 · 退款率 ${percent(overview.value.refundRate)}` },
  { label: '净销售额', value: money(overview.value.netSalesCent), hint: '销售总额减成功退款' },
  { label: '待发货', value: `${overview.value.pendingShipmentCount ?? 0} 单`, hint: `待处理售后 ${overview.value.pendingAfterSaleCount ?? 0} 单` },
  { label: '可售库存', value: `${overview.value.availableStockQuantity ?? 0} 件`, hint: `货值 ${money(overview.value.availableStockValueCent)}` },
  { label: '低库存 SKU', value: `${overview.value.lowStockSkuCount ?? 0} 个`, hint: '可售库存不高于预警值' }
])
const afterSaleEntries = computed(() => [
  ['仅退款', afterSale.value.refundOnlyCount], ['退货退款', afterSale.value.returnRefundCount],
  ['待审核', afterSale.value.pendingReviewCount], ['待买家退货', afterSale.value.waitingReturnCount],
  ['待签收退货', afterSale.value.returnedCount], ['退款中', afterSale.value.refundingCount],
  ['已完成', afterSale.value.completedCount], ['已拒绝', afterSale.value.rejectedCount],
  ['已取消', afterSale.value.cancelledCount]
])
const salesMax = computed(() => Math.max(1, ...trend.value.map(item => Math.abs(Number(item[salesMetric.value] || 0)))))
const stockMax = computed(() => Math.max(1, ...movements.value.map(item => Math.abs(Number(item[stockMetric.value] || 0)))))
const reversedTrend = computed(() => [...trend.value].reverse())

async function run(action) {
  try { loading.value = true; error.value = ''; notice.value = ''; await action() }
  catch (e) { error.value = e.message }
  finally { loading.value = false }
}
function validateRange() {
  if (!startDate.value || !endDate.value) throw new Error('请选择完整的开始和结束日期')
  if (startDate.value > endDate.value) throw new Error('开始日期不能晚于结束日期')
  const days = (new Date(`${endDate.value}T00:00:00`) - new Date(`${startDate.value}T00:00:00`)) / 86400000
  if (days > 366) throw new Error('报表日期范围最长为366天')
}
async function loadDashboard() {
  validateRange()
  const params = { startDate: startDate.value, endDate: endDate.value }
  const [dashboardData, movementData] = await Promise.all([
    getCulturalReportDashboard(params), getCulturalStockMovements(params)
  ])
  dashboard.value = dashboardData; movements.value = movementData || []
}
async function loadInventory(targetPage = inventoryPage.value) {
  inventoryPage.value = targetPage
  const data = await getCulturalInventoryReport({
    keyword: inventoryFilters.keyword || undefined,
    lowStockOnly: inventoryFilters.lowStockOnly || undefined,
    page: inventoryPage.value, pageSize: inventoryFilters.pageSize
  })
  inventory.value = data?.items || []; inventoryTotal.value = Number(data?.total || 0)
  inventoryPages.value = Number(data?.totalPages || 0)
}
async function loadAll() { await run(async () => { await Promise.all([loadDashboard(), loadInventory(1)]) }) }
async function refreshDashboard() { await run(loadDashboard) }
async function searchInventory() { await run(() => loadInventory(1)) }
async function switchView(value) {
  view.value = value
  if (value === 'inventory' && !inventory.value.length) await searchInventory()
}
function usePreset(days) {
  const end = new Date(); endDate.value = localDate(end)
  startDate.value = localDate(new Date(end.getTime() - (days - 1) * 86400000))
  refreshDashboard()
}
async function downloadCsv() {
  try {
    validateRange(); exporting.value = true; error.value = ''; notice.value = ''
    const file = await exportCulturalSalesReport({ startDate: startDate.value, endDate: endDate.value })
    const blob = file instanceof Blob ? file : new Blob([file], { type: 'text/csv;charset=utf-8' })
    const url = URL.createObjectURL(blob); const link = document.createElement('a')
    link.href = url; link.download = `文创商城销售明细_${startDate.value}_${endDate.value}.csv`
    document.body.appendChild(link); link.click(); link.remove(); setTimeout(() => URL.revokeObjectURL(url), 1000)
    notice.value = '销售明细 CSV 已生成'
  } catch (e) { error.value = e.message }
  finally { exporting.value = false }
}
function salesValue(item) { return Number(item[salesMetric.value] || 0) }
function salesHeight(item) { return `${Math.max(salesValue(item) ? 5 : 1, Math.abs(salesValue(item)) / salesMax.value * 100)}%` }
function salesLabel(item) { return salesMetric.value === 'paidOrderCount' ? `${salesValue(item)}单` : money(salesValue(item)) }
function stockValue(item) { return Number(item[stockMetric.value] || 0) }
function stockHeight(item) { return `${Math.max(stockValue(item) ? 5 : 1, Math.abs(stockValue(item)) / stockMax.value * 100)}%` }
function localDate(value) {
  const year = value.getFullYear(); const month = String(value.getMonth() + 1).padStart(2, '0'); const day = String(value.getDate()).padStart(2, '0')
  return `${year}-${month}-${day}`
}
function shortDate(value) { return value?.slice(5) || '-' }
function dateTime(value) { return value ? String(value).replace('T', ' ').slice(0, 16) : '-' }
function money(value) { return `¥${(Number(value || 0) / 100).toFixed(2)}` }
function percent(value) { return `${Number(value || 0).toFixed(2)}%` }

onMounted(loadAll)
</script>

<template>
  <div class="cultural-report-panel">
    <div class="toolbar report-toolbar"><div><h3>文创商城运营报表</h3><p>销售、退款、售后和库存数据采用后端实时统计口径。</p></div><div class="actions"><button :disabled="exporting" @click="downloadCsv">{{ exporting?'导出中…':'导出销售明细 CSV' }}</button><button class="primary" :disabled="loading" @click="refreshDashboard">刷新报表</button></div></div>
    <div class="report-filter panel"><label>开始日期<input v-model="startDate" type="date" /></label><label>结束日期<input v-model="endDate" type="date" /></label><div class="actions"><button @click="usePreset(7)">近7天</button><button @click="usePreset(30)">近30天</button><button @click="usePreset(90)">近90天</button></div><button class="primary" :disabled="loading" @click="refreshDashboard">生成报表</button></div>
    <p v-if="error" class="error cultural-message">{{ error }}</p><p v-if="notice" class="success cultural-message">{{ notice }}</p>
    <div class="content-tabs inventory-tabs"><button :class="{active:view==='sales'}" @click="switchView('sales')">销售与售后</button><button :class="{active:view==='inventory'}" @click="switchView('inventory')">库存分析</button></div>

    <template v-if="view==='sales'">
      <div class="report-stats cultural-report-stats"><article v-for="item in stats" :key="item.label"><span>{{ item.label }}</span><strong>{{ loading?'…':item.value }}</strong><small>{{ item.hint }}</small></article></div>
      <div class="grid-two report-top-grid">
        <article class="panel report-trend"><div class="panel-title"><div><h3>销售与退款趋势</h3><small>{{ startDate }} 至 {{ endDate }}</small></div><div class="metric-toggle"><button :class="{active:salesMetric==='netSalesCent'}" @click="salesMetric='netSalesCent'">净销售额</button><button :class="{active:salesMetric==='grossSalesCent'}" @click="salesMetric='grossSalesCent'">销售额</button><button :class="{active:salesMetric==='refundAmountCent'}" @click="salesMetric='refundAmountCent'">退款</button><button :class="{active:salesMetric==='paidOrderCount'}" @click="salesMetric='paidOrderCount'">订单</button></div></div><div v-if="!trend.length" class="empty-report">暂无趋势数据</div><div v-else class="bar-chart"><div v-for="item in trend" :key="item.date" class="bar-column" :title="`${item.date}：${salesLabel(item)}`"><div class="bar-value">{{ salesValue(item)?salesLabel(item):'' }}</div><div class="bar-track"><i :class="{'negative-bar':salesValue(item)<0}" :style="{height:salesHeight(item)}" /></div><small>{{ shortDate(item.date) }}</small></div></div></article>
        <article class="panel"><div class="panel-title"><div><h3>售后统计</h3><small>申请 {{ afterSale.totalApplications || 0 }} 单 · 完成退款 {{ money(afterSale.completedRefundAmountCent) }}</small></div></div><ul class="status-report"><li v-for="entry in afterSaleEntries" :key="entry[0]"><span>{{ entry[0] }}</span><b>{{ entry[1] || 0 }} 单</b></li></ul></article>
      </div>

      <article class="panel table-wrap report-section"><div class="report-table-title"><div><h3>热销商品排行</h3><p>按统计期内成功支付订单的销售数量排序。</p></div></div><table><thead><tr><th>排名</th><th>商品</th><th>SKU数</th><th>销量</th><th>销售额</th><th>退款订单</th></tr></thead><tbody><tr v-if="!topProducts.length"><td colspan="6">统计期内暂无成交商品。</td></tr><tr v-for="(item,index) in topProducts" :key="item.productId"><td><b>#{{ index+1 }}</b></td><td>{{ item.productName }}<small>{{ item.productCode }} · #{{ item.productId }}</small></td><td>{{ item.skuCount }}</td><td><b>{{ item.soldQuantity }} 件</b></td><td>{{ money(item.salesAmountCent) }}</td><td>{{ item.refundedOrderCount }} 单</td></tr></tbody></table></article>

      <article class="panel table-wrap report-section"><div class="report-table-title"><div><h3>每日销售明细</h3><p>按支付日与成功退款日分别统计。</p></div></div><table><thead><tr><th>日期</th><th>支付订单</th><th>销售件数</th><th>销售总额</th><th>退款金额</th><th>净销售额</th></tr></thead><tbody><tr v-if="!reversedTrend.length"><td colspan="6">暂无每日数据。</td></tr><tr v-for="item in reversedTrend" :key="item.date"><td>{{ item.date }}</td><td>{{ item.paidOrderCount }} 单</td><td>{{ item.soldQuantity }} 件</td><td>{{ money(item.grossSalesCent) }}</td><td>{{ money(item.refundAmountCent) }}</td><td><b>{{ money(item.netSalesCent) }}</b></td></tr></tbody></table></article>
    </template>

    <template v-else>
      <div class="inventory-report-overview"><article><span>可售库存</span><strong>{{ overview.availableStockQuantity || 0 }} 件</strong><small>全部文创 SKU</small></article><article><span>可售货值</span><strong>{{ money(overview.availableStockValueCent) }}</strong><small>按当前 SKU 售价计算</small></article><article :class="{warning:overview.lowStockSkuCount}"><span>低库存 SKU</span><strong>{{ overview.lowStockSkuCount || 0 }} 个</strong><small>需要及时补货</small></article></div>
      <article class="panel report-trend report-section"><div class="panel-title"><div><h3>库存变动趋势</h3><small>{{ startDate }} 至 {{ endDate }}</small></div><div class="metric-toggle"><button :class="{active:stockMetric==='netChangeQuantity'}" @click="stockMetric='netChangeQuantity'">净变动</button><button :class="{active:stockMetric==='inboundQuantity'}" @click="stockMetric='inboundQuantity'">入库</button><button :class="{active:stockMetric==='outboundQuantity'}" @click="stockMetric='outboundQuantity'">出库</button><button :class="{active:stockMetric==='saleQuantity'}" @click="stockMetric='saleQuantity'">销售</button><button :class="{active:stockMetric==='returnQuantity'}" @click="stockMetric='returnQuantity'">退货</button></div></div><div v-if="!movements.length" class="empty-report">暂无库存变动数据</div><div v-else class="bar-chart"><div v-for="item in movements" :key="item.date" class="bar-column" :title="`${item.date}：${stockValue(item)}`"><div class="bar-value">{{ stockValue(item) || '' }}</div><div class="bar-track"><i :class="{'negative-bar':stockValue(item)<0}" :style="{height:stockHeight(item)}" /></div><small>{{ shortDate(item.date) }}</small></div></div></article>
      <div class="filters panel cultural-filters report-section"><input v-model.trim="inventoryFilters.keyword" placeholder="商品、SKU名称或编码" @keyup.enter="searchInventory" /><label class="check-label inventory-check"><input v-model="inventoryFilters.lowStockOnly" type="checkbox" />仅看低库存</label><button class="primary" :disabled="loading" @click="searchInventory">查询库存</button></div>
      <div class="panel table-wrap"><table><thead><tr><th>商品/SKU</th><th>售价</th><th>现货/锁定</th><th>可售库存</th><th>预警值</th><th>可售货值</th><th>更新时间</th></tr></thead><tbody><tr v-if="!inventory.length"><td colspan="7">暂无符合条件的库存数据。</td></tr><tr v-for="item in inventory" :key="item.skuId" :class="{'low-stock-row':item.lowStock}"><td><b>{{ item.productName }}</b><small>{{ item.skuName }} · {{ item.skuCode }} · #{{ item.skuId }}</small></td><td>{{ money(item.priceCent) }}</td><td>{{ item.onHandQuantity }} / {{ item.lockedQuantity }}</td><td><b>{{ item.availableQuantity }}</b></td><td><span :class="{'stock-warning':item.lowStock}">{{ item.warningThreshold }}{{ item.lowStock?' · 库存不足':'' }}</span></td><td><b>{{ money(item.availableValueCent) }}</b></td><td>{{ dateTime(item.updatedAt) }}</td></tr></tbody></table><footer class="pagination"><span>共 {{ inventoryTotal }} 条，第 {{ inventoryPage }}/{{ inventoryPages || 1 }} 页</span><div><button :disabled="inventoryPage<=1||loading" @click="run(()=>loadInventory(inventoryPage-1))">上一页</button><button :disabled="inventoryPage>=inventoryPages||loading" @click="run(()=>loadInventory(inventoryPage+1))">下一页</button></div></footer></div>
    </template>
  </div>
</template>
