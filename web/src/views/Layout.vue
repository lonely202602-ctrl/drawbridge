<template>
  <el-container style="height: 100vh">
    <el-aside width="200px" style="border-right: 1px solid #eee">
      <div style="font-weight: bold; padding: 18px 16px">🌉 drawbridge</div>
      <el-menu :default-active="$route.path" router>
        <el-menu-item index="/dashboard">📈 监控面板</el-menu-item>
        <el-menu-item index="/channels">🔌 上游渠道</el-menu-item>
        <el-menu-item index="/groups">🗂️ 模型分组</el-menu-item>
        <el-menu-item index="/apps">🔑 应用与令牌</el-menu-item>
        <el-menu-item index="/tasks">📋 任务查询</el-menu-item>
        <el-menu-item index="/settings">⚙️ 设置</el-menu-item>
      </el-menu>
    </el-aside>
    <el-container>
      <el-header style="display: flex; align-items: center; justify-content: flex-end; border-bottom: 1px solid #eee">
        <el-button link type="danger" @click="logout">退出登录</el-button>
      </el-header>
      <el-main><router-view /></el-main>
    </el-container>
  </el-container>
</template>

<script setup>
function logout() {
  localStorage.removeItem('admin_token')
  location.href = './login'
}
</script>
