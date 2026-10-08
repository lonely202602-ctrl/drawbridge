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
        { path: 'dashboard', component: () => import('./views/Dashboard.vue') },
        { path: 'channels', component: () => import('./views/Channels.vue') },
        { path: 'groups', component: () => import('./views/Groups.vue') },
        { path: 'apps', component: () => import('./views/Apps.vue') },
        { path: 'tasks', component: () => import('./views/Tasks.vue') },
        { path: 'settings', component: () => import('./views/Settings.vue') }
      ]
    }
  ]
})

router.beforeEach((to) => {
  if (to.path !== '/login' && !localStorage.getItem('admin_token')) return '/login'
})

export default router
