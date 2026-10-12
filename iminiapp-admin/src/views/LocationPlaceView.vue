<script setup>
import { computed, onMounted, reactive, ref } from 'vue'
import {
  createLocationPlace,
  getLocationPlaces,
  updateLocationPlace
} from '../services/locationPlaces'

const places = ref([])
const loading = ref(false)
const saving = ref(false)
const error = ref('')
const notice = ref('')
const typeFilter = ref('')
const statusFilter = ref('')
const editingId = ref(null)
const modalOpen = ref(false)
const form = reactive(emptyForm())

const filteredPlaces = computed(() => places.value.filter((item) =>
  (!typeFilter.value || item.placeType === typeFilter.value) &&
  (!statusFilter.value || item.status === statusFilter.value)
))

async function load() {
  try {
    loading.value = true
    error.value = ''
    places.value = await getLocationPlaces() || []
  } catch (e) {
    error.value = e.message
  } finally {
    loading.value = false
  }
}

function emptyForm() {
  return {
    placeCode: '',
    placeType: 'VENUE',
    name: '',
    address: '',
    longitude: '',
    latitude: '',
    coordinateSystem: 'GCJ-02',
    sortOrder: 0,
    status: 'INACTIVE'
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
    placeCode: item.placeCode,
    placeType: item.placeType,
    name: item.name,
    address: item.address || '',
    longitude: item.longitude ?? '',
    latitude: item.latitude ?? '',
    coordinateSystem: 'GCJ-02',
    sortOrder: item.sortOrder ?? 0,
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

function validatedPayload() {
  const placeCode = form.placeCode.trim().toUpperCase()
  const name = form.name.trim()
  const address = form.address.trim()
  if (!/^[A-Z0-9_]{1,64}$/.test(placeCode)) throw new Error('地点编码仅支持大写字母、数字和下划线，最长64位')
  if (!name || name.length > 120) throw new Error('地点名称不能为空且最长120个字符')
  if (address.length > 255) throw new Error('详细地址最长255个字符')

  const longitudeText = String(form.longitude ?? '').trim()
  const latitudeText = String(form.latitude ?? '').trim()
  if (Boolean(longitudeText) !== Boolean(latitudeText)) throw new Error('经纬度必须同时填写或同时留空')
  let longitude = null
  let latitude = null
  if (longitudeText) {
    longitude = Number(longitudeText)
    latitude = Number(latitudeText)
    if (!Number.isFinite(longitude) || longitude < -180 || longitude > 180) throw new Error('经度应在 -180 至 180 之间')
    if (!Number.isFinite(latitude) || latitude < -90 || latitude > 90) throw new Error('纬度应在 -90 至 90 之间')
  }
  if (form.status === 'ACTIVE' && longitude === null) throw new Error('启用地点前必须填写有效的 GCJ-02 经纬度')

  const sortOrder = Number(form.sortOrder)
  if (!Number.isInteger(sortOrder) || sortOrder < 0 || sortOrder > 100000) throw new Error('排序值必须是0至100000之间的整数')
  return {
    placeCode,
    placeType: form.placeType,
    name,
    address: address || null,
    longitude,
    latitude,
    coordinateSystem: 'GCJ-02',
    sortOrder,
    status: form.status
  }
}

async function save() {
  try {
    saving.value = true
    error.value = ''
    notice.value = ''
    const payload = validatedPayload()
    const wasEditing = Boolean(editingId.value)
    if (wasEditing) await updateLocationPlace(editingId.value, payload)
    else await createLocationPlace(payload)
    closeAfterSave()
    await load()
    notice.value = wasEditing ? '地点已更新' : '地点已创建'
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

function typeText(value) {
  return value === 'VENUE' ? '场馆导航点' : value === 'COMMON_PICKUP' ? '车队常用地点' : value
}

function dateTime(value) {
  return value ? String(value).replace('T', ' ').slice(0, 16) : '-'
}

onMounted(load)
</script>

<template>
  <div class="location-config-page">
    <div class="toolbar">
      <div>
        <h2>地点与一键导航</h2>
        <p>维护场馆导航点和车队常用地点，坐标统一使用腾讯地图 GCJ-02。</p>
      </div>
      <div class="toolbar-actions"><button @click="load">刷新</button><button class="primary" @click="openCreate">新增地点</button></div>
    </div>

    <p v-if="error" class="error config-message">{{ error }}</p>
    <p v-if="notice" class="success config-message">{{ notice }}</p>

    <div class="filters location-filters">
      <select v-model="typeFilter"><option value="">全部类型</option><option value="VENUE">场馆导航点</option><option value="COMMON_PICKUP">车队常用地点</option></select>
      <select v-model="statusFilter"><option value="">全部状态</option><option value="ACTIVE">已启用</option><option value="INACTIVE">已停用</option></select>
    </div>

    <div class="panel table-wrap">
      <table>
        <thead><tr><th>地点</th><th>类型</th><th>地址</th><th>GCJ-02 坐标</th><th>状态</th><th>更新时间</th><th>操作</th></tr></thead>
        <tbody>
          <tr v-if="loading"><td colspan="7">正在加载...</td></tr>
          <tr v-for="item in filteredPlaces" :key="item.id">
            <td><b>{{ item.name }}</b><small>{{ item.placeCode }}</small></td>
            <td>{{ typeText(item.placeType) }}</td>
            <td>{{ item.address || '-' }}</td>
            <td><template v-if="item.longitude != null && item.latitude != null"><b>{{ item.longitude }}, {{ item.latitude }}</b><small>{{ item.coordinateSystem }}</small></template><span v-else class="muted">尚未配置</span></td>
            <td><span class="badge" :class="{ inactive: item.status !== 'ACTIVE' }">{{ item.status === 'ACTIVE' ? '已启用' : '已停用' }}</span><small>{{ item.configured ? '可供小程序导航' : '暂不可导航' }}</small></td>
            <td>{{ dateTime(item.updatedAt) }}</td>
            <td><button @click="openEdit(item)">编辑</button></td>
          </tr>
          <tr v-if="!loading && !filteredPlaces.length"><td colspan="7">暂无符合条件的地点。</td></tr>
        </tbody>
      </table>
    </div>

    <div v-if="modalOpen" class="modal-backdrop" @click.self="closeModal">
      <form class="modal panel location-modal" @submit.prevent="save">
        <div class="modal-header"><div><h3>{{ editingId ? '编辑地点' : '新增地点' }}</h3><p>启用前必须填写成对且有效的 GCJ-02 经纬度。</p></div><button type="button" class="text-button" aria-label="关闭" @click="closeModal">×</button></div>
        <div class="location-form-grid">
          <label>地点编码<input v-model.trim="form.placeCode" maxlength="64" placeholder="例如 BEIFANG_HOTEL" autocomplete="off"></label>
          <label>地点类型<select v-model="form.placeType"><option value="VENUE">场馆导航点</option><option value="COMMON_PICKUP">车队常用地点</option></select></label>
          <label class="span-two">地点名称<input v-model.trim="form.name" maxlength="120" placeholder="请输入对用户展示的名称"></label>
          <label class="span-two">详细地址<input v-model.trim="form.address" maxlength="255" placeholder="可选，用于导航页辅助展示"></label>
          <label>经度<input v-model.trim="form.longitude" inputmode="decimal" placeholder="例如 109.840000"></label>
          <label>纬度<input v-model.trim="form.latitude" inputmode="decimal" placeholder="例如 40.657000"></label>
          <label>坐标系<input value="GCJ-02" disabled></label>
          <label>排序值<input v-model.number="form.sortOrder" type="number" min="0" max="100000" step="1"></label>
          <label>状态<select v-model="form.status"><option value="INACTIVE">停用</option><option value="ACTIVE">启用</option></select></label>
        </div>
        <p v-if="form.status === 'ACTIVE' && (!String(form.longitude).trim() || !String(form.latitude).trim())" class="inline-warning">当前选择“启用”，保存前必须填写经度和纬度。</p>
        <div class="modal-actions"><button type="button" @click="closeModal">取消</button><button class="primary" type="submit" :disabled="saving">{{ saving ? '保存中...' : '保存' }}</button></div>
      </form>
    </div>
  </div>
</template>

<style scoped>
.toolbar-actions{display:flex;gap:9px}.config-message{margin:0 0 14px}.location-filters{margin-top:0}.muted{color:#8a9890}.location-modal{width:min(720px,100%)}.location-form-grid{display:grid;grid-template-columns:1fr 1fr;gap:13px}.location-form-grid label{display:grid;gap:6px;color:#53685e;font-size:13px}.location-form-grid input,.location-form-grid select{width:100%;padding:10px;border:1px solid #dce6e0;border-radius:8px;background:#fff}.location-form-grid input:disabled{color:#708078;background:#f2f5f3}.span-two{grid-column:1/-1}.inline-warning{margin:0;padding:11px 13px;color:#76551b;background:#fff5df;border:1px solid #f0dfb7;border-radius:8px;font-size:13px}@media(max-width:820px){.toolbar-actions{flex-wrap:wrap;justify-content:flex-end}.location-form-grid{grid-template-columns:1fr}.span-two{grid-column:auto}}
</style>
