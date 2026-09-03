import { defineConfig, loadEnv } from 'vite';
import react from '@vitejs/plugin-react';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

// 开发期通过 proxy 将 /user /goods /miaosha /admin 转发到 gateway(8080)，
// 前端仅面向网关通信，不直连业务服务端口。
// 可用 VITE_PROXY_TARGET 覆盖目标地址（见 .env.example）。
export default defineConfig(({ mode }) => {
  const env = loadEnv(mode, process.cwd(), '');
  const proxyTarget = env.VITE_PROXY_TARGET || 'http://localhost:8080';

  return {
    plugins: [react()],
    resolve: {
      alias: {
        '@': path.resolve(path.dirname(fileURLToPath(import.meta.url)), 'src'),
      },
    },
    server: {
      port: 5173,
      proxy: Object.fromEntries(
        ['/user', '/goods', '/miaosha', '/admin'].map((p) => [
          p,
          { target: proxyTarget, changeOrigin: true },
        ]),
      ),
    },
  };
});
