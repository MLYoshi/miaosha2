# frontend/AGENTS.md — 秒杀商城前端（Agent 指南）

## 项目

Vite + React 18 + TypeScript + Tailwind CSS + shadcn/ui 单页应用。仅通过网关 gateway(8080) 通信，**不直连业务服务端口 8081–8084**（gateway 是唯一公网入口）。

## 常用命令

```bash
cd frontend
npm install
npm run dev        # 开发服务器 http://localhost:5173
npm run build      # 类型检查（tsc）+ 生产构建，改代码后必须跑通
npm run preview    # 预览生产构建
```

## 请求代理（联调关键）

`vite.config.ts` 将 `/user`、`/goods`、`/miaosha`、`/admin` 四类前缀代理到 gateway：

- 默认目标 `http://localhost:8080`，可用环境变量 `VITE_PROXY_TARGET` 覆盖（复制 `.env.example` 为 `.env`）。
- `VITE_API_BASE_URL` 可选覆盖后端 API 地址，默认空 = 同源（走 proxy）。
- 新增后端路由前缀时需同步在 `vite.config.ts` 加代理，否则请求 404。

## 路由与鉴权

| 路径 | 页面 | 鉴权 |
|---|---|---|
| `/login` | 登录 | 公开 |
| `/register` | 注册 | 公开 |
| `/goods` | 秒杀会场列表 | 需登录 |
| `/goods/:id` | 商品详情 + 抢购 | 需登录 |
| `/profile` | 个人中心 | 需登录 |
| `/admin` | 运营管理端 | 需登录 |

- token 存 `localStorage`，键 `miaosha_token`（见 `src/lib/auth.ts`）。
- 受保护路由由 `App.tsx` 的 `RequireAuth` 守卫，未登录跳 `/login` 并携带回跳地址。
- 后端 JWT 鉴权在 gateway 的 `JwtGlobalFilter`，前端只管携带 token。

## 分层约定（改代码必须遵守）

```
src/types/api.ts       # 类型契约层：1:1 对齐后端 Result/VO，后端改字段先改这里
src/lib/               # 请求层：axios 拦截器、token 存取、错误码映射、cn() 工具
src/api/               # 领域 API 层（user/goods/miaosha/admin），按领域分文件
src/components/ui/     # shadcn/ui 组件（components.json 已配置，可用 CLI 追加）
src/pages/             # 页面层
```

- **页面不直接触碰 axios**：所有 HTTP 调用走 `src/api/` 领域封装。
- 新增 shadcn/ui 组件用 CLI（`npx shadcn@latest add <component>`），不要手写进 `src/components/ui/`。
- 错误码映射统一加在 `src/lib/` 错误码表（与 common 的 `CodeMsg` 对齐，如 500212 重复下单、500214 库存空）。

## 联调流程

1. 根目录 `docker compose up -d mysql redis kafka nacos`
2. 启动 gateway 与各业务服务（8080–8084，见根 `AGENTS.md`）
3. `npm run dev`，访问 http://localhost:5173
4. 后端联调报「未开始/已结束」→ 先确认 MySQL 是否完成自动初始化（`db/init/01-init.sql`，时间窗 `NOW()` 动态生成；重新初始化需清空 `db/mysql_data`），不要先怀疑前端
