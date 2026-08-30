# Seckill 秒杀商城 · 前端

基于 React 18 + Vite 7 + Ant Design 5 + TanStack Query 的秒杀商城前端。

## 技术栈

| 层 | 选型 |
|---|---|
| 框架 | React 18 + TypeScript 5.9 |
| 构建 | Vite 7 |
| UI | Ant Design 5（中文 locale） |
| 路由 | react-router-dom 6 |
| 数据 | TanStack Query 5（retry 关闭） |
| HTTP | axios（统一 `Result` 解包 + `ApiError` 归一） |
| 测试 | Vitest 4（jsdom，探针模式渲染 hook） |

## 目录结构

```
frontend/
├─ src/
│  ├─ api/          # http 实例、错误码、领域 API（goods/auth/admin/miaosha）、types
│  ├─ auth/         # AuthContext、RequireAuth 守卫、token 存储
│  ├─ layouts/      # AppLayout 顶部导航壳
│  ├─ pages/        # GoodsList/GoodsDetail/Profile/Preheat/Login/NotFound
│  ├─ hooks/        # useCountdown/useSeckill/usePreheat
│  ├─ utils/        # goods/profile/seckill 纯函数
│  └─ test/         # vitest setup（jsdom localStorage 垫片）
├─ Dockerfile       # 多阶段：node 构建 → nginx serve + /api 反代
├─ nginx.conf       # SPA 回退 + /api 反代 + 静态缓存
├─ .env.development # 开发环境图片 base
└─ .env.production  # 生产环境图片 base（默认空 = 同源）
```

## 环境变量

| 变量 | 用途 | 开发默认 | 生产默认 |
|---|---|---|---|
| `VITE_GOODS_IMG_BASE` | 商品图片资源前缀，拼接后端相对路径 `/img/xx.png` | `http://localhost:8080` | 空（同源，由 nginx 提供） |

> 生产部署若图片在独立 CDN，构建前覆盖：`VITE_GOODS_IMG_BASE=https://cdn.example.com npm run build`

---

## 三种跑法

### 0. 前置：后端服务就绪

前端依赖后端（Spring Boot）+ MySQL + Redis + Kafka。后端启动见 `backend/` 目录与 `backend/docker-compose.yaml`：

```bash
cd backend
docker compose up -d          # MySQL + Redis + Kafka
# 后端 Spring Boot 监听 :8080
```

> 后端无 context-path，所有接口前缀为 `/`。前端通过 `/api` 反代并去掉前缀（dev proxy 与 nginx 一致）。

### 1. 本地开发（dev server + proxy）

```bash
cd frontend
npm install
npm run dev          # http://localhost:5173
```

- Vite dev server 监听 5173，`/api/*` 自动代理到 `http://localhost:8080/*`（去 `/api` 前缀）。
- 图片 base 由 `.env.development` 指向 `http://localhost:8080`。
- HMR 热更新，改动即生效。

### 2. 本地生产验证（build + preview）

```bash
cd frontend
npm install
npm run build        # tsc 类型检查 + vite build → dist/
npm run preview      # http://localhost:4173（vite preview，serve dist/）
```

- 用于在本地验证生产构建产物是否正常。
- 注意：`vite preview` **不**提供 `/api` 代理，需后端直接监听 4173 同源，或用 nginx（见方式 3）做反代。
- 如需在 preview 下联调后端，可临时把后端起在 4173 同端口，或直接用 Docker 方式。

### 3. 容器部署（Docker build + run）

```bash
cd frontend
# 构建镜像（默认反代到 host.docker.internal:8080，即宿主机后端）
docker build -t seckill-web .

# 运行（80 端口）
docker run -d --name seckill-web -p 80:80 seckill-web
# 访问 http://localhost
```

**自定义后端地址**（后端不在宿主机）：

```bash
docker build \
  --build-arg BACKEND_HOST=10.0.0.5 \
  --build-arg BACKEND_PORT=8080 \
  -t seckill-web .

docker run -d --name seckill-web -p 80:80 seckill-web
```

- nginx 监听 80，`/api/*` 反代到 `${BACKEND_HOST}:${BACKEND_PORT}/*`（去 `/api` 前缀）。
- SPA 路由回退：除静态资源外一律回 `index.html`，交给 react-router。
- 静态资源 `/assets/*` 长缓存 1 年（hash 文件名，immutable）；`index.html` 不缓存。
- 健康检查：`GET http://localhost/` 返回 200。
- 与后端容器同网络部署时，`BACKEND_HOST` 填后端容器名（需在同一 docker network）。

---

## 测试与质量

```bash
npm run typecheck    # tsc --noEmit 类型检查
npm test             # vitest run 全量单测（纯函数 + hook 探针）
npm run build        # 生产构建（含类型检查）
```

## 构建产物

`npm run build` 产出 `dist/`：

- `index.html` — 入口（不缓存）
- `assets/index-[hash].js` — 业务代码
- `assets/react-[hash].js` — react/react-dom/react-router vendor
- `assets/antd-[hash].js` — antd vendor
- `assets/query-[hash].js` — tanstack query vendor

vendor 与业务代码拆分，便于浏览器长缓存。
