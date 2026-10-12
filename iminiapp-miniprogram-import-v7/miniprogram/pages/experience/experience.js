const PLACE_GROUPS = [
  [
    {name:'示例景区',desc:'门票、坦克试乘与军事体验一站预约',distance:'距你 0.8 km',tag:'热门必游',color:'olive',symbol:'⚑',label:'MUST VISIT',url:'/pages/weapon-city/weapon-city'},
    {name:'i某小程序休闲场馆',desc:'浴都放松、体育运动，线上购票便捷入场',distance:'距你 1.2 km',tag:'人气场馆',color:'orange',symbol:'◌',label:'POPULAR',url:'/pages/leisure/leisure'},
    {name:'工业遗址公园',desc:'在钢铁骨架与绿意之间感受i某小程序记忆',distance:'距你 1.5 km',tag:'免费开放',color:'teal',symbol:'⌘',label:'CITY WALK'}
  ],
  [
    {name:'示例景区',desc:'从装备展陈与工业故事中了解i某小程序发展历程',distance:'距你 0.8 km',tag:'工业文化',color:'olive',symbol:'史',label:'HISTORY',url:'/pages/weapon-city/weapon-city'},
    {name:'工业遗址公园',desc:'漫步老厂区遗存，阅读建筑背后的时代印记',distance:'距你 1.5 km',tag:'遗址参观',color:'teal',symbol:'迹',label:'HERITAGE'},
    {name:'i某小程序工业文化展厅',desc:'集中展示企业历史、制造技艺与工业精神',distance:'距你 1.0 km',tag:'预约参观',color:'rust',symbol:'展',label:'EXHIBITION'}
  ],
  [
    {name:'浴都休闲体验',desc:'舒适洗浴与休息空间，放松旅途疲惫',distance:'距你 1.2 km',tag:'门票购买',color:'orange',symbol:'浴',label:'RELAX',url:'/pages/leisure/leisure'},
    {name:'体育馆运动体验',desc:'篮球、羽毛球等运动场地分时段预约入场',distance:'距你 1.2 km',tag:'运动健身',color:'blue',symbol:'体',label:'SPORTS',url:'/pages/leisure/leisure'},
    {name:'后山山居漫步',desc:'沿山景步道慢行，在自然绿意中享受悠闲时光',distance:'距你 3.6 km',tag:'轻松漫游',color:'green',symbol:'山',label:'NATURE',url:'/pages/venues/venues?area=houshan'}
  ]
]

Page({
  data:{tabs:['热门推荐','文化探索','休闲活动'],active:0,places:PLACE_GROUPS[0]},
  switchTab(e){
    const active=Number(e.currentTarget.dataset.index)
    this.setData({active,places:PLACE_GROUPS[active]||PLACE_GROUPS[0]})
  },
  openPlace(e){
    const place=this.data.places[Number(e.currentTarget.dataset.index)]
    if(place&&place.url){wx.navigateTo({url:place.url});return}
    wx.showModal({title:place?place.name:'游玩i某小程序',content:'该模块将在后续阶段继续完善。',showCancel:false})
  }
})
