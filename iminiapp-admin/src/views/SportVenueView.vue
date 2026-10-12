<script setup>
import { computed, onMounted, reactive, ref, watch } from 'vue'
import {
  assignSportMemberLevel, cancelSportClosure, completeSportBooking, consumeSportVerification,
  createSportClosure, createSportCourt, createSportCourtType, createSportMemberLevel,
  createSportOpeningRule, createSportPriceRule, createSportVenue, exportSportUsageCsv,
  getSportClosures, getSportCourtTypes, getSportCourts, getSportMemberLevels,
  getSportMembers, getSportMemberTransactions, getSportOpeningRules, getSportPriceRules,
  getSportReportOverview, getSportUsageDetails, getSportUtilization, getSportVenues,
  getSportVerifications, updateSportCourt, updateSportCourtType, updateSportMemberLevel,
  updateSportOpeningRule, updateSportPriceRule, updateSportVenue
} from '../services/sports'

const tab = ref('config')
const configTab = ref('venues')
const loading = ref(false)
const error = ref('')
const notice = ref('')
const modal = ref('')
const form = reactive({})
const venues = ref([])
const courtTypes = ref([])
const courts = ref([])
const openings = ref([])
const prices = ref([])
const closures = ref([])
const bookings = ref([])
const bookingPage = ref({ page: 1, total: 0, totalPages: 0 })
const levels = ref([])
const members = ref([])
const memberPage = ref({ page: 1, total: 0, totalPages: 0 })
const transactions = ref([])
const verifications = ref([])
const verificationPage = ref({ page: 1, total: 0, totalPages: 0 })
const overview = ref(null)
const utilization = ref([])
const today = localDate(new Date())
const reportStart = ref(localDate(new Date(Date.now() - 29 * 86400000)))
const reportEnd = ref(today)
const bookingStart = ref(localDate(new Date(Date.now() - 7 * 86400000)))
const bookingEnd = ref(localDate(new Date(Date.now() + 30 * 86400000)))
const venueFilter = ref('')
const bookingStatus = ref('')
const memberLevelFilter = ref('')
const memberStatusFilter = ref('')
const utilizationDimension = ref('COURT')
const verifyCode = ref('')
const verifyDevice = ref('FRONT-DESK-01')

const selectedVenue = computed(() => venues.value.find(v => String(v.id) === String(venueFilter.value)))
const cards = computed(() => overview.value ? [
  ['预订总量', overview.value.bookingCount, `已支付 ${overview.value.paidBookingCount}`],
  ['净收入', money(overview.value.netRevenueCent), `实收 ${money(overview.value.grossRevenueCent)}`],
  ['成功退款', money(overview.value.refundAmountCent), `${overview.value.refundedBookingCount} 笔`],
  ['核销率', `${overview.value.verificationRatePercent || 0}%`, `${overview.value.verificationCount} 次核销`],
  ['会员净消费', money(overview.value.memberConsumptionCent), `${overview.value.memberBookingCount} 笔会员预订`],
  ['场地利用率', `${overview.value.utilizationRatePercent || 0}%`, `${overview.value.occupiedSlots}/${overview.value.capacitySlots} 时段`]
] : [])
const statusText = { ACTIVE:'启用', INACTIVE:'停用', HELD:'待支付占用', CONFIRMED:'已确认', IN_USE:'使用中', COMPLETED:'已完成', CANCELLED:'已取消', REFUND_PENDING:'退款待审', REFUNDING:'退款中', REFUNDED:'已退款', PAID:'已支付', PENDING_PAYMENT:'待支付', USED:'已核销' }
const dayText = ['','周一','周二','周三','周四','周五','周六','周日']

async function run(action, success='') { try { loading.value=true; error.value=''; await action(); if(success) notice.value=success } catch(e){ error.value=e.message } finally { loading.value=false } }
async function loadConfig(){ await run(async()=>{ const [v,t,c,o,p]=await Promise.all([getSportVenues(),getSportCourtTypes(),getSportCourts(),getSportOpeningRules(),getSportPriceRules()]); venues.value=v||[];courtTypes.value=t||[];courts.value=c||[];openings.value=o||[];prices.value=p||[] }) }
async function loadBookings(page=1){ await run(async()=>{ const data=await getSportUsageDetails({startDate:bookingStart.value,endDate:bookingEnd.value,venueId:numOrNull(venueFilter.value),status:bookingStatus.value||undefined,page,pageSize:20});bookings.value=data?.items||[];bookingPage.value=data||{} }) }
async function loadClosures(){ await run(async()=>{ closures.value=await getSportClosures({startDate:bookingStart.value,endDate:bookingEnd.value})||[] }) }
async function loadMembers(page=1){ await run(async()=>{ const [l,m]=await Promise.all([getSportMemberLevels(),getSportMembers({levelCode:memberLevelFilter.value||undefined,status:memberStatusFilter.value||undefined,page,pageSize:20})]);levels.value=l||[];members.value=m?.items||[];memberPage.value=m||{} }) }
async function loadVerifications(page=1){ await run(async()=>{ const data=await getSportVerifications({venueId:numOrNull(venueFilter.value),bookingDate:reportStart.value===reportEnd.value?reportStart.value:undefined,page,pageSize:20});verifications.value=data?.items||[];verificationPage.value=data||{} }) }
async function loadReport(){ await run(async()=>{ const params={startDate:reportStart.value,endDate:reportEnd.value,venueId:numOrNull(venueFilter.value)}; const [o,u]=await Promise.all([getSportReportOverview(params),getSportUtilization({...params,dimension:utilizationDimension.value})]);overview.value=o;utilization.value=u||[] }) }

function reset(values){ Object.keys(form).forEach(k=>delete form[k]);Object.assign(form,values) }
function openVenue(item){ reset(item?{...item}:{venueCode:'',name:'',address:'',contactPhone:'',description:'',openTime:'08:00',closeTime:'22:00',status:'ACTIVE'});modal.value='venue' }
function openCourtType(item){ reset(item?{...item,basePriceYuan:item.basePriceCent/100}:{venueId:venueFilter.value||'',typeCode:'',name:'',description:'',basePriceYuan:60,status:'ACTIVE'});modal.value='courtType' }
function openCourt(item){ reset(item?{...item}:{venueId:venueFilter.value||'',courtTypeId:'',courtCode:'',name:'',location:'',capacity:4,status:'ACTIVE'});modal.value='court' }
function openOpening(item){ reset(item?{...item}:{courtId:'',dayOfWeek:1,startTime:'08:00',endTime:'22:00',slotMinutes:60,status:'ACTIVE'});modal.value='opening' }
function openPrice(item){ reset(item?{...item,priceYuan:item.priceCent/100}:{venueId:venueFilter.value||'',courtTypeId:'',courtId:'',ruleCode:'',name:'',ruleType:'NORMAL',startDate:'',endDate:'',dayOfWeek:'',startTime:'',endTime:'',priceYuan:60,priority:0,status:'ACTIVE'});modal.value='price' }
function openClosure(){ reset({courtId:'',closureDate:today,startTime:'08:00',endTime:'09:00',reason:'设备维护'});modal.value='closure' }
function openLevel(item){ reset(item?{...item,discount:Number(item.sportDiscountBp)/1000,benefitText:(item.benefits||[]).join('，')}:{levelCode:'',levelName:'',discount:10,minGrowthValue:0,benefitText:'会员储值，优先活动通知',status:'ACTIVE'});modal.value='level' }

async function submit(){ await run(async()=>{
  if(modal.value==='venue'){ const p=pick(form,['venueCode','name','address','contactPhone','description','openTime','closeTime','status']);form.id?await updateSportVenue(form.id,p):await createSportVenue(p) }
  if(modal.value==='courtType'){ const p={...pick(form,['venueId','typeCode','name','description','status']),venueId:Number(form.venueId),basePriceCent:yuanCent(form.basePriceYuan)};form.id?await updateSportCourtType(form.id,p):await createSportCourtType(p) }
  if(modal.value==='court'){ const p={...pick(form,['courtCode','name','location','status']),venueId:Number(form.venueId),courtTypeId:Number(form.courtTypeId),capacity:Number(form.capacity)};form.id?await updateSportCourt(form.id,p):await createSportCourt(p) }
  if(modal.value==='opening'){ const p={...pick(form,['startTime','endTime','status']),courtId:Number(form.courtId),dayOfWeek:Number(form.dayOfWeek),slotMinutes:Number(form.slotMinutes)};form.id?await updateSportOpeningRule(form.id,p):await createSportOpeningRule(p) }
  if(modal.value==='price'){ const p={...pick(form,['ruleCode','name','ruleType','startDate','endDate','startTime','endTime','status']),venueId:Number(form.venueId),courtTypeId:numOrNull(form.courtTypeId),courtId:numOrNull(form.courtId),dayOfWeek:numOrNull(form.dayOfWeek),priceCent:yuanCent(form.priceYuan),priority:Number(form.priority)};form.id?await updateSportPriceRule(form.id,p):await createSportPriceRule(p) }
  if(modal.value==='closure') await createSportClosure({...pick(form,['closureDate','startTime','endTime','reason']),courtId:Number(form.courtId)})
  if(modal.value==='level'){ const p={levelCode:form.levelCode,levelName:form.levelName,sportDiscountBp:Math.round(Number(form.discount)*1000),minGrowthValue:Number(form.minGrowthValue),benefits:String(form.benefitText||'').split(/[，,]/).map(v=>v.trim()).filter(Boolean),status:form.status};form.id?await updateSportMemberLevel(form.id,p):await createSportMemberLevel(p) }
  modal.value='';await Promise.all([loadConfig(),loadClosures(),loadMembers()])
},'保存成功，数据已刷新') }

async function cancelClosure(item){ await run(async()=>{await cancelSportClosure(item.id);await loadClosures()},'闭场记录已取消') }
async function completeBooking(item){ if(!confirm(`确认场地“${item.courtName}”已使用完成？`))return;await run(async()=>{await completeSportBooking(item.orderId);await loadBookings(bookingPage.value.page||1)},'场地使用已完成') }
async function changeMemberLevel(item,event){ const code=event.target.value;if(!code)return;await run(async()=>{await assignSportMemberLevel(item.id,code);await loadMembers(memberPage.value.page||1)},'会员等级已调整') }
async function showTransactions(item){ await run(async()=>{ const data=await getSportMemberTransactions(item.id,{page:1,pageSize:100});transactions.value=data?.items||[];reset({item});modal.value='transactions' }) }
async function consume(){ await run(async()=>{ await consumeSportVerification({code:verifyCode.value,deviceNo:verifyDevice.value});verifyCode.value='';await Promise.all([loadVerifications(),loadBookings()]) },'核销成功，场地已进入使用中') }
async function downloadCsv(){ await run(async()=>{ const blob=await exportSportUsageCsv({startDate:reportStart.value,endDate:reportEnd.value,venueId:numOrNull(venueFilter.value)});download(blob,`体育场地使用明细_${reportStart.value}_${reportEnd.value}.csv`) }) }

function switchTab(value){tab.value=value;if(value==='bookings')loadBookings();if(value==='closures')loadClosures();if(value==='members')loadMembers();if(value==='verification')loadVerifications();if(value==='report')loadReport()}
function money(v){return `¥${(Number(v||0)/100).toFixed(2)}`}
function localDate(d){return new Date(d.getTime()-d.getTimezoneOffset()*60000).toISOString().slice(0,10)}
function dateTime(v){return v?String(v).replace('T',' ').slice(0,16):'-'}
function status(v){return statusText[v]||v||'-'}
function badge(v){return ['CANCELLED','REFUNDED','INACTIVE'].includes(v)?'inactive':['HELD','PENDING_PAYMENT','REFUND_PENDING','REFUNDING'].includes(v)?'draft':''}
function numOrNull(v){return v===''||v==null?null:Number(v)}
function yuanCent(v){return Math.round(Number(v||0)*100)}
function pick(source,keys){return Object.fromEntries(keys.map(k=>[k,source[k]===''?null:source[k]]))}
function download(blob,name){const url=URL.createObjectURL(blob);const a=document.createElement('a');a.href=url;a.download=name;a.click();URL.revokeObjectURL(url)}

watch(venueFilter,()=>{if(tab.value==='report')loadReport()})
onMounted(async()=>{await loadConfig();if(venues.value.length)venueFilter.value=venues.value[0].id})
</script>

<template>
  <div class="sport-page">
    <div class="toolbar"><div><h2>体育场馆运营</h2><p>场馆配置、预订履约、会员储值、扫码核销和经营分析统一工作台。</p></div><button :disabled="loading" @click="loadConfig">{{ loading?'处理中…':'刷新基础数据' }}</button></div>
    <p v-if="error" class="error sport-message">{{ error }}</p><p v-if="notice" class="success sport-message">{{ notice }}</p>
    <div class="sport-context panel"><label>当前场馆<select v-model="venueFilter"><option value="">全部场馆</option><option v-for="v in venues" :key="v.id" :value="v.id">{{ v.name }}</option></select></label><div><b>{{ selectedVenue?.venueCode||'全局视图' }}</b><small>{{ selectedVenue?.address||'查看全部场馆运营数据' }}</small></div></div>
    <div class="content-tabs sport-tabs"><button v-for="item in [['config','场馆配置'],['bookings','预订工作台'],['closures','临时闭场'],['members','会员储值'],['verification','核销工作台'],['report','运营报表']]" :key="item[0]" :class="{active:tab===item[0]}" @click="switchTab(item[0])">{{ item[1] }}</button></div>

    <section v-if="tab==='config'">
      <div class="config-nav"><button v-for="item in [['venues','场馆'],['types','场地类型'],['courts','具体场地'],['openings','开放时段'],['prices','价格规则']]" :key="item[0]" :class="{active:configTab===item[0]}" @click="configTab=item[0]">{{ item[1] }}</button></div>
      <div class="toolbar"><div><h3>{{ {venues:'场馆资料',types:'场地类型',courts:'具体场地',openings:'每周开放时段',prices:'淡旺季与节假日价格'}[configTab] }}</h3><p>所有停启用、时段与价格调整均由后端校验并写入审计日志。</p></div><button class="primary" @click="({venues:openVenue,types:openCourtType,courts:openCourt,openings:openOpening,prices:openPrice}[configTab])()">新增</button></div>
      <div class="panel table-wrap"><table v-if="configTab==='venues'"><thead><tr><th>场馆</th><th>地址/联系</th><th>营业时间</th><th>场地规模</th><th>状态</th><th>操作</th></tr></thead><tbody><tr v-for="v in venues" :key="v.id"><td><b>{{ v.name }}</b><small>{{ v.venueCode }} · #{{ v.id }}</small></td><td>{{ v.address }}<small>{{ v.contactPhone||'-' }}</small></td><td>{{ v.openTime }}—{{ v.closeTime }}</td><td>{{ v.courtTypeCount }} 类 / {{ v.courtCount }} 块</td><td><span class="badge" :class="badge(v.status)">{{ status(v.status) }}</span></td><td><button @click="openVenue(v)">编辑</button></td></tr></tbody></table>
        <table v-if="configTab==='types'"><thead><tr><th>类型</th><th>场馆</th><th>基础价</th><th>场地数</th><th>状态</th><th>操作</th></tr></thead><tbody><tr v-for="v in courtTypes" :key="v.id"><td><b>{{ v.name }}</b><small>{{ v.typeCode }}</small></td><td>{{ v.venueName }}</td><td>{{ money(v.basePriceCent) }}</td><td>{{ v.courtCount }}</td><td><span class="badge" :class="badge(v.status)">{{ status(v.status) }}</span></td><td><button @click="openCourtType(v)">编辑</button></td></tr></tbody></table>
        <table v-if="configTab==='courts'"><thead><tr><th>场地</th><th>场馆/类型</th><th>位置</th><th>容量</th><th>开放规则</th><th>状态</th><th>操作</th></tr></thead><tbody><tr v-for="v in courts" :key="v.id"><td><b>{{ v.name }}</b><small>{{ v.courtCode }}</small></td><td>{{ v.venueName }}<small>{{ v.courtTypeName }}</small></td><td>{{ v.location||'-' }}</td><td>{{ v.capacity }} 人</td><td>{{ v.openingRuleCount }} 条</td><td><span class="badge" :class="badge(v.status)">{{ status(v.status) }}</span></td><td><button @click="openCourt(v)">编辑</button></td></tr></tbody></table>
        <table v-if="configTab==='openings'"><thead><tr><th>场地</th><th>星期</th><th>开放时间</th><th>预订时长</th><th>状态</th><th>操作</th></tr></thead><tbody><tr v-for="v in openings" :key="v.id"><td>{{ v.courtName }}</td><td>{{ dayText[v.dayOfWeek] }}</td><td>{{ v.startTime }}—{{ v.endTime }}</td><td>{{ v.slotMinutes }} 分钟</td><td><span class="badge" :class="badge(v.status)">{{ status(v.status) }}</span></td><td><button @click="openOpening(v)">编辑</button></td></tr></tbody></table>
        <table v-if="configTab==='prices'"><thead><tr><th>价格规则</th><th>适用范围</th><th>日期/星期</th><th>时段</th><th>价格</th><th>优先级</th><th>操作</th></tr></thead><tbody><tr v-for="v in prices" :key="v.id"><td><b>{{ v.name }}</b><small>{{ v.ruleCode }} · {{ v.ruleType }}</small></td><td>{{ v.venueName }}<small>{{ v.courtName||v.courtTypeName||'全场馆' }}</small></td><td>{{ v.startDate||'不限' }}—{{ v.endDate||'不限' }}<small>{{ v.dayOfWeek?dayText[v.dayOfWeek]:'每天' }}</small></td><td>{{ v.startTime||'全天' }}—{{ v.endTime||'全天' }}</td><td>{{ money(v.priceCent) }}</td><td>{{ v.priority }}</td><td><button @click="openPrice(v)">编辑</button></td></tr></tbody></table></div>
    </section>

    <section v-if="tab==='bookings'"><div class="filters sport-filters"><input v-model="bookingStart" type="date"/><input v-model="bookingEnd" type="date"/><select v-model="bookingStatus"><option value="">全部状态</option><option v-for="v in ['HELD','CONFIRMED','IN_USE','COMPLETED','CANCELLED','REFUND_PENDING','REFUNDING','REFUNDED']" :key="v" :value="v">{{ status(v) }}</option></select><button class="primary" @click="loadBookings(1)">查询</button></div><div class="panel table-wrap"><table><thead><tr><th>订单</th><th>场馆/场地</th><th>预订时段</th><th>金额</th><th>会员</th><th>状态</th><th>履约</th></tr></thead><tbody><tr v-if="!bookings.length"><td colspan="7">所选条件暂无预订。</td></tr><tr v-for="v in bookings" :key="v.bookingId"><td><b>{{ v.orderNo }}</b><small>#{{ v.orderId }}</small></td><td>{{ v.venueName }}<small>{{ v.courtTypeName }} / {{ v.courtName }}</small></td><td>{{ v.bookingDate }}<small>{{ v.startTime }}—{{ v.endTime }}</small></td><td>{{ money(v.paidAmountCent||v.originalPriceCent) }}<small v-if="v.refundAmountCent">退款 {{ money(v.refundAmountCent) }}</small></td><td>{{ v.memberLevelCode||'非会员' }}</td><td><span class="badge" :class="badge(v.bookingStatus)">{{ status(v.bookingStatus) }}</span><small>{{ status(v.orderStatus) }}</small></td><td><button v-if="v.bookingStatus==='IN_USE'" class="primary" @click="completeBooking(v)">结束使用</button><span v-else>{{ dateTime(v.checkedInAt) }}</span></td></tr></tbody></table><div class="pagination"><span>共 {{ bookingPage.total||0 }} 条</span><div><button :disabled="bookingPage.page<=1" @click="loadBookings(bookingPage.page-1)">上一页</button><button :disabled="bookingPage.page>=bookingPage.totalPages" @click="loadBookings(bookingPage.page+1)">下一页</button></div></div></div></section>

    <section v-if="tab==='closures'"><div class="toolbar"><div><h3>临时闭场与维护占用</h3><p>创建前自动检查与已支付或待支付预订的时间冲突。</p></div><button class="primary" @click="openClosure">新增闭场</button></div><div class="panel table-wrap"><table><thead><tr><th>场地</th><th>日期</th><th>时间</th><th>原因</th><th>状态</th><th>操作</th></tr></thead><tbody><tr v-if="!closures.length"><td colspan="6">当前日期范围暂无闭场记录。</td></tr><tr v-for="v in closures" :key="v.id"><td>{{ v.courtName }}<small>#{{ v.courtId }}</small></td><td>{{ v.closureDate }}</td><td>{{ v.startTime }}—{{ v.endTime }}</td><td>{{ v.reason }}</td><td><span class="badge" :class="badge(v.status)">{{ status(v.status) }}</span></td><td><button v-if="v.status==='ACTIVE'" class="danger" @click="cancelClosure(v)">取消闭场</button></td></tr></tbody></table></div></section>

    <section v-if="tab==='members'"><div class="toolbar"><div><h3>会员等级与储值账户</h3><p>配置专属折扣和权益，查询余额及不可变资金流水。</p></div><button class="primary" @click="openLevel()">新增等级</button></div><div class="member-level-grid"><article v-for="v in levels" :key="v.id" class="panel"><span class="badge" :class="badge(v.status)">{{ status(v.status) }}</span><h3>{{ v.levelName }}</h3><strong>{{ (v.sportDiscountBp/1000).toFixed(2) }} 折</strong><p>{{ (v.benefits||[]).join(' · ')||'暂无权益' }}</p><button @click="openLevel(v)">编辑权益</button></article></div><div class="filters sport-filters"><select v-model="memberLevelFilter"><option value="">全部等级</option><option v-for="v in levels" :key="v.id" :value="v.levelCode">{{ v.levelName }}</option></select><select v-model="memberStatusFilter"><option value="">全部状态</option><option value="ACTIVE">启用</option><option value="INACTIVE">停用</option></select><button @click="loadMembers(1)">筛选会员</button></div><div class="panel table-wrap"><table><thead><tr><th>会员</th><th>用户</th><th>等级/折扣</th><th>储值余额</th><th>成长值/积分</th><th>注册时间</th><th>操作</th></tr></thead><tbody><tr v-for="v in members" :key="v.id"><td><b>{{ v.memberNo }}</b><small>#{{ v.id }}</small></td><td>{{ v.nickname }}<small>{{ v.mobile||'未绑定手机' }}</small></td><td><select :value="v.levelCode" @change="changeMemberLevel(v,$event)"><option v-for="l in levels" :key="l.id" :value="l.levelCode">{{ l.levelName }}</option></select><small>{{ (v.sportDiscountBp/1000).toFixed(2) }} 折</small></td><td><b>{{ money(v.balanceCent) }}</b></td><td>{{ v.growthValue }} / {{ v.points }}</td><td>{{ dateTime(v.registeredAt) }}</td><td><button @click="showTransactions(v)">资金流水</button></td></tr></tbody></table><div class="pagination"><span>共 {{ memberPage.total||0 }} 位</span><div><button :disabled="memberPage.page<=1" @click="loadMembers(memberPage.page-1)">上一页</button><button :disabled="memberPage.page>=memberPage.totalPages" @click="loadMembers(memberPage.page+1)">下一页</button></div></div></div></section>

    <section v-if="tab==='verification'"><div class="verification-console panel"><div><h3>扫码核销</h3><p>扫码枪可直接输入完整核销码；重复核销会被数据库行锁和唯一约束拦截。</p></div><label>核销设备<input v-model.trim="verifyDevice" maxlength="64"/></label><label>核销码<input v-model.trim="verifyCode" placeholder="BFHV-…" @keyup.enter="consume"/></label><button class="primary" :disabled="!verifyCode||loading" @click="consume">确认核销</button></div><div class="panel table-wrap"><table><thead><tr><th>订单</th><th>场馆/场地</th><th>预订时段</th><th>核销时间</th><th>核销人员</th><th>渠道/设备</th></tr></thead><tbody><tr v-if="!verifications.length"><td colspan="6">暂无体育核销记录。</td></tr><tr v-for="v in verifications" :key="v.id"><td>{{ v.orderNo }}<small>#{{ v.orderId }}</small></td><td>{{ v.venueName }}<small>{{ v.courtName }}</small></td><td>{{ v.bookingDate }}<small>{{ v.startTime }}—{{ v.endTime }}</small></td><td>{{ dateTime(v.verifiedAt) }}</td><td>{{ v.operatorName }}</td><td>{{ v.verificationChannel }}<small>{{ v.deviceNo||'-' }}</small></td></tr></tbody></table></div></section>

    <section v-if="tab==='report'"><div class="report-filter panel"><label>开始日期<input v-model="reportStart" type="date"/></label><label>结束日期<input v-model="reportEnd" type="date"/></label><label>利用率维度<select v-model="utilizationDimension"><option value="VENUE">场馆</option><option value="COURT_TYPE">场地类型</option><option value="COURT">具体场地</option><option value="TIME_SLOT">预订时段</option></select></label><button class="primary" @click="loadReport">生成报表</button><button @click="downloadCsv">导出CSV</button></div><div class="report-stats"><article v-for="v in cards" :key="v[0]"><span>{{ v[0] }}</span><strong>{{ v[1] }}</strong><small>{{ v[2] }}</small></article></div><div class="panel table-wrap"><div class="report-table-title"><h3>场地及时段利用率</h3><p>容量按开放规则逐日生成，并自动剔除临时闭场。</p></div><table><thead><tr><th>统计维度</th><th>对象</th><th>可经营时段</th><th>已占用时段</th><th>利用率</th></tr></thead><tbody><tr v-if="!utilization.length"><td colspan="5">所选周期暂无开放时段。</td></tr><tr v-for="v in utilization" :key="v.dimensionKey"><td>{{ v.dimension }}</td><td><b>{{ v.dimensionName }}</b><small>{{ v.dimensionKey }}</small></td><td>{{ v.capacitySlots }}</td><td>{{ v.occupiedSlots }}</td><td><div class="utilization-cell"><span :style="{width:`${Math.min(100,Number(v.utilizationRatePercent||0))}%`}"></span></div><b>{{ v.utilizationRatePercent }}%</b></td></tr></tbody></table></div></section>

    <div v-if="modal" class="modal-backdrop" @click.self="modal='' "><section class="modal panel sport-modal"><div class="modal-header"><div><h3>{{ {venue:'场馆资料',courtType:'场地类型',court:'具体场地',opening:'开放时段',price:'价格规则',closure:'临时闭场',level:'会员等级权益',transactions:'会员资金流水'}[modal] }}</h3><p>提交后由后端执行完整业务校验。</p></div><button class="text-button" @click="modal=''">×</button></div>
      <div v-if="modal==='venue'" class="sport-form-grid"><label>场馆编码<input v-model.trim="form.venueCode" :disabled="Boolean(form.id)"/></label><label>场馆名称<input v-model.trim="form.name"/></label><label class="span-two">地址<input v-model.trim="form.address"/></label><label>联系电话<input v-model.trim="form.contactPhone"/></label><label>状态<select v-model="form.status"><option value="ACTIVE">启用</option><option value="INACTIVE">停用</option></select></label><label>营业开始<input v-model="form.openTime" type="time"/></label><label>营业结束<input v-model="form.closeTime" type="time"/></label><label class="span-two">说明<textarea v-model="form.description" rows="3"/></label></div>
      <div v-if="modal==='courtType'" class="sport-form-grid"><label>所属场馆<select v-model="form.venueId"><option v-for="v in venues" :key="v.id" :value="v.id">{{ v.name }}</option></select></label><label>类型编码<input v-model.trim="form.typeCode"/></label><label>类型名称<input v-model.trim="form.name"/></label><label>基础价（元）<input v-model="form.basePriceYuan" type="number" min="0" step="0.01"/></label><label>状态<select v-model="form.status"><option value="ACTIVE">启用</option><option value="INACTIVE">停用</option></select></label><label class="span-two">说明<textarea v-model="form.description" rows="3"/></label></div>
      <div v-if="modal==='court'" class="sport-form-grid"><label>所属场馆<select v-model="form.venueId"><option v-for="v in venues" :key="v.id" :value="v.id">{{ v.name }}</option></select></label><label>场地类型<select v-model="form.courtTypeId"><option v-for="v in courtTypes.filter(x=>String(x.venueId)===String(form.venueId))" :key="v.id" :value="v.id">{{ v.name }}</option></select></label><label>场地编码<input v-model.trim="form.courtCode"/></label><label>场地名称<input v-model.trim="form.name"/></label><label>具体位置<input v-model.trim="form.location"/></label><label>容纳人数<input v-model="form.capacity" type="number" min="1"/></label><label>状态<select v-model="form.status"><option value="ACTIVE">启用</option><option value="INACTIVE">停用</option></select></label></div>
      <div v-if="modal==='opening'" class="sport-form-grid"><label>场地<select v-model="form.courtId"><option v-for="v in courts" :key="v.id" :value="v.id">{{ v.venueName }} / {{ v.name }}</option></select></label><label>星期<select v-model="form.dayOfWeek"><option v-for="i in 7" :key="i" :value="i">{{ dayText[i] }}</option></select></label><label>开始时间<input v-model="form.startTime" type="time"/></label><label>结束时间<input v-model="form.endTime" type="time"/></label><label>每段分钟<input v-model="form.slotMinutes" type="number" min="15"/></label><label>状态<select v-model="form.status"><option value="ACTIVE">启用</option><option value="INACTIVE">停用</option></select></label></div>
      <div v-if="modal==='price'" class="sport-form-grid"><label>场馆<select v-model="form.venueId"><option v-for="v in venues" :key="v.id" :value="v.id">{{ v.name }}</option></select></label><label>规则编码<input v-model.trim="form.ruleCode"/></label><label>规则名称<input v-model.trim="form.name"/></label><label>规则类型<select v-model="form.ruleType"><option value="NORMAL">日常价</option><option value="PEAK">旺季价</option><option value="OFF_PEAK">淡季价</option><option value="HOLIDAY">节假日价</option></select></label><label>场地类型<select v-model="form.courtTypeId"><option value="">全部类型</option><option v-for="v in courtTypes.filter(x=>String(x.venueId)===String(form.venueId))" :key="v.id" :value="v.id">{{ v.name }}</option></select></label><label>具体场地<select v-model="form.courtId"><option value="">全部场地</option><option v-for="v in courts.filter(x=>String(x.venueId)===String(form.venueId))" :key="v.id" :value="v.id">{{ v.name }}</option></select></label><label>开始日期<input v-model="form.startDate" type="date"/></label><label>结束日期<input v-model="form.endDate" type="date"/></label><label>星期<select v-model="form.dayOfWeek"><option value="">每天</option><option v-for="i in 7" :key="i" :value="i">{{ dayText[i] }}</option></select></label><label>开始时间<input v-model="form.startTime" type="time"/></label><label>结束时间<input v-model="form.endTime" type="time"/></label><label>价格（元）<input v-model="form.priceYuan" type="number" min="0" step="0.01"/></label><label>优先级<input v-model="form.priority" type="number"/></label><label>状态<select v-model="form.status"><option value="ACTIVE">启用</option><option value="INACTIVE">停用</option></select></label></div>
      <div v-if="modal==='closure'" class="sport-form-grid"><label>场地<select v-model="form.courtId"><option v-for="v in courts" :key="v.id" :value="v.id">{{ v.venueName }} / {{ v.name }}</option></select></label><label>闭场日期<input v-model="form.closureDate" type="date"/></label><label>开始时间<input v-model="form.startTime" type="time"/></label><label>结束时间<input v-model="form.endTime" type="time"/></label><label class="span-two">闭场原因<textarea v-model="form.reason" rows="3"/></label></div>
      <div v-if="modal==='level'" class="sport-form-grid"><label>等级编码<input v-model.trim="form.levelCode"/></label><label>等级名称<input v-model.trim="form.levelName"/></label><label>预订折扣（折）<input v-model="form.discount" type="number" min="0.01" max="10" step="0.01"/></label><label>最低成长值<input v-model="form.minGrowthValue" type="number" min="0"/></label><label>状态<select v-model="form.status"><option value="ACTIVE">启用</option><option value="INACTIVE">停用</option></select></label><label class="span-two">会员权益（逗号分隔）<textarea v-model="form.benefitText" rows="3"/></label></div>
      <div v-if="modal==='transactions'" class="table-wrap"><p class="review-tip">{{ form.item?.nickname }} · {{ form.item?.memberNo }} · 当前余额 {{ money(form.item?.balanceCent) }}</p><table><thead><tr><th>流水号</th><th>类型</th><th>发生额</th><th>变更前</th><th>变更后</th><th>关联订单</th><th>时间</th></tr></thead><tbody><tr v-if="!transactions.length"><td colspan="7">暂无储值流水。</td></tr><tr v-for="v in transactions" :key="v.id"><td>{{ v.transactionNo }}</td><td>{{ v.transactionType }}</td><td :class="v.amountCent>=0?'stock-positive':'stock-negative'">{{ money(v.amountCent) }}</td><td>{{ money(v.balanceBeforeCent) }}</td><td>{{ money(v.balanceAfterCent) }}</td><td>{{ v.referenceNo||'-' }}</td><td>{{ dateTime(v.createdAt) }}</td></tr></tbody></table></div>
      <div v-if="modal!=='transactions'" class="modal-actions"><button @click="modal=''">取消</button><button class="primary" :disabled="loading" @click="submit">保存</button></div>
    </section></div>
  </div>
</template>
