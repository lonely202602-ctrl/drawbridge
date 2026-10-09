<template>
  <div class="login-wrap">
    <div class="login-card">
      <div style="font-size: 40px; text-align: center">🌉</div>
      <h2 style="text-align: center; margin: 6px 0 20px">drawbridge 控制台</h2>
      <el-form @keyup.enter="login">
        <el-form-item><el-input v-model="form.username" placeholder="用户名" size="large" /></el-form-item>
        <el-form-item><el-input v-model="form.password" type="password" placeholder="密码" size="large" show-password /></el-form-item>
        <el-button type="primary" size="large" round style="width: 100%" :loading="loading" @click="login">登 录</el-button>
      </el-form>
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
  background: #f2f0ea;
}
.login-card {
  background: #fff; border-radius: 22px; padding: 36px 34px 30px; width: 380px;
  box-shadow: 0 10px 40px rgba(60, 60, 50, 0.1);
}
</style>
