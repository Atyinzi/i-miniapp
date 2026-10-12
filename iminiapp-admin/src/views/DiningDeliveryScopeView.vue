<script setup>
import { computed, onMounted, ref } from 'vue'
import { getDiningStores } from '../services/dining'
import { getDiningDeliveryScope, updateDiningDeliveryScope } from '../services/deliveryScopes'

const stores = ref([])
const selectedStoreId = ref('')
const scope = ref(null)
const areas = ref([''])
const roomRequired = ref(true)
const loading = ref(false)
const saving = ref(false)
const error = ref('')
const notice = ref('')

const selectedStore = computed(() => stores.value.find((item) => String(item.id) === String(selectedStoreId.value)))

async function loadStores() {
  try {
    loading.value = true
    error.value = ''
    notice.value = ''
    const data = await getDiningStores()
    stores.value = data || []
    if (!stores.value.some((item) => String(item.id) === String(selectedStoreId.value))) {
      selectedStoreId.value = stores.value[0]?.id || ''
    }
    if (selectedStoreId.value) await loadScope(false)
    else resetScope()
  } catch (e) {
    error.value = e.message
  } finally {
    loading.value = false
  }
}

async function loadScope(manageLoading = true) {
  if (!selectedStoreId.value) return resetScope()
  try {
    if (manageLoading) loading.value = true
    error.value = ''
    notice.value = ''
    const data = await getDiningDeliveryScope(selectedStoreId.value)
    applyScope(data)
  } catch (e) {
    error.value = e.message
    resetScope()
  } finally {
    if (manageLoading) loading.value = false
  }
}

function applyScope(data) {
  scope.value = data || null
  areas.value = data?.areas?.length ? [...data.areas] : ['']
  roomRequired.value = data?.roomRequired !== false
}

function resetScope() {
  scope.value = null
  areas.value = ['']
  roomRequired.value = true
}

function addArea() {
  error.value = ''
  if (areas.value.length >= 50) return error.value = '单个门店最多配置50个内部配送区域'
  areas.value.push('')
}

function removeArea(index) {
  if (areas.value.length === 1) areas.value[0] = ''
  else areas.value.splice(index, 1)
}

function normalizedAreas() {
  const values = areas.value.map((value) => String(value || '').trim()).filter(Boolean)
  if (!values.length) throw new Error('至少配置一个内部配送区域')
  if (values.some((value) => value.length > 120)) throw new Error('单个配送区域名称最长120个字符')
  const unique = new Set(values)
  if (unique.size !== values.length) throw new Error('内部配送区域不能重复')
  if (values.length > 50) throw new Error('单个门店最多配置50个内部配送区域')
  return values
}

async function save() {
  if (!selectedStoreId.value) return
  try {
    saving.value = true
    error.value = ''
    notice.value = ''
    const data = await updateDiningDeliveryScope(selectedStoreId.value, {
      areas: normalizedAreas(),
      roomRequired: roomRequired.value
    })
    applyScope(data)
    notice.value = `${data?.storeName || selectedStore.value?.name || '门店'}的内部配送范围已保存`
  } catch (e) {
    error.value = e.message
  } finally {
    saving.value = false
  }
}

onMounted(loadStores)
</script>

<template>
  <div class="delivery-scope-page">
    <div class="toolbar">
      <div>
        <h2>宾馆内部餐饮配送范围</h2>
        <p>餐饮配送仅选择宾馆内部区域，不调用地图选点；用户另行填写房号或会议室号。</p>
      </div>
      <button @click="loadStores">刷新门店</button>
    </div>

    <p v-if="error" class="error scope-message">{{ error }}</p>
    <p v-if="notice" class="success scope-message">{{ notice }}</p>

    <section class="panel scope-context">
      <label>餐饮门店<select v-model="selectedStoreId" :disabled="loading || !stores.length" @change="loadScope()"><option v-if="!stores.length" value="">暂无门店</option><option v-for="store in stores" :key="store.id" :value="store.id">{{ store.name }}（{{ store.storeCode }}）</option></select></label>
      <div><span>配送模式</span><b>{{ scope?.scopeType === 'INTERNAL_AREA' ? '宾馆内部区域' : '待配置为内部区域' }}</b></div>
      <div><span>当前门店地址</span><b>{{ selectedStore?.address || '-' }}</b></div>
    </section>

    <section class="panel scope-editor">
      <div class="panel-title">
        <div><h3>可选内部区域</h3><p>示例：1号楼3层会议室、示例宾馆大堂、客房楼A区。</p></div>
        <button :disabled="!selectedStoreId || areas.length >= 50" @click="addArea">新增区域</button>
      </div>

      <div v-if="loading" class="scope-empty">正在加载...</div>
      <div v-else-if="!selectedStoreId" class="scope-empty">请先在餐饮运营中创建门店。</div>
      <template v-else>
        <div class="area-list">
          <div v-for="(area, index) in areas" :key="index" class="area-row">
            <span>{{ index + 1 }}</span>
            <input v-model="areas[index]" maxlength="120" :placeholder="`内部区域 ${index + 1}`">
            <button class="danger" aria-label="删除区域" @click="removeArea(index)">删除</button>
          </div>
        </div>
        <label class="room-rule"><input v-model="roomRequired" type="checkbox"><span><b>要求填写房号或会议室号</b><small>开启后，小程序提交内部配送订单时必须补充具体房号或会议室号。</small></span></label>
        <div class="scope-tip">该配置只维护示例宾馆内部配送区域，不会扩大为社会道路配送范围。</div>
        <div class="scope-actions"><span>已填写 {{ areas.filter((item) => String(item).trim()).length }} / 50 个区域</span><button class="primary" :disabled="saving || !selectedStoreId" @click="save">{{ saving ? '保存中...' : '保存配送范围' }}</button></div>
      </template>
    </section>
  </div>
</template>

<style scoped>
.scope-message{margin:0 0 14px}.scope-context{display:grid;grid-template-columns:minmax(260px,1fr) 1fr 1.4fr;gap:18px;align-items:end;margin-bottom:18px}.scope-context label,.scope-context div{display:grid;gap:6px}.scope-context label,.scope-context span{color:#607167;font-size:12px}.scope-context select{padding:10px;border:1px solid #dce6e0;border-radius:8px;background:#fff}.scope-context b{font-size:14px}.scope-editor{max-width:920px}.panel-title{align-items:flex-start}.panel-title p{margin:6px 0 0;color:#7c8c83;font-size:13px}.area-list{display:grid;gap:9px}.area-row{display:grid;grid-template-columns:34px 1fr auto;gap:9px;align-items:center}.area-row>span{width:30px;height:30px;display:grid;place-items:center;color:#397260;background:#eaf4ee;border-radius:50%;font-size:12px}.area-row input{width:100%;padding:10px;border:1px solid #dce6e0;border-radius:8px}.area-row button{padding:8px 11px}.room-rule{display:flex;align-items:flex-start;gap:11px;margin-top:20px;padding:14px;background:#f7faf8;border-radius:10px;cursor:pointer}.room-rule input{margin-top:3px}.room-rule b,.room-rule small{display:block}.room-rule small{margin-top:5px;color:#7c8c83}.scope-tip{margin-top:13px;padding:11px 13px;color:#76551b;background:#fff5df;border:1px solid #f0dfb7;border-radius:8px;font-size:13px}.scope-actions{display:flex;align-items:center;justify-content:space-between;gap:12px;margin-top:18px}.scope-actions span,.scope-empty{color:#829188;font-size:13px}.scope-empty{padding:30px 0;text-align:center}@media(max-width:900px){.scope-context{grid-template-columns:1fr}.scope-actions{align-items:stretch;flex-direction:column}.scope-actions button{align-self:flex-end}}@media(max-width:600px){.area-row{grid-template-columns:30px 1fr}.area-row button{grid-column:2;justify-self:end}}
</style>
