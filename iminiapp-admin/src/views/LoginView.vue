<script setup>
import { ref } from 'vue'
import { useRouter } from 'vue-router'
import { useAuthStore } from '../stores/auth'

const username = ref('')
const password = ref('')
const loading = ref(false)
const error = ref('')
const router = useRouter()
const auth = useAuthStore()

async function submit() {
  try {
    loading.value = true
    error.value = ''
    await auth.login(username.value, password.value)
    await router.replace('/dashboard')
  } catch (e) {
    error.value = e.message
  } finally {
    loading.value = false
  }
}
</script>

<template>
  <main class="login">
    <section>
      <div class="login-brand"><span>北</span><div><h1>i某小程序运营管理平台</h1><p>北方文旅产业服务平台</p></div></div>
      <form @submit.prevent="submit">
        <h2>欢迎登录</h2>
        <p>使用管理员账号进入运营管理后台</p>
        <label>用户名<input v-model.trim="username" maxlength="64" autocomplete="username" required placeholder="请输入管理员用户名" /></label>
        <label>密码<input v-model="password" type="password" maxlength="128" autocomplete="current-password" required placeholder="请输入密码" /></label>
        <p v-if="error" class="error">{{ error }}</p>
        <button :disabled="loading || !username || !password">{{ loading ? '登录中...' : '登录管理平台' }}</button>
        <small>连续登录失败会临时锁定账号；请勿与他人共享管理员账号。</small>
      </form>
    </section>
  </main>
</template>
