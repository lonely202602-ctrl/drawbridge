<template>
  <div style="display: flex; justify-content: center; align-items: center; height: 100vh; background: #f5f7fa">
    <el-card style="width: 360px">
      <h2 style="text-align: center; margin-top: 4px">🌉 drawbridge 控制台</h2>
      <el-form @keyup.enter="login">
        <el-form-item><el-input v-model="form.username" placeholder="用户名" /></el-form-item>
        <el-form-item><el-input v-model="form.password" type="password" placeholder="密码" show-password /></el-form-item>
        <el-button type="primary" style="width: 100%" :loading="loading" @click="login">登 录</el-button>
      </el-form>
    </el-card>
  </div>
</template>

<script setup>
import { reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
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
