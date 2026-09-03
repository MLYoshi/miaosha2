# 秒杀商城前端（frontend/）

Vite + React 18 + TypeScript + Tailwind CSS + shadcn/ui 单页应用。仅面向网关 gateway(8080) 通信，不直连各业务服务端口。

## 启动

```bash
cd frontend
npm install
npm run dev        # http://localhost:5173
npm run build      # 类型检查 + 生产构建
npm run preview    # 预览生产构建
```

## 开发期代理

`vite.config.ts` 将 `/user`、`/goods`、`/miaosha`、`/admin` 四类前缀代理到 gateway：

- 默认目标 `http://localhost:8080`，可用环境变量 `VITE_PROXY_TARGET` 覆盖（复制 `.env.example` 为 `.env`）。
- `VITE_API_BASE_URL` 为后端 API 地址可选覆盖，默认空 = 同源（走 proxy）。

## 路由

| 路径 | 页面 | 鉴权 |
|---|---|---|
| `/login` | 登录 | 公开 |
| `/register` | 注册 | 公开 |
| `/goods` | 秒杀会场列表 | 需登录 |
| `/goods/:id` | 商品详情 + 抢购 | 需登录 |
| `/profile` | 个人中心 | 需登录 |
| `/admin` | 运营管理端 | 需登录 |

鉴权：token 存 `localStorage`（键 `miaosha_token`，见 `src/lib/auth.ts`）；受保护路由由 `App.tsx` 的 `RequireAuth` 守卫，未登录跳 `/login` 并携带回跳地址。

## 分层约定

- `src/types/api.ts`：类型契约层（1:1 对齐后端 Result/VO）
- `src/lib/`：请求层（axios 拦截器）、token 存取、错误码映射、`cn()` 工具
- `src/api/`：领域 API 层（user/goods/miaosha/admin），页面不直接触碰 axios
- `src/components/ui/`：shadcn/ui 组件（`components.json` 已配置，可用 CLI 追加）
- `src/pages/`：页面层

## 联调

1. 启动中间件：根目录 `docker compose up -d mysql redis kafka nacos`
2. 启动 gateway 与各业务服务（8080–8084）
3. `npm run dev`，浏览器访问 http://localhost:5173
