import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'

// base './'：构建产物由 Spring Boot 同源托管（web/dist），也支持任意子路径
export default defineConfig({
  base: './',
  plugins: [vue()],
  server: {
    port: 5173,
    proxy: {
      '/admin': 'http://localhost:8180',
      '/v1': 'http://localhost:8180'
    }
  },
  build: { outDir: 'dist' }
})
