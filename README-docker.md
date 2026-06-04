# FileShare Docker 部署

把**前端 + 后端 + nginx + MySQL + Redis** 用 `docker compose` 编排成 5 个容器，实现与原项目一致的全部功能（登录注册、文件树、上传/下载、Office 转 PDF 预览、分享、广场、历史版本、管理后台）。

## 架构与网络

```
浏览器
  │  http://localhost:80
  ▼
web 容器 (nginx:alpine)  ── 托管 Vue dist，History 路由回退
  │  /api/* 反代（保留前缀）
  ▼
backend 容器 (Spring Boot :9090 + LibreOffice)
  │                          │
  ▼                          ▼
mysql:8 容器 (fileshare 库)   redis:7 容器 (Spring Session)
```

- 5 个容器在同一自定义网络 `fileshare-net`，应用通过**服务名** `mysql` / `redis` / `backend` 互通。
- 3 个**命名卷**持久化：`mysql-data`(库数据)、`file-storage`(上传文件/预览缓存)、`redis-data`(会话,可选)。

## 快速开始

```bash
# 1) 可选：按需改密码/端口
cp .env.example .env

# 2) 构建镜像（基础镜像走国内仓库，构建内 apt/maven/npm 也走国内源，无需代理）
docker compose build

# 3) 启动
docker compose up -d

# 4) 查看启动/健康状态
docker compose ps
docker compose logs -f backend web

# 5) 访问
# 打开 http://localhost   （WEB_PORT=80 默认；若改端口则用 http://localhost:<端口>）
# 在页面注册新账号即可使用。默认管理员账号需在库里手动改 role（见“管理后台”）。
```

停止/清理：

```bash
docker compose down          # 停容器，保留数据卷
docker compose down -v       # 连同数据卷一起删除（慎用，会清空上传文件与数据库）
docker compose pull          # 升级基础镜像
docker compose build backend web && docker compose up -d   # 改代码后重建
```

## 免代理 · 国内镜像源（按需开启）

默认用**官方 Docker Hub**（海外 / CI 直接用）；国内网络下用 `.env` 一行 `REGISTRY_PREFIX` 切换，构建时容器内的 apt/maven/npm 始终走国内源，**完全不需要代理**：

| 层 | 走的源 | 配置位置 |
|---|---|---|
| 基础镜像（maven/jre/node/nginx/mysql/redis） | 默认官方；国内 `docker.m.daocloud.io` | `.env` 的 `REGISTRY_PREFIX` + compose `image`/`build.args` |
| apt（Ubuntu 装 LibreOffice） | `mirrors.aliyun.com` | `fileshare_server/Dockerfile` |
| Maven 依赖 | `maven.aliyun.com`（写入 `/root/.m2/settings.xml`） | `fileshare_server/Dockerfile` |
| npm 依赖 | `registry.npmmirror.com` | `fileshare_browser/Dockerfile` |

**切换镜像站（一行）**：

```bash
cp .env.example .env
# 取消下面这行注释（或换成其它可用仓库）：
#   REGISTRY_PREFIX=docker.m.daocloud.io/library/
docker compose build
```

> 实测经验：`docker.1ms.run` 的 `/v2/` 虽然返回 `401`，但它的层数据会 302 到 CloudFront 域名
> `cloudfront-docker-cf.mrs.1ms.run`，该域名在部分网络下 **DNS 解析失败**（`no such host`），导致拉取失败。
> 建议用 `docker.m.daocloud.io`。
>
> 判断镜像站是否真正可用，不能只看 `/v2/` 的响应码，要**实际 `docker pull` 一个小镜像**验证。

## 数据库如何初始化

- MySQL 首次启动（数据卷为空）由镜像自动建库 `fileshare`、建用户。
- **表结构由后端在启动时自动创建**：Spring `spring.sql.init.mode: always` 执行 classpath 下
  幂等的 `CREATE TABLE IF NOT EXISTS` 脚本（5 张表，最新结构）。
- 因此**无需**手工灌 `database/schema.sql`。该文件是含本机物理文件路径的开发 dump，
  灌进容器只会产生无法下载/预览的死链记录，徒增体积——不建议使用。
  > 若确实想要演示数据，可 `docker compose exec -T mysql mysql -ufileshare -p... fileshare < database/schema.sql`，
  > 但需同步把 dump 引用的 `2026/05/...` 物理文件放进 `file-storage` 卷对应目录，否则仍会 404。

## 环境/资源配置说明

所有可调项集中在 `docker-compose.yml` + `.env`：

| 项 | 说明 |
|---|---|
| `MYSQL_DATABASE/USER/PASSWORD` | 库名/账号/密码，JDBC url 与其联动，默认 `fileshare/fileshare_pass` |
| `SPRING_DATASOURCE_URL` | 容器内用 `mysql:3306`，切勿用 localhost |
| `SPRING_DATA_REDIS_HOST` | `redis`（服务名） |
| `FILE_STORAGE_ROOT` | 上传文件根，已挂到 `file-storage` 卷 |
| `FILE_PREVIEW_LIBREOFFENCE_*` | LibreOffice 开关与路径；`/usr/bin/soffice` 已在镜像内 |
| `WEB_PORT` | web 对外端口，默认 80 |

**内存**（已在 compose 里收紧，避免占满宿主机）：

- `backend`：`mem_limit 2048m`，JVM 用 `MaxRAMPercentage=50` + SerialGC → 堆约 1G，
  其余留给 soffice 转换子进程。**多用户并发转 PDF 时若 OOM，调大 `mem_limit`**。
- `mysql`：`mem_limit 1024m`，buffer pool 限制 128M。
- `redis`：`mem_limit 128m`，`maxmemory 64mb`（volatile-lru，会话键带 TTL 安全）。
- `web`：`mem_limit 256m`，nginx 缓冲已关闭（大文件流式）。

**镜像体积最小化策略**：

- 前后端均多阶段构建，构建工具不进入最终镜像。
- LibreOffice 只装转 PDF 必需的 writer/calc/impress（覆盖 doc/xls/ppt 等）+
  `fontconfig` + `fonts-noto-cjk`（中文不乱码），并 `--no-install-recommends` 去掉冗余。
- 前后端各自 `.dockerignore` 排除 `node_modules`/`dist`/`target`/`data`/`.git` 等，
  只把源码打进构建上下文。

> 若完全用不到 Office 转 PDF，可把后端 `FILE_PREVIEW_LIBREOFFICE_ENABLED=false`，
> 并删去 `Dockerfile` 中 LibreOffice 安装段重建，镜像会再小数百 MB（此操作需同时删 `server` 段重新 build）。

## LibreOffice 中文预览

- 容器内已装 `fonts-noto-cjk` 并配 `fontconfig`，转换 doc/docx/xls/ppt 成 PDF 中文正常。
- 首次转换有冷启动延迟，保留 `timeout-seconds: 120` 足够；单并发预览按文件锁排队，不会叠进程。

## 健康检查与启动顺序

`mysql`/`redis` 用官方 healthcheck，`backend` 用 `bash /dev/tcp` 探测 9090 监听；
`backend` 等 `mysql`+`redis` healthy，`web` 等 `backend` healthy 后才对外提供，杜绝半启动访问 502。

> 注意：`backend` 的探针必须写成 `["CMD", "bash", "-c", ...]` 而**不能**用 `CMD-SHELL`——
> Ubuntu 的 `/bin/sh` 是 dash，不支持 `/dev/tcp`，会让探针恒失败、容器一直 `unhealthy`。

## 默认账号

首次启动自动种入演示管理员（幂等）：`admin@fileshare.local` / `admin123`（role=admin）。
生产环境请登录后改密或删除。把其它用户提升为管理员：

```bash
docker compose exec mysql mysql -ufileshare -pfileshare_pass fileshare \
  -e "UPDATE user SET role='admin' WHERE email='你的注册邮箱';"
```

## 前端依赖锁定文件不同步（构建报 `npm error code EUSAGE`）

若 `web` 构建报：

```
`npm ci` can only install packages when your package.json and package-lock.json are in sync
Missing: @emnapi/core@... from lock file
```

说明 `fileshare_browser/package-lock.json` 已过期（与 `package.json` 不一致）。

- **临时**：Dockerfile 已内置回退——`npm ci` 失败时自动改用 `npm install --legacy-peer-deps`，构建不会中断。
- **彻底修复（推荐）**：用与构建**完全相同**的 node/npm 重新生成锁文件：

  ```bash
  cd fileshare_browser
  docker run --rm -v "$PWD":/app -w /app docker.m.daocloud.io/library/node:20-alpine \
    npm install --registry=https://registry.npmmirror.com
  # 生成后提交新的 package-lock.json，之后 npm ci 即可严格复现
  git add package-lock.json
  ```

## 常见问题

- **访问 502**：等 `docker compose ps` 显示 backend healthy 再访问；或 `docker compose logs backend` 看是否连不上库。
- **端口占用**：改 `.env` 的 `WEB_PORT` 后 `docker compose up -d` 重建 web。
- **想保留登录态**：重启后 Redis 清空会要求重新登录，属正常（Session 存 Redis 的默认行为）。
- **改完代码**：`docker compose build backend web` 即可只重建应用层，db/redis 不动。
- **`up` 报"免费节点当前繁忙"/轩辕镜像**：说明 Docker Desktop 的 `registry-mirrors` 里还残留
  `docker.xuanyuan.me`。清空该字段（本方案基础镜像已带 daocloud 前缀，不需要 registry-mirrors）。
- **基础镜像拉不动**：先把对应镜像单独 `docker pull` 验通（要**实际拉取**，不能只看 `curl /v2/` 的响应码），
  再 `docker compose build`。
- **`http://127.0.0.1/` 能开、`http://localhost/` 没反应（WSL2 常见）**：
  浏览器把 `localhost` 优先解析为 IPv6 `::1`，而该地址被 Windows 的 `wslrelay.exe` 占用
  （Docker 的端口转发 `com.docker.backend` 绑的是 IPv4 `0.0.0.0:80`），请求被中继进 WSL 里导致无响应。
  解决：直接访问 `http://127.0.0.1/`；或在 Windows `hosts` 文件保留 `127.0.0.1 localhost` 并注释掉 `::1 localhost`；
  或改用非 80 端口（`.env` 里 `WEB_PORT=8080`）。
