# 多阶段构建：node 阶段产出静态资源 → nginx 阶段 serve 并反代 /api

# ---------- 1. 构建阶段 ----------
FROM node:20-alpine AS builder
WORKDIR /app

# 先拷依赖清单，利用层缓存
COPY package.json package-lock.json ./
RUN npm ci

# 拷源码并构建（.env.production 控制图片 base 等环境差异）
COPY . .
RUN npm run build

# ---------- 2. 运行阶段 ----------
FROM nginx:1.27-alpine AS runner

# 后端地址构建参数：默认指向宿主机后端（macOS/Windows Docker Desktop 支持 host.docker.internal）
# 生产/远程部署：docker build --build-arg BACKEND_HOST=10.0.0.5 --build-arg BACKEND_PORT=8080 -t seckill-web .
ARG BACKEND_HOST=host.docker.internal
ARG BACKEND_PORT=8080

# 拷自定义 nginx 配置（SPA 回退 + /api 反代 + 静态资源缓存），并替换后端地址占位
# 注意：不使用 /etc/nginx/templates（会被 envsubst 误伤 $host 等 nginx 变量），直接放 conf.d
COPY nginx.conf /etc/nginx/conf.d/default.conf
RUN sed -i "s|BACKEND_HOST|${BACKEND_HOST}|g; s|BACKEND_PORT|${BACKEND_PORT}|g" \
    /etc/nginx/conf.d/default.conf

# 拷构建产物到 nginx 静态目录
COPY --from=builder /app/dist /usr/share/nginx/html

EXPOSE 80

# 健康检查：容器自检 nginx 是否在响应。
# 用 127.0.0.1 而非 localhost：busybox wget 会把 localhost 解析到 IPv6 ::1，而 nginx 仅监听 IPv4，导致误判
HEALTHCHECK --interval=30s --timeout=3s --start-period=5s --retries=3 \
  CMD wget -qO- http://127.0.0.1/ >/dev/null 2>&1 || exit 1

CMD ["nginx", "-g", "daemon off;"]
