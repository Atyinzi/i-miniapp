<script setup>
import { computed, onMounted, reactive, ref } from 'vue'
import {
  acceptRentalDispatch, acceptVehicleDispatch, cancelUnavailablePeriod, completeVehicleDispatch,
  createUnavailablePeriod, createVehicle, createVehicleDriver, createVehicleModel,
  departRentalDispatch, departVehicleDispatch, dispatchVehicleApplication, dispatchVehicleRental,
  exportVehicleReport, getRentalDispatches, getUnavailablePeriods, getVehicleApplications,
  getVehicleDrivers, getVehicleModels, getVehicleReport, getVehicleRentals, getVehicles, printVehicleReport,
  returnRentalVehicle, reviewVehicleApplication, updateVehicle, updateVehicleDriverStatus,
  updateVehicleTariff
} from '../services/vehicles'

const tab = ref('applications')
const loading = ref(false)
const error = ref('')
const notice = ref('')
const applications = ref([])
const rentals = ref([])
const dispatches = ref([])
const vehicles = ref([])
const models = ref([])
const drivers = ref([])
const report = ref(null)
const modal = ref(null)
const lastCreatedModelId = ref('')
const applicationStatus = ref('')
const rentalStatus = ref('')
const startDate = ref(localDate(new Date(Date.now() - 29 * 86400000)))
const endDate = ref(localDate(new Date()))

const form = reactive({})
const availableVehicles = computed(() => vehicles.value.filter(item => item.status === 'AVAILABLE'))
const availableDrivers = computed(() => drivers.value.filter(item => item.status === 'AVAILABLE'))
const dispatchVehicles = computed(() => form.kind === 'rental'
  ? availableVehicles.value.filter(item => item.modelName === form.item?.modelName)
  : availableVehicles.value)
const dispatchByOrder = computed(() => Object.fromEntries(dispatches.value.map(item => [item.orderId, item])))
const reportCards = computed(() => report.value ? [
  ['内部用车申请', report.value.internalApplications, `通过 ${report.value.approvedApplications} / 拒绝 ${report.value.rejectedApplications}`],
  ['完成行程', report.value.completedTripCount, `派车 ${report.value.dispatchCount} 次`],
  ['总行驶里程', `${report.value.totalDistanceKm || 0} km`, `油耗 ${report.value.totalFuelLiter || 0} L`],
  ['社会租赁订单', report.value.socialRentalOrders, `实付 ${money(report.value.rentalPaidAmountCent)}`],
  ['押金已退', money(report.value.depositRefundedCent), `抵扣 ${money(report.value.depositDeductedCent)}`],
  ['待/已补款', money(report.value.supplementAmountCent), `押金总额 ${money(report.value.depositAmountCent)}`]
] : [])

const statusText = {
  PENDING_APPROVAL: '待审核', APPROVED: '已通过', REJECTED: '已拒绝',
  PENDING_PAYMENT: '待支付', PAID: '已支付', WAITING_DRIVER: '待司机接单',
  ACCEPTED: '司机已接单', IN_SERVICE: '行程中', COMPLETED: '已完成',
  AVAILABLE: '可用', DISPATCHED: '已派出', BUSY: '忙碌', MAINTENANCE: '维修中',
  INACTIVE: '停用', CANCELLED: '已取消', SETTLED: '已结算', PENDING_SUPPLEMENT: '待补款'
}

async function loadAll() {
  try {
    loading.value = true
    error.value = ''
    const [appData, rentalData, dispatchData, vehicleData, driverData, modelData] = await Promise.all([
      getVehicleApplications({ status: applicationStatus.value || undefined }),
      getVehicleRentals({ status: rentalStatus.value || undefined }),
      getRentalDispatches(), getVehicles(), getVehicleDrivers(), getVehicleModels()
    ])
    applications.value = appData || []
    rentals.value = rentalData || []
    dispatches.value = dispatchData || []
    vehicles.value = vehicleData || []
    drivers.value = driverData || []
    models.value = modelData || []
  } catch (e) { error.value = e.message } finally { loading.value = false }
}

async function loadReport() {
  try {
    loading.value = true
    error.value = ''
    report.value = await getVehicleReport({ startDate: startDate.value, endDate: endDate.value })
  } catch (e) { error.value = e.message } finally { loading.value = false }
}

function openReview(item, approved) {
  Object.assign(form, { item, approved, comment: approved ? '申请信息完整，同意安排用车' : '' })
  modal.value = 'review'
}

function openDispatch(item, kind) {
  Object.assign(form, { item, kind, vehicleId: '', driverId: '' })
  modal.value = 'dispatch'
}

function openComplete(item, kind) {
  const vehicle = vehicles.value.find(v => v.id === item.vehicleId)
  Object.assign(form, { item, kind, endMileageKm: vehicle?.mileageKm || 0, fuelLiter: '', remark: '车辆安全返回，车况正常' })
  modal.value = kind === 'rental' ? 'return' : 'complete'
}

function openCreate(type) {
  Object.keys(form).forEach(key => delete form[key])
  if (type === 'model') Object.assign(form, { modelCode: '', name: '', seatCount: 5, fuelType: 'GASOLINE_OR_DIESEL', status: 'ACTIVE', halfDayCent: 0, fullDayCent: 0, monthCent: '', bareMonthCent: '', overtimeCentPerHour: 0, overDistanceCentPerKm: 0 })
  if (type === 'vehicle') Object.assign(form, { modelId: lastCreatedModelId.value, vehicleNo: '', plateNo: '', imageUrl: '', configuration: '{"color":"白色","gps":true}', status: 'AVAILABLE', mileageKm: 0 })
  if (type === 'driver') Object.assign(form, { driverNo: '', name: '', mobile: '', licenseNo: '', status: 'AVAILABLE' })
  modal.value = type
}

function openUnavailable(item) {
  Object.assign(form, { item, startAt: '', endAt: '', reason: '计划保养' })
  modal.value = 'unavailable'
}

async function submitModal() {
  try {
    loading.value = true
    error.value = ''
    let successMessage = '操作成功，数据已刷新'
    if (modal.value === 'review') {
      await reviewVehicleApplication(form.item.id, { approved: form.approved, comment: form.comment })
    } else if (modal.value === 'dispatch') {
      const data = { vehicleId: Number(form.vehicleId), driverId: Number(form.driverId) }
      if (form.kind === 'rental') await dispatchVehicleRental(form.item.orderId, data)
      else await dispatchVehicleApplication(form.item.id, data)
    } else if (modal.value === 'complete') {
      await completeVehicleDispatch(form.item.dispatchId, { endMileageKm: Number(form.endMileageKm), fuelLiter: nullableNumber(form.fuelLiter), remark: form.remark })
    } else if (modal.value === 'return') {
      await returnRentalVehicle(form.item.dispatchId, { returnedAt: null, endMileageKm: Number(form.endMileageKm), fuelLiter: nullableNumber(form.fuelLiter), condition: form.remark, photoUrls: [] })
    } else if (modal.value === 'model') {
      const model = await createVehicleModel({ modelCode: form.modelCode, name: form.name, seatCount: Number(form.seatCount), fuelType: form.fuelType, status: form.status })
      await updateVehicleTariff(model.id, {
        halfDayCent: yuanToCent(form.halfDayCent), fullDayCent: yuanToCent(form.fullDayCent),
        monthCent: nullableCent(form.monthCent), bareMonthCent: nullableCent(form.bareMonthCent),
        overtimeCentPerHour: nullableCent(form.overtimeCentPerHour), overDistanceCentPerKm: nullableCent(form.overDistanceCentPerKm), enabled: true
      })
      lastCreatedModelId.value = model.id
      successMessage = `车型和价格创建成功，车型ID为 ${model.id}；新增车辆时已自动填入`
    } else if (modal.value === 'vehicle') {
      await createVehicle({ modelId: Number(form.modelId), vehicleNo: form.vehicleNo, plateNo: form.plateNo, imageUrl: form.imageUrl, configuration: form.configuration, status: form.status, mileageKm: Number(form.mileageKm) })
    } else if (modal.value === 'driver') {
      await createVehicleDriver({ driverNo: form.driverNo, name: form.name, mobile: form.mobile, licenseNo: form.licenseNo, status: form.status })
    } else if (modal.value === 'unavailable') {
      await createUnavailablePeriod(form.item.id, { startAt: form.startAt, endAt: form.endAt, reason: form.reason })
    }
    modal.value = null
    notice.value = successMessage
    await loadAll()
  } catch (e) { error.value = e.message } finally { loading.value = false }
}

async function quickAction(action, item) {
  try {
    loading.value = true
    error.value = ''
    if (action === 'accept-internal') await acceptVehicleDispatch(item.dispatchId)
    if (action === 'depart-internal') await departVehicleDispatch(item.dispatchId)
    if (action === 'accept-rental') await acceptRentalDispatch(item.dispatchId)
    if (action === 'depart-rental') await departRentalDispatch(item.dispatchId)
    if (action === 'vehicle-status') await updateVehicle(item.id, { imageUrl: null, configuration: null, status: item.status === 'MAINTENANCE' ? 'AVAILABLE' : 'MAINTENANCE' })
    if (action === 'driver-status') await updateVehicleDriverStatus(item.id, item.status === 'INACTIVE' ? 'AVAILABLE' : 'INACTIVE')
    notice.value = '状态已更新'
    await loadAll()
  } catch (e) { error.value = e.message } finally { loading.value = false }
}

async function showPeriods(item) {
  try {
    const rows = await getUnavailablePeriods(item.id)
    Object.assign(form, { item, periods: rows || [] })
    modal.value = 'periods'
  } catch (e) { error.value = e.message }
}

async function cancelPeriod(id) {
  try {
    await cancelUnavailablePeriod(id)
    form.periods = await getUnavailablePeriods(form.item.id)
  } catch (e) { error.value = e.message }
}

async function downloadReport() {
  try {
    const blob = await exportVehicleReport({ startDate: startDate.value, endDate: endDate.value })
    downloadBlob(blob, `车辆运营报表_${startDate.value}_${endDate.value}.csv`)
  } catch (e) { error.value = e.message }
}

async function openPrint() {
  try {
    const html = await printVehicleReport({ startDate: startDate.value, endDate: endDate.value })
    const url = URL.createObjectURL(new Blob([html], { type: 'text/html;charset=utf-8' }))
    window.open(url, '_blank')
    setTimeout(() => URL.revokeObjectURL(url), 60000)
  } catch (e) { error.value = e.message }
}

function status(value) { return statusText[value] || value || '-' }
function badgeClass(value) { return ['REJECTED', 'INACTIVE', 'CANCELLED'].includes(value) ? 'inactive' : ['PENDING_APPROVAL', 'PENDING_PAYMENT', 'WAITING_DRIVER'].includes(value) ? 'draft' : '' }
function money(value) { return `¥${((value || 0) / 100).toFixed(2)}` }
function date(value) { return value?.replace('T', ' ') || '-' }
function localDate(value) { return `${value.getFullYear()}-${String(value.getMonth() + 1).padStart(2, '0')}-${String(value.getDate()).padStart(2, '0')}` }
function nullableNumber(value) { return value === '' || value == null ? null : Number(value) }
function yuanToCent(value) { return Math.round(Number(value || 0) * 100) }
function nullableCent(value) { return value === '' || value == null ? null : yuanToCent(value) }
function downloadBlob(blob, name) { const url = URL.createObjectURL(blob); const a = document.createElement('a'); a.href = url; a.download = name; a.click(); URL.revokeObjectURL(url) }

onMounted(() => Promise.all([loadAll(), loadReport()]))
</script>

<template>
  <div class="vehicle-page">
    <div class="toolbar"><div><h2>车辆租赁与调度</h2><p>统一处理内部用车审核、社会租赁派车、车队档案、还车结算和运营统计。</p></div><button class="primary" :disabled="loading" @click="loadAll">{{ loading ? '处理中...' : '刷新全部' }}</button></div>
    <div class="content-tabs vehicle-tabs"><button :class="{ active: tab === 'applications' }" @click="tab = 'applications'">内部用车审核</button><button :class="{ active: tab === 'rentals' }" @click="tab = 'rentals'">社会租赁调度</button><button :class="{ active: tab === 'fleet' }" @click="tab = 'fleet'">车辆与司机</button><button :class="{ active: tab === 'report' }" @click="tab = 'report'; loadReport()">运营报表</button></div>
    <p v-if="error" class="error vehicle-message">{{ error }}</p><p v-if="notice" class="success vehicle-message">{{ notice }}</p>

    <section v-if="tab === 'applications'">
      <div class="filters"><select v-model="applicationStatus"><option value="">全部申请</option><option value="PENDING_APPROVAL">待审核</option><option value="APPROVED">已通过</option><option value="REJECTED">已拒绝</option></select><button @click="loadAll">筛选</button></div>
      <div class="panel table-wrap"><table><thead><tr><th>申请单</th><th>时间与路线</th><th>人数 / 里程</th><th>用车事由</th><th>状态</th><th>车辆 / 司机</th><th>操作</th></tr></thead><tbody><tr v-if="!applications.length"><td colspan="7">暂无内部用车申请。</td></tr><tr v-for="item in applications" :key="item.id"><td><b>{{ item.applicationNo }}</b><small>#{{ item.id }} · {{ date(item.createdAt) }}</small></td><td>{{ item.departure }} → {{ item.destination }}<small>{{ date(item.startAt) }} 至 {{ date(item.endAt) }}</small></td><td>{{ item.passengerCount }} 人<small>预计 {{ item.estimatedKm }} km</small></td><td>{{ item.purpose }}</td><td><span class="badge" :class="badgeClass(item.dispatchStatus || item.status)">{{ status(item.dispatchStatus || item.status) }}</span><small v-if="item.reviewerComment">{{ item.reviewerComment }}</small></td><td>{{ item.plateNo || '-' }}<small>{{ item.driverNo || '未派司机' }}</small></td><td><div class="actions vehicle-actions"><template v-if="item.status === 'PENDING_APPROVAL'"><button class="primary" @click="openReview(item, true)">通过</button><button class="danger" @click="openReview(item, false)">拒绝</button></template><button v-else-if="item.status === 'APPROVED' && !item.dispatchId" class="primary" @click="openDispatch(item, 'internal')">派车</button><button v-if="item.dispatchStatus === 'WAITING_DRIVER'" @click="quickAction('accept-internal', item)">确认接单</button><button v-if="item.dispatchStatus === 'ACCEPTED'" @click="quickAction('depart-internal', item)">确认出车</button><button v-if="item.dispatchStatus === 'IN_SERVICE'" class="primary" @click="openComplete(item, 'internal')">完成行程</button></div></td></tr></tbody></table></div>
    </section>

    <section v-if="tab === 'rentals'">
      <div class="filters"><select v-model="rentalStatus"><option value="">全部租赁订单</option><option value="PAID">已支付待派车</option><option value="WAITING_DRIVER">待接单</option><option value="ACCEPTED">已接单</option><option value="IN_SERVICE">行程中</option><option value="COMPLETED">已完成</option></select><button @click="loadAll">筛选</button></div>
      <div class="panel table-wrap"><table><thead><tr><th>订单</th><th>车型 / 方式</th><th>租金 / 押金</th><th>订单状态</th><th>派车信息</th><th>操作</th></tr></thead><tbody><tr v-if="!rentals.length"><td colspan="6">暂无社会租赁订单。</td></tr><tr v-for="item in rentals" :key="item.orderId"><td><b>{{ item.orderNo }}</b><small>#{{ item.orderId }} · {{ date(item.createdAt) }}</small></td><td>{{ item.modelName }}<small>{{ item.rentalMode }}</small></td><td>{{ money(item.rentalAmountCent) }}<small>押金 {{ money(item.depositAmountCent) }}</small></td><td><span class="badge" :class="badgeClass(item.status)">{{ status(item.status) }}</span></td><td><template v-if="dispatchByOrder[item.orderId]">{{ dispatchByOrder[item.orderId].plateNo }}<small>{{ dispatchByOrder[item.orderId].driverNo }} · {{ status(dispatchByOrder[item.orderId].dispatchStatus) }}</small></template><span v-else>-</span></td><td><div class="actions vehicle-actions"><button v-if="item.status === 'PAID'" class="primary" @click="openDispatch(item, 'rental')">安排车辆</button><button v-if="dispatchByOrder[item.orderId]?.dispatchStatus === 'WAITING_DRIVER'" @click="quickAction('accept-rental', dispatchByOrder[item.orderId])">确认接单</button><button v-if="dispatchByOrder[item.orderId]?.dispatchStatus === 'ACCEPTED'" @click="quickAction('depart-rental', dispatchByOrder[item.orderId])">确认交车</button><button v-if="dispatchByOrder[item.orderId]?.dispatchStatus === 'IN_SERVICE'" class="primary" @click="openComplete(dispatchByOrder[item.orderId], 'rental')">还车结算</button></div></td></tr></tbody></table></div>
    </section>

    <section v-if="tab === 'fleet'">
      <div class="toolbar fleet-toolbar"><div><h3>实体车辆</h3><p>维修状态和不可租时段会参与派车冲突校验。</p></div><div class="actions"><button @click="openCreate('model')">新增车型及价格</button><button class="primary" @click="openCreate('vehicle')">新增车辆</button><button @click="openCreate('driver')">新增司机</button></div></div>
      <div class="panel table-wrap"><table><thead><tr><th>车辆编号</th><th>车牌</th><th>车型</th><th>座位</th><th>里程</th><th>状态</th><th>管理</th></tr></thead><tbody><tr v-if="!vehicles.length"><td colspan="7">暂无车辆。</td></tr><tr v-for="item in vehicles" :key="item.id"><td>{{ item.vehicleNo }}<small>#{{ item.id }}</small></td><td><b>{{ item.plateNo }}</b></td><td>{{ item.modelName }}<small>{{ item.modelCode }}</small></td><td>{{ item.seatCount }}</td><td>{{ item.mileageKm }} km</td><td><span class="badge" :class="badgeClass(item.status)">{{ status(item.status) }}</span></td><td><div class="actions vehicle-actions"><button @click="openUnavailable(item)">设置不可租</button><button @click="showPeriods(item)">查看时段</button><button v-if="['AVAILABLE','MAINTENANCE'].includes(item.status)" @click="quickAction('vehicle-status', item)">{{ item.status === 'MAINTENANCE' ? '恢复可用' : '进入维修' }}</button></div></td></tr></tbody></table></div>
      <div class="panel table-wrap vehicle-section"><div class="report-table-title"><h3>司机管理</h3><p>敏感信息由后端加密保存，列表仅显示司机编号。</p></div><table><thead><tr><th>ID</th><th>司机编号</th><th>状态</th><th>操作</th></tr></thead><tbody><tr v-if="!drivers.length"><td colspan="4">暂无司机。</td></tr><tr v-for="item in drivers" :key="item.id"><td>#{{ item.id }}</td><td><b>{{ item.driverNo }}</b></td><td><span class="badge" :class="badgeClass(item.status)">{{ status(item.status) }}</span></td><td><button v-if="['AVAILABLE','INACTIVE'].includes(item.status)" @click="quickAction('driver-status', item)">{{ item.status === 'INACTIVE' ? '恢复可用' : '停用司机' }}</button></td></tr></tbody></table></div>
    </section>

    <section v-if="tab === 'report'">
      <div class="report-filter panel"><label>开始日期<input v-model="startDate" type="date" /></label><label>结束日期<input v-model="endDate" type="date" /></label><button class="primary" @click="loadReport">生成报表</button><button @click="downloadReport">导出 CSV</button><button @click="openPrint">打印报表</button></div>
      <div class="report-stats"><article v-for="item in reportCards" :key="item[0]"><span>{{ item[0] }}</span><strong>{{ item[1] }}</strong><small>{{ item[2] }}</small></article></div>
    </section>

    <div v-if="modal" class="modal-backdrop" @click.self="modal = null"><section class="modal panel vehicle-modal"><div class="modal-header"><div><h3>{{ { review:'审核用车申请',dispatch:'安排车辆与司机',complete:'完成内部行程',return:'社会租赁还车结算',model:'新增车型及计价规则',vehicle:'新增实体车辆',driver:'新增司机',unavailable:'设置不可租时段',periods:'不可租时段记录' }[modal] }}</h3></div><button class="text-button" @click="modal = null">×</button></div>
      <template v-if="modal === 'review'"><p class="review-tip">{{ form.item?.applicationNo }}：{{ form.item?.departure }} → {{ form.item?.destination }}</p><label>审核结论<input :value="form.approved ? '通过' : '拒绝'" disabled /></label><label>审核意见<textarea v-model="form.comment" rows="3" placeholder="请输入审核意见" /></label></template>
      <template v-if="modal === 'dispatch'"><p class="review-tip">系统仍会校验车型、座位数、订单时段、不可租时段和司机状态。</p><label>车辆<select v-model="form.vehicleId"><option value="">请选择可用车辆</option><option v-for="item in dispatchVehicles" :key="item.id" :value="item.id">{{ item.plateNo }} · {{ item.modelName }} · {{ item.seatCount }}座</option></select></label><label>司机<select v-model="form.driverId"><option value="">请选择可用司机</option><option v-for="item in availableDrivers" :key="item.id" :value="item.id">{{ item.driverNo }}</option></select></label></template>
      <template v-if="['complete','return'].includes(modal)"><label>结束里程（km）<input v-model="form.endMileageKm" type="number" min="0" step="0.1" /></label><label>本次油耗（L）<input v-model="form.fuelLiter" type="number" min="0" step="0.1" /></label><label>{{ modal === 'return' ? '还车车况' : '行程备注' }}<textarea v-model="form.remark" rows="3" /></label><p v-if="modal === 'return'" class="review-tip">提交后自动计算超时和超公里费用，并完成押金抵扣、退款或生成补款单。</p></template>
      <template v-if="modal === 'model'"><div class="vehicle-form-grid"><label>车型编码<input v-model.trim="form.modelCode" placeholder="例如 EV_BUS" /></label><label>车型名称<input v-model.trim="form.name" placeholder="新能源商务车" /></label><label>座位数<input v-model="form.seatCount" type="number" min="1" /></label><label>能源类型<select v-model="form.fuelType"><option value="GASOLINE_OR_DIESEL">燃油</option><option value="ELECTRIC">纯电</option><option value="HYBRID">混动</option></select></label><label>半日价（元）<input v-model="form.halfDayCent" type="number" min="0" /></label><label>全日价（元）<input v-model="form.fullDayCent" type="number" min="0" /></label><label>包月价（元）<input v-model="form.monthCent" type="number" min="0" /></label><label>裸租包月（元）<input v-model="form.bareMonthCent" type="number" min="0" /></label><label>超时每小时（元）<input v-model="form.overtimeCentPerHour" type="number" min="0" /></label><label>超公里每公里（元）<input v-model="form.overDistanceCentPerKm" type="number" min="0" /></label></div></template>
      <template v-if="modal === 'vehicle'"><label>车型<select v-model="form.modelId"><option value="">请选择车型</option><option v-for="item in models" :key="item.id" :value="item.id">#{{ item.id }} · {{ item.name }} · {{ item.seatCount }}座</option></select></label><label>车辆编号<input v-model.trim="form.vehicleNo" /></label><label>车牌号<input v-model.trim="form.plateNo" /></label><label>图片地址<input v-model.trim="form.imageUrl" /></label><label>当前里程（km）<input v-model="form.mileageKm" type="number" min="0" /></label><label>车辆配置（JSON字符串）<textarea v-model="form.configuration" rows="3" /></label></template>
      <template v-if="modal === 'driver'"><label>司机编号<input v-model.trim="form.driverNo" /></label><label>姓名<input v-model.trim="form.name" /></label><label>手机号<input v-model.trim="form.mobile" /></label><label>驾驶证号<input v-model.trim="form.licenseNo" /></label></template>
      <template v-if="modal === 'unavailable'"><p class="review-tip">{{ form.item?.plateNo }} · {{ form.item?.modelName }}</p><label>开始时间<input v-model="form.startAt" type="datetime-local" /></label><label>结束时间<input v-model="form.endAt" type="datetime-local" /></label><label>原因<input v-model.trim="form.reason" /></label></template>
      <template v-if="modal === 'periods'"><div v-if="!form.periods?.length" class="empty-report">暂无不可租时段</div><ul v-else class="period-list"><li v-for="item in form.periods" :key="item.id"><div><b>{{ date(item.startAt) }} 至 {{ date(item.endAt) }}</b><small>{{ item.reason }} · {{ status(item.status) }}</small></div><button v-if="item.status === 'ACTIVE'" @click="cancelPeriod(item.id)">取消</button></li></ul></template>
      <div v-if="modal !== 'periods'" class="modal-actions"><button @click="modal = null">取消</button><button class="primary" :disabled="loading" @click="submitModal">确认提交</button></div>
    </section></div>
  </div>
</template>
