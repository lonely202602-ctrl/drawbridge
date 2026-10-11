import axios from 'axios'
import { toast } from './ui'

const api = axios.create({ timeout: 60000 })

api.interceptors.request.use((cfg) => {
  const token = localStorage.getItem('admin_token')
  if (token) cfg.headers['X-Admin-Token'] = token
  return cfg
})

api.interceptors.response.use(
  (resp) => resp.data,
  (err) => {
    if (err.response?.status === 401 && location.pathname !== '/login') {
      localStorage.removeItem('admin_token')
      location.href = './login'
    }
    const body = err.response?.data
    const msg = body?.msg || err.message
    toast(msg, 'error')
    return Promise.reject(body || err)
  }
)

/** 统一解包 ApiResponse：code=0 返回 data，否则抛错。 */
export async function unwrap(promise) {
  const body = await promise
  if (body.code !== 0) throw new Error(body.msg)
  return body.data
}

export default api
