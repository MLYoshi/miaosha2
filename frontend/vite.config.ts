import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'

// https://vite.dev/config/
export default defineConfig({
  plugins: [react()],
  server: {
    port: 5173,
    proxy: {
      // 后端无 context-path，代理时去掉 /api 前缀
      '/api': {
        target: 'http://localhost:8080',
        changeOrigin: true,
        rewrite: (path) => path.replace(/^\/api/, ''),
      },
    },
  },
  build: {
    // 产物体积告警阈值：业务代码与依赖拆分后单 chunk 通常 < 500KB，
    // 此处放宽以避免 vendor chunk（antd/react 体积较大）误报，真正超大再人工拆分
    chunkSizeWarningLimit: 900,
    rollupOptions: {
      output: {
        // 依赖拆分为独立 vendor chunk，便于浏览器长缓存
        manualChunks: {
          react: ['react', 'react-dom', 'react-router-dom'],
          antd: ['antd'],
          query: ['@tanstack/react-query'],
        },
      },
    },
  },
})
