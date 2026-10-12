<script setup>
import { ref } from 'vue'
import http from '../services/http'
const code = ref(''), result = ref(null), error = ref(''), loading = ref(false)
async function consume() { try { loading.value = true; error.value = ''; result.value = await http.post('/admin/verifications/consume', { code: code.value.trim() }) } catch (e) { error.value = e.message } finally { loading.value = false } }
</script>
<template><div class="verify-page"><div class="toolbar"><div><h2>电子核销</h2><p>工作人员输入或扫码获取电子票核销码，核销操作将由后端审计记录。</p></div></div><section class="verify-card panel"><div class="ticket">✓</div><h3>核销电子票</h3><p>请确认用户出示的电子票信息后再核销，重复核销将被后端拦截。</p><form @submit.prevent="consume"><input v-model.trim="code" placeholder="请输入或粘贴核销码，例如 BFHV-..." /><button class="primary" :disabled="!code || loading">{{ loading ? '核销中...' : '确认核销' }}</button></form><p v-if="error" class="error">{{ error }}</p><div v-if="result" class="success">核销成功：{{ result.verificationId || result.id || '已记录' }}</div></section></div></template>
