# =============================================================================
# FileShare 前端 + 反向代理镜像
# 默认用官方 Docker Hub 基础镜像；国内网络下由 docker-compose 的 REGISTRY_PREFIX
# 统一注入镜像仓库前缀。npm 走 npmmirror，构建不依赖代理。
# 多阶段：node 编译出静态 dist -> 复制进精简 nginx，并注入容器专用 nginx.conf，
# 由 nginx 同时托管前端静态页并反代 /api 到 backend:9090（与原架构一致）。
# =============================================================================

ARG NODE_IMAGE=node:20-alpine
ARG NGINX_IMAGE=nginx:1.27-alpine

# ---------- 阶段 1：Vue 生产构建（package.json 要求 Node >=20.19） ----------
FROM ${NODE_IMAGE} AS build
WORKDIR /app
# 先装依赖并走 npmmirror 国内源。
# 优先用 npm ci（严格按 package-lock 复现）；若锁文件与 package.json 不同步
# （npm ci 会直接报 EUSAGE 拒绝），则回退到 npm install 兜底。
# 正确的长期做法是本地用同版本 node 重新生成 package-lock.json，详见 README。
COPY package*.json ./
RUN npm ci --registry=https://registry.npmmirror.com \
 || npm install --registry=https://registry.npmmirror.com --no-audit --no-fund --legacy-peer-deps
# 再拷源码构建。
COPY . .
RUN npm run build

# ---------- 阶段 2：nginx 运行时 ----------
FROM ${NGINX_IMAGE}
# 容器专用 server 配置（反代到同网络的后端服务名 backend）。
COPY nginx.conf /etc/nginx/conf.d/default.conf
# 覆盖官方默认首页目录为前端构建产物。
COPY --from=build /app/dist /usr/share/nginx/html
EXPOSE 80
