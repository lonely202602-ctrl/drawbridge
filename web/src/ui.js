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

/**
 * 确认弹窗：const ok = await confirm({ title, message, danger, confirmText })
 * 队列化：连弹时依次显示依次返回（否则后一个会把前一个的 resolve 顶掉，前一个永远挂起）。
 */
export const confirmState = reactive({
  visible: false, title: '', message: '', confirmText: '确定', danger: false, _resolve: null
})
const confirmQueue = []

export function confirm(opts = {}) {
  return new Promise(resolve => {
    confirmQueue.push({ opts, resolve })
    if (!confirmState.visible) {
      showNextConfirm()
    }
  })
}

function showNextConfirm() {
  const next = confirmQueue[0]
  if (!next) {
    confirmState.visible = false
    confirmState._resolve = null
    return
  }
  confirmState.title = next.opts.title || '提示'
  confirmState.message = next.opts.message || ''
  confirmState.confirmText = next.opts.confirmText || '确定'
  confirmState.danger = !!next.opts.danger
  confirmState.visible = true
  confirmState._resolve = next.resolve
}

export function resolveConfirm(ok) {
  if (!confirmState.visible) return
  confirmQueue.shift()
  const resolve = confirmState._resolve
  confirmState._resolve = null
  confirmState.visible = false
  resolve?.(ok)
  showNextConfirm()
}
