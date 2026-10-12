<script setup>
import { onMounted, ref } from 'vue'
import { expireOverdueOrders, getOrderDetail, getOrders } from '../services/orders'

const loading = ref(false)
const detailLoading = ref(false)
const error = ref('')
const orders = ref([])
const total = ref(0)
const status = ref('')
const orderNo = ref('')
const detail = ref(null)

async function load() {
  try {
    loading.value = true
    error.value = ''
    const data = await getOrders({ status: status.value || undefined, orderNo: orderNo.value || undefined, page: 1, pageSize: 50 })
    orders.value = data.items || []
    total.value = data.total || 0
  } catch (e) { error.value = e.message } finally { loading.value = false }
}

async function openDetail(item) {
  try {
    detailLoading.value = true
    error.value = ''
    detail.value = await getOrderDetail(item.id)
  } catch (e) { error.value = e.message } finally { detailLoading.value = false }
}

async function expireOverdue() {
  if (!confirm('确认扫描并关闭已超时的待支付订单吗？')) return
  try {
    loading.value = true
    error.value = ''
    const result = await expireOverdueOrders()
    alert(`扫描完成，关闭 ${result.expiredCount || 0} 个超时订单`)
    await load()
  } catch (e) { error.value = e.message } finally { loading.value = false }
}

function money(value) { return `¥${((value || 0) / 100).toFixed(2)}` }
function date(value) { return value?.replace('T', ' ') || '-' }
onMounted(load)
</script>

<template>
  <div>
    <div class="toolbar"><div><h2>订单管理</h2><p>查询全部订单、查看商品明细，并扫描关闭超时未支付订单。</p></div><div class="actions"><button @click="load">刷新</button><button class="primary" @click="expireOverdue">扫描超时订单</button></div></div>
    <div class="filters"><select v-model="status"><option value="">全部状态</option><option value="PENDING_PAYMENT">待支付</option><option value="PAID">已支付</option><option value="READY">待使用</option><option value="COMPLETED">已完成</option><option value="REFUNDED">已退款</option><option value="CANCELLED">已取消</option><option value="EXPIRED">已超时</option></select><input v-model.trim="orderNo" placeholder="订单号" @keyup.enter="load" /><button @click="load">查询</button></div>
    <p v-if="error" class="error">{{ error }}</p>
    <div class="panel table-wrap"><table><thead><tr><th>订单号</th><th>用户</th><th>订单类型</th><th>应付 / 已付</th><th>状态</th><th>创建时间</th><th>操作</th></tr></thead><tbody><tr v-if="loading"><td colspan="7">加载中...</td></tr><tr v-else-if="!orders.length"><td colspan="7">暂无订单数据。</td></tr><tr v-for="item in orders" :key="item.id"><td><b>{{ item.orderNo }}</b><small>#{{ item.id }}</small></td><td>{{ item.userNickname || item.userId }}</td><td>{{ item.orderType }}</td><td>{{ money(item.payableAmountCent) }}<small>已付 {{ money(item.paidAmountCent) }}</small></td><td><span class="badge">{{ item.status }}</span></td><td>{{ date(item.createdAt) }}</td><td><button @click="openDetail(item)">{{ detailLoading ? '加载中' : '查看详情' }}</button></td></tr></tbody></table><footer>共 {{ total }} 条订单</footer></div>

    <div v-if="detail" class="modal-backdrop" @click.self="detail = null"><section class="modal modal-wide panel"><div class="modal-header"><div><h3>订单详情：{{ detail.orderNo }}</h3><p>{{ detail.orderType }} · {{ detail.status }} · 创建于 {{ date(detail.createdAt) }}</p></div><button class="text-button" @click="detail = null">×</button></div>
      <div class="detail-grid"><div><span>订单总额</span><b>{{ money(detail.totalAmountCent) }}</b></div><div><span>应付金额</span><b>{{ money(detail.payableAmountCent) }}</b></div><div><span>已付金额</span><b>{{ money(detail.paidAmountCent) }}</b></div><div><span>支付截止</span><b>{{ date(detail.expiresAt) }}</b></div></div>
      <div class="subsection"><h4>订单项目</h4><table><thead><tr><th>资源 / SKU</th><th>服务日期</th><th>数量</th><th>单价</th><th>小计</th></tr></thead><tbody><tr v-if="!detail.items?.length"><td colspan="5">无订单项目</td></tr><tr v-for="item in detail.items" :key="item.id"><td><b>{{ item.resourceName }}</b><small>{{ item.skuName }} · {{ item.skuCode }}</small></td><td>{{ item.serviceDate }} {{ item.timeSlot || '全天' }}</td><td>{{ item.quantity }}</td><td>{{ money(item.unitPriceCent) }}</td><td>{{ money(item.amountCent) }}</td></tr></tbody></table></div>
      <p v-if="detail.remark" class="review-tip">订单备注：{{ detail.remark }}</p>
    </section></div>
  </div>
</template>
