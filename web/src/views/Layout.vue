<template>
  <div class="shell">
    <!-- 浮动侧栏 -->
    <aside class="side">
      <div class="brand">🌉</div>
      <router-link v-for="item in menus" :key="item.path" :to="item.path"
                   class="side-item" active-class="on" :title="item.title">{{ item.icon }}</router-link>
    </aside>

    <main class="main">
      <div class="page-head">
        <div>
          <h1>{{ title }}</h1>
          <div class="sub">{{ sub }}</div>
        </div>
        <button class="btn dangertext sm" @click="logout">退出登录</button>
      </div>
      <router-view v-slot="{ Component }">
        <transition name="page" mode="out-in">
          <component :is="Component" />
        </transition>
      </router-view>
    </main>
  </div>
</template>

<script setup>
import { computed } from 'vue'
import { useRoute, useRouter } from 'vue-router'

const route = useRoute()
const router = useRouter()
const menus = [
  { path: '/dashboard', icon: '📈', title: '监控面板' },
  { path: '/channels', icon: '🔌', title: '上游渠道' },
  { path: '/groups', icon: '🗂️', title: '模型分组' },
  { path: '/apps', icon: '🔑', title: '令牌' },
  { path: '/tasks', icon: '📋', title: '任务查询' },
  { path: '/queue-monitor', icon: '🚦', title: '队列监控' },
  { path: '/docs', icon: '📄', title: 'API 文档' },
  { path: '/settings', icon: '⚙️', title: '设置' }
]
const title = computed(() => route.meta.title || 'drawbridge')
const sub = computed(() => route.meta.sub || '')

function logout() {
  localStorage.removeItem('admin_token')
  router.push('/login')
}
</script>

<style scoped>
.shell { display: flex; min-height: 100vh; }
.side {
  position: fixed;
  left: 18px; top: 18px; bottom: 18px;
  width: 62px;
  background: var(--surface);
  border: 1px solid var(--line);
  border-radius: 22px;
  box-shadow: var(--shadow);
  display: flex;
  flex-direction: column;
  align-items: center;
  padding: 14px 0;
  gap: 6px;
  z-index: 10;
}
.brand { font-size: 22px; margin-bottom: 10px; }
.side-item {
  width: 42px; height: 42px;
  display: flex; align-items: center; justify-content: center;
  border-radius: 14px;
  font-size: 18px;
  text-decoration: none;
  color: var(--ink2);
  transition: all 0.15s;
}
.side-item:hover { background: var(--bg); }
.side-item.on { background: var(--accent-wash); color: var(--accent); }
.main { flex: 1; min-width: 0; margin-left: 104px; padding: 26px 28px 40px; }
</style>
