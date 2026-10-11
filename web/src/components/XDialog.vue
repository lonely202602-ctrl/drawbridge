<template>
  <Teleport to="body">
    <div v-if="modelValue" class="overlay" :style="{ zIndex }"
         @mousedown="onOverlayMousedown" @click="onOverlayClick">
      <div class="dialog" :style="{ width }">
        <div class="dlg-head">
          <span>{{ title }}</span>
          <button class="dlg-x" @click="close">✕</button>
        </div>
        <div class="dlg-main"><slot /></div>
        <div v-if="$slots.footer" class="dlg-foot"><slot name="footer" /></div>
      </div>
    </div>
  </Teleport>
</template>

<script setup>
import { ref, watch, onBeforeUnmount } from 'vue'

const props = defineProps({
  modelValue: Boolean,
  title: { type: String, default: '' },
  width: { type: String, default: '560px' },
  zIndex: { type: Number, default: 100 }
})
const emit = defineEmits(['update:modelValue'])
function close() { emit('update:modelValue', false) }

// 防拖选误关：只有"按下和松开都在遮罩上"才关——从输入框拖选文字拖到遮罩松手不算（防丢已填表单）
const downOnOverlay = ref(false)
function onOverlayMousedown(e) {
  downOnOverlay.value = e.target === e.currentTarget
}
function onOverlayClick(e) {
  if (downOnOverlay.value && e.target === e.currentTarget) {
    close()
  }
  downOnOverlay.value = false
}

// ESC 关闭（确认框开着时让位给确认框，只关确认框）
function onKey(e) {
  if (e.key !== 'Escape') return
  if (document.querySelector('.confirm-overlay')) return
  close()
}
watch(() => props.modelValue, open => {
  if (open) {
    document.addEventListener('keydown', onKey)
  } else {
    document.removeEventListener('keydown', onKey)
  }
})
onBeforeUnmount(() => document.removeEventListener('keydown', onKey))
</script>
