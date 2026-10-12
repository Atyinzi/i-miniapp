<script setup>
import { computed, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useAuthStore } from '../stores/auth'

const route = useRoute()
const router = useRouter()
const auth = useAuthStore()
const collapsed = ref(false)
const items = [
  ['◆', '运营概览', '/dashboard', 'dashboard.view'],
  ['◆', '资源与库存', '/resources', 'catalog.manage'],
  ['◆', '订单管理', '/orders', 'orders.view'],
  ['景', '景区票务运营', '/scenic', 'catalog.manage'],
  ['创', '文创商城', '/cultural-store', 'catalog.manage'],
  ['体', '体育场馆运营', '/sports', 'catalog.manage'],
  ['会', '会议场地运营', '/meetings', 'catalog.manage'],
  ['餐', '餐饮预订运营', '/dining', 'catalog.manage'],
  ['住', '住宿入住运营', '/hotels', 'catalog.manage'],
  ['导', '地点与一键导航', '/location-places', 'catalog.manage'],
  ['配', '餐饮内部配送范围', '/dining-delivery-scopes', 'catalog.manage'],
  ['闸', '闸机与门禁设备', '/gate-devices', 'gate.manage'],
  ['▣', '车辆租赁与调度', '/vehicles', 'catalog.manage'],
  ['人', '车辆人员与账号', '/vehicle-identities', 'users.manage'],
  ['◆', '退款审核', '/refunds', 'refunds.review'],
  ['◆', '电子核销', '/verification', 'verification.use'],
  ['◆', '用户与会员', '/users', 'users.manage'],
  ['◆', '内容运营', '/content', 'content.manage'],
  ['◆', '数据报表', '/reports', 'reports.view'],
  ['⚙', '系统设置', '/settings', 'settings.manage']
]
const visibleItems = computed(() => items.filter(item => auth.hasPermission(item[3])))
const title = computed(() => route.meta.title || 'i某小程序运营管理平台')
async function logout() { await auth.logout(); router.push('/login') }
</script>

<template>
  <div class="shell" :class="{ collapsed }">
    <aside class="sidebar"><div class="logo"><span>北</span><b v-if="!collapsed">i某小程序运营</b></div>
      <nav><RouterLink v-for="item in visibleItems" :key="item[2]" :to="item[2]"><i>{{ item[0] }}</i><span v-if="!collapsed">{{ item[1] }}</span></RouterLink></nav>
      <button class="collapse" @click="collapsed = !collapsed">{{ collapsed ? '展开' : '收起' }}</button>
    </aside>
    <main><header><div><h1>{{ title }}</h1><p>北方文旅产业服务平台</p></div><div class="operator"><span class="avatar">管</span><span>{{ auth.profile?.displayName || '管理员' }}</span><button @click="logout">退出</button></div></header><section class="content"><RouterView /></section></main>
  </div>
</template>
