<script setup>
import { computed, onMounted, ref } from 'vue'
import { getDashboardSummary } from '../services/dashboard'
import { getOrders } from '../services/orders'

const loading = ref(false)
const error = ref('')
const summary = ref(null)
const recentOrders = ref([])

const stats = computed(() => [
  { label: '今日订单', value: summary.value?.todayOrderCount ?? '-', hint: `待支付 ${summary.value?.pendingPaymentOrderCount ?? '-'} 单` },
  { label: '待核销电子票', value: summary.value?.pendingVerificationCount ?? '-', hint: '尚未使用的核销凭证' },
  { label: '可售资源', value: summary.value?.activeResourceCount ?? '-', hint: `启用 SKU ${summary.value?.activeSkuCount ?? '-'} 个` },
  { label: '今日实收', value: summary.value ? money(summary.value.todayRevenueCent) : '-', hint: '按成功支付流水统计' }
])

async function load() {
  try {
    loading.value = true
    error.value = ''
    const [summaryData, orderData] = await Promise.all([
      getDashboardSummary(),
      getOrders({ page: 1, pageSize: 5 })
    ])
    summary.value = summaryData
    recentOrders.value = orderData.items || []
  } catch (e) {
    error.value = e.message
  } finally {
    loading.value = false
  }
}

function money(value) { return `¥${((value || 0) / 100).toFixed(2)}` }
function date(value) { return value?.replace('T', ' ') || '-' }

onMounted(load)
</script>

<template>
  <div>
    <div class="welcome"><div><p>运营工作台</p><h2>欢迎回来，开始今天的运营管理</h2><span>数据来自当前后台数据库，刷新页面即可获取最新状态。</span></div><b>北方文旅</b></div>
    <p v-if="error" class="error">{{ error }} <button @click="load">重新加载</button></p>
    <div class="stats"><article v-for="item in stats" :key="item.label"><span>{{ item.label }}</span><strong>{{ loading ? '...' : item.value }}</strong><small>{{ item.hint }}</small></article></div>
    <div class="grid-two">
      <article class="panel"><div class="panel-title"><h3>今日待办</h3><button @click="load">刷新</button></div><ul>
        <li><RouterLink to="/orders">待支付订单</RouterLink><span>{{ summary?.pendingPaymentOrderCount ?? '-' }} 单</span></li>
        <li><RouterLink to="/refunds">待审核退款</RouterLink><span>{{ summary?.pendingRefundCount ?? '-' }} 单</span></li>
        <li><RouterLink to="/verification">待核销电子票</RouterLink><span>{{ summary?.pendingVerificationCount ?? '-' }} 张</span></li>
        <li><RouterLink to="/resources">上架资源 / SKU</RouterLink><span>{{ summary?.activeResourceCount ?? '-' }} / {{ summary?.activeSkuCount ?? '-' }}</span></li>
      </ul></article>
      <article class="panel"><div class="panel-title"><h3>最近订单</h3><RouterLink to="/orders">查看全部</RouterLink></div><ul class="recent-orders"><li v-if="!recentOrders.length"><span>暂无订单</span></li><li v-for="item in recentOrders" :key="item.id"><div><b>{{ item.orderNo }}</b><small>{{ date(item.createdAt) }}</small></div><div><b>{{ money(item.payableAmountCent) }}</b><small>{{ item.status }}</small></div></li></ul></article>
    </div>
    <p v-if="summary" class="generated-at">统计生成时间：{{ date(summary.generatedAt) }}</p>
  </div>
</template>
