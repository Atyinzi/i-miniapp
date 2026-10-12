const FAVORITE_STORAGE_KEY = 'iminiappInformationFavorites'

const articles = [
  {
    id: 'platform-update',
    category: 'i某小程序动态',
    title: 'i某小程序产业服务平台服务功能持续完善',
    date: '2026-08-03',
    author: 'i某小程序产业服务平台',
    summary: '住宿、餐饮、景区、车队及休闲场馆服务已完成统一整合，为游客提供更清晰的一站式服务入口。',
    cover: '/assets/images/houshan/mountain-twin-room.jpg',
    images: ['/assets/images/houshan/mountain-twin-room.jpg'],
    body: [
      'i某小程序产业服务平台围绕游客出行、住宿、游玩与消费需求，持续完善线上服务流程。用户可在首页快捷进入示例宾馆、示例景区、车队预约、休闲场馆及后山住宿等核心服务。',
      '平台近期完成餐饮服务汇总、住宿房态筛选、订单退款、车辆接单进度及休闲场馆门票购买等功能优化，减少跨页面查找成本。',
      '后续平台将根据实际运营数据逐步接入库存、订单状态和消息通知接口，页面展示内容以各服务单位最新公告为准。'
    ]
  },
  {
    id: 'hotel-service',
    category: '场馆介绍',
    title: '示例宾馆会议与住宿预约服务焕新',
    date: '2026-08-01',
    author: '示例宾馆',
    summary: '会议室、客房和餐饮服务统一展示，支持按日期筛选房态、查看剩余房间并在线提交预订。',
    cover: '/assets/images/meetings/business-meeting-main.jpg',
    images: ['/assets/images/meetings/business-meeting-main.jpg', '/assets/images/rooms/elegant-king-main.jpg'],
    body: [
      '示例宾馆详情页现已汇总会议展示、酒店预订和餐饮服务。会议室信息包含容纳人数、设备、照片与预约入口，便于团队客户提前规划。',
      '住宿预订支持选择入住及离店日期，并展示可订状态和剩余房量。房间图片可在页面内查看大图，避免跳转加载带来的等待。',
      '餐饮板块提供雅间预订、提前点餐和宾馆场内配送服务，具体营业时间及可订状态请以提交订单时页面显示为准。'
    ]
  },
  {
    id: 'vehicle-guide',
    category: '通知公告',
    title: '车队预约车型及服务流程说明',
    date: '2026-07-31',
    author: 'i某小程序车队服务中心',
    summary: '车队预约支持多种车型与用车时段，订单可查看司机接单、服务进行和服务结束状态。',
    cover: '/assets/images/weapon-city/tank-ride.jpg',
    images: [],
    body: [
      '车队预约服务现支持小轿车、商务车、越野车及多种座位数客车。用户可选择用车日期、时间、服务类型和乘车人数后提交预约。',
      '预约成功后可在订单中查看司机接单、前往服务、服务进行中及服务结束等状态。未进入服务阶段的订单可按页面规则申请取消。',
      '车辆价格、超时费和超公里费用以预约页面展示及最终确认结果为准。如行程发生变化，请尽早通过订单入口处理。'
    ]
  },
  {
    id: 'weapon-city-experience',
    category: '活动资讯',
    title: '示例景区军事体验项目开放预约',
    date: '2026-07-30',
    author: '示例景区',
    summary: '坦克试乘、迫击炮试射、步枪模拟射击及军事体能体验项目现可在线查看与预约。',
    cover: '/assets/images/weapon-city/tank-ride.jpg',
    images: ['/assets/images/weapon-city/tank-ride.jpg', '/assets/images/weapon-city/rifle-simulation.jpg'],
    body: [
      '示例景区军事体验板块已完善项目介绍与预约入口，游客可查看体验时长、参与要求及安全提示。',
      '部分体验项目对参与年龄、身体状况和现场装备有明确要求，请在购买前认真阅读项目说明，并服从工作人员现场安排。',
      '项目开放时间可能受天气、设备维护和现场客流影响，最终安排以订单确认及场馆通知为准。'
    ]
  },
  {
    id: 'houshan-lodging',
    category: '场馆介绍',
    title: '后山住宿山居体验服务上线',
    date: '2026-07-29',
    author: '后山住宿服务中心',
    summary: '山景客房、会议展示和餐饮预订统一上线，提供更安静舒适的山居住宿选择。',
    cover: '/assets/images/houshan/mountain-twin-room.jpg',
    images: ['/assets/images/houshan/mountain-twin-room.jpg'],
    body: [
      '后山住宿以山林景观与舒适居住体验为核心，页面提供山景客房展示、日期筛选、剩余房量和在线预订服务。',
      '住宿区域同步提供会议展示及餐饮预订，餐饮服务包含用餐地址、日期、时段和提前点餐选择。后山住宿暂不提供宾馆场内餐饮配送。',
      '房间照片支持页面内大图预览，实际房型、景观和配套以入住当天安排为准。'
    ]
  },
  {
    id: 'leisure-venues',
    category: '场馆介绍',
    title: '游泳馆与体育馆预约服务指南',
    date: '2026-07-28',
    author: 'i某小程序休闲场馆',
    summary: '休闲场馆集中展示游泳馆和体育馆信息，体育馆门票支持选择日期和数量后购买。',
    cover: '/assets/images/leisure/sports-arena.jpg',
    images: ['/assets/images/leisure/swimming-pool.jpg', '/assets/images/leisure/sports-arena.jpg'],
    body: [
      '休闲场馆页面集中介绍游泳馆和体育馆的开放服务、设施信息和注意事项。用户可通过场馆卡片查看详情及图片。',
      '体育馆门票支持选择入场日期和购买数量，提交订单后可在“我的待使用”中查看凭证并按规则申请退款。',
      '场馆开放时间可能因赛事、维护或临时活动调整，出行前请留意平台最新通知。'
    ]
  },
  {
    id: 'dining-service',
    category: 'i某小程序动态',
    title: '两地餐饮服务完成统一汇总',
    date: '2026-07-27',
    author: 'i某小程序产业服务平台',
    summary: '示例宾馆餐饮与后山住宿餐饮已汇总至统一入口，可按用餐地点切换对应服务。',
    cover: '/assets/images/dining/banquet-private-room.jpg',
    images: ['/assets/images/dining/banquet-private-room.jpg', '/assets/images/dining/elegant-private-room.jpg'],
    body: [
      '平台餐饮模块已汇总示例宾馆与后山住宿两处餐饮服务。用户进入页面后可先选择用餐地点，再查看对应雅间、用餐地址和可预订时段。',
      '示例宾馆提供雅间预订、提前点餐及场内配送；后山住宿提供餐饮预订与提前点餐。不同地点的订单将分别标注来源，便于后续核销。',
      '配送服务当前仅限示例宾馆会议室、客房和公共区域，具体配送范围以页面说明为准。'
    ]
  },
  {
    id: 'service-notice',
    category: '通知公告',
    title: '平台服务信息更新说明',
    date: '2026-07-26',
    author: '平台运营中心',
    summary: '库存、开放时间及价格信息将根据运营情况更新，提交订单前请再次确认页面提示。',
    cover: '/assets/images/rooms/industrial-twin-main.jpg',
    images: [],
    body: [
      '平台展示的房量、票务库存、车辆排期和场馆开放时间将根据实际运营情况更新。用户提交订单前，请再次确认日期、数量、价格和退款规则。',
      '如遇设备维护、天气变化或临时活动，部分服务可能暂停或调整。已经购买的订单将通过订单状态或平台公告同步处理信息。',
      '如需帮助，可通过平台底部客服电话或意见反馈入口联系服务人员。'
    ]
  }
]

const normalizeIndex = value => {
  const index = Number(value)
  return Number.isInteger(index) && index >= 0 && index < articles.length ? index : -1
}

Page({
  data: {
    statusBarHeight: 20,
    viewMode: 'list',
    categories: ['全部', 'i某小程序动态', '场馆介绍', '活动资讯', '通知公告'],
    activeCategory: '全部',
    keyword: '',
    articles,
    visibleArticles: articles,
    currentArticle: articles[0],
    favoriteIds: [],
    previewVisible: false,
    previewImage: '',
    sharePanelVisible: false
  },
  onLoad(query = {}) {
    let statusBarHeight = 20
    try {
      const system = wx.getSystemInfoSync && wx.getSystemInfoSync()
      statusBarHeight = system && system.statusBarHeight ? system.statusBarHeight : 20
    } catch (error) {}
    const stored = wx.getStorageSync ? wx.getStorageSync(FAVORITE_STORAGE_KEY) : []
    const favoriteIds = Array.isArray(stored) ? stored : []
    this.setData({ statusBarHeight, favoriteIds })
    const sharedArticle = query.id ? articles.find(article => article.id === query.id) : null
    if (sharedArticle) {
      this.showArticle(sharedArticle)
      return
    }
    const index = normalizeIndex(query.index)
    if (index >= 0) this.showArticle(articles[index])
  },
  filterArticles(keyword = this.data.keyword, category = this.data.activeCategory) {
    const normalized = String(keyword || '').trim().toLowerCase()
    return this.data.articles.filter(article => {
      const categoryMatched = category === '全部' || article.category === category
      const searchable = `${article.title}${article.summary}${article.author}${article.category}`.toLowerCase()
      return categoryMatched && (!normalized || searchable.includes(normalized))
    })
  },
  keywordInput(e) {
    const keyword = e.detail.value
    this.setData({ keyword, visibleArticles: this.filterArticles(keyword, this.data.activeCategory) })
  },
  clearKeyword() {
    this.setData({ keyword: '', visibleArticles: this.filterArticles('', this.data.activeCategory) })
  },
  chooseCategory(e) {
    const activeCategory = e.currentTarget.dataset.category
    this.setData({ activeCategory, visibleArticles: this.filterArticles(this.data.keyword, activeCategory) })
  },
  openArticle(e) {
    const article = this.data.articles.find(item => item.id === e.currentTarget.dataset.id)
    if (article) this.showArticle(article)
  },
  showArticle(article) {
    this.setData({
      viewMode: 'detail',
      currentArticle: { ...article, isFavorite: this.data.favoriteIds.includes(article.id) }
    })
    if (wx.pageScrollTo) wx.pageScrollTo({ scrollTop: 0, duration: 0 })
  },
  navBack() {
    if (this.data.viewMode === 'detail') {
      this.setData({ viewMode: 'list', previewVisible: false })
      if (wx.pageScrollTo) wx.pageScrollTo({ scrollTop: 0, duration: 0 })
      return
    }
    const pages = typeof getCurrentPages === 'function' ? getCurrentPages() : []
    if (pages.length > 1 && wx.navigateBack) wx.navigateBack()
    else if (wx.switchTab) wx.switchTab({ url: '/pages/index/index' })
  },
  toggleFavorite() {
    const id = this.data.currentArticle.id
    const exists = this.data.favoriteIds.includes(id)
    const favoriteIds = exists ? this.data.favoriteIds.filter(item => item !== id) : [...this.data.favoriteIds, id]
    this.setData({ favoriteIds, 'currentArticle.isFavorite': !exists })
    if (wx.setStorageSync) wx.setStorageSync(FAVORITE_STORAGE_KEY, favoriteIds)
    wx.showToast({ title: exists ? '已取消收藏' : '已收藏', icon: 'none' })
  },
  previewArticleImage(e) {
    this.setData({ previewVisible: true, previewImage: e.currentTarget.dataset.url })
  },
  closePreview() {
    this.setData({ previewVisible: false, previewImage: '' })
  },
  preventPreviewTap() {},
  openSharePanel() { this.setData({ sharePanelVisible: true }) },
  closeSharePanel() { this.setData({ sharePanelVisible: false }) },
  onShareAppMessage() {
    if (this.data.viewMode === 'detail') {
      return { title: this.data.currentArticle.title, path: `/pages/notices/notices?id=${this.data.currentArticle.id}` }
    }
    return { title: 'i某小程序资讯｜文旅动态与服务公告', path: '/pages/notices/notices' }
  },
  onShareTimeline() {
    return { title: this.data.viewMode === 'detail' ? this.data.currentArticle.title : 'i某小程序资讯' }
  }
})
