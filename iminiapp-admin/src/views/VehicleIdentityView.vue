<script setup>
import { computed, onMounted, reactive, ref } from 'vue'
import { getUsers } from '../services/users'
import {
  bindVehicleDriverAccount, bindVehicleEmployeeAccount, createVehicleDepartment,
  createVehicleEmployee, getVehicleDepartments, getVehicleDriverAccounts, getVehicleEmployees,
  unbindVehicleDriverAccount, unbindVehicleEmployeeAccount, updateVehicleEmployeeStatus
} from '../services/vehicles'

const tab = ref('employees')
const loading = ref(false)
const error = ref('')
const notice = ref('')
const departments = ref([])
const employees = ref([])
const drivers = ref([])
const users = ref([])
const employeeStatus = ref('')
const modal = ref('')
const form = reactive({})

const activeDepartments = computed(() => departments.value.filter(item => item.status === 'ACTIVE'))
const availableUsers = computed(() => users.value.filter(item => item.status === 'ACTIVE'))

async function load() {
  try {
    loading.value = true
    error.value = ''
    const [departmentData, employeeData, driverData, userData] = await Promise.all([
      getVehicleDepartments(),
      getVehicleEmployees({ status: employeeStatus.value || undefined }),
      getVehicleDriverAccounts(),
      getUsers({ status: 'ACTIVE', page: 1, pageSize: 100 })
    ])
    departments.value = departmentData || []
    employees.value = employeeData || []
    drivers.value = driverData || []
    users.value = userData?.items || []
  } catch (e) {
    error.value = e.message
  } finally {
    loading.value = false
  }
}

function resetForm(values) {
  Object.keys(form).forEach(key => delete form[key])
  Object.assign(form, values)
}

function openCreateDepartment() {
  resetForm({ departmentCode: '', departmentName: '', parentId: '', status: 'ACTIVE' })
  modal.value = 'department'
}

function openCreateEmployee() {
  resetForm({ employeeNo: '', name: '', mobile: '', departmentId: activeDepartments.value[0]?.id || '', status: 'ACTIVE', userId: '' })
  modal.value = 'employee'
}

function openBind(kind, item) {
  resetForm({ kind, item, userId: item.userId || '' })
  modal.value = 'bind'
}

async function submitModal() {
  try {
    loading.value = true
    error.value = ''
    if (modal.value === 'department') {
      await createVehicleDepartment({
        departmentCode: form.departmentCode,
        departmentName: form.departmentName,
        parentId: form.parentId ? Number(form.parentId) : null,
        status: form.status
      })
      notice.value = '部门创建成功'
    } else if (modal.value === 'employee') {
      await createVehicleEmployee({
        employeeNo: form.employeeNo,
        name: form.name,
        mobile: form.mobile,
        departmentId: Number(form.departmentId),
        status: form.status,
        userId: form.userId ? Number(form.userId) : null
      })
      notice.value = '员工档案创建成功'
    } else if (modal.value === 'bind') {
      const userId = Number(form.userId)
      if (!userId) throw new Error('请选择需要绑定的微信用户')
      if (form.kind === 'employee') await bindVehicleEmployeeAccount(form.item.id, userId)
      else await bindVehicleDriverAccount(form.item.id, userId)
      notice.value = '微信账号绑定成功'
    }
    modal.value = ''
    await load()
  } catch (e) {
    error.value = e.message
  } finally {
    loading.value = false
  }
}

async function toggleEmployee(item) {
  const next = item.status === 'ACTIVE' ? 'INACTIVE' : 'ACTIVE'
  if (!confirm(`确认${next === 'ACTIVE' ? '启用' : '停用'}员工“${item.maskedName}”吗？`)) return
  await run(() => updateVehicleEmployeeStatus(item.id, next), '员工状态已更新')
}

async function unbind(kind, item) {
  if (!confirm(`确认解除${kind === 'employee' ? '员工' : '司机'}“${kind === 'employee' ? item.maskedName : item.driverNo}”的微信账号绑定吗？`)) return
  await run(
    () => kind === 'employee' ? unbindVehicleEmployeeAccount(item.id) : unbindVehicleDriverAccount(item.id),
    '账号已解绑'
  )
}

async function run(operation, success) {
  try {
    loading.value = true
    error.value = ''
    await operation()
    notice.value = success
    await load()
  } catch (e) {
    error.value = e.message
  } finally {
    loading.value = false
  }
}

function date(value) { return value?.replace('T', ' ') || '-' }
function statusText(value) { return value === 'ACTIVE' || value === 'AVAILABLE' ? '正常' : value === 'INACTIVE' ? '已停用' : value }
function userText(userId) {
  const user = users.value.find(item => Number(item.id) === Number(userId))
  return user ? `${user.nickname}（ID ${user.id}）` : `用户 ID ${userId}`
}

onMounted(load)
</script>

<template>
  <div>
    <div class="toolbar">
      <div><h2>车辆人员与账号</h2><p>维护内部员工、组织部门及司机微信账号绑定；社会用户无需在此建档。</p></div>
      <button :disabled="loading" @click="load">{{ loading ? '加载中...' : '刷新' }}</button>
    </div>
    <div class="identity-summary">
      <article><span>部门</span><b>{{ departments.length }}</b><small>内部组织范围</small></article>
      <article><span>在职员工</span><b>{{ employees.filter(item => item.status === 'ACTIVE').length }}</b><small>可申请内部用车</small></article>
      <article><span>已绑定员工</span><b>{{ employees.filter(item => item.userId).length }}</b><small>已关联微信用户</small></article>
      <article><span>已绑定司机</span><b>{{ drivers.filter(item => item.userId).length }}</b><small>可进入司机工作台</small></article>
    </div>
    <div class="content-tabs identity-tabs">
      <button :class="{ active: tab === 'employees' }" @click="tab = 'employees'">员工档案</button>
      <button :class="{ active: tab === 'departments' }" @click="tab = 'departments'">部门管理</button>
      <button :class="{ active: tab === 'drivers' }" @click="tab = 'drivers'">司机账号</button>
    </div>
    <p v-if="error" class="error">{{ error }}</p>
    <p v-if="notice" class="success identity-notice">{{ notice }}</p>

    <section v-if="tab === 'employees'">
      <div class="toolbar identity-toolbar"><div><h3>内部员工</h3><p>只有在职且部门有效的员工可提交内部用车申请。</p></div><button class="primary" @click="openCreateEmployee">新增员工</button></div>
      <div class="filters"><select v-model="employeeStatus"><option value="">全部状态</option><option value="ACTIVE">在职</option><option value="INACTIVE">停用</option></select><button @click="load">筛选</button></div>
      <div class="panel table-wrap"><table><thead><tr><th>员工</th><th>联系方式</th><th>部门</th><th>微信账号</th><th>状态</th><th>操作</th></tr></thead><tbody>
        <tr v-if="!employees.length"><td colspan="6">暂无员工档案。</td></tr>
        <tr v-for="item in employees" :key="item.id"><td><b>{{ item.maskedName }}</b><small>{{ item.employeeNo }} · ID {{ item.id }}</small></td><td>{{ item.maskedMobile }}</td><td>{{ item.departmentName }}<small>{{ item.departmentCode }}</small></td><td><span v-if="item.userId">{{ userText(item.userId) }}</span><span v-else class="muted-text">未绑定</span><small v-if="item.boundAt">{{ date(item.boundAt) }}</small></td><td><span class="badge" :class="{ inactive: item.status !== 'ACTIVE' }">{{ statusText(item.status) }}</span></td><td><div class="actions"><button @click="openBind('employee', item)">{{ item.userId ? '更换账号' : '绑定账号' }}</button><button v-if="item.userId" @click="unbind('employee', item)">解绑</button><button @click="toggleEmployee(item)">{{ item.status === 'ACTIVE' ? '停用' : '启用' }}</button></div></td></tr>
      </tbody></table></div>
    </section>

    <section v-if="tab === 'departments'">
      <div class="toolbar identity-toolbar"><div><h3>内部部门</h3><p>停用部门后，该部门员工不能新增内部用车申请。</p></div><button class="primary" @click="openCreateDepartment">新增部门</button></div>
      <div class="panel table-wrap"><table><thead><tr><th>部门编码</th><th>部门名称</th><th>上级部门</th><th>状态</th></tr></thead><tbody>
        <tr v-if="!departments.length"><td colspan="4">暂无部门。</td></tr>
        <tr v-for="item in departments" :key="item.id"><td><b>{{ item.departmentCode }}</b><small>ID {{ item.id }}</small></td><td>{{ item.departmentName }}</td><td>{{ item.parentId || '-' }}</td><td><span class="badge" :class="{ inactive: item.status !== 'ACTIVE' }">{{ statusText(item.status) }}</span></td></tr>
      </tbody></table></div>
    </section>

    <section v-if="tab === 'drivers'">
      <div class="toolbar identity-toolbar"><div><h3>司机微信账号</h3><p>司机完成绑定后，只能在小程序操作分配给自己的任务。</p></div></div>
      <div class="panel table-wrap"><table><thead><tr><th>司机</th><th>联系方式</th><th>微信账号</th><th>状态</th><th>操作</th></tr></thead><tbody>
        <tr v-if="!drivers.length"><td colspan="5">暂无司机档案，请先到车辆租赁与调度中新增司机。</td></tr>
        <tr v-for="item in drivers" :key="item.id"><td><b>{{ item.driverNo }}</b><small>司机档案 ID {{ item.id }}</small></td><td>敏感信息已隐藏</td><td><span v-if="item.userId">{{ userText(item.userId) }}</span><span v-else class="muted-text">未绑定</span></td><td><span class="badge" :class="{ inactive: item.status === 'INACTIVE' }">{{ statusText(item.status) }}</span></td><td><div class="actions"><button @click="openBind('driver', item)">{{ item.userId ? '更换账号' : '绑定账号' }}</button><button v-if="item.userId" @click="unbind('driver', item)">解绑</button></div></td></tr>
      </tbody></table></div>
    </section>

    <div v-if="modal" class="modal-backdrop" @click.self="modal = ''">
      <section class="modal panel identity-modal">
        <div class="modal-header"><div><h3>{{ modal === 'department' ? '新增部门' : modal === 'employee' ? '新增员工档案' : '绑定微信账号' }}</h3><p>操作会写入后台审计日志。</p></div><button class="text-button" @click="modal = ''">×</button></div>
        <template v-if="modal === 'department'"><label>部门编码<input v-model.trim="form.departmentCode" placeholder="例如 WLJQ" /></label><label>部门名称<input v-model.trim="form.departmentName" placeholder="例如 文旅景区运营部" /></label><label>上级部门<select v-model="form.parentId"><option value="">无上级部门</option><option v-for="item in activeDepartments" :key="item.id" :value="item.id">{{ item.departmentName }}</option></select></label></template>
        <template v-if="modal === 'employee'"><label>工号<input v-model.trim="form.employeeNo" placeholder="例如 YG0001" /></label><label>真实姓名<input v-model.trim="form.name" /></label><label>手机号<input v-model.trim="form.mobile" placeholder="用于员工自助绑定校验" /></label><label>所属部门<select v-model="form.departmentId"><option value="">请选择部门</option><option v-for="item in activeDepartments" :key="item.id" :value="item.id">{{ item.departmentName }}</option></select></label><label>同时绑定微信用户（可选）<select v-model="form.userId"><option value="">暂不绑定</option><option v-for="item in availableUsers" :key="item.id" :value="item.id">{{ item.nickname }} · ID {{ item.id }} · {{ item.mobile || '未授权手机号' }}</option></select></label></template>
        <template v-if="modal === 'bind'"><p class="review-tip">{{ form.kind === 'employee' ? form.item?.maskedName : form.item?.driverNo }}</p><label>微信用户<select v-model="form.userId"><option value="">请选择用户</option><option v-for="item in availableUsers" :key="item.id" :value="item.id">{{ item.nickname }} · ID {{ item.id }} · {{ item.mobile || '未授权手机号' }}</option></select></label><small class="identity-help">更换绑定前请确认该用户未绑定其他员工或司机档案。</small></template>
        <div class="modal-actions"><button @click="modal = ''">取消</button><button class="primary" :disabled="loading" @click="submitModal">确认提交</button></div>
      </section>
    </div>
  </div>
</template>
