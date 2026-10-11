<template>
  <div class="login-wrap">
    <div class="login-card">
      <div style="font-size: 40px; text-align: center">🌉</div>
      <h2 style="text-align: center; margin: 6px 0 22px">drawbridge 控制台</h2>
      <div class="field"><label>用户名</label>
        <input v-model="form.username" class="input" placeholder="用户名" @keyup.enter="login" /></div>
      <div class="field"><label>密码</label>
        <input v-model="form.password" class="input" type="password" placeholder="密码" @keyup.enter="login" /></div>
      <button class="btn primary block" style="margin-top: 8px" :disabled="loading" @click="login">
        <span v-if="loading" class="spin"></span>登 录
      </button>
    </div>
  </div>
</template>

<script setup>
import { reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import api, { unwrap } from '../api'

const router = useRouter()
const form = reactive({ username: 'admin', password: '' })
const loading = ref(false)

async function login() {
  loading.value = true
  try {
    const data = await unwrap(api.post('/admin/login', form))
    localStorage.setItem('admin_token', data.token)
    router.push('/dashboard')
  } catch (e) {
    // 拦截器已弹错误提示
  } finally {
    loading.value = false
  }
}
</script>

<style scoped>
.login-wrap {
  display: flex; justify-content: center; align-items: center; height: 100vh;
  background: var(--bg);
}
.login-card {
  background: var(--surface); border: 1px solid var(--line);
  border-radius: 22px; padding: 36px 34px 30px; width: 380px;
  box-shadow: var(--shadow-lg);
}
</style>
