const {
  fetchMallCategories,
  fetchMallProducts,
  fetchMallProductDetail,
  fetchMallCart,
  putMallCartItem,
  removeMallCartItem,
  fetchMallAddresses,
  createMallAddress,
  createMallOrder
} = require('../../services/mallService')
const { createClientRequestId, fenToYuan } = require('../../utils/contract')
const { getMemberProfile, registerPendingMemberPoints } = require('../../services/memberService')

const CATALOG_CACHE_KEY = 'iminiappMallCatalogCacheV2'
const CATALOG_CACHE_TTL = 5 * 60 * 1000

const productPresentation = [
  {
    id: 'industrial-magnet', productCode: 'MALL_MAGNET', category: '特色产品', name: 'i某小程序工业铭牌冰箱贴',
    desc: '以i某小程序工业铭牌为灵感，留住一段工业记忆', price: 29.9, sales: 326, inventory: 88,
    image: '/assets/images/mall/industrial-magnet.jpg',
    tone: 'brass', art: '铭', edition: 'INDUSTRIAL MEMORY', specs: ['黄铜色', '钢灰色'],
    detailSlides: [{ label: '商品正面', art: '铭' }, { label: '金属细节', art: '钢' }, { label: '礼盒包装', art: '礼' }]
  },
  {
    id: 'tank-model', productCode: 'MALL_TANK_MODEL', category: '特色产品', name: '兵器城坦克合金模型',
    desc: '合金车身与精细涂装，适合收藏和桌面陈列', price: 129, sales: 218, inventory: 36,
    image: '/assets/images/mall/tank-model.jpg',
    tone: 'army', art: '坦', edition: 'ARMORED COLLECTION', specs: ['军绿色', '沙漠色'],
    detailSlides: [{ label: '模型全貌', art: '坦' }, { label: '履带细节', art: '履' }, { label: '收藏包装', art: '藏' }]
  },
  {
    id: 'canvas-bag', productCode: 'MALL_CANVAS_BAG', category: '手工艺品', name: 'i某小程序文创帆布袋',
    desc: '厚实帆布结合工业线稿，轻装出发装下热爱', price: 69, sales: 196, inventory: 57,
    image: '/assets/images/mall/canvas-bag.jpg',
    tone: 'brick', art: '袋', edition: 'CARRY THE STORY', specs: ['米白色', '砖红色'],
    detailSlides: [{ label: '整体展示', art: '袋' }, { label: '刺绣工艺', art: '绣' }, { label: '容量展示', art: '容' }]
  },
  {
    id: 'thermal-cup', productCode: 'MALL_THERMAL_CUP', category: '手工艺品', name: '工业风手作保温杯',
    desc: '机械线条与磨砂杯身，把温暖带在身边', price: 129, sales: 154, inventory: 42,
    image: '/assets/images/mall/thermal-cup.jpg',
    tone: 'steel', art: '杯', edition: 'STEEL & WARMTH', specs: ['深空灰 500ml', '墨绿色 500ml'],
    detailSlides: [{ label: '杯身正面', art: '杯' }, { label: '杯盖细节', art: '盖' }, { label: '礼盒组合', art: '盒' }]
  },
  {
    id: 'sea-buckthorn-cake', productCode: 'MALL_SEA_BUCKTHORN', category: '特殊零食', name: 'i某小程序沙棘果糕',
    desc: '酸甜果香与独立小包装，旅途中轻松分享', price: 36.8, sales: 428, inventory: 120,
    image: '/assets/images/mall/sea-buckthorn-cake.jpg',
    tone: 'orange', art: '果', edition: 'LOCAL FLAVOR', specs: ['原味 180g', '混合口味 180g'],
    detailSlides: [{ label: '果糕展示', art: '果' }, { label: '独立包装', art: '装' }, { label: '配料信息', art: '料' }]
  },
  {
    id: 'tea-gift', productCode: 'MALL_TEA_GIFT', category: '特殊零食', name: 'i某小程序茶点伴手礼盒',
    desc: '地方茶点与定制包装，适合作为旅行伴手礼', price: 158, sales: 137, inventory: 31,
    image: '/assets/images/mall/tea-gift.jpg',
    tone: 'olive', art: '礼', edition: 'IMINIAPP GIFT', specs: ['经典礼盒', '臻选礼盒'],
    detailSlides: [{ label: '礼盒外观', art: '礼' }, { label: '茶点组合', art: '点' }, { label: '手提包装', art: '提' }]
  }
]

const money = value => Number(value || 0).toFixed(2).replace(/\.00$/, '')

function mapProduct(detail) {
  const presentation = productPresentation.find(item => item.productCode === detail.productCode) || {}
  const skus = (detail.skus || []).filter(item => item.status === 'ACTIVE')
  const priceCent = skus.length ? Math.min(...skus.map(item => Number(item.priceCent))) : 0
  return {
    ...presentation,
    id: String(detail.id),
    legacyId: presentation.id || '',
    backendId: detail.id,
    productCode: detail.productCode,
    category: detail.categoryName,
    name: detail.name,
    desc: detail.description || '',
    image: detail.coverUrl || presentation.image || '',
    price: Number((priceCent / 100).toFixed(2)),
    sales: Number(detail.salesCount || 0),
    inventory: skus.reduce((sum, item) => sum + Number(item.availableQuantity || 0), 0),
    specs: skus.map(item => item.name),
    skus,
    tone: presentation.tone || 'steel',
    art: presentation.art || '物',
    edition: presentation.edition || 'IMINIAPP SELECT',
    detailSlides: presentation.detailSlides || [{ label: '商品图片', art: presentation.art || '物' }]
  }
}

function mapProductListItem(item) {
  const presentation = productPresentation.find(product => product.productCode === item.productCode) || {}
  return {
    ...presentation,
    id: String(item.id),
    legacyId: presentation.id || '',
    backendId: item.id,
    productCode: item.productCode,
    category: item.categoryName,
    name: item.name,
    desc: item.description || presentation.desc || '',
    image: item.coverUrl || presentation.image || '',
    price: Number((Number(item.minimumPriceCent || 0) / 100).toFixed(2)),
    sales: Number(item.salesCount || 0),
    inventory: Number(item.totalAvailable || 0),
    specs: [],
    skus: [],
    tone: presentation.tone || 'steel',
    art: presentation.art || '物',
    edition: presentation.edition || 'IMINIAPP SELECT',
    detailSlides: presentation.detailSlides || [{ label: '商品图片', art: presentation.art || '物' }],
    detailLoaded: false
  }
}

function addressLabel(address) {
  return `${address.receiverName} ${address.receiverMobile} · ${address.province}${address.city}${address.district}${address.detailAddress}`
}

Page({
  data: {
    statusBarHeight: 20,
    navBarHeight: 47,
    menuSafeRight: 12,
    viewMode: 'list',
    catalogLoading: true, catalogError: '', skeletonItems: [0, 1, 2, 3],
    categories: ['全部'],
    activeCategory: '全部', keyword: '',
    sortOptions: ['综合排序', '价格从低到高', '价格从高到低', '销量优先'], sortIndex: 0,
    products: [], visibleProducts: [],
    currentProduct: null, detailSlide: 0, selectedSpecIndex: 0, selectedSkuPrice: 0, selectedSkuInventory: 0, detailQuantity: 1,
    cartItems: [], cartCount: 0, cartAmountLabel: '0', cartBump: false, addPulseId: '',
    flyVisible: false, flyLeft: 0, flyTop: 0, flyTone: 'brass', flyArt: '', flyAnimation: {},
    productPreviewVisible: false, productPreviewImage: '', productPreviewTitle: '',
    checkoutVisible: false, checkoutSource: '', checkoutItems: [], checkoutQuantity: 0, checkoutAmountLabel: '0',
    checkoutOriginalAmountLabel: '0.00', checkoutDiscountAmountLabel: '0.00', memberBenefit: getMemberProfile(),
    addressRecords: [], shippingAddresses: ['填写新收货地址'], shippingAddressIndex: 0,
    newReceiverName: '', newReceiverMobile: '', newAddressRegion: ['内蒙古自治区', '包头市', '青山区'], newDetailAddress: '',
    checkoutSuccess: false, successOrderNo: '', sharePanelVisible: false, submitting: false
  },
  async onLoad(query = {}) {
    let statusBarHeight = 20
    let navBarHeight = 47
    let menuSafeRight = 12
    try {
      const windowInfo = wx.getWindowInfo
        ? wx.getWindowInfo()
        : (wx.getSystemInfoSync ? wx.getSystemInfoSync() : {})
      statusBarHeight = Number(windowInfo.statusBarHeight) || 20
      if (wx.getMenuButtonBoundingClientRect) {
        const capsule = wx.getMenuButtonBoundingClientRect()
        const windowWidth = Number(windowInfo.windowWidth || windowInfo.screenWidth) || 375
        const capsuleTopGap = Math.max(0, Number(capsule.top) - statusBarHeight)
        navBarHeight = Math.max(44, capsuleTopGap * 2 + Number(capsule.height || 32))
        menuSafeRight = Math.max(12, windowWidth - Number(capsule.left || windowWidth) + 8)
      }
    } catch (error) {}
    this.setData({ statusBarHeight, navBarHeight, menuSafeRight })
    this.productDetailCache = {}
    this.loadAddresses()
    this.loadCart()
    await this.loadCatalog(query.id)
    this.catalogLoaded = true
  },
  onShow() {
    this.setData({ memberBenefit: getMemberProfile() })
    if (this.catalogLoaded) Promise.all([this.loadCart(), this.loadAddresses()])
  },
  syncGlobalCart(count) {
    try {
      const app = typeof getApp === 'function' ? getApp() : null
      if (app && app.globalData) app.globalData.cartCount = count
    } catch (error) {}
  },
  async loadCatalog(requestedId) {
    let cacheApplied = false
    const cached = wx.getStorageSync ? wx.getStorageSync(CATALOG_CACHE_KEY) : null
    if (cached && cached.cachedAt && Date.now() - cached.cachedAt < CATALOG_CACHE_TTL
      && Array.isArray(cached.categories) && Array.isArray(cached.products)) {
      this.applyCatalog(cached.categories, cached.products)
      cacheApplied = true
    }
    this.setData({ catalogLoading: !cacheApplied, catalogError: '' })
    try {
      const [categories, page] = await Promise.all([fetchMallCategories(), fetchMallProducts()])
      const categoryNames = (categories || []).map(item => item.name)
      const products = (page.items || []).map(mapProductListItem)
      this.applyCatalog(categoryNames, products)
      if (wx.setStorageSync) wx.setStorageSync(CATALOG_CACHE_KEY, { cachedAt: Date.now(), categories: categoryNames, products })
      if (requestedId) {
        const product = products.find(item => item.id === String(requestedId)
          || item.productCode === requestedId || item.legacyId === requestedId)
        if (product) await this.openProductById(product.id)
      }
    } catch (error) {
      if (!cacheApplied) this.setData({ products: [], visibleProducts: [], catalogError: error.message || '商品加载失败' })
    } finally {
      this.setData({ catalogLoading: false })
    }
  },

  applyCatalog(categoryNames, products) {
    this.setData({
      categories: ['全部', ...categoryNames],
      products,
      visibleProducts: this.applyFilters(this.data.keyword, this.data.activeCategory, this.data.sortIndex, products)
    })
  },

  retryCatalog() {
    this.loadCatalog()
  },
  async loadCart() {
    try {
      const rows = await fetchMallCart()
      const cartItems = (rows || []).map(row => {
        const product = this.data.products.find(item => item.skus.some(sku => Number(sku.skuId) === Number(row.skuId)))
        return {
          key: String(row.skuId), skuId: row.skuId, productId: product ? product.id : '',
          name: row.productName, spec: row.skuName, price: Number(row.priceCent) / 100,
          quantity: row.quantity, inventory: row.availableQuantity, purchasable: row.purchasable,
          tone: product ? product.tone : 'steel', art: product ? product.art : '物',
          image: row.coverUrl || (product && product.image) || ''
        }
      })
      this.updateCart(cartItems)
    } catch (error) {}
  },
  async loadAddresses() {
    try {
      const addressRecords = await fetchMallAddresses()
      const shippingAddresses = [...addressRecords.map(addressLabel), '填写新收货地址']
      const defaultIndex = Math.max(0, addressRecords.findIndex(item => item.isDefault))
      this.setData({ addressRecords, shippingAddresses, shippingAddressIndex: defaultIndex })
    } catch (error) {}
  },
  updateCart(cartItems) {
    const cartCount = cartItems.reduce((sum, item) => sum + item.quantity, 0)
    const total = cartItems.reduce((sum, item) => sum + item.price * item.quantity, 0)
    this.setData({ cartItems, cartCount, cartAmountLabel: money(total) })
    this.syncGlobalCart(cartCount)
  },
  applyFilters(keyword = this.data.keyword, category = this.data.activeCategory, sortIndex = this.data.sortIndex, source = this.data.products) {
    const normalized = String(keyword || '').trim().toLowerCase()
    const result = source.filter(product => {
      const matchesCategory = category === '全部' || product.category === category
      const searchable = `${product.name}${product.desc}${product.category}`.toLowerCase()
      return matchesCategory && (!normalized || searchable.includes(normalized))
    })
    if (Number(sortIndex) === 1) result.sort((a, b) => a.price - b.price)
    if (Number(sortIndex) === 2) result.sort((a, b) => b.price - a.price)
    if (Number(sortIndex) === 3) result.sort((a, b) => b.sales - a.sales)
    return result
  },
  keywordInput(e) {
    const keyword = e.detail.value
    this.setData({ keyword, visibleProducts: this.applyFilters(keyword, this.data.activeCategory, this.data.sortIndex) })
  },
  clearKeyword() {
    this.setData({ keyword: '', visibleProducts: this.applyFilters('', this.data.activeCategory, this.data.sortIndex) })
  },
  changeCategory(e) {
    const activeCategory = e.currentTarget.dataset.category
    this.setData({ activeCategory, visibleProducts: this.applyFilters(this.data.keyword, activeCategory, this.data.sortIndex) })
  },
  sortChange(e) {
    const sortIndex = Number(e.detail.value)
    this.setData({ sortIndex, visibleProducts: this.applyFilters(this.data.keyword, this.data.activeCategory, sortIndex) })
  },
  openProduct(e) {
    this.openProductById(e.currentTarget.dataset.id)
  },

  async fetchProductOnDemand(productId) {
    const key = String(productId)
    if (this.productDetailCache[key]) return this.productDetailCache[key]
    const detail = await fetchMallProductDetail(productId)
    const product = mapProduct(detail)
    product.detailLoaded = true
    this.productDetailCache[key] = product
    const products = this.data.products.map(item => item.id === key ? product : item)
    this.setData({ products, visibleProducts: this.applyFilters(this.data.keyword, this.data.activeCategory, this.data.sortIndex, products) })
    return product
  },

  async openProductById(productId) {
    wx.showLoading({ title: '加载商品' })
    try {
      const product = await this.fetchProductOnDemand(productId)
      this.showProduct(product)
    } catch (error) {
      wx.showToast({ title: error.message || '商品详情加载失败', icon: 'none' })
    } finally {
      wx.hideLoading()
    }
  },
  showProduct(product) {
    const sku = product.skus[0]
    this.setData({
      viewMode: 'detail', currentProduct: product, detailSlide: 0, selectedSpecIndex: 0, detailQuantity: 1,
      selectedSkuPrice: sku ? Number(sku.priceCent) / 100 : 0,
      selectedSkuInventory: sku ? Number(sku.availableQuantity) : 0
    })
    if (wx.pageScrollTo) wx.pageScrollTo({ scrollTop: 0, duration: 0 })
  },
  detailSlideChange(e) { this.setData({ detailSlide: e.detail.current }) },
  selectSpec(e) {
    const selectedSpecIndex = Number(e.currentTarget.dataset.index)
    const sku = this.data.currentProduct.skus[selectedSpecIndex]
    this.setData({
      selectedSpecIndex, detailQuantity: 1,
      selectedSkuPrice: Number(sku.priceCent) / 100,
      selectedSkuInventory: Number(sku.availableQuantity)
    })
  },
  adjustDetailQuantity(e) {
    const quantity = Math.max(1, Math.min(this.data.selectedSkuInventory, this.data.detailQuantity + Number(e.currentTarget.dataset.delta)))
    this.setData({ detailQuantity: quantity })
  },
  async addToCart(product, specIndex, quantity) {
    const sku = product.skus[specIndex]
    if (!sku || Number(sku.availableQuantity) < 1) {
      wx.showToast({ title: '当前规格库存不足', icon: 'none' })
      return false
    }
    const existing = this.data.cartItems.find(item => Number(item.skuId) === Number(sku.skuId))
    const nextQuantity = Math.min(Number(sku.availableQuantity), Number(existing ? existing.quantity : 0) + quantity)
    try {
      await putMallCartItem({ skuId: sku.skuId, quantity: nextQuantity })
      await this.loadCart()
      this.setData({ addPulseId: product.id, cartBump: true })
      clearTimeout(this.cartAnimationTimer)
      this.cartAnimationTimer = setTimeout(() => this.setData({ addPulseId: '', cartBump: false }), 360)
      wx.showToast({ title: '已加入购物车', icon: 'none' })
      return true
    } catch (error) {
      return false
    }
  },
  async quickAdd(e) {
    const productId = String(e.currentTarget.dataset.id)
    wx.showLoading({ title: '加入购物车' })
    try {
      const product = await this.fetchProductOnDemand(productId)
      if (await this.addToCart(product, 0, 1)) this.flyProductToCart(product)
    } catch (error) {
      wx.showToast({ title: error.message || '加入购物车失败', icon: 'none' })
    } finally {
      wx.hideLoading()
    }
  },
  flyProductToCart(product) {
    if (!wx.createSelectorQuery || !wx.createAnimation) return
    const query = wx.createSelectorQuery().in(this)
    query.select(`#mall-add-${product.id}`).boundingClientRect()
    query.select('.nav-cart').boundingClientRect()
    query.exec(rects => {
      const start = rects && rects[0]
      const target = rects && rects[1]
      if (!start || !target) return
      const size = 42
      const flyLeft = start.left + start.width / 2 - size / 2
      const flyTop = start.top + start.height / 2 - size / 2
      const translateX = target.left + target.width / 2 - (flyLeft + size / 2)
      const translateY = target.top + target.height / 2 - (flyTop + size / 2)
      this.setData({
        flyVisible: true, flyLeft, flyTop, flyTone: product.tone, flyArt: product.art, flyAnimation: {}
      }, () => {
        const animation = wx.createAnimation({ duration: 680, timingFunction: 'ease-in-out' })
        animation.translate(translateX * .48, translateY * .22).scale(.92).rotate(8).step({ duration: 230, timingFunction: 'ease-out' })
        animation.translate(translateX, translateY).scale(.2).rotate(24).opacity(0).step({ duration: 450, timingFunction: 'ease-in' })
        this.setData({ flyAnimation: animation.export() })
      })
      clearTimeout(this.flyAnimationTimer)
      this.flyAnimationTimer = setTimeout(() => this.setData({ flyVisible: false, flyAnimation: {} }), 720)
    })
  },
  async addDetailCart() {
    const product = this.data.currentProduct
    await this.addToCart(product, this.data.selectedSpecIndex, this.data.detailQuantity)
  },
  previewProductImage() {
    const product = this.data.currentProduct
    if (!product || !product.image) return
    this.setData({ productPreviewVisible: true, productPreviewImage: product.image, productPreviewTitle: product.name })
  },
  closeProductPreview() { this.setData({ productPreviewVisible: false }) },
  openCart() {
    this.setData({ viewMode: 'cart', checkoutVisible: false })
    if (wx.pageScrollTo) wx.pageScrollTo({ scrollTop: 0, duration: 0 })
  },
  async adjustCart(e) {
    const key = e.currentTarget.dataset.key
    const delta = Number(e.currentTarget.dataset.delta)
    const item = this.data.cartItems.find(row => row.key === key)
    if (!item) return
    const quantity = Math.max(0, Math.min(item.inventory, item.quantity + delta))
    try {
      if (quantity === 0) await removeMallCartItem(item.skuId)
      else await putMallCartItem({ skuId: item.skuId, quantity })
      await this.loadCart()
    } catch (error) {}
  },
  async removeCartItem(e) {
    const key = e.currentTarget.dataset.key
    const item = this.data.cartItems.find(row => row.key === key)
    if (!item) return
    try {
      await removeMallCartItem(item.skuId)
      await this.loadCart()
    } catch (error) {}
  },
  backPage() {
    if (this.data.viewMode !== 'list') {
      this.setData({ viewMode: 'list', checkoutVisible: false, checkoutSuccess: false })
      if (wx.pageScrollTo) wx.pageScrollTo({ scrollTop: 0, duration: 0 })
      return
    }
    if (wx.switchTab) wx.switchTab({ url: '/pages/index/index' })
  },
  openSharePanel() { this.setData({ sharePanelVisible: true }) },
  closeSharePanel() { this.setData({ sharePanelVisible: false }) },
  preventShareTap() {},
  openDetailCheckout() {
    const product = this.data.currentProduct
    const sku = product.skus[this.data.selectedSpecIndex]
    if (!sku || Number(sku.availableQuantity) < this.data.detailQuantity) {
      wx.showToast({ title: '当前规格库存不足', icon: 'none' })
      return
    }
    this.openCheckout([{
      key: String(sku.skuId), skuId: sku.skuId,
      productId: product.id, name: product.name, price: Number(sku.priceCent) / 100,
      spec: sku.name, quantity: this.data.detailQuantity,
      tone: product.tone, art: product.art, image: product.image
    }], 'detail')
  },
  openCartCheckout() {
    if (!this.data.cartItems.length) {
      wx.showToast({ title: '购物车还是空的', icon: 'none' })
      return
    }
    if (this.data.cartItems.some(item => !item.purchasable)) {
      wx.showToast({ title: '购物车中有下架或库存不足的商品', icon: 'none' })
      return
    }
    this.openCheckout(this.data.cartItems, 'cart')
  },
  openCheckout(items, source) {
    const amount = items.reduce((sum, item) => sum + item.price * item.quantity, 0)
    const memberBenefit = getMemberProfile()
    const checkoutQuantity = items.reduce((sum, item) => sum + item.quantity, 0)
    const checkoutItems = items.map(item => ({ ...item, lineTotalLabel: money(item.price * item.quantity) }))
    this.setData({
      checkoutVisible: true, checkoutSource: source, checkoutItems,
      checkoutQuantity,
      checkoutOriginalAmountLabel: money(amount),
      checkoutDiscountAmountLabel: '0.00',
      checkoutAmountLabel: money(amount),
      memberBenefit,
      shippingAddressIndex: this.data.addressRecords.findIndex(item => item.isDefault) >= 0
        ? this.data.addressRecords.findIndex(item => item.isDefault) : 0
    })
  },
  closeCheckout() { this.setData({ checkoutVisible: false }) },
  shippingAddressChange(e) { this.setData({ shippingAddressIndex: Number(e.detail.value) }) },
  newReceiverNameInput(e) { this.setData({ newReceiverName: e.detail.value }) },
  newReceiverMobileInput(e) { this.setData({ newReceiverMobile: e.detail.value }) },
  newAddressRegionChange(e) { this.setData({ newAddressRegion: e.detail.value }) },
  newDetailAddressInput(e) { this.setData({ newDetailAddress: e.detail.value }) },
  async submitCheckout() {
    if (this.data.submitting) return
    let selectedAddress = this.data.addressRecords[this.data.shippingAddressIndex]
    try {
      this.setData({ submitting: true })
      if (!selectedAddress) {
        const receiverName = this.data.newReceiverName.trim()
        const receiverMobile = this.data.newReceiverMobile.trim()
        const detailAddress = this.data.newDetailAddress.trim()
        if (!receiverName || !/^1\d{10}$/.test(receiverMobile) || !detailAddress) {
          wx.showToast({ title: '请填写收货人、11位手机号和详细地址', icon: 'none' })
          return
        }
        selectedAddress = await createMallAddress({
          receiverName,
          receiverMobile,
          province: this.data.newAddressRegion[0],
          city: this.data.newAddressRegion[1],
          district: this.data.newAddressRegion[2],
          detailAddress,
          postalCode: '',
          isDefault: this.data.addressRecords.length === 0
        })
        await this.loadAddresses()
      }
      const result = await createMallOrder({
        clientRequestId: createClientRequestId('mall_order'),
        addressId: selectedAddress.id,
        items: this.data.checkoutItems.map(item => ({
          skuId: item.skuId,
          quantity: item.quantity
        })),
        remark: '微信小程序商城订单'
      })
      const order = result.order
      registerPendingMemberPoints(order.payableAmountCent, String(order.id), '商城订单完成获得积分')
      await Promise.all([this.loadCart(), this.loadCatalog()])
      this.setData({
        checkoutVisible: false,
        checkoutSuccess: true,
        successOrderNo: order.orderNo || String(order.id),
        checkoutAmountLabel: fenToYuan(order.payableAmountCent)
      })
    } catch (error) {
    } finally {
      this.setData({ submitting: false })
    }
  },
  closeSuccess() {
    this.setData({ checkoutSuccess: false, viewMode: 'list' })
    if (wx.pageScrollTo) wx.pageScrollTo({ scrollTop: 0, duration: 0 })
  },
  preventOverlayTap() {},
  onUnload() { clearTimeout(this.cartAnimationTimer); clearTimeout(this.flyAnimationTimer) },
  onShareAppMessage() {
    if (this.data.viewMode === 'detail') {
      return { title: `${this.data.currentProduct.name}｜i某小程序特色商城`, path: `/pages/mall/mall?id=${this.data.currentProduct.id}` }
    }
    return { title: 'i某小程序特色商城｜把工业记忆带回家', path: '/pages/mall/mall' }
  }
})
