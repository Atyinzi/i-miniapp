<script setup>
import { computed, onMounted, ref } from 'vue'
import { checkInHotelByCode, fetchHotelBookings, fetchVacantHotelRooms } from '../services/hotels'

const loading = ref(false)
const error = ref('')
const notice = ref('')
const code = ref('')
const selectedRoomIds = ref([])
const bookings = ref([])
const rooms = ref([])
const availableRooms = computed(() => rooms.value.filter(item => item.status === 'VACANT'))

async function load() {
  loading.value = true
  error.value = ''
  try {
    const [bookingRows, roomRows] = await Promise.all([fetchHotelBookings(), fetchVacantHotelRooms()])
    bookings.value = bookingRows || []
    rooms.value = roomRows || []
  } catch (e) { error.value = e.message } finally { loading.value = false }
}

async function checkIn() {
  if (!code.value.trim() || !selectedRoomIds.value.length) return
  loading.value = true
  error.value = ''
  notice.value = ''
  try {
    await checkInHotelByCode({
      checkInCode: code.value.trim().toUpperCase(),
      roomIds: selectedRoomIds.value.map(Number),
      allowEarlyCheckIn: false,
      note: '管理后台扫描入住二维码办理'
    })
    code.value = ''
    selectedRoomIds.value = []
    notice.value = '入住二维码核销成功，房间已分配。'
    await load()
  } catch (e) { error.value = e.message } finally { loading.value = false }
}

const dateTime = value => value ? String(value).replace('T', ' ').slice(0, 16) : '-'
onMounted(load)
</script>

<template>
  <div class="hotel-page">
    <div class="toolbar"><div><h2>住宿入住运营</h2><p>扫描用户住宿二维码、分配空闲房间并办理入住。</p></div><button :disabled="loading" @click="load">刷新</button></div>
    <p v-if="error" class="error msg">{{ error }}</p><p v-if="notice" class="success msg">{{ notice }}</p>
    <section class="panel hotel-checkin">
      <div><h3>二维码入住</h3><p>扫码枪或手机扫描后，将完整入住码输入下方；身份证仅用于前台身份核验，不作为本页面核销入口。</p></div>
      <label>入住二维码内容<input v-model.trim="code" placeholder="HC-…" @keyup.enter="checkIn"></label>
      <label>分配房间<select v-model="selectedRoomIds" multiple><option v-for="room in availableRooms" :key="room.id" :value="room.id">{{ room.roomNo }} · {{ room.roomTypeName || `房型 ${room.roomTypeId}` }}</option></select></label>
      <button class="primary" :disabled="loading || !code || !selectedRoomIds.length" @click="checkIn">核销并办理入住</button>
    </section>
    <section class="panel table-wrap"><h3>住宿预订台账</h3><table><thead><tr><th>预订/订单</th><th>酒店与房型</th><th>入住日期</th><th>联系人</th><th>状态</th><th>实际入住</th></tr></thead><tbody>
      <tr v-if="!bookings.length"><td colspan="6">暂无住宿预订。</td></tr>
      <tr v-for="item in bookings" :key="item.reservationId"><td>{{ item.reservationNo }}<small>{{ item.orderNo }}</small></td><td>{{ item.hotelName }}<small>{{ item.roomTypeName }} · {{ item.roomCount }}间</small></td><td>{{ item.checkInDate }} — {{ item.checkOutDate }}</td><td>{{ item.contactName }}<small>{{ item.contactMobile }}</small></td><td><span class="badge">{{ item.stayStatus }}</span></td><td>{{ dateTime(item.actualCheckInAt) }}</td></tr>
    </tbody></table></section>
  </div>
</template>

<style scoped>
.hotel-page{display:flex;flex-direction:column;gap:18px}.hotel-checkin{display:grid;grid-template-columns:minmax(240px,1.3fr) minmax(220px,1fr) minmax(220px,1fr) auto;align-items:end;gap:18px}.hotel-checkin h3{margin:0 0 7px}.hotel-checkin p{margin:0;color:#7c746d;line-height:1.55}.hotel-checkin label{display:flex;flex-direction:column;gap:7px;color:#625951}.hotel-checkin input,.hotel-checkin select{min-height:42px;border:1px solid #d8cdc2;border-radius:8px;padding:8px 10px;background:#fff}.hotel-checkin select{height:86px}.table-wrap small{display:block;margin-top:5px;color:#928981}@media(max-width:1100px){.hotel-checkin{grid-template-columns:1fr 1fr}.hotel-checkin>div{grid-column:1/-1}}
</style>
