<script setup>
import { onMounted, ref } from 'vue'
import {
  getAuditLogs, getPermissions, getPlatformSettings, getRoles,
  updatePlatformSetting, updateRolePermissions, getAdminAccounts, createAdminAccount,
  updateAdminAccountStatus, resetAdminPassword, updateAdminAccountRoles
} from '../services/settings'

const tab = ref('platform')
const loading = ref(false)
const saving = ref(false)
const error = ref('')
const settings = ref([])
const roles = ref([])
const permissions = ref([])
const logs = ref([])
const logTotal = ref(0)
const logPages = ref(0)
const logPage = ref(1)
const logKeyword = ref('')
const logAction = ref('')
const settingForm = ref(null)
const roleForm = ref(null)
const accounts = ref([])
const accountForm = ref(null)

async function loadPlatform() {
  try { loading.value = true; error.value = ''; settings.value = await getPlatformSettings() }
  catch (e) { error.value = e.message } finally { loading.value = false }
}

async function loadRoles() {
  try {
    loading.value = true
    error.value = ''
    const [roleData, permissionData] = await Promise.all([getRoles(), getPermissions()])
    roles.value = roleData || []
    permissions.value = permissionData || []
  } catch (e) { error.value = e.message } finally { loading.value = false }
}

async function loadLogs(resetPage = false) {
  if (resetPage) logPage.value = 1
  try {
    loading.value = true
    error.value = ''
    const data = await getAuditLogs({
      keyword: logKeyword.value || undefined,
      action: logAction.value || undefined,
      page: logPage.value,
      pageSize: 20
    })
    logs.value = data.items || []
    logTotal.value = data.total || 0
    logPages.value = data.totalPages || 0
  } catch (e) { error.value = e.message } finally { loading.value = false }
}

async function loadAccounts() {
  try { loading.value = true; error.value = ''; accounts.value = await getAdminAccounts() || [] }
  catch (e) { error.value = e.message } finally { loading.value = false }
}

function switchTab(value) {
  tab.value = value
  error.value = ''
  if (value === 'platform') loadPlatform()
  if (value === 'roles') loadRoles()
  if (value === 'accounts') Promise.all([loadAccounts(), loadRoles()])
  if (value === 'audit') loadLogs()
}

function newAccount() {
  accountForm.value = { username: '', displayName: '', password: '', roles: ['OPERATIONS'] }
}
async function saveAccount() {
  try { saving.value = true; error.value = ''; await createAdminAccount(accountForm.value); accountForm.value = null; await loadAccounts() }
  catch (e) { error.value = e.message } finally { saving.value = false }
}
async function toggleAccount(item) {
  if (!confirm(`确定${item.status === 'ACTIVE' ? '停用' : '启用'}账号 ${item.username} 吗？`)) return
  try { await updateAdminAccountStatus(item.id, item.status === 'ACTIVE' ? 'DISABLED' : 'ACTIVE'); await loadAccounts() }
  catch (e) { error.value = e.message }
}
async function resetPassword(item) {
  const password = prompt(`请输入 ${item.username} 的新密码（至少12位，包含字母和数字）`)
  if (!password) return
  try { await resetAdminPassword(item.id, password); alert('密码已重置，该账号需要重新登录') }
  catch (e) { error.value = e.message }
}
async function changeRoles(item) {
  const current = (item.roles || []).join(',')
  const value = prompt('输入角色，多个用逗号分隔：ADMIN、OPERATIONS、VERIFIER', current)
  if (!value) return
  try { await updateAdminAccountRoles(item.id, value.split(',').map(v => v.trim()).filter(Boolean)); await loadAccounts() }
  catch (e) { error.value = e.message }
}

function editSetting(item) { settingForm.value = { ...item } }

async function saveSetting() {
  try {
    saving.value = true
    error.value = ''
    await updatePlatformSetting(settingForm.value.key, String(settingForm.value.value ?? ''))
    settingForm.value = null
    await loadPlatform()
  } catch (e) { error.value = e.message } finally { saving.value = false }
}

function editRole(role) {
  roleForm.value = { ...role, permissionIds: [...(role.permissionIds || [])] }
}

async function saveRole() {
  try {
    saving.value = true
    error.value = ''
    await updateRolePermissions(roleForm.value.id, roleForm.value.permissionIds)
    roleForm.value = null
    await loadRoles()
  } catch (e) { error.value = e.message } finally { saving.value = false }
}

async function turnLogPage(next) {
  if (next < 1 || next > logPages.value || next === logPage.value) return
  logPage.value = next
  await loadLogs()
}

function date(value) { return value?.replace('T', ' ') || '-' }
function settingTypeText(value) { return ({ STRING: '文本', INTEGER: '整数', BOOLEAN: '开关', JSON: 'JSON' })[value] || value }
function roleText(value) { return ({ ADMIN: '系统管理员', OPERATIONS: '运营人员', VERIFIER: '核销人员' })[value] || value }
function formatDetail(value) {
  if (!value) return '-'
  try { return JSON.stringify(JSON.parse(value), null, 2) } catch { return value }
}

onMounted(() => Promise.all([loadPlatform(), loadRoles(), loadLogs(), loadAccounts()]))
</script>

<template>
  <div>
    <div class="toolbar"><div><h2>系统设置</h2><p>维护管理员账号、平台参数、角色权限矩阵并查询操作审计。</p></div><button @click="tab === 'platform' ? loadPlatform() : tab === 'roles' ? loadRoles() : tab === 'accounts' ? loadAccounts() : loadLogs()">刷新</button></div>
    <div class="content-tabs"><button :class="{ active: tab === 'accounts' }" @click="switchTab('accounts')">管理员账号</button><button :class="{ active: tab === 'platform' }" @click="switchTab('platform')">平台参数</button><button :class="{ active: tab === 'roles' }" @click="switchTab('roles')">角色权限</button><button :class="{ active: tab === 'audit' }" @click="switchTab('audit')">操作审计</button></div>
    <p v-if="error" class="error">{{ error }}</p>

    <template v-if="tab === 'accounts'">
      <div class="toolbar"><div><p>每位管理人员使用独立账号，离职时停用账号，禁止多人共享密码。</p></div><button class="primary" @click="newAccount">新增管理员</button></div>
      <div class="panel table-wrap"><table><thead><tr><th>账号</th><th>角色</th><th>状态</th><th>最近登录</th><th>操作</th></tr></thead><tbody><tr v-if="loading"><td colspan="5">加载中...</td></tr><tr v-for="item in accounts" :key="item.id"><td><b>{{ item.displayName }}</b><small>{{ item.username }}</small></td><td>{{ (item.roles || []).map(roleText).join('、') }}</td><td><span class="badge" :class="{ inactive: item.status !== 'ACTIVE' }">{{ item.status === 'ACTIVE' ? '正常' : '已停用' }}</span></td><td>{{ date(item.lastLoginAt) }}<small>{{ item.lastLoginIp || '-' }}</small></td><td><div class="actions"><button @click="changeRoles(item)">角色</button><button @click="resetPassword(item)">重置密码</button><button @click="toggleAccount(item)">{{ item.status === 'ACTIVE' ? '停用' : '启用' }}</button></div></td></tr><tr v-if="!loading && !accounts.length"><td colspan="5">暂无管理员账号</td></tr></tbody></table></div>
    </template>

    <template v-if="tab === 'platform'">
      <div class="settings-warning">生产配置中的数据库密码、微信密钥、商户私钥不得通过此页面保存，应使用环境变量或密钥管理服务。</div>
      <div class="settings-grid"><article v-for="item in settings" :key="item.key" class="panel setting-card"><div><span>{{ item.description || item.key }}</span><small>{{ item.key }} · {{ settingTypeText(item.valueType) }} · {{ item.publicSetting ? '公开参数' : '内部参数' }}</small></div><strong>{{ item.value }}</strong><footer><small>更新于 {{ date(item.updatedAt) }}</small><button @click="editSetting(item)">修改</button></footer></article><article v-if="!loading && !settings.length" class="panel empty-report">暂无平台参数</article></div>
    </template>

    <template v-if="tab === 'roles'">
      <div class="settings-warning">角色权限会同时控制左侧菜单、前端路由和后端接口；修改账号角色后，该账号的现有登录会话会立即失效。</div>
      <div class="role-grid"><article v-for="role in roles" :key="role.id" class="panel role-card"><div class="panel-title"><div><h3>{{ roleText(role.code) }}</h3><small>{{ role.code }} · 已关联 {{ role.userCount }} 个数据库用户</small></div><button @click="editRole(role)">配置权限</button></div><div class="permission-tags"><span v-for="code in role.permissionCodes" :key="code">{{ code }}</span><small v-if="!role.permissionCodes.length">暂无权限</small></div></article><article v-if="!loading && !roles.length" class="panel empty-report">暂无角色数据</article></div>
    </template>

    <template v-if="tab === 'audit'">
      <div class="filters"><input v-model.trim="logKeyword" placeholder="操作人、目标或 TraceId" @keyup.enter="loadLogs(true)" /><input v-model.trim="logAction" placeholder="动作，如 UPDATE" @keyup.enter="loadLogs(true)" /><button class="primary" @click="loadLogs(true)">查询</button></div>
      <div class="panel table-wrap"><table><thead><tr><th>时间</th><th>操作人</th><th>动作</th><th>目标</th><th>详情</th><th>TraceId</th></tr></thead><tbody><tr v-if="loading"><td colspan="6">加载中...</td></tr><tr v-else-if="!logs.length"><td colspan="6">暂无审计记录。</td></tr><tr v-for="item in logs" :key="item.id"><td>{{ date(item.createdAt) }}</td><td>{{ item.operatorName || item.operatorId || '系统' }}</td><td><span class="badge">{{ item.action }}</span></td><td>{{ item.targetType }}<small>{{ item.targetId || '-' }}</small></td><td><pre class="audit-detail">{{ formatDetail(item.detail) }}</pre></td><td>{{ item.traceId || '-' }}</td></tr></tbody></table><footer class="pagination"><span>共 {{ logTotal }} 条，第 {{ logPage }} / {{ logPages || 1 }} 页</span><div><button :disabled="logPage <= 1" @click="turnLogPage(logPage - 1)">上一页</button><button :disabled="logPage >= logPages" @click="turnLogPage(logPage + 1)">下一页</button></div></footer></div>
    </template>

    <div v-if="settingForm" class="modal-backdrop" @click.self="settingForm = null"><form class="modal panel" @submit.prevent="saveSetting"><div class="modal-header"><div><h3>修改平台参数</h3><p>{{ settingForm.key }} · {{ settingTypeText(settingForm.valueType) }}</p></div><button type="button" class="text-button" @click="settingForm = null">×</button></div><label>{{ settingForm.description || '参数值' }}<select v-if="settingForm.valueType === 'BOOLEAN'" v-model="settingForm.value"><option value="true">开启</option><option value="false">关闭</option></select><input v-else-if="settingForm.valueType === 'INTEGER'" v-model="settingForm.value" type="number" required /><textarea v-else-if="settingForm.valueType === 'JSON'" v-model="settingForm.value" rows="8" required /><input v-else v-model="settingForm.value" maxlength="10000" required /></label><div class="modal-actions"><button type="button" @click="settingForm = null">取消</button><button class="primary" :disabled="saving">{{ saving ? '保存中...' : '保存参数' }}</button></div></form></div>

    <div v-if="roleForm" class="modal-backdrop" @click.self="roleForm = null"><form class="modal panel" @submit.prevent="saveRole"><div class="modal-header"><div><h3>配置{{ roleText(roleForm.code) }}权限</h3><p>勾选该角色允许使用的管理模块。</p></div><button type="button" class="text-button" @click="roleForm = null">×</button></div><div class="permission-checks"><label v-for="permission in permissions" :key="permission.id"><input v-model="roleForm.permissionIds" type="checkbox" :value="permission.id" /><span><b>{{ permission.name }}</b><small>{{ permission.code }}</small></span></label></div><div class="modal-actions"><button type="button" @click="roleForm = null">取消</button><button class="primary" :disabled="saving">{{ saving ? '保存中...' : '保存权限' }}</button></div></form></div>

    <div v-if="accountForm" class="modal-backdrop" @click.self="accountForm = null"><form class="modal panel" @submit.prevent="saveAccount"><div class="modal-header"><div><h3>新增管理员账号</h3><p>请为每位管理人员创建独立账号。</p></div><button type="button" class="text-button" @click="accountForm = null">×</button></div><label>用户名<input v-model.trim="accountForm.username" maxlength="64" required placeholder="例如 zhangsan" /></label><label>显示名称<input v-model.trim="accountForm.displayName" maxlength="64" required /></label><label>初始密码<input v-model="accountForm.password" type="password" minlength="12" maxlength="128" required placeholder="至少12位，包含字母和数字" /></label><div class="permission-checks"><label v-for="role in roles" :key="role.code"><input v-model="accountForm.roles" type="checkbox" :value="role.code" /><span><b>{{ roleText(role.code) }}</b><small>{{ role.code }}</small></span></label></div><div class="modal-actions"><button type="button" @click="accountForm = null">取消</button><button class="primary" :disabled="saving">{{ saving ? '创建中...' : '创建账号' }}</button></div></form></div>
  </div>
</template>
