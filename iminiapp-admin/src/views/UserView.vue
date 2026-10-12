<script setup>
import { onMounted, ref } from 'vue'
import { getUserDetail, getUsers, updateUserStatus } from '../services/users'

const loading = ref(false)
const detailLoading = ref(false)
const error = ref('')
const users = ref([])
const total = ref(0)
const totalPages = ref(0)
const page = ref(1)
const keyword = ref('')
const status = ref('')
const levelCode = ref('')
const detail = ref(null)

async function load(resetPage = false) {
  if (resetPage) page.value = 1
  try {
    loading.value = true
    error.value = ''
    const data = await getUsers({
      keyword: keyword.value || undefined,
      status: status.value || undefined,
      levelCode: levelCode.value || undefined,
      page: page.value,
      pageSize: 20
    })
    users.value = data.items || []
    total.value = data.total || 0
    totalPages.value = data.totalPages || 0
  } catch (e) {
    error.value = e.message
  } finally {
    loading.value = false
  }
}

async function openDetail(user) {
  try {
    detailLoading.value = true
    error.value = ''
    detail.value = await getUserDetail(user.id)
  } catch (e) {
    error.value = e.message
  } finally {
    detailLoading.value = false
  }
}

async function changeStatus(user) {
  const nextStatus = user.status === 'ACTIVE' ? 'DISABLED' : 'ACTIVE'
  const action = nextStatus === 'ACTIVE' ? '启用' : '禁用'
  if (!confirm(`确认${action}用户“${user.nickname}”吗？`)) return
  const reason = prompt(`请输入${action}原因（将写入审计日志）：`)
  if (reason === null) return
  if (!reason.trim()) {
    alert('状态变更原因不能为空')
    return
  }
  try {
    loading.value = true
    error.value = ''
    const updated = await updateUserStatus(user.id, { status: nextStatus, reason: reason.trim() })
    if (detail.value?.id === user.id) detail.value = updated
    await load()
  } catch (e) {
    error.value = e.message
  } finally {
    loading.value = false
  }
}

async function turnPage(next) {
  if (next < 1 || next > totalPages.value || next === page.value) return
  page.value = next
  await load()
}

function money(value) { return `¥${((value || 0) / 100).toFixed(2)}` }
function date(value) { return value?.replace('T', ' ') || '-' }
function gender(value) { return ({ 1: '男', 2: '女' })[value] || '未设置' }
function statusText(value) { return value === 'ACTIVE' ? '正常' : '已禁用' }

onMounted(load)
</script>

<template>
  <div>
    <div class="toolbar">
      <div><h2>用户与会员</h2><p>查询用户、会员资产和消费记录，并对异常账号进行状态管理。</p></div>
      <button @click="load()">刷新</button>
    </div>

    <div class="filters">
      <input v-model.trim="keyword" placeholder="昵称或手机号" @keyup.enter="load(true)" />
      <select v-model="status"><option value="">全部状态</option><option value="ACTIVE">正常</option><option value="DISABLED">已禁用</option></select>
      <input v-model.trim="levelCode" placeholder="会员等级，如 NORMAL" @keyup.enter="load(true)" />
      <button class="primary" @click="load(true)">查询</button>
    </div>

    <p v-if="error" class="error">{{ error }}</p>
    <div class="panel table-wrap">
      <table>
        <thead><tr><th>用户</th><th>手机号</th><th>会员</th><th>订单</th><th>累计消费</th><th>状态</th><th>注册时间</th><th>操作</th></tr></thead>
        <tbody>
          <tr v-if="loading"><td colspan="8">加载中...</td></tr>
          <tr v-else-if="!users.length"><td colspan="8">暂无符合条件的用户。</td></tr>
          <tr v-for="user in users" :key="user.id">
            <td><b>{{ user.nickname }}</b><small>用户 ID：{{ user.id }}</small></td>
            <td>{{ user.mobile || '未绑定' }}</td>
            <td><b>{{ user.levelCode }}</b><small>成长值 {{ user.growthValue }} · 积分 {{ user.points }}</small></td>
            <td>{{ user.orderCount }} 单</td>
            <td>{{ money(user.cumulativeSpendCent) }}</td>
            <td><span class="badge" :class="{ inactive: user.status !== 'ACTIVE' }">{{ statusText(user.status) }}</span></td>
            <td>{{ date(user.createdAt) }}</td>
            <td><div class="actions"><button @click="openDetail(user)">{{ detailLoading ? '加载中' : '详情' }}</button><button @click="changeStatus(user)">{{ user.status === 'ACTIVE' ? '禁用' : '启用' }}</button></div></td>
          </tr>
        </tbody>
      </table>
      <footer class="pagination"><span>共 {{ total }} 位用户，第 {{ page }} / {{ totalPages || 1 }} 页</span><div><button :disabled="page <= 1" @click="turnPage(page - 1)">上一页</button><button :disabled="page >= totalPages" @click="turnPage(page + 1)">下一页</button></div></footer>
    </div>

    <div v-if="detail" class="modal-backdrop" @click.self="detail = null">
      <section class="modal modal-wide panel user-detail">
        <div class="modal-header"><div><h3>{{ detail.nickname }}</h3><p>用户 ID：{{ detail.id }} · 注册于 {{ date(detail.createdAt) }}</p></div><button class="text-button" @click="detail = null">×</button></div>
        <div class="detail-grid user-profile-grid">
          <div><span>账号状态</span><b>{{ statusText(detail.status) }}</b></div>
          <div><span>手机号</span><b>{{ detail.mobile || '未绑定' }}</b></div>
          <div><span>真实姓名</span><b>{{ detail.realName || '未填写' }}</b></div>
          <div><span>性别</span><b>{{ gender(detail.gender) }}</b></div>
          <div><span>会员等级</span><b>{{ detail.levelCode }}</b></div>
          <div><span>成长值 / 积分</span><b>{{ detail.growthValue }} / {{ detail.points }}</b></div>
          <div><span>会员余额</span><b>{{ money(detail.balanceCent) }}</b></div>
          <div><span>累计消费</span><b>{{ money(detail.cumulativeSpendCent) }}</b></div>
        </div>

        <div class="user-summary-line"><span>订单 {{ detail.orderCount }} 单</span><span>退款 {{ detail.refundCount }} 笔</span><span>核销凭证 {{ detail.verificationCount }} 张</span><span>最近登录 {{ date(detail.lastLoginAt) }}</span></div>

        <div class="subsection"><h4>最近订单</h4><table><thead><tr><th>订单号</th><th>状态</th><th>应付 / 已付</th><th>创建时间</th></tr></thead><tbody><tr v-if="!detail.recentOrders?.length"><td colspan="4">暂无订单</td></tr><tr v-for="item in detail.recentOrders" :key="item.id"><td>{{ item.orderNo }}</td><td>{{ item.status }}</td><td>{{ money(item.payableAmountCent) }} / {{ money(item.paidAmountCent) }}</td><td>{{ date(item.createdAt) }}</td></tr></tbody></table></div>
        <div class="subsection"><h4>最近退款</h4><table><thead><tr><th>退款单号</th><th>状态</th><th>金额</th><th>原因</th><th>申请时间</th></tr></thead><tbody><tr v-if="!detail.recentRefunds?.length"><td colspan="5">暂无退款记录</td></tr><tr v-for="item in detail.recentRefunds" :key="item.id"><td>{{ item.refundNo }}</td><td>{{ item.status }}</td><td>{{ money(item.amountCent) }}</td><td>{{ item.reason || '-' }}</td><td>{{ date(item.createdAt) }}</td></tr></tbody></table></div>
        <div class="subsection"><h4>最近核销</h4><table><thead><tr><th>资源</th><th>状态</th><th>核销时间</th><th>创建时间</th></tr></thead><tbody><tr v-if="!detail.recentVerifications?.length"><td colspan="4">暂无核销记录</td></tr><tr v-for="item in detail.recentVerifications" :key="item.id"><td>{{ item.resourceName }}</td><td>{{ item.status }}</td><td>{{ date(item.verifiedAt) }}</td><td>{{ date(item.createdAt) }}</td></tr></tbody></table></div>
      </section>
    </div>
  </div>
</template>
