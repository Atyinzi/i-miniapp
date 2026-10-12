import { createRouter, createWebHistory } from 'vue-router'
import AppLayout from '../layouts/AppLayout.vue'
import LoginView from '../views/LoginView.vue'
import DashboardView from '../views/DashboardView.vue'
import ResourceView from '../views/ResourceView.vue'
import OrderView from '../views/OrderView.vue'
import RefundView from '../views/RefundView.vue'
import VerificationView from '../views/VerificationView.vue'
import UserView from '../views/UserView.vue'
import ContentView from '../views/ContentView.vue'
import ReportView from '../views/ReportView.vue'
import SettingView from '../views/SettingView.vue'
import VehicleView from '../views/VehicleView.vue'
import ScenicView from '../views/ScenicView.vue'
import CulturalStoreView from '../views/CulturalStoreView.vue'
import SportVenueView from '../views/SportVenueView.vue'
import MeetingView from '../views/MeetingView.vue'
import DiningView from '../views/DiningView.vue'
import HotelView from '../views/HotelView.vue'
import LocationPlaceView from '../views/LocationPlaceView.vue'
import DiningDeliveryScopeView from '../views/DiningDeliveryScopeView.vue'
import GateDeviceView from '../views/GateDeviceView.vue'
import VehicleIdentityView from '../views/VehicleIdentityView.vue'
import { useAuthStore } from '../stores/auth'

const router = createRouter({
  history: createWebHistory(),
  routes: [
    { path: '/login', component: LoginView, meta: { public: true } },
    { path: '/', component: AppLayout, children: [
      { path: '', redirect: '/dashboard' },
      { path: 'dashboard', component: DashboardView, meta: { title: '运营概览', permission: 'dashboard.view' } },
      { path: 'resources', component: ResourceView, meta: { title: '资源与库存', permission: 'catalog.manage' } },
      { path: 'orders', component: OrderView, meta: { title: '订单管理', permission: 'orders.view' } },
      { path: 'vehicles', component: VehicleView, meta: { title: '车辆租赁与调度', permission: 'catalog.manage' } },
      { path: 'vehicle-identities', component: VehicleIdentityView, meta: { title: '车辆人员与账号', permission: 'users.manage' } },
      { path: 'scenic', component: ScenicView, meta: { title: '景区票务运营', permission: 'catalog.manage' } },
      { path: 'cultural-store', component: CulturalStoreView, meta: { title: '文创商城', permission: 'catalog.manage' } },
      { path: 'sports', component: SportVenueView, meta: { title: '体育场馆运营', permission: 'catalog.manage' } },
      { path: 'meetings', component: MeetingView, meta: { title: '会议场地运营', permission: 'catalog.manage' } },
      { path: 'dining', component: DiningView, meta: { title: '餐饮预订运营', permission: 'catalog.manage' } },
      { path: 'hotels', component: HotelView, meta: { title: '住宿入住运营', permission: 'catalog.manage' } },
      { path: 'location-places', component: LocationPlaceView, meta: { title: '地点与一键导航', permission: 'catalog.manage' } },
      { path: 'dining-delivery-scopes', component: DiningDeliveryScopeView, meta: { title: '餐饮内部配送范围', permission: 'catalog.manage' } },
      { path: 'gate-devices', component: GateDeviceView, meta: { title: '闸机与门禁设备', permission: 'gate.manage' } },
      { path: 'refunds', component: RefundView, meta: { title: '退款审核', permission: 'refunds.review' } },
      { path: 'verification', component: VerificationView, meta: { title: '电子核销', permission: 'verification.use' } },
      { path: 'users', component: UserView, meta: { title: '用户与会员', permission: 'users.manage' } },
      { path: 'content', component: ContentView, meta: { title: '内容运营', permission: 'content.manage' } },
      { path: 'reports', component: ReportView, meta: { title: '数据报表', permission: 'reports.view' } },
      { path: 'settings', component: SettingView, meta: { title: '系统设置', permission: 'settings.manage' } }
    ] }
  ]
})

router.beforeEach(async (to) => {
  const auth = useAuthStore()
  if (!to.meta.public && !auth.token) return '/login'
  if (auth.token && !auth.validated) {
    try { await auth.restore() } catch { return '/login' }
  }
  if (to.meta.permission && !auth.hasPermission(to.meta.permission)) {
    const first = router.getRoutes().find(route => route.meta.permission && auth.hasPermission(route.meta.permission))
    return first?.path || '/login'
  }
  if (to.path === '/login' && auth.token && auth.validated) return '/dashboard'
})

export default router
