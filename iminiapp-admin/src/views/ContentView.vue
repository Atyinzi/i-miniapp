<script setup>
import { onMounted, ref } from 'vue'
import {
  createArticle, createBanner, getArticles, getBanners,
  updateArticle, updateArticleStatus, updateBanner, updateBannerEnabled
} from '../services/content'

const tab = ref('articles')
const loading = ref(false)
const saving = ref(false)
const error = ref('')
const articles = ref([])
const banners = ref([])
const articleTotal = ref(0)
const articlePages = ref(0)
const articlePage = ref(1)
const keyword = ref('')
const articleType = ref('')
const articleStatus = ref('')
const articleForm = ref(null)
const bannerForm = ref(null)

async function loadArticles(resetPage = false) {
  if (resetPage) articlePage.value = 1
  try {
    loading.value = true
    error.value = ''
    const data = await getArticles({
      keyword: keyword.value || undefined,
      articleType: articleType.value || undefined,
      status: articleStatus.value || undefined,
      page: articlePage.value,
      pageSize: 20
    })
    articles.value = data.items || []
    articleTotal.value = data.total || 0
    articlePages.value = data.totalPages || 0
  } catch (e) { error.value = e.message } finally { loading.value = false }
}

async function loadBanners() {
  try {
    loading.value = true
    error.value = ''
    banners.value = await getBanners()
  } catch (e) { error.value = e.message } finally { loading.value = false }
}

function switchTab(value) {
  tab.value = value
  error.value = ''
  if (value === 'articles') loadArticles()
  else loadBanners()
}

function editArticle(item = null) {
  articleForm.value = item ? { ...item } : {
    id: null, articleType: 'NEWS', title: '', summary: '', coverUrl: '',
    content: '', status: 'DRAFT', pinned: false
  }
}

async function saveArticle() {
  const form = articleForm.value
  if (!form.title.trim() || !form.content.trim()) {
    error.value = '标题和正文不能为空'
    return
  }
  try {
    saving.value = true
    error.value = ''
    const payload = {
      articleType: form.articleType, title: form.title.trim(), summary: form.summary?.trim() || null,
      coverUrl: form.coverUrl?.trim() || null, content: form.content.trim(),
      status: form.status, pinned: Boolean(form.pinned)
    }
    if (form.id) await updateArticle(form.id, payload)
    else await createArticle(payload)
    articleForm.value = null
    await loadArticles(true)
  } catch (e) { error.value = e.message } finally { saving.value = false }
}

async function changeArticleStatus(item) {
  const next = item.status === 'PUBLISHED' ? 'OFFLINE' : 'PUBLISHED'
  if (!confirm(`确认${next === 'PUBLISHED' ? '发布' : '下线'}“${item.title}”吗？`)) return
  try {
    loading.value = true
    error.value = ''
    await updateArticleStatus(item.id, next)
    await loadArticles()
  } catch (e) { error.value = e.message } finally { loading.value = false }
}

function editBanner(item = null) {
  bannerForm.value = item ? {
    ...item, startAt: inputDate(item.startAt), endAt: inputDate(item.endAt)
  } : {
    id: null, title: '', imageUrl: '', targetType: 'NONE', targetValue: '',
    startAt: '', endAt: '', sortOrder: 0, enabled: true
  }
}

async function saveBanner() {
  const form = bannerForm.value
  if (!form.title.trim() || !form.imageUrl.trim()) {
    error.value = '轮播标题和图片地址不能为空'
    return
  }
  try {
    saving.value = true
    error.value = ''
    const payload = {
      title: form.title.trim(), imageUrl: form.imageUrl.trim(), targetType: form.targetType,
      targetValue: form.targetType === 'NONE' ? null : form.targetValue?.trim() || null,
      startAt: form.startAt || null, endAt: form.endAt || null,
      sortOrder: Number(form.sortOrder || 0), enabled: Boolean(form.enabled)
    }
    if (form.id) await updateBanner(form.id, payload)
    else await createBanner(payload)
    bannerForm.value = null
    await loadBanners()
  } catch (e) { error.value = e.message } finally { saving.value = false }
}

async function toggleBanner(item) {
  try {
    loading.value = true
    error.value = ''
    await updateBannerEnabled(item.id, !item.enabled)
    await loadBanners()
  } catch (e) { error.value = e.message } finally { loading.value = false }
}

async function turnArticlePage(next) {
  if (next < 1 || next > articlePages.value || next === articlePage.value) return
  articlePage.value = next
  await loadArticles()
}

function inputDate(value) { return value ? value.slice(0, 16) : '' }
function date(value) { return value?.replace('T', ' ') || '-' }
function articleTypeText(value) { return ({ NEWS: '资讯', NOTICE: '公告', ACTIVITY: '活动', TRAVEL_GUIDE: '攻略' })[value] || value }
function articleStatusText(value) { return ({ DRAFT: '草稿', PUBLISHED: '已发布', OFFLINE: '已下线' })[value] || value }
function targetText(item) { return item.targetType === 'NONE' ? '无跳转' : `${item.targetType} · ${item.targetValue || '-'}` }

onMounted(() => Promise.all([loadArticles(), loadBanners()]))
</script>

<template>
  <div>
    <div class="toolbar"><div><h2>内容运营</h2><p>维护小程序资讯、公告、活动、攻略和首页轮播投放。</p></div><button @click="tab === 'articles' ? editArticle() : editBanner()">{{ tab === 'articles' ? '新建内容' : '新建轮播' }}</button></div>
    <div class="content-tabs"><button :class="{ active: tab === 'articles' }" @click="switchTab('articles')">文章内容</button><button :class="{ active: tab === 'banners' }" @click="switchTab('banners')">首页轮播</button></div>
    <p v-if="error" class="error">{{ error }}</p>

    <template v-if="tab === 'articles'">
      <div class="filters"><input v-model.trim="keyword" placeholder="标题或摘要" @keyup.enter="loadArticles(true)" /><select v-model="articleType"><option value="">全部类型</option><option value="NEWS">资讯</option><option value="NOTICE">公告</option><option value="ACTIVITY">活动</option><option value="TRAVEL_GUIDE">攻略</option></select><select v-model="articleStatus"><option value="">全部状态</option><option value="DRAFT">草稿</option><option value="PUBLISHED">已发布</option><option value="OFFLINE">已下线</option></select><button class="primary" @click="loadArticles(true)">查询</button></div>
      <div class="panel table-wrap"><table><thead><tr><th>标题</th><th>类型</th><th>状态</th><th>置顶</th><th>发布时间</th><th>更新时间</th><th>操作</th></tr></thead><tbody><tr v-if="loading"><td colspan="7">加载中...</td></tr><tr v-else-if="!articles.length"><td colspan="7">暂无内容，可以先新建一篇草稿。</td></tr><tr v-for="item in articles" :key="item.id"><td><b>{{ item.title }}</b><small>{{ item.summary || '无摘要' }}</small></td><td>{{ articleTypeText(item.articleType) }}</td><td><span class="badge" :class="{ draft: item.status === 'DRAFT', inactive: item.status === 'OFFLINE' }">{{ articleStatusText(item.status) }}</span></td><td>{{ item.pinned ? '是' : '否' }}</td><td>{{ date(item.publishedAt) }}</td><td>{{ date(item.updatedAt) }}</td><td><div class="actions"><button @click="editArticle(item)">编辑</button><button @click="changeArticleStatus(item)">{{ item.status === 'PUBLISHED' ? '下线' : '发布' }}</button></div></td></tr></tbody></table><footer class="pagination"><span>共 {{ articleTotal }} 条，第 {{ articlePage }} / {{ articlePages || 1 }} 页</span><div><button :disabled="articlePage <= 1" @click="turnArticlePage(articlePage - 1)">上一页</button><button :disabled="articlePage >= articlePages" @click="turnArticlePage(articlePage + 1)">下一页</button></div></footer></div>
    </template>

    <template v-else>
      <div class="content-help">轮播图片当前填写可公开访问的图片 URL；对象存储接入后可替换为上传控件。</div>
      <div class="panel table-wrap"><table><thead><tr><th>轮播</th><th>跳转目标</th><th>投放时间</th><th>排序</th><th>状态</th><th>操作</th></tr></thead><tbody><tr v-if="loading"><td colspan="6">加载中...</td></tr><tr v-else-if="!banners.length"><td colspan="6">暂无轮播，可以新建第一条投放内容。</td></tr><tr v-for="item in banners" :key="item.id"><td><b>{{ item.title }}</b><small>{{ item.imageUrl }}</small></td><td>{{ targetText(item) }}</td><td>{{ date(item.startAt) }}<small>至 {{ date(item.endAt) }}</small></td><td>{{ item.sortOrder }}</td><td><span class="badge" :class="{ inactive: !item.enabled }">{{ item.enabled ? '已启用' : '已停用' }}</span></td><td><div class="actions"><button @click="editBanner(item)">编辑</button><button @click="toggleBanner(item)">{{ item.enabled ? '停用' : '启用' }}</button></div></td></tr></tbody></table><footer>共 {{ banners.length }} 条轮播</footer></div>
    </template>

    <div v-if="articleForm" class="modal-backdrop" @click.self="articleForm = null"><form class="modal modal-wide panel content-form" @submit.prevent="saveArticle"><div class="modal-header"><div><h3>{{ articleForm.id ? '编辑内容' : '新建内容' }}</h3><p>建议先保存草稿，校对后再发布。</p></div><button type="button" class="text-button" @click="articleForm = null">×</button></div><div class="form-grid"><label>内容类型<select v-model="articleForm.articleType"><option value="NEWS">资讯</option><option value="NOTICE">公告</option><option value="ACTIVITY">活动</option><option value="TRAVEL_GUIDE">攻略</option></select></label><label>状态<select v-model="articleForm.status"><option value="DRAFT">草稿</option><option value="PUBLISHED">立即发布</option><option value="OFFLINE">下线</option></select></label><label class="span-two">标题<input v-model="articleForm.title" maxlength="200" required /></label><label class="span-two">摘要<textarea v-model="articleForm.summary" rows="2" maxlength="500" /></label><label class="span-two">封面图片 URL<input v-model="articleForm.coverUrl" maxlength="512" placeholder="https://..." /></label><label class="span-two">正文<textarea v-model="articleForm.content" rows="12" maxlength="200000" required /></label><label class="check-label"><input v-model="articleForm.pinned" type="checkbox" /> 在同类型内容中置顶</label></div><div class="modal-actions"><button type="button" @click="articleForm = null">取消</button><button class="primary" :disabled="saving">{{ saving ? '保存中...' : '保存内容' }}</button></div></form></div>

    <div v-if="bannerForm" class="modal-backdrop" @click.self="bannerForm = null"><form class="modal panel content-form" @submit.prevent="saveBanner"><div class="modal-header"><div><h3>{{ bannerForm.id ? '编辑轮播' : '新建轮播' }}</h3><p>配置首页展示图片、跳转目标和有效期。</p></div><button type="button" class="text-button" @click="bannerForm = null">×</button></div><div class="form-grid"><label class="span-two">标题<input v-model="bannerForm.title" maxlength="100" required /></label><label class="span-two">图片 URL<input v-model="bannerForm.imageUrl" maxlength="512" placeholder="https://..." required /></label><label>跳转类型<select v-model="bannerForm.targetType"><option value="NONE">无跳转</option><option value="PATH">小程序路径</option><option value="RESOURCE">资源</option><option value="ARTICLE">文章</option><option value="URL">网页链接</option></select></label><label>跳转值<input v-model="bannerForm.targetValue" :disabled="bannerForm.targetType === 'NONE'" maxlength="255" /></label><label>开始时间<input v-model="bannerForm.startAt" type="datetime-local" /></label><label>结束时间<input v-model="bannerForm.endAt" type="datetime-local" /></label><label>排序<input v-model.number="bannerForm.sortOrder" type="number" min="0" max="9999" /></label><label class="check-label"><input v-model="bannerForm.enabled" type="checkbox" /> 立即启用</label></div><div class="modal-actions"><button type="button" @click="bannerForm = null">取消</button><button class="primary" :disabled="saving">{{ saving ? '保存中...' : '保存轮播' }}</button></div></form></div>
  </div>
</template>
