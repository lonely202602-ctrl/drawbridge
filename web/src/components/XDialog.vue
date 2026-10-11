<template>
  <Teleport to="body">
    <div v-if="modelValue" class="overlay" :style="{ zIndex }" @click.self="close">
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
defineProps({
  modelValue: Boolean,
  title: { type: String, default: '' },
  width: { type: String, default: '560px' },
  zIndex: { type: Number, default: 100 }
})
const emit = defineEmits(['update:modelValue'])
function close() { emit('update:modelValue', false) }
</script>
