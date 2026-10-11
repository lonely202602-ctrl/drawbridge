<template>
  <router-view />
  <!-- 轻提示 -->
  <div class="toast-wrap">
    <TransitionGroup name="toast">
      <div v-for="t in toasts" :key="t.id" class="toast" :class="t.type">
        <span class="dot"></span>{{ t.msg }}
      </div>
    </TransitionGroup>
  </div>
  <!-- 确认弹窗 -->
  <div v-if="cs.visible" class="overlay confirm-overlay" style="z-index: 150" @click.self="resolveConfirm(false)">
    <div class="dialog confirm-dialog">
      <div class="dlg-head">{{ cs.title }}</div>
      <div class="dlg-body">{{ cs.message }}</div>
      <div class="dlg-foot">
        <button class="btn ghost sm" @click="resolveConfirm(false)">取 消</button>
        <button class="btn sm" :class="cs.danger ? 'danger' : 'primary'" @click="resolveConfirm(true)">{{ cs.confirmText }}</button>
      </div>
    </div>
  </div>
</template>

<script setup>
import { onMounted, onBeforeUnmount } from 'vue'
import { toasts, confirmState as cs, resolveConfirm } from './ui'

// ESC 取消当前确认框（XDialog 检测到 .confirm-overlay 时会把 ESC 让位给这里）
function onKey(e) {
  if (e.key === 'Escape' && cs.visible) {
    resolveConfirm(false)
  }
}
onMounted(() => document.addEventListener('keydown', onKey))
onBeforeUnmount(() => document.removeEventListener('keydown', onKey))
</script>
