<script setup>
import { onMounted, ref } from 'vue'
import { approveRefund, getRefundApplications, rejectRefund } from '../services/refunds'

const loading = ref(false)
const saving = ref(false)
const error = ref('')
const items = ref([])
const total = ref(0)
const status = ref('')
const reviewTarget = ref(null)
const reviewMode = ref('approve')
const comment = ref('')

async function load() {
  try {
    loading.value = true; error.value = ''
    const data = await getRefundApplications({ status: status.value || undefined, page: 1, pageSize: 50 })
    items.value = data.items || []
    total.value = data.total || 0
  } catch (e) { error.value = e.message } finally { loading.value = false }
}
function openReview(item, mode) {
  reviewTarget.value = item
  reviewMode.value = mode
  comment.value = ''
}
async function submitReview() {
  if (reviewMode.value === 'reject' && !comment.value.trim()) { error.value = '拒绝退款时必须填写原因'; return }
  try {
    saving.value = true; error.value = ''
    if (reviewMode.value === 'approve') await approveRefund(reviewTarget.value.id, comment.value.trim() || null)
    else await rejectRefund(reviewTarget.value.id, comment.value.trim())
    reviewTarget.value = null
    await load()
  } catch (e) { error.value = e.message } finally { saving.value = false }
}
function money(value) { return `¥${((value || 0) / 100).toFixed(2)}` }
function date(value) { return value?.replace('T', ' ') || '-' }
onMounted(load)
</script>

<template>
  <div>
    <div class="toolbar"><div><h2>退款审核</h2><p>审核通过后将执行模拟退款、回补库存，并使未核销电子票作废；该操作不可撤销。</p></div><button @click="load">刷新</button></div>
    <div class="filters"><select v-model="status" @change="load"><option value="">全部状态</option><option value="PENDING_REVIEW">待审核</option><option value="SUCCESS">已退款</option><option value="REJECTED">已拒绝</option><option value="PROCESSING">处理中</option></select><button @click="load">查询</button></div>
    <p v-if="error" class="error">{{ error }}</p>
    <div class="panel table-wrap"><table><thead><tr><th>退款单号</th><th>关联订单</th><th>退款金额</th><th>申请原因</th><th>状态</th><th>申请时间</th><th>操作</th></tr></thead><tbody><tr v-if="loading"><td colspan="7">加载中...</td></tr><tr v-else-if="!items.length"><td colspan="7">暂无退款申请。</td></tr><tr v-for="item in items" :key="item.id"><td><b>{{ item.refundNo }}</b><small>#{{ item.id }}</small></td><td>{{ item.orderNo }}</td><td>{{ money(item.amountCent) }}</td><td>{{ item.reason }}</td><td><span class="badge" :class="item.status === 'REJECTED' ? 'inactive' : item.status === 'PENDING_REVIEW' ? 'draft' : ''">{{ item.status }}</span></td><td>{{ date(item.requestedAt) }}</td><td class="actions"><template v-if="item.status === 'PENDING_REVIEW'"><button class="primary" @click="openReview(item, 'approve')">通过</button><button @click="openReview(item, 'reject')">拒绝</button></template><span v-else>{{ item.reviewComment || '-' }}</span></td></tr></tbody></table><footer>共 {{ total }} 条退款申请</footer></div>
    <div v-if="reviewTarget" class="modal-backdrop" @click.self="reviewTarget = null"><form class="modal panel" @submit.prevent="submitReview"><div class="modal-header"><h3>{{ reviewMode === 'approve' ? '确认通过退款' : '拒绝退款申请' }}</h3><button type="button" class="text-button" @click="reviewTarget = null">×</button></div><p class="review-tip">退款单：{{ reviewTarget.refundNo }}；订单：{{ reviewTarget.orderNo }}；金额：{{ money(reviewTarget.amountCent) }}</p><label>{{ reviewMode === 'approve' ? '审核备注（可选）' : '拒绝原因' }}<textarea v-model.trim="comment" maxlength="500" rows="4" :required="reviewMode === 'reject'" :placeholder="reviewMode === 'approve' ? '可填写审核说明' : '请填写拒绝原因'" /></label><div class="modal-actions"><button type="button" @click="reviewTarget = null">取消</button><button class="primary" :disabled="saving">{{ saving ? '提交中...' : reviewMode === 'approve' ? '确认退款' : '确认拒绝' }}</button></div></form></div>
  </div>
</template>
