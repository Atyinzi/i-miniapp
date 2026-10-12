const {
  DELIVERY_STATUS_FLOW,
  fetchDiningStores,
  fetchDiningTables,
  fetchDiningCategories,
  fetchDiningDishes,
  createDiningReservation,
  createDeliveryOrder,
  getDeliveryOrder,
  cancelDeliveryOrder
} = require('../../services/diningService')
const { createClientRequestId, formatDateTime, yuanToFen, fenToYuan } = require('../../utils/contract')
const { getMemberProfile, registerPendingMemberPoints, settleMemberPoints, reverseMemberPoints } = require('../../services/memberService')

const DELIVERY_ORDER_STORAGE_KEY = 'iminiappDiningDeliveryOrder'

const rooms=[
  {name:'雅韵小雅间',size:'4人桌',capacity:4,facilities:'茶台 · 独立卫生间',image:'/assets/images/dining/elegant-private-room.jpg'},
  {name:'i某小程序雅间',size:'6人桌',capacity:6,facilities:'独立卫生间 · 茶台 · 投影',image:'/assets/images/dining/iminiapp-private-room.jpg'},
  {name:'宾朋宴会雅间',size:'10人桌以上',capacity:12,facilities:'投影 · 音响 · 独立卫生间',image:'/assets/images/dining/banquet-private-room.jpg'}
]
const reserveFoods=[
  {id:'r1',category:'凉菜',name:'i某小程序什锦凉菜',intro:'清爽开胃，时令搭配',price:38,icon:'凉',tone:'green',qty:0},
  {id:'r2',category:'凉菜',name:'老醋花生',intro:'酸甜爽脆，经典佐餐',price:22,icon:'醋',tone:'gold',qty:0},
  {id:'r3',category:'热菜',name:'北方红烧肉',intro:'慢火焖制，软糯入味',price:68,icon:'烧',tone:'red',qty:0},
  {id:'r4',category:'热菜',name:'锅包肉',intro:'外酥里嫩，酸甜适口',price:58,icon:'锅',tone:'warm',qty:0},
  {id:'r5',category:'主食',name:'莜面窝窝',intro:'西北风味，粗粮主食',price:28,icon:'面',tone:'sand',qty:0},
  {id:'r6',category:'饮品',name:'沙棘汁',intro:'酸甜清爽，冷饮推荐',price:16,icon:'饮',tone:'orange',qty:0}
]
const deliveryFoods=[
  {id:'d1',category:'快餐',name:'北方风味套餐',intro:'两荤一素配米饭',time:'约30分钟',price:36,icon:'餐',tone:'red',qty:0},
  {id:'d2',category:'快餐',name:'家常盖饭',intro:'当日热菜现做现送',time:'约25分钟',price:28,icon:'饭',tone:'gold',qty:0},
  {id:'d3',category:'小吃',name:'香酥拼盘',intro:'适合会议茶歇分享',time:'约20分钟',price:32,icon:'酥',tone:'warm',qty:0},
  {id:'d4',category:'饮品',name:'沙棘汁',intro:'酸甜清爽',time:'约15分钟',price:16,icon:'饮',tone:'orange',qty:0},
  {id:'d5',category:'水果',name:'时令水果盒',intro:'当日鲜切，独立包装',time:'约20分钟',price:26,icon:'果',tone:'green',qty:0}
]
const reservationAddresses={
  hotel:['示例宾馆中餐厅','示例宾馆雅间区','示例宾馆宴会厅'],
  houshan:['后山住宿山景餐厅','后山住宿雅间区','后山住宿户外用餐区']
}
const byCategory=(items,category)=>items.filter(item=>item.category===category)
const dateValue=date=>`${date.getFullYear()}-${String(date.getMonth()+1).padStart(2,'0')}-${String(date.getDate()).padStart(2,'0')}`
const dateLabel=value=>{const parts=String(value).split('-');return parts.length===3?`${parts[1]}月${parts[2]}日`:value}
const roomSize=capacity=>capacity<=4?'4人桌':capacity<=6?'6人桌':'10人桌以上'
const roomImages=['/assets/images/dining/elegant-private-room.jpg','/assets/images/dining/iminiapp-private-room.jpg','/assets/images/dining/banquet-private-room.jpg']
const categoryPresentation={
  '凉菜':{icon:'凉',tone:'green'},'热菜':{icon:'锅',tone:'red'},'主食':{icon:'面',tone:'sand'},'饮品':{icon:'饮',tone:'orange'}
}
const parseJson=value=>{if(!value)return{};if(typeof value==='object')return value;try{return JSON.parse(value)}catch(error){return{}}}
Page({
  data:{
    isHoushan:false,venueName:'示例宾馆',deliveryEnabled:true,
    diningVenues:[
      {id:'hotel',name:'示例宾馆餐饮',shortName:'示例宾馆',desc:'雅间预订 · 提前点餐 · 场内配送',tag:'可配送',icon:'宾'},
      {id:'houshan',name:'后山住宿餐饮',shortName:'后山住宿',desc:'山居用餐 · 雅间预订 · 提前点餐',tag:'山居餐饮',icon:'山'}
    ],
    activeDiningVenue:'hotel',
    activeTab:'reservation',today:'',reservationDate:'',reservationDateLabel:'',
    timeSlots:['午餐 11:30–13:30','午餐 12:00–14:00','晚餐 17:30–19:30','晚餐 18:00–20:00'],timeSlotValues:['11:30','12:00','17:30','18:00'],timeSlotIndex:0,
    reservationAddresses:reservationAddresses.hotel,reservationAddressIndex:0,
    roomFilters:['全部','4人桌','6人桌','10人桌以上'],activeRoomFilter:'全部',rooms:[],visibleRooms:[],selectedRoom:'',selectedTableId:null,currentStoreId:null,storeRecords:[],
    reserveCategories:['凉菜','热菜','主食','饮品'],activeReserveCategory:'凉菜',reserveFoods,visibleReserveFoods:byCategory(reserveFoods,'凉菜'),reserveCart:[],reserveCount:0,reserveTotal:0,deposit:50,
    cartVisible:false,addPulseId:'',cartBump:false,flyToCart:false,
    roomPreviewVisible:false,roomPreviewTitle:'',roomPreviewImage:'',
    addresses:['示例宾馆客房区','示例宾馆会议区','示例宾馆大堂及公共区域'],addressIndex:0,deliveryRoom:'',
    deliveryCategories:['快餐','小吃','饮品','水果'],activeDeliveryCategory:'快餐',deliveryFoods,visibleDeliveryFoods:byCategory(deliveryFoods,'快餐'),deliveryCart:[],
    deliveryCount:0,deliverySubtotal:0,deliveryFee:5,deliveryTotal:5,deliveryStatus:'',deliveryStatusCode:'',
    deliveryStatusIndex:-1,deliveryProgressSteps:DELIVERY_STATUS_FLOW.map(item=>({...item,state:'upcoming',time:''})),
    deliveryOrderId:'',deliveryStatusUpdatedAt:'',deliveryEta:'',deliverySubmitting:false,
    deliveryCartVisible:false,deliveryAddPulseId:'',deliveryCartBump:false,deliveryFlyToCart:false,
    contactName:'',contactMobile:'',reservationSubmitting:false,
    memberBenefit:getMemberProfile(),reservePayable:'50.00',reserveDiscount:'0.00',deliveryPayable:'5.00',deliveryDiscount:'0.00'
  },
  async onLoad(options){
    const today=dateValue(new Date())
    const isHoushan=options.area==='houshan'
    await new Promise(resolve=>this.setData({
      isHoushan,
      activeDiningVenue:isHoushan?'houshan':'hotel',
      venueName:isHoushan?'后山住宿':'示例宾馆',
      deliveryEnabled:!isHoushan,
      reservationAddresses:isHoushan?reservationAddresses.houshan:reservationAddresses.hotel,
      reservationAddressIndex:0,
      today,
      reservationDate:today,
      reservationDateLabel:dateLabel(today),
      activeTab:!isHoushan&&options.tab==='delivery'?'delivery':'reservation'
    },resolve))
    this.refreshMemberPricing()
    await this.loadDiningCatalog(isHoushan?'houshan':'hotel')
    if(!isHoushan)this.restoreDeliveryOrder()
  },
  onShow(){this.refreshMemberPricing();if(this.data.deliveryOrderId)this.startDeliveryPolling()},
  async loadDiningCatalog(preferredArea){
    try{
      const stores=await fetchDiningStores()
      const storeRecords=(stores||[]).map(store=>({
        ...store,
        area:store.storeCode==='HOUSHAN_DINING'?'houshan':'hotel'
      }))
      const diningVenues=storeRecords.map(store=>({
        id:store.area,name:store.name,shortName:store.name.replace('餐饮',''),
        desc:store.area==='houshan'?'山居用餐 · 雅间预订 · 提前点餐':'雅间预订 · 提前点餐 · 场内配送',
        tag:store.area==='houshan'?'山居餐饮':'可配送',icon:store.area==='houshan'?'山':'宾'
      }))
      const selected=storeRecords.find(store=>store.area===preferredArea)||storeRecords[0]
      this.setData({storeRecords,diningVenues})
      if(selected)await this.loadDiningStore(selected)
    }catch(error){}
  },
  async loadDiningStore(store){
    const isHoushan=store.area==='houshan'
    const deliveryScope=parseJson(store.deliveryScopeConfig)
    const configuredAreas=Array.isArray(deliveryScope.areas)&&deliveryScope.areas.length?deliveryScope.areas:null
    this.setData({
      currentStoreId:store.id,activeDiningVenue:store.area,isHoushan,venueName:store.name.replace('餐饮',''),
      deliveryEnabled:!isHoushan,reservationAddresses:[store.address],reservationAddressIndex:0,
      addresses:!isHoushan&&configuredAreas?configuredAreas:this.data.addresses,addressIndex:0,deliveryRoom:'',
      selectedRoom:'',selectedTableId:null,activeRoomFilter:'全部',cartVisible:false,deliveryCartVisible:false
    })
    try{
      const [categories,dishes]=await Promise.all([fetchDiningCategories(store.id),fetchDiningDishes(store.id)])
      const reserveCategories=(categories||[]).map(item=>item.name)
      const reserveFoods=(dishes||[]).map(item=>{
        const ui=categoryPresentation[item.categoryName]||{icon:'餐',tone:'gold'}
        return {id:String(item.id),backendId:item.id,category:item.categoryName,name:item.name,intro:item.description||'',price:Number(item.salePriceCent)/100,inventory:item.availableQuantity,icon:ui.icon,tone:ui.tone,qty:0}
      })
      const activeReserveCategory=reserveCategories[0]||''
      const deliveryFoods=isHoushan?this.data.deliveryFoods:reserveFoods.map(item=>({...item,time:'以实际配送进度为准'}))
      const deliveryCategories=isHoushan?this.data.deliveryCategories:reserveCategories
      const activeDeliveryCategory=deliveryCategories[0]||''
      this.setData({reserveCategories,activeReserveCategory,reserveFoods,visibleReserveFoods:byCategory(reserveFoods,activeReserveCategory),reserveCart:[],reserveCount:0,reserveTotal:0,deliveryCategories,activeDeliveryCategory,deliveryFoods,visibleDeliveryFoods:byCategory(deliveryFoods,activeDeliveryCategory),deliveryCart:[],deliveryCount:0,deliverySubtotal:0,deliveryTotal:5},()=>this.refreshMemberPricing())
      await this.refreshAvailableRooms()
    }catch(error){}
  },
  async refreshAvailableRooms(){
    if(!this.data.currentStoreId)return
    try{
      const rows=await fetchDiningTables({
        storeId:this.data.currentStoreId,date:this.data.reservationDate,
        time:this.data.timeSlotValues[this.data.timeSlotIndex],diningMinutes:120,guestCount:1
      })
      const rooms=(rows||[]).map((item,index)=>({
        id:item.id,name:item.name,size:roomSize(item.capacity),capacity:item.capacity,
        facilities:item.capacity>=10?'投影 · 音响 · 独立卫生间':'茶台 · 独立卫生间',
        image:roomImages[Math.min(index,roomImages.length-1)]
      }))
      const selectedStillExists=rooms.some(item=>item.id===this.data.selectedTableId)
      this.setData({rooms,visibleRooms:this.data.activeRoomFilter==='全部'?rooms:rooms.filter(item=>item.size===this.data.activeRoomFilter),...(selectedStillExists?{}:{selectedRoom:'',selectedTableId:null})})
    }catch(error){}
  },
  refreshMemberPricing(){
    const memberBenefit=getMemberProfile()
    const reserveAmountFen=yuanToFen(this.data.deposit+this.data.reserveTotal)
    this.setData({
      memberBenefit,
      reservePayable:fenToYuan(reserveAmountFen),
      reserveDiscount:'0.00',
      deliveryPayable:fenToYuan(yuanToFen(this.data.deliveryTotal)),
      deliveryDiscount:'0.00'
    })
  },
  async switchDiningVenue(e){
    const area=e.currentTarget.dataset.area
    if(!area||area===this.data.activeDiningVenue)return
    const store=this.data.storeRecords.find(item=>item.area===area)
    if(!store)return
    if(area==='houshan')this.setData({activeTab:'reservation'})
    await this.loadDiningStore(store)
    if(area!=='houshan')this.restoreDeliveryOrder()
  },
  switchTab(e){
    const tab=e.currentTarget.dataset.tab
    if(tab==='delivery'&&!this.data.deliveryEnabled)return
    this.setData({activeTab:tab})
  },
  changeRoomFilter(e){const f=e.currentTarget.dataset.filter;this.setData({activeRoomFilter:f,visibleRooms:f==='全部'?this.data.rooms:this.data.rooms.filter(item=>item.size===f)})},
  selectRoom(e){
    const id=Number(e.currentTarget.dataset.id)
    const room=this.data.rooms.find(item=>item.id===id)
    if(room)this.setData({selectedRoom:room.name,selectedTableId:room.id})
  },
  previewPrivateRoom(e){
    const room=this.data.rooms.find(item=>item.name===e.currentTarget.dataset.name)
    if(!room||!room.image){wx.showToast({title:'雅间图片暂不可用',icon:'none'});return}
    this.setData({roomPreviewVisible:true,roomPreviewTitle:room.name,roomPreviewImage:room.image})
  },
  closePrivateRoomPreview(){this.setData({roomPreviewVisible:false,roomPreviewImage:''})},
  preventPreviewTap(){},
  reservationAddressChange(e){this.setData({reservationAddressIndex:Number(e.detail.value)})},
  reservationDateChange(e){this.setData({reservationDate:e.detail.value,reservationDateLabel:dateLabel(e.detail.value)},()=>this.refreshAvailableRooms())},
  timeSlotChange(e){this.setData({timeSlotIndex:Number(e.detail.value)},()=>this.refreshAvailableRooms())},
  changeReserveCategory(e){const c=e.currentTarget.dataset.category;this.setData({activeReserveCategory:c,visibleReserveFoods:byCategory(this.data.reserveFoods,c)})},
  adjustReserve(e){
    const id=String(e.currentTarget.dataset.id),delta=Number(e.currentTarget.dataset.delta)
    const reserveFoods=this.data.reserveFoods.map(item=>item.id===id?{...item,qty:Math.max(0,Math.min(item.inventory,item.qty+delta))}:item)
    const reserveCart=reserveFoods.filter(item=>item.qty>0)
    this.setData({
      reserveFoods,
      reserveCart,
      visibleReserveFoods:byCategory(reserveFoods,this.data.activeReserveCategory),
      reserveCount:reserveFoods.reduce((s,i)=>s+i.qty,0),
      reserveTotal:reserveFoods.reduce((s,i)=>s+i.qty*i.price,0),
      addPulseId:delta>0?id:'',
      cartBump:delta>0,
      flyToCart:delta>0
    },()=>this.refreshMemberPricing())
    if(delta>0){
      clearTimeout(this.addAnimationTimer)
      clearTimeout(this.flyAnimationTimer)
      this.addAnimationTimer=setTimeout(()=>this.setData({addPulseId:'',cartBump:false}),320)
      this.flyAnimationTimer=setTimeout(()=>this.setData({flyToCart:false}),650)
    }
  },
  toggleReserveCart(){this.setData({cartVisible:!this.data.cartVisible})},
  closeReserveCart(){this.setData({cartVisible:false})},
  clearReserveCart(){
    const reserveFoods=this.data.reserveFoods.map(item=>({...item,qty:0}))
    this.setData({reserveFoods,reserveCart:[],visibleReserveFoods:byCategory(reserveFoods,this.data.activeReserveCategory),reserveCount:0,reserveTotal:0,cartVisible:false},()=>this.refreshMemberPricing())
  },
  contactNameInput(e){this.setData({contactName:e.detail.value})},
  contactMobileInput(e){this.setData({contactMobile:e.detail.value})},
  submitReservation(){
    if(!this.data.selectedRoom){wx.showToast({title:'请先选择雅间',icon:'none'});return}
    if(!this.data.contactName.trim()||!/^1\d{10}$/.test(this.data.contactMobile.trim())){wx.showToast({title:'请填写联系人和11位手机号',icon:'none'});return}
    const slot=this.data.timeSlots[this.data.timeSlotIndex]
    const diningAddress=this.data.reservationAddresses[this.data.reservationAddressIndex]
    wx.showModal({
      title:'确认餐饮预订',
      content:`${this.data.selectedRoom}\n${diningAddress}\n${this.data.reservationDate} ${slot}\n预选餐品 ${this.data.reserveCount} 份\n预订费 ¥50.00 + 餐品 ¥${this.data.reserveTotal}\n待支付 ¥${this.data.reservePayable}`,
      confirmText:'提交订单',
      success:async r=>{
        if(!r.confirm)return
        try{
          this.setData({reservationSubmitting:true})
          const reservation=await createDiningReservation({
            clientRequestId:createClientRequestId('dining_reservation'),
            storeId:this.data.currentStoreId,
            tableId:this.data.selectedTableId,
            fulfillmentType:'DINE_IN',
            reservationDate:this.data.reservationDate,
            reservationTime:this.data.timeSlotValues[this.data.timeSlotIndex],
            diningMinutes:120,
            guestCount:this.data.rooms.find(item=>item.id===this.data.selectedTableId).capacity,
            contactName:this.data.contactName.trim(),
            contactMobile:this.data.contactMobile.trim(),
            deliveryAddress:null,
            remark:'微信小程序餐饮预订',
            items:this.data.reserveCart.map(item=>({itemType:'DISH',itemId:item.backendId,quantity:item.qty}))
          })
          registerPendingMemberPoints(reservation.totalAmountCent,String(reservation.orderId),'餐饮订单完成获得积分')
          wx.showModal({title:'预订提交成功',content:`预订号 ${reservation.reservationNo}\n订单已进入“我的订单”，当前状态为待支付。`,showCancel:false})
          await this.refreshAvailableRooms()
        }catch(error){}finally{this.setData({reservationSubmitting:false})}
      }
    })
  },
  addressChange(e){this.setData({addressIndex:Number(e.detail.value)})},
  deliveryRoomInput(e){this.setData({deliveryRoom:e.detail.value})},
  changeDeliveryCategory(e){const c=e.currentTarget.dataset.category;this.setData({activeDeliveryCategory:c,visibleDeliveryFoods:byCategory(this.data.deliveryFoods,c)})},
  adjustDelivery(e){
    const id=e.currentTarget.dataset.id,delta=Number(e.currentTarget.dataset.delta)
    const deliveryFoods=this.data.deliveryFoods.map(item=>item.id===id?{...item,qty:Math.max(0,item.qty+delta)}:item)
    const count=deliveryFoods.reduce((s,i)=>s+i.qty,0),subtotal=deliveryFoods.reduce((s,i)=>s+i.qty*i.price,0)
    this.setData({
      deliveryFoods,
      deliveryCart:deliveryFoods.filter(item=>item.qty>0),
      visibleDeliveryFoods:byCategory(deliveryFoods,this.data.activeDeliveryCategory),
      deliveryCount:count,
      deliverySubtotal:subtotal,
      deliveryTotal:subtotal+5,
      deliveryAddPulseId:delta>0?id:'',
      deliveryCartBump:delta>0,
      deliveryFlyToCart:delta>0
    },()=>this.refreshMemberPricing())
    if(delta>0){
      clearTimeout(this.deliveryAddAnimationTimer)
      clearTimeout(this.deliveryFlyAnimationTimer)
      this.deliveryAddAnimationTimer=setTimeout(()=>this.setData({deliveryAddPulseId:'',deliveryCartBump:false}),320)
      this.deliveryFlyAnimationTimer=setTimeout(()=>this.setData({deliveryFlyToCart:false}),650)
    }
  },
  toggleDeliveryCart(){this.setData({deliveryCartVisible:!this.data.deliveryCartVisible})},
  closeDeliveryCart(){this.setData({deliveryCartVisible:false})},
  clearDeliveryCart(){
    const deliveryFoods=this.data.deliveryFoods.map(item=>({...item,qty:0}))
    this.setData({deliveryFoods,deliveryCart:[],visibleDeliveryFoods:byCategory(deliveryFoods,this.data.activeDeliveryCategory),deliveryCount:0,deliverySubtotal:0,deliveryTotal:5,deliveryCartVisible:false},()=>this.refreshMemberPricing())
  },
  submitDelivery(){
    if(!this.data.deliveryCount){wx.showToast({title:'请先选择餐品',icon:'none'});return}
    if(!this.data.contactName.trim()||!/^1\d{10}$/.test(this.data.contactMobile.trim())){wx.showToast({title:'请先在预订信息中填写联系人和手机号',icon:'none'});return}
    const selected=this.data.addresses[this.data.addressIndex],room=this.data.deliveryRoom.trim()
    if(!selected||!room){wx.showToast({title:'请选择内部区域并填写房号或会议室号',icon:'none'});return}
    const address=`${selected} · ${room}`
    wx.showModal({
      title:'确认场内配送',
      content:`送至：${address}\n餐品 ${this.data.deliveryCount} 份\n餐品 ¥${this.data.deliverySubtotal} + 配送费 ¥${this.data.deliveryFee}\n待支付 ¥${this.data.deliveryPayable}`,
      confirmText:'提交订单',
      success:r=>{
        if(!r.confirm)return
        this.setData({deliverySubmitting:true})
        const payload={
          clientRequestId:createClientRequestId('dining_delivery'),
          storeId:this.data.currentStoreId,
          tableId:null,
          fulfillmentType:'DELIVERY',
          reservationDate:this.data.today,
          reservationTime:`${String(new Date().getHours()).padStart(2,'0')}:${String(new Date().getMinutes()).padStart(2,'0')}`,
          diningMinutes:120,
          guestCount:1,
          contactName:this.data.contactName.trim(),
          contactMobile:this.data.contactMobile.trim(),
          deliveryAddress:address,
          deliveryArea:selected,
          roomNo:room,
          remark:'微信小程序场内配送',
          items:this.data.deliveryCart.map(item=>({itemType:'DISH',itemId:item.backendId,quantity:item.qty}))
        }
        createDeliveryOrder(payload).then(order=>{
          this.setData({
            deliveryOrderId:String(order.id),
            deliveryEta:'状态由后厨与配送人员实时更新',
            deliverySubmitting:false,
            deliveryCartVisible:false
          })
          this.applyDeliveryStatus(order.status,order.createdAt)
          registerPendingMemberPoints(order.totalAmountCent,String(order.orderId),'餐饮配送完成获得积分')
          this.startDeliveryPolling()
          wx.showModal({title:'订单已创建',content:'订单当前为待支付，请在“我的订单”中完成支付。支付后，后厨和配送状态会自动同步到这里。',showCancel:false})
        }).catch(()=>this.setData({deliverySubmitting:false}))
      }
    })
  },
  formatStatusTime(value){
    const date=value?new Date(value):new Date()
    return `${String(date.getHours()).padStart(2,'0')}:${String(date.getMinutes()).padStart(2,'0')}:${String(date.getSeconds()).padStart(2,'0')}`
  },
  applyDeliveryStatus(statusCode,updatedAt){
    const index=DELIVERY_STATUS_FLOW.findIndex(item=>item.code===statusCode)
    if(index<0)return
    const time=this.formatStatusTime(updatedAt)
    const previous=this.data.deliveryProgressSteps
    const deliveryProgressSteps=DELIVERY_STATUS_FLOW.map((item,stepIndex)=>({
      ...item,
      state:stepIndex<index?'done':stepIndex===index?'current':'upcoming',
      time:stepIndex===index?time:(previous[stepIndex]&&previous[stepIndex].time)||''
    }))
    this.setData({
      deliveryStatusCode:statusCode,
      deliveryStatus:DELIVERY_STATUS_FLOW[index].label,
      deliveryStatusIndex:index,
      deliveryStatusUpdatedAt:time,
      deliveryProgressSteps
    })
    this.persistDeliveryOrder()
    if(statusCode==='COMPLETED')settleMemberPoints(this.data.deliveryOrderId)
  },
  startDeliveryPolling(){
    clearInterval(this.deliveryProgressTimer)
    this.refreshDeliveryStatus()
    if(!['COMPLETED','CANCELLED'].includes(this.data.deliveryStatusCode))this.deliveryProgressTimer=setInterval(()=>this.refreshDeliveryStatus(),8000)
  },
  refreshDeliveryStatus(){
    if(!this.data.deliveryOrderId)return Promise.resolve()
    return getDeliveryOrder(this.data.deliveryOrderId).then(order=>this.applyDeliveryStatus(order.status,order.verifiedAt||order.createdAt)).catch(()=>{})
  },
  persistDeliveryOrder(){
    if(!this.data.deliveryOrderId||!wx.setStorageSync)return
    wx.setStorageSync(DELIVERY_ORDER_STORAGE_KEY,{
      orderId:this.data.deliveryOrderId,
      statusCode:this.data.deliveryStatusCode,
      statusUpdatedAt:formatDateTime(),
      eta:this.data.deliveryEta,
      progressSteps:this.data.deliveryProgressSteps
    })
  },
  restoreDeliveryOrder(){
    if(!wx.getStorageSync)return
    const order=wx.getStorageSync(DELIVERY_ORDER_STORAGE_KEY)
    if(!order||!order.orderId)return
    this.setData({
      deliveryOrderId:order.orderId,
      deliveryEta:order.eta||'预计 25 分钟送达',
      deliveryProgressSteps:Array.isArray(order.progressSteps)?order.progressSteps:this.data.deliveryProgressSteps
    })
    this.applyDeliveryStatus(order.statusCode||'PENDING_PAYMENT',order.statusUpdatedAt)
    this.startDeliveryPolling()
  },
  cancelDelivery(){
    if(this.data.deliveryStatusCode!=='PENDING_PAYMENT'){
      wx.showToast({title:'已支付订单请通过售后申请取消',icon:'none'})
      return
    }
    wx.showModal({
      title:'取消订单',
       content:'未支付订单可以直接取消，已支付订单需要申请退款。',
      confirmText:'确认取消',
      success:r=>{
        if(!r.confirm)return
        cancelDeliveryOrder(this.data.deliveryOrderId).then(()=>{
          reverseMemberPoints(this.data.deliveryOrderId)
          clearTimeout(this.deliveryProgressTimer)
          if(wx.removeStorageSync)wx.removeStorageSync(DELIVERY_ORDER_STORAGE_KEY)
          this.setData({
            deliveryStatus:'',deliveryStatusCode:'',deliveryStatusIndex:-1,deliveryOrderId:'',
            deliveryStatusUpdatedAt:'',deliveryEta:'',
            deliveryProgressSteps:DELIVERY_STATUS_FLOW.map(item=>({...item,state:'upcoming',time:''}))
          })
          wx.showToast({title:'订单已取消',icon:'none'})
        })
      }
    })
  },
  onUnload(){
    clearTimeout(this.addAnimationTimer)
    clearTimeout(this.flyAnimationTimer)
    clearTimeout(this.deliveryAddAnimationTimer)
    clearTimeout(this.deliveryFlyAnimationTimer)
    clearInterval(this.deliveryProgressTimer)
  },
  onShareAppMessage(){
    const area=this.data.isHoushan?'&area=houshan':''
    return{
      title:this.data.isHoushan?'后山住宿｜餐饮预订':'示例宾馆｜餐饮预订与场内配送',
      path:`/pages/dining/dining?tab=${this.data.activeTab}${area}`
    }
  }
})
