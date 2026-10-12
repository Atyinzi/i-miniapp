import { defineStore } from 'pinia'
import { getCurrentAdmin, loginAsAdmin, logoutAdmin } from '../services/auth'

function savedProfile() {
  try {
    return JSON.parse(localStorage.getItem('bf_admin_profile') || 'null')
  } catch {
    localStorage.removeItem('bf_admin_profile')
    return null
  }
}

export const useAuthStore = defineStore('auth', {
  state: () => ({
    token: localStorage.getItem('bf_admin_token') || '',
    profile: savedProfile(),
    validated: false
  }),
  actions: {
    async login(username, password) {
      const data = await loginAsAdmin(username, password)
      if (!data?.accessToken) throw new Error('登录响应缺少 accessToken')
      this.token = data.accessToken
      this.profile = data.user || data
      localStorage.setItem('bf_admin_token', this.token)
      localStorage.setItem('bf_admin_profile', JSON.stringify(this.profile))
      return this.profile
    },
    async restore() {
      if (!this.token) return null
      try {
        this.profile = await getCurrentAdmin()
        this.validated = true
        localStorage.setItem('bf_admin_profile', JSON.stringify(this.profile))
        return this.profile
      } catch (error) {
        this.clear()
        throw error
      }
    },
    hasPermission(permission) {
      return this.profile?.permissions?.includes(permission) || false
    },
    clear() {
      this.token = ''
      this.profile = null
      this.validated = false
      localStorage.removeItem('bf_admin_token')
      localStorage.removeItem('bf_admin_profile')
    },
    async logout() {
      try {
        if (this.token) await logoutAdmin()
      } finally {
        this.clear()
      }
    }
  }
})
