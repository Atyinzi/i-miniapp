<script setup>
defineProps({ targets: { type: Array, required: true }, categories: { type: Array, default: () => [] }, products: { type: Array, default: () => [] }, disabled: Boolean })
defineEmits(['add'])
function options(type, categories, products) { return type === 'CATEGORY' ? categories : type === 'PRODUCT' ? products : [] }
function label(item, type) { return type === 'CATEGORY' ? `${item.name}（${item.categoryCode}）` : `${item.name}（${item.productCode}）` }
</script>

<template>
  <div class="subsection"><div class="subsection-title"><h4>适用范围</h4><button :disabled="disabled || targets.length>=20" @click="$emit('add')">增加范围</button></div>
    <div v-for="(target,index) in targets" :key="index" class="target-row"><select v-model="target.targetType" :disabled="disabled" @change="target.targetId=null"><option value="ALL">全场商品</option><option value="CATEGORY">指定分类</option><option value="PRODUCT">指定商品</option><option value="SKU">指定SKU</option></select><select v-if="target.targetType==='CATEGORY'||target.targetType==='PRODUCT'" v-model="target.targetId" :disabled="disabled"><option value="">请选择</option><option v-for="item in options(target.targetType,categories,products)" :key="item.id" :value="item.id">{{ label(item,target.targetType) }}</option></select><input v-else-if="target.targetType==='SKU'" v-model.number="target.targetId" type="number" min="1" placeholder="SKU ID" :disabled="disabled" /><span v-else>适用于全部文创商品</span><button :disabled="disabled || targets.length===1" @click="targets.splice(index,1)">删除</button></div>
  </div>
</template>
