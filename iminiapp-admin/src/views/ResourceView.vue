<script setup>
import { onMounted, reactive, ref } from 'vue'
import {
  adjustInventory, createResource, createSku, getResourceInventories, getResources, getResourceSkus,
  setupInventory, updateResource, updateResourceStatus, updateSku
} from '../services/catalog'

const loading = ref(false)
const saving = ref(false)
const error = ref('')
const resources = ref([])
const total = ref(0)
const type = ref('')
const status = ref('')
const keyword = ref('')
const resourceDialog = ref(false)
const inventoryDialog = ref(false)
const skuDialog = ref(false)
const editingId = ref(null)
const editingSkuId = ref(null)
const inventoryRows = ref([])
const skuRows = ref([])
const inventoryResource = ref(null)
const adjustTarget = ref(null)
const adjustment = reactive({ delta: '', remark: '' })
const form = reactive({ resourceType: 'HOTEL_ROOM', name: '', categoryCode: '', description: '', coverUrl: '', status: 'DRAFT' })
const skuForm = reactive({ skuCode: '', name: '', priceYuan: '', status: 'DRAFT' })
const inventoryForm = reactive({ skuId: '', businessDate: new Date().toISOString().slice(0, 10), timeSlot: '', totalQuantity: '', availableQuantity: '', priceYuan: '' })

const resourceTypes = [
  ['HOTEL_ROOM', '住宿房型'], ['MEETING_ROOM', '会议室'], ['SCENIC_TICKET', '景区门票'],
  ['VENUE', '场馆服务'], ['PRODUCT', '商城商品'], ['VEHICLE', '车辆服务']
]

function toCent(value, field) {
  const raw = String(value).trim()
  if (!/^\d+(\.\d{1,2})?$/.test(raw)) throw new Error(`${field}必须是最多两位小数的非负金额`)
  return Math.round(Number(raw) * 100)
}
function resetForm(item) {
  editingId.value = item?.id ?? null
  form.resourceType = item?.resourceType ?? 'HOTEL_ROOM'
  form.name = item?.name ?? ''
  form.categoryCode = item?.categoryCode ?? ''
  form.description = item?.description ?? ''
  form.coverUrl = item?.coverUrl ?? ''
  form.status = item?.status ?? 'DRAFT'
}
function resetSkuForm(item) {
  editingSkuId.value = item?.id ?? null
  skuForm.skuCode = item?.skuCode ?? ''
  skuForm.name = item?.name ?? ''
  skuForm.priceYuan = item ? ((item.priceCent || 0) / 100).toFixed(2) : ''
  skuForm.status = item?.status ?? 'DRAFT'
}
function openCreate() { resetForm(); resourceDialog.value = true }
function openEdit(item) { resetForm(item); resourceDialog.value = true }
function openCreateSku() { resetSkuForm(); skuDialog.value = true }
function openEditSku(item) { resetSkuForm(item); skuDialog.value = true }
async function load() {
  try {
    loading.value = true; error.value = ''
    const data = await getResources({ type: type.value || undefined, status: status.value || undefined, keyword: keyword.value || undefined, page: 1, pageSize: 50 })
    resources.value = data.items || []
    total.value = data.total || 0
  } catch (e) { error.value = e.message } finally { loading.value = false }
}
async function saveResource() {
  try {
    saving.value = true; error.value = ''
    const payload = { ...form, categoryCode: form.categoryCode || null, description: form.description || null, coverUrl: form.coverUrl || null }
    if (editingId.value) await updateResource(editingId.value, payload)
    else await createResource(payload)
    resourceDialog.value = false
    await load()
  } catch (e) { error.value = e.message } finally { saving.value = false }
}
async function saveSku() {
  try {
    saving.value = true; error.value = ''
    const payload = { skuCode: skuForm.skuCode, name: skuForm.name, priceCent: toCent(skuForm.priceYuan, 'SKU价格'), status: skuForm.status }
    if (editingSkuId.value) await updateSku(inventoryResource.value.id, editingSkuId.value, payload)
    else await createSku(inventoryResource.value.id, payload)
    skuDialog.value = false
    await refreshResourceDetails()
    await load()
  } catch (e) { error.value = e.message } finally { saving.value = false }
}
async function toggleStatus(item) {
  const next = item.status === 'ACTIVE' ? 'INACTIVE' : 'ACTIVE'
  if (!confirm(`确定将“${item.name}”设为 ${next} 吗？`)) return
  try { await updateResourceStatus(item.id, next); await load() } catch (e) { error.value = e.message }
}
async function refreshResourceDetails() {
  const [skus, inventories] = await Promise.all([getResourceSkus(inventoryResource.value.id), getResourceInventories(inventoryResource.value.id)])
  skuRows.value = skus
  inventoryRows.value = inventories
}
async function openInventory(item) {
  try {
    loading.value = true; error.value = ''
    inventoryResource.value = item
    await refreshResourceDetails()
    adjustTarget.value = null
    inventoryDialog.value = true
  } catch (e) { error.value = e.message } finally { loading.value = false }
}
function chooseAdjustment(item) {
  adjustTarget.value = item
  adjustment.delta = ''
  adjustment.remark = ''
}
async function submitAdjustment() {
  const delta = Number(adjustment.delta)
  if (!Number.isInteger(delta) || delta === 0) { error.value = '调整数量必须是非 0 整数'; return }
  try {
    saving.value = true; error.value = ''
    await adjustInventory(adjustTarget.value.inventoryId, { delta, remark: adjustment.remark || null })
    await refreshResourceDetails()
    adjustTarget.value = null
  } catch (e) { error.value = e.message } finally { saving.value = false }
}
async function createInventory() {
  try {
    const totalQuantity = Number(inventoryForm.totalQuantity)
    const availableQuantity = inventoryForm.availableQuantity === '' ? totalQuantity : Number(inventoryForm.availableQuantity)
    if (!Number.isInteger(totalQuantity) || !Number.isInteger(availableQuantity)) throw new Error('总库存和可用库存必须是整数')
    saving.value = true; error.value = ''
    await setupInventory(inventoryResource.value.id, {
      skuId: Number(inventoryForm.skuId), businessDate: inventoryForm.businessDate, timeSlot: inventoryForm.timeSlot || null,
      totalQuantity, availableQuantity, priceCent: inventoryForm.priceYuan === '' ? null : toCent(inventoryForm.priceYuan, '库存价格')
    })
    inventoryForm.skuId = ''; inventoryForm.timeSlot = ''; inventoryForm.totalQuantity = ''; inventoryForm.availableQuantity = ''; inventoryForm.priceYuan = ''
    await refreshResourceDetails()
  } catch (e) { error.value = e.message } finally { saving.value = false }
}
function money(value) { return `¥${((value || 0) / 100).toFixed(2)}` }

onMounted(load)
</script>

<template>
  <div>
    <div class="toolbar"><div><h2>资源与库存</h2><p>按“资源 → SKU → 日期库存”的顺序配置；资源和 SKU 均为 ACTIVE 后，才能在小程序端销售。</p></div><button class="primary" @click="openCreate">+ 新增资源</button></div>
    <div class="filters"><select v-model="type"><option value="">全部类型</option><option v-for="item in resourceTypes" :key="item[0]" :value="item[0]">{{ item[1] }}</option></select><select v-model="status"><option value="">全部状态</option><option value="DRAFT">草稿</option><option value="ACTIVE">上架</option><option value="INACTIVE">下架</option></select><input v-model.trim="keyword" placeholder="资源名称或分类编码" @keyup.enter="load" /><button @click="load">查询</button></div>
    <p v-if="error" class="error">{{ error }}</p>
    <div class="panel table-wrap"><table><thead><tr><th>资源名称</th><th>资源类型</th><th>状态</th><th>SKU</th><th>更新时间</th><th>操作</th></tr></thead><tbody><tr v-if="loading"><td colspan="6">加载中...</td></tr><tr v-else-if="!resources.length"><td colspan="6">暂无资源，可先新建一条草稿资源。</td></tr><tr v-for="item in resources" :key="item.id"><td><b>{{ item.name }}</b><small>{{ item.categoryCode || '未设置分类' }} · #{{ item.id }}</small></td><td>{{ item.resourceType }}</td><td><span class="badge" :class="item.status.toLowerCase()">{{ item.status }}</span></td><td>{{ item.skuCount }}</td><td>{{ item.updatedAt?.replace('T', ' ') || '-' }}</td><td class="actions"><button @click="openEdit(item)">编辑</button><button @click="openInventory(item)">SKU / 库存</button><button @click="toggleStatus(item)">{{ item.status === 'ACTIVE' ? '下架' : '上架' }}</button></td></tr></tbody></table><footer>共 {{ total }} 条资源</footer></div>

    <div v-if="resourceDialog" class="modal-backdrop" @click.self="resourceDialog = false"><form class="modal panel" @submit.prevent="saveResource"><div class="modal-header"><h3>{{ editingId ? '编辑资源' : '新增资源' }}</h3><button type="button" class="text-button" @click="resourceDialog = false">×</button></div><label>资源类型<select v-model="form.resourceType" required><option v-for="item in resourceTypes" :key="item[0]" :value="item[0]">{{ item[1] }}（{{ item[0] }}）</option></select></label><label>资源名称<input v-model.trim="form.name" maxlength="200" required placeholder="例如：示例宾馆标准间" /></label><label>分类编码<input v-model.trim="form.categoryCode" maxlength="50" placeholder="例如：STANDARD_ROOM" /></label><label>状态<select v-model="form.status"><option value="DRAFT">草稿</option><option value="ACTIVE">上架</option><option value="INACTIVE">下架</option></select></label><label>封面地址<input v-model.trim="form.coverUrl" maxlength="512" placeholder="https://..." /></label><label>资源说明<textarea v-model.trim="form.description" maxlength="5000" rows="4" placeholder="供用户端展示的服务说明"></textarea></label><div class="modal-actions"><button type="button" @click="resourceDialog = false">取消</button><button class="primary" :disabled="saving">{{ saving ? '保存中...' : '保存' }}</button></div></form></div>

    <div v-if="skuDialog" class="modal-backdrop" @click.self="skuDialog = false"><form class="modal panel" @submit.prevent="saveSku"><div class="modal-header"><h3>{{ editingSkuId ? '编辑 SKU' : '新增 SKU' }}</h3><button type="button" class="text-button" @click="skuDialog = false">×</button></div><label>SKU 编码<input v-model.trim="skuForm.skuCode" maxlength="64" required placeholder="例如：HOTEL_STANDARD_001" /></label><label>SKU 名称<input v-model.trim="skuForm.name" maxlength="200" required placeholder="例如：标准间一晚" /></label><label>价格（元）<input v-model.trim="skuForm.priceYuan" inputmode="decimal" required placeholder="例如：298.00" /></label><label>状态<select v-model="skuForm.status"><option value="DRAFT">草稿</option><option value="ACTIVE">启用</option><option value="INACTIVE">停用</option></select></label><div class="modal-actions"><button type="button" @click="skuDialog = false">取消</button><button class="primary" :disabled="saving">{{ saving ? '保存中...' : '保存 SKU' }}</button></div></form></div>

    <div v-if="inventoryDialog" class="modal-backdrop" @click.self="inventoryDialog = false"><section class="modal modal-wide panel"><div class="modal-header"><div><h3>{{ inventoryResource?.name }}：SKU 与库存</h3><p>先创建 SKU，再按日期/时段初始化库存；后续只能通过“调整”改变可用库存。</p></div><button class="text-button" @click="inventoryDialog = false">×</button></div>
      <div class="subsection"><div class="subsection-title"><h4>SKU 列表</h4><button class="primary" @click="openCreateSku">+ 新增 SKU</button></div><table><thead><tr><th>编码</th><th>名称</th><th>价格</th><th>状态</th><th></th></tr></thead><tbody><tr v-if="!skuRows.length"><td colspan="5">暂无 SKU，请先新增。</td></tr><tr v-for="item in skuRows" :key="item.id"><td>{{ item.skuCode }}</td><td>{{ item.name }}</td><td>{{ money(item.priceCent) }}</td><td><span class="badge" :class="item.status.toLowerCase()">{{ item.status }}</span></td><td><button @click="openEditSku(item)">编辑</button></td></tr></tbody></table></div>
      <form class="setup-form" @submit.prevent="createInventory"><h4>初始化日期库存</h4><select v-model="inventoryForm.skuId" required><option value="" disabled>选择 SKU</option><option v-for="item in skuRows" :key="item.id" :value="item.id">{{ item.name }}（{{ item.skuCode }}）</option></select><input v-model="inventoryForm.businessDate" type="date" required /><input v-model.trim="inventoryForm.timeSlot" maxlength="64" placeholder="时段，留空表示全天" /><input v-model="inventoryForm.totalQuantity" type="number" min="0" step="1" required placeholder="总库存" /><input v-model="inventoryForm.availableQuantity" type="number" min="0" step="1" placeholder="可用库存，留空=总库存" /><input v-model.trim="inventoryForm.priceYuan" inputmode="decimal" placeholder="销售价（元，留空=SKU价）" /><button class="primary" :disabled="saving || !skuRows.length">{{ saving ? '保存中...' : '新增库存' }}</button></form>
      <div class="subsection"><h4>已配置库存</h4><table><thead><tr><th>SKU</th><th>日期 / 时段</th><th>总库存</th><th>可用库存</th><th>价格</th><th></th></tr></thead><tbody><tr v-if="!inventoryRows.length"><td colspan="6">暂无日期库存。</td></tr><tr v-for="item in inventoryRows" :key="item.inventoryId"><td><b>{{ item.skuName }}</b><small>{{ item.skuCode }}</small></td><td>{{ item.businessDate }} {{ item.timeSlot || '全天' }}</td><td>{{ item.totalQuantity }}</td><td>{{ item.availableQuantity }}</td><td>{{ money(item.priceCent) }}</td><td><button @click="chooseAdjustment(item)">调整</button></td></tr></tbody></table></div>
      <form v-if="adjustTarget" class="adjust-form" @submit.prevent="submitAdjustment"><b>调整：{{ adjustTarget.skuName }}（当前可用 {{ adjustTarget.availableQuantity }}）</b><input v-model="adjustment.delta" type="number" step="1" required placeholder="增减数量，如 -1 / 2" /><input v-model.trim="adjustment.remark" maxlength="255" placeholder="调整原因（建议填写）" /><button class="primary" :disabled="saving">确认调整</button><button type="button" @click="adjustTarget = null">取消</button></form>
    </section></div>
  </div>
</template>
