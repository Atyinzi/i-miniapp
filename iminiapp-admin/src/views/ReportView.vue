<script setup>
import { computed, onMounted, ref } from 'vue'
import { getDailyReport, getReportOverview, getResourceSalesReport } from '../services/reports'

const endDate = ref(localDate(new Date()))
const startDate = ref(localDate(new Date(Date.now() - 29 * 86400000)))
const loading = ref(false)
const error = ref('')
const overview = ref(null)
const daily = ref([])
const resources = ref([])
const chartMetric = ref('revenue')

const stats = computed(() => [
  { label: '订单总数', value: `${overview.value?.orderCount ?? '-'} 单`, hint: `支付成功 ${overview.value?.paidOrderCount ?? '-'} 单` },
  { label: '成交总额', value: overview.value ? money(overview.value.grossRevenueCent) : '-', hint: '按成功支付流水统计' },
  { label: '退款金额', value: overview.value ? money(overview.value.refundAmountCent) : '-', hint: '按成功退款流水统计' },
  { label: '净收入', value: overview.value ? money(overview.value.netRevenueCent) : '-', hint: '成交总额减成功退款' },
  { label: '新增用户', value: `${overview.value?.newUserCount ?? '-'} 人`, hint: '统计期内首次注册' },
  { label: '完成核销', value: `${overview.value?.verificationCount ?? '-'} 次`, hint: '统计期内已使用凭证' }
])

const maxChartValue = computed(() => Math.max(1, ...daily.value.map(item => chartValue(item))))
const statusEntries = computed(() => Object.entries(overview.value?.orderStatusCounts || {}))
const reversedDaily = computed(() => [...daily.value].reverse())

async function load() {
  if (!startDate.value || !endDate.value) {
    error.value = '请选择完整的开始和结束日期'
    return
  }
  if (startDate.value > endDate.value) {
    error.value = '开始日期不能晚于结束日期'
    return
  }
  try {
    loading.value = true
    error.value = ''
    const params = { startDate: startDate.value, endDate: endDate.value }
    const [overviewData, dailyData, resourceData] = await Promise.all([
      getReportOverview(params), getDailyReport(params), getResourceSalesReport({ ...params, limit: 10 })
    ])
    overview.value = overviewData
    daily.value = dailyData || []
    resources.value = resourceData || []
  } catch (e) { error.value = e.message } finally { loading.value = false }
}

function usePreset(days) {
  const end = new Date()
  endDate.value = localDate(end)
  startDate.value = localDate(new Date(end.getTime() - (days - 1) * 86400000))
  load()
}

function chartValue(item) { return chartMetric.value === 'revenue' ? item.netRevenueCent : item.orderCount }
function chartHeight(item) { return `${Math.max(chartValue(item) ? 5 : 1, chartValue(item) / maxChartValue.value * 100)}%` }
function chartLabel(item) { return chartMetric.value === 'revenue' ? money(item.netRevenueCent) : `${item.orderCount} 单` }
function localDate(value) {
  const year = value.getFullYear()
  const month = String(value.getMonth() + 1).padStart(2, '0')
  const day = String(value.getDate()).padStart(2, '0')
  return `${year}-${month}-${day}`
}
function money(value) { return `¥${((value || 0) / 100).toFixed(2)}` }
function shortDate(value) { return value?.slice(5) || '-' }

function exportCsv() {
  if (!daily.value.length) return
  const rows = [
    ['日期', '订单数', '支付订单数', '成交总额（元）', '退款金额（元）', '净收入（元）', '新增用户', '核销次数'],
    ...daily.value.map(item => [item.date, item.orderCount, item.paidOrderCount,
      (item.grossRevenueCent / 100).toFixed(2), (item.refundAmountCent / 100).toFixed(2),
      (item.netRevenueCent / 100).toFixed(2), item.newUserCount, item.verificationCount])
  ]
  const csv = rows.map(row => row.map(value => `"${String(value).replaceAll('"', '""')}"`).join(',')).join('\r\n')
  const url = URL.createObjectURL(new Blob(['\ufeff' + csv], { type: 'text/csv;charset=utf-8' }))
  const link = document.createElement('a')
  link.href = url
  link.download = `i某小程序经营日报_${startDate.value}_${endDate.value}.csv`
  link.click()
  URL.revokeObjectURL(url)
}

onMounted(load)
</script>

<template>
  <div>
    <div class="toolbar"><div><h2>数据报表</h2><p>基于订单、支付、退款、用户和核销流水生成实时经营统计。</p></div><div class="actions"><button :disabled="!daily.length" @click="exportCsv">导出 CSV</button><button class="primary" @click="load">刷新报表</button></div></div>
    <div class="report-filter panel"><label>开始日期<input v-model="startDate" type="date" /></label><label>结束日期<input v-model="endDate" type="date" /></label><div class="actions"><button @click="usePreset(7)">近 7 天</button><button @click="usePreset(30)">近 30 天</button><button @click="usePreset(90)">近 90 天</button></div><button class="primary" @click="load">生成报表</button></div>
    <p v-if="error" class="error">{{ error }}</p>

    <div class="report-stats"><article v-for="item in stats" :key="item.label"><span>{{ item.label }}</span><strong>{{ loading ? '...' : item.value }}</strong><small>{{ item.hint }}</small></article></div>

    <div class="grid-two report-top-grid">
      <article class="panel report-trend"><div class="panel-title"><div><h3>每日趋势</h3><small>{{ startDate }} 至 {{ endDate }}</small></div><div class="metric-toggle"><button :class="{ active: chartMetric === 'revenue' }" @click="chartMetric = 'revenue'">净收入</button><button :class="{ active: chartMetric === 'orders' }" @click="chartMetric = 'orders'">订单数</button></div></div><div v-if="!daily.length" class="empty-report">暂无趋势数据</div><div v-else class="bar-chart"><div v-for="item in daily" :key="item.date" class="bar-column" :title="`${item.date}：${chartLabel(item)}`"><div class="bar-value">{{ chartValue(item) ? chartLabel(item) : '' }}</div><div class="bar-track"><i :style="{ height: chartHeight(item) }" /></div><small>{{ shortDate(item.date) }}</small></div></div></article>
      <article class="panel"><div class="panel-title"><h3>订单状态分布</h3><small>按创建订单统计</small></div><div v-if="!statusEntries.length" class="empty-report">暂无订单数据</div><ul v-else class="status-report"><li v-for="entry in statusEntries" :key="entry[0]"><span>{{ entry[0] }}</span><b>{{ entry[1] }} 单</b></li></ul></article>
    </div>

    <article class="panel table-wrap report-section"><div class="report-table-title"><div><h3>资源销售排行</h3><p>按成功支付订单中的商品金额统计，最多显示 10 项。</p></div></div><table><thead><tr><th>排名</th><th>资源</th><th>类型</th><th>成交订单</th><th>销售数量</th><th>成交额</th></tr></thead><tbody><tr v-if="!resources.length"><td colspan="6">统计期内暂无成交资源。</td></tr><tr v-for="(item, index) in resources" :key="item.resourceId"><td><b>#{{ index + 1 }}</b></td><td>{{ item.resourceName }}</td><td>{{ item.resourceType }}</td><td>{{ item.orderCount }} 单</td><td>{{ item.quantity }}</td><td><b>{{ money(item.grossSalesCent) }}</b></td></tr></tbody></table></article>

    <article class="panel table-wrap report-section"><div class="report-table-title"><div><h3>每日经营明细</h3><p>CSV 导出内容与本表口径一致。</p></div></div><table><thead><tr><th>日期</th><th>订单 / 支付</th><th>成交总额</th><th>退款金额</th><th>净收入</th><th>新增用户</th><th>核销</th></tr></thead><tbody><tr v-if="!reversedDaily.length"><td colspan="7">暂无日报数据。</td></tr><tr v-for="item in reversedDaily" :key="item.date"><td>{{ item.date }}</td><td>{{ item.orderCount }} / {{ item.paidOrderCount }}</td><td>{{ money(item.grossRevenueCent) }}</td><td>{{ money(item.refundAmountCent) }}</td><td><b>{{ money(item.netRevenueCent) }}</b></td><td>{{ item.newUserCount }}</td><td>{{ item.verificationCount }}</td></tr></tbody></table></article>
    <p v-if="overview" class="generated-at">统计口径：{{ overview.startDate }} 至 {{ overview.endDate }}；生成时间：{{ overview.generatedAt?.replace('T', ' ') }}</p>
  </div>
</template>
