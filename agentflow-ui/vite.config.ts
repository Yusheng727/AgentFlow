import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'

export default defineConfig({
  plugins: [react()],
  server: {
    port: 5173,
    proxy: {
      // 开发环境 /api 转发到后端（避免浏览器 CORS）；直连可用 VITE_API_BASE 覆盖
      '/api': {
        target: 'http://localhost:8080',
        changeOrigin: true
      }
    }
  }
})
