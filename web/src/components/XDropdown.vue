<template>
  <div class="xdrop" ref="root">
    <div @click="open = !open"><slot name="trigger" /></div>
    <div v-if="open" class="menu xdrop-menu">
      <slot />
    </div>
  </div>
</template>

<script setup>
import { ref, onMounted, onBeforeUnmount } from 'vue'

const open = ref(false)
const root = ref(null)

function onDoc(e) {
  if (root.value && !root.value.contains(e.target)) open.value = false
}
function close() { open.value = false }
defineExpose({ close })

onMounted(() => {
  document.addEventListener('click', onDoc)
  // 菜单项被点击后自动收起（事件从 menu 冒泡到 document 前在容器上拦截不到，这里兜底）
  root.value?.addEventListener('click', e => {
    if (e.target.closest('.menu-item')) open.value = false
  })
})
onBeforeUnmount(() => document.removeEventListener('click', onDoc))
</script>
