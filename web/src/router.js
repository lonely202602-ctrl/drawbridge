import { createRouter, createWebHistory } from 'vue-router'
import Layout from './views/Layout.vue'

const router = createRouter({
  history: createWebHistory(),
  routes: [
    { path: '/login', component: () => import('./views/Login.vue') },
    {
      path: '/',
      component: Layout,
      children: [
        { path: '', redirect: '/dashboard' },
        { path: 'dashboard', component: () => import('./views/Dashboard.vue'), meta: { title: '监控面板', sub: '任务量 / 在途 / 渠道健康一览，10 秒自动刷新' } },
        { path: 'channels', component: () => import('./views/Channels.vue'), meta: { title: '上游渠道', sub: '接入上游中转站：选协议 → 贴文档 curl 参考 → 填 Key → 试跑验收' } },
        { path: 'groups', component: () => import('./views/Groups.vue'), meta: { title: '模型分组', sub: '分组 = 业务方调用的 model 名；一个分组挂多个上游成员互为备份，点策略标签即时切换' } },
        { path: 'apps', component: () => import('./views/Apps.vue'), meta: { title: '令牌', sub: '发令牌 = 起个名 + 选授权分组；SK 加密存储，可随时查看' } },
        { path: 'tasks', component: () => import('./views/Tasks.vue'), meta: { title: '任务查询', sub: '任务详情含重试时间线、业务方请求体、上游请求体与响应结果' } },
        { path: 'queue-monitor', component: () => import('./views/QueueMonitor.vue'), meta: { title: '队列监控', sub: '每个上游账号的并发占用与排队积压，5 秒自动刷新' } },
        { path: 'settings', component: () => import('./views/Settings.vue'), meta: { title: '设置', sub: '运营参数，保存即热更新生效，无需重启' } },
        { path: 'docs', component: () => import('./views/ApiDocs.vue'), meta: { title: 'API 文档', sub: '业务方快速接入：可复制的 curl 测试脚本（model 自动带出你的分组）' } }
      ]
    }
  ]
})

// 构建更新后，旧页面懒加载已删除的 chunk 会失败：自动整页刷新一次拿新版本（防循环）
router.onError((error, to) => {
  const chunkFailed = /Failed to fetch dynamically imported module|Importing a module script failed|Loading chunk .+ failed/i
      .test(error?.message || '');
  if (chunkFailed && !sessionStorage.getItem('db_chunk_reload')) {
    sessionStorage.setItem('db_chunk_reload', '1');
    window.location.href = to?.fullPath || '/';
  }
})

router.beforeEach((to) => {
  if (to.path !== '/login' && !localStorage.getItem('admin_token')) return '/login'
})

export default router
