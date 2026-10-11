<template>
  <div class="xselect" :class="{ 'xselect-block': block }" ref="root">
    <button type="button" class="input xselect-btn" @click="toggle">
      <span :class="{ placeholder: isEmpty }">{{ label }}</span>
      <span class="caret" :style="open ? 'transform: rotate(180deg)' : ''">▾</span>
    </button>
    <Teleport to="body">
      <div v-if="open" class="menu xselect-menu" :style="menuStyle">
        <div v-if="clearable && !isEmpty" class="menu-item" @click="pick(multiple ? [] : '')">不限（全部）</div>
        <div v-for="o in options" :key="String(o.value)"
             class="menu-item" :class="{ on: isOn(o.value) }"
             @click="pick(o.value)">{{ o.label }}</div>
      </div>
    </Teleport>
  </div>
</template>

<script setup>
import { computed, ref, nextTick, onMounted, onBeforeUnmount } from 'vue'

const props = defineProps({
  modelValue: { type: [String, Number, Array], default: '' },
  options: { type: Array, default: () => [] },   // [{ label, value }]
  multiple: Boolean,
  clearable: Boolean,
  block: Boolean,
  placeholder: { type: String, default: '请选择' }
})
const emit = defineEmits(['update:modelValue'])

const open = ref(false)
const root = ref(null)
const menuStyle = ref({})

const isEmpty = computed(() =>
  props.multiple ? !(props.modelValue || []).length : (props.modelValue === '' || props.modelValue == null))

const label = computed(() => {
  if (props.multiple) {
    const arr = props.modelValue || []
    return arr.length ? arr.map(v => props.options.find(o => o.value === v)?.label ?? v).join('、')
      : (props.placeholder || '请选择')
  }
  const hit = props.options.find(o => o.value === props.modelValue)
  return hit ? hit.label : (isEmpty.value ? props.placeholder || '请选择' : String(props.modelValue))
})

function isOn(v) {
  return props.multiple ? (props.modelValue || []).includes(v) : v === props.modelValue
}

/** 菜单 Teleport 到 body：弹窗/滚动容器内不被裁剪；位置按按钮实时计算。 */
async function toggle() {
  open.value = !open.value
  if (open.value) {
    await nextTick()
    const rect = root.value.getBoundingClientRect()
    const below = window.innerHeight - rect.bottom
    const maxH = Math.min(260, Math.max(120, below - 14))
    menuStyle.value = {
      position: 'fixed',
      left: rect.left + 'px',
      top: below > 90 ? rect.bottom + 6 + 'px' : '',
      bottom: below > 90 ? '' : (window.innerHeight - rect.top + 6) + 'px',
      minWidth: rect.width + 'px',
      maxHeight: maxH + 'px'
    }
  }
}

function pick(v) {
  if (props.multiple) {
    const arr = [...(props.modelValue || [])]
    const i = arr.indexOf(v)
    i >= 0 ? arr.splice(i, 1) : arr.push(v)
    emit('update:modelValue', arr)
  } else {
    emit('update:modelValue', v)
    open.value = false
  }
}

function onDoc(e) {
  if (root.value && !root.value.contains(e.target)
      && !(e.target.closest && e.target.closest('.xselect-menu'))) open.value = false
}
function onScroll() { if (open.value) open.value = false }
onMounted(() => {
  document.addEventListener('click', onDoc)
  window.addEventListener('scroll', onScroll, true)
  window.addEventListener('resize', onScroll)
})
onBeforeUnmount(() => {
  document.removeEventListener('click', onDoc)
  window.removeEventListener('scroll', onScroll, true)
  window.removeEventListener('resize', onScroll)
})
</script>
