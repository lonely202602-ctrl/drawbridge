import { reactive } from 'vue'

/** 轻提示：toast('已保存') / toast('失败原因', 'error') */
export const toasts = reactive([])
let toastSeq = 0

export function toast(msg, type = 'success', dur = 2800) {
  const id = ++toastSeq
  toasts.push({ id, msg, type })
  setTimeout(() => {
    const i = toasts.findIndex(t => t.id === id)
    if (i >= 0) toasts.splice(i, 1)
  }, dur)
}

/** 确认弹窗：const ok = await confirm({ title, message, danger, confirmText }) */
export const confirmState = reactive({
  visible: false, title: '', message: '', confirmText: '确定', danger: false, _resolve: null
})

export function confirm(opts = {}) {
  return new Promise(resolve => {
    confirmState.title = opts.title || '提示'
    confirmState.message = opts.message || ''
    confirmState.confirmText = opts.confirmText || '确定'
    confirmState.danger = !!opts.danger
    confirmState.visible = true
    confirmState._resolve = resolve
  })
}

export function resolveConfirm(ok) {
  if (!confirmState.visible) return
  confirmState.visible = false
  confirmState._resolve?.(ok)
  confirmState._resolve = null
}
