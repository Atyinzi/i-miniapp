<script setup>
import { onMounted, reactive, ref } from 'vue'
import {
  createGateDevice,
  getGateDevices,
  rotateGateDeviceSecret,
  updateGateDevice
} from '../services/gateDevices'

const devices = ref([])
const loading = ref(false)
const saving = ref(false)
const error = ref('')
const notice = ref('')
const modalOpen = ref(false)
const editingId = ref(null)
const oneTimeSecret = ref(null)
const secretCopied = ref(false)
const secretCopyError = ref('')
const form = reactive(emptyForm())

function emptyForm() {
  return { deviceNo: '', name: '', venueCode: '', status: 'INACTIVE' }
}

async function load() {
  try {
    loading.value = true
    error.value = ''
    devices.value = await getGateDevices() || []
  } catch (e) {
    error.value = e.message
  } finally {
    loading.value = false
  }
}

function resetForm(value = emptyForm()) {
  Object.assign(form, value)
}

function openCreate() {
  error.value = ''
  notice.value = ''
  editingId.value = null
  resetForm()
  modalOpen.value = true
}

function openEdit(item) {
  error.value = ''
  notice.value = ''
  editingId.value = item.id
  resetForm({
    deviceNo: item.deviceNo,
    name: item.name,
    venueCode: item.venueCode || '',
    status: item.status
  })
  modalOpen.value = true
}

function closeModal() {
  if (saving.value) return
  modalOpen.value = false
  editingId.value = null
  resetForm()
}

function payload() {
  const deviceNo = form.deviceNo.trim().toUpperCase()
  const name = form.name.trim()
  const venueCode = form.venueCode.trim().toUpperCase()
  if (!editingId.value && !/^[A-Z0-9_-]{1,64}$/.test(deviceNo)) throw new Error('设备编号仅支持字母、数字、下划线和连字符，最长64位')
  if (!name || name.length > 120) throw new Error('设备名称不能为空且最长120个字符')
  if (venueCode && !/^[A-Z0-9_-]{1,64}$/.test(venueCode)) throw new Error('场馆编码仅支持字母、数字、下划线和连字符，最长64位')
  return { deviceNo, name, venueCode: venueCode || null, status: form.status }
}

async function save() {
  try {
    saving.value = true
    error.value = ''
    notice.value = ''
    const value = payload()
    if (editingId.value) {
      await updateGateDevice(editingId.value, {
        name: value.name,
        venueCode: value.venueCode,
        status: value.status
      })
      closeAfterSave()
      await load()
      notice.value = '闸机设备资料已更新'
    } else {
      const created = await createGateDevice({
        deviceNo: value.deviceNo,
        name: value.name,
        venueCode: value.venueCode
      })
      if (!created?.provisioningSecret) throw new Error('设备已创建，但服务端未返回一次性密钥，请保持设备停用并联系技术人员')
      closeAfterSave()
      await load()
      showSecret(created.deviceNo, created.provisioningSecret, '设备创建成功')
    }
  } catch (e) {
    error.value = e.message
  } finally {
    saving.value = false
  }
}

function closeAfterSave() {
  modalOpen.value = false
  editingId.value = null
  resetForm()
}

async function toggleStatus(item) {
  const nextStatus = item.status === 'ACTIVE' ? 'INACTIVE' : 'ACTIVE'
  const action = nextStatus === 'ACTIVE' ? '启用' : '停用'
  if (!window.confirm(`确定${action}闸机设备“${item.name}”吗？`)) return
  try {
    error.value = ''
    notice.value = ''
    await updateGateDevice(item.id, {
      name: item.name,
      venueCode: item.venueCode || null,
      status: nextStatus
    })
    await load()
    notice.value = `设备已${action}`
  } catch (e) {
    error.value = e.message
  }
}

async function rotateSecret(item) {
  const confirmed = window.confirm(
    `确定轮换“${item.name}”的设备密钥吗？\n\n旧密钥会立即失效，设备将自动停用。必须将新密钥安全配置到闸机后再重新启用。`
  )
  if (!confirmed) return
  try {
    error.value = ''
    notice.value = ''
    const updated = await rotateGateDeviceSecret(item.id)
    if (!updated?.provisioningSecret) throw new Error('密钥已轮换，但服务端未返回一次性密钥，请勿启用设备并联系技术人员')
    await load()
    showSecret(updated.deviceNo, updated.provisioningSecret, '设备密钥已轮换')
  } catch (e) {
    error.value = e.message
  }
}

function showSecret(deviceNo, secret, title) {
  secretCopied.value = false
  secretCopyError.value = ''
  oneTimeSecret.value = { deviceNo, secret, title }
}

async function copySecret() {
  try {
    await navigator.clipboard.writeText(oneTimeSecret.value.secret)
    secretCopied.value = true
  } catch {
    secretCopyError.value = '浏览器未允许复制，请手动选择密钥并安全保存。'
  }
}

function closeSecret() {
  oneTimeSecret.value = null
  secretCopied.value = false
  secretCopyError.value = ''
}

function dateTime(value) {
  return value ? String(value).replace('T', ' ').slice(0, 19) : '尚未连接'
}

onMounted(load)
</script>

<template>
  <div class="gate-device-page">
    <div class="toolbar">
      <div>
        <h2>闸机与门禁设备</h2>
        <p>维护接入设备及 HMAC 认证状态；本页面不保存、回显或再次查询设备明文密钥。</p>
      </div>
      <div class="toolbar-actions"><button @click="load">刷新</button><button class="primary" @click="openCreate">创建设备</button></div>
    </div>

    <div class="security-tip"><b>安全规则</b><span>新建或轮换时密钥只展示一次。请保存到公司密码管理器，配置完成并联调成功后再启用设备。</span></div>
    <p v-if="error" class="error gate-message">{{ error }}</p>
    <p v-if="notice" class="success gate-message">{{ notice }}</p>

    <div class="panel table-wrap">
      <table>
        <thead><tr><th>设备</th><th>绑定场馆</th><th>接入方式</th><th>状态</th><th>最近连接</th><th>更新时间</th><th>操作</th></tr></thead>
        <tbody>
          <tr v-if="loading"><td colspan="7">正在加载...</td></tr>
          <tr v-for="item in devices" :key="item.id">
            <td><b>{{ item.name }}</b><small>{{ item.deviceNo }}</small></td>
            <td>{{ item.venueCode || '未绑定' }}</td>
            <td>API + HMAC<small>{{ item.integrationMode }}</small></td>
            <td><span class="badge" :class="{ inactive: item.status !== 'ACTIVE' }">{{ item.status === 'ACTIVE' ? '已启用' : '已停用' }}</span></td>
            <td>{{ dateTime(item.lastSeenAt) }}</td>
            <td>{{ dateTime(item.updatedAt) }}</td>
            <td><div class="actions gate-actions"><button @click="openEdit(item)">编辑</button><button @click="toggleStatus(item)">{{ item.status === 'ACTIVE' ? '停用' : '启用' }}</button><button class="danger" @click="rotateSecret(item)">轮换密钥</button></div></td>
          </tr>
          <tr v-if="!loading && !devices.length"><td colspan="7">暂无闸机设备。</td></tr>
        </tbody>
      </table>
    </div>

    <div v-if="modalOpen" class="modal-backdrop" @click.self="closeModal">
      <form class="modal panel gate-modal" @submit.prevent="save">
        <div class="modal-header"><div><h3>{{ editingId ? '编辑闸机设备' : '创建闸机设备' }}</h3><p>设备默认停用，需先安全配置密钥并完成联调。</p></div><button type="button" class="text-button" aria-label="关闭" @click="closeModal">×</button></div>
        <label>设备编号<input v-model.trim="form.deviceNo" maxlength="64" :disabled="Boolean(editingId)" placeholder="例如 GATE-BQC-01" autocomplete="off"></label>
        <label>设备名称<input v-model.trim="form.name" maxlength="120" placeholder="例如 兵器城东门1号闸机"></label>
        <label>场馆编码（可选）<input v-model.trim="form.venueCode" maxlength="64" placeholder="例如 WEAPON_CITY"></label>
        <label v-if="editingId">设备状态<select v-model="form.status"><option value="INACTIVE">停用</option><option value="ACTIVE">启用</option></select></label>
        <div class="modal-actions"><button type="button" @click="closeModal">取消</button><button class="primary" type="submit" :disabled="saving">{{ saving ? '保存中...' : editingId ? '保存' : '创建并生成密钥' }}</button></div>
      </form>
    </div>

    <div v-if="oneTimeSecret" class="modal-backdrop secret-backdrop">
      <section class="modal panel secret-modal" role="dialog" aria-modal="true" aria-labelledby="secret-title">
        <div class="secret-icon">!</div>
        <div><h3 id="secret-title">{{ oneTimeSecret.title }}</h3><p>设备 {{ oneTimeSecret.deviceNo }} 的新密钥只在本次展示。关闭窗口后，系统无法再次显示。</p></div>
        <label>一次性设备密钥<textarea :value="oneTimeSecret.secret" rows="3" readonly spellcheck="false"></textarea></label>
        <p v-if="secretCopyError" class="error secret-copy-error">{{ secretCopyError }}</p>
        <div class="secret-warning"><b>请立即安全保存</b><span>不要通过微信、邮件或截图传播。应保存到公司密码管理器，并通过安全方式配置到对应闸机。</span></div>
        <div class="modal-actions"><button @click="copySecret">{{ secretCopied ? '已复制' : '复制密钥' }}</button><button class="primary" @click="closeSecret">我已安全保存，关闭</button></div>
      </section>
    </div>
  </div>
</template>

<style scoped>
.toolbar-actions{display:flex;gap:9px}.gate-message{margin:0 0 14px}.security-tip{display:flex;gap:12px;margin-bottom:16px;padding:13px 15px;color:#76551b;background:#fff5df;border:1px solid #f0dfb7;border-radius:9px;font-size:13px}.security-tip span{line-height:1.55}.gate-actions{white-space:normal}.gate-modal{width:min(620px,100%)}.gate-modal select{width:100%;padding:10px;border:1px solid #dce6e0;border-radius:8px;background:#fff}.secret-backdrop{z-index:20}.secret-modal{width:min(650px,100%);text-align:left}.secret-icon{width:48px;height:48px;display:grid;place-items:center;color:#7f5814;background:#fff0cc;border-radius:50%;font-size:27px;font-weight:800}.secret-modal h3{margin:0 0 7px}.secret-modal p{margin:0;color:#607167;line-height:1.6}.secret-modal textarea{font:14px/1.6 Consolas,monospace;overflow-wrap:anywhere;resize:none}.secret-copy-error{margin:0}.secret-warning{display:grid;gap:5px;padding:13px;color:#8b3434;background:#fff0f0;border:1px solid #efd0d0;border-radius:9px;font-size:13px}.secret-warning span{line-height:1.5}@media(max-width:820px){.toolbar-actions{flex-wrap:wrap}.security-tip{flex-direction:column}}
</style>
