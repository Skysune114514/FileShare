# FileShare

一个自托管的**文件共享 / 网盘**平台：支持用户注册登录、多项目文件树、上传下载、Office 文档在线转 PDF 预览、历史版本、临时分享链接（可选 PIN）、公开广场，以及管理员日志。

前后端分离 + Docker 一键部署，开箱即用。

## 技术栈

| 层 | 技术 |
|---|---|
| 前端 | Vue 3 + Vite + Vue Router + Axios |
| 后端 | Spring Boot 3.2（Java 17）+ MyBatis-Plus + Spring Security (Crypto) + Spring Session |
| 数据库 | MySQL 8 |
| 缓存 / 会话 | Redis 7（Spring Session） |
| 反向代理 / 静态托管 | Nginx |
| 文档转换 | LibreOffice（headless，Office → PDF 在线预览） |

## 架构

```
浏览器
  │  http://localhost:80
  ▼
web 容器 (Nginx) ── 托管 Vue 静态产物，History 路由回退
  │  /api/* 反代（保留前缀）
  ▼
backend 容器 (Spring Boot :9090 + LibreOffice)
  │                          │
  ▼                          ▼
mysql:8 容器 (fileshare 库)   redis:7 容器 (Spring Session)
```

## 快速开始（Docker 一键部署）

> 前置：安装 [Docker Desktop](https://www.docker.com/products/docker-desktop/)（含 docker compose）。

```bash
git clone <你的仓库地址>
cd FileShare

# 构建并启动（首次会拉镜像 + 编译，稍慢）
docker compose up -d --build

# 查看状态（等 backend 显示 healthy）
docker compose ps
```

启动完成后访问 **http://127.0.0.1/**（Docker Desktop + WSL2 下建议用 `127.0.0.1` 而非 `localhost`，见下文 FAQ）。

页面内注册账号即可使用。默认无管理员，提升方式见下方「管理后台」。

### 国内网络：切换镜像源

海外 / CI 环境默认走官方 Docker Hub，无需任何配置。国内拉不动镜像时，一行切换：

```bash
cp .env.example .env
# 编辑 .env，取消 REGISTRY_PREFIX 这行注释（或改成可用仓库）：
#   REGISTRY_PREFIX=docker.m.daocloud.io/library/
docker compose up -d --build
```

> 说明：构建阶段的 `apt`/`Maven`/`npm` 已分别走阿里云 / 阿里云 / npmmirror，**全程无需代理**。详见 [`README-docker.md`](README-docker.md)。

## 本地开发（不用 Docker）

后端（需本机 JDK 17 + Maven + MySQL + Redis）：

```bash
cd fileshare_server
./mvnw spring-boot:run      # 默认 learn profile，监听 9090
```

前端（需 Node ≥ 20.19）：

```bash
cd fileshare_browser
npm install
npm run dev                 # Vite dev server，/api 已代理到 9090
```

## 目录结构

```
.
├── fileshare_browser/   # 前端（Vue 3 + Vite）
│   ├── Dockerfile       # node 构建 → nginx 托管
│   └── nginx.conf       # 容器版 nginx：静态 + /api 反代
├── fileshare_server/    # 后端（Spring Boot）
│   ├── Dockerfile       # maven 构建 → JRE + LibreOffice
│   └── src/main/resources/schema/  # 启动时自动建表脚本
├── docker-compose.yml   # 一键编排 4 个服务 + 命名卷
├── .env.example         # 配置模板（端口 / 镜像源 / 数据库口令）
├── scripts/deploy.sh    # 一键部署脚本
└── README-docker.md     # 详细部署说明与踩坑记录
```

## 配置

所有可调项集中在 `.env`（由 `.env.example` 复制而来）：

| 变量 | 说明 | 默认 |
|---|---|---|
| `WEB_PORT` | web 对外端口 | `80` |
| `REGISTRY_PREFIX` | 镜像仓库前缀，留空 = 官方 Docker Hub | 空 |
| `MYSQL_DATABASE/USER/PASSWORD` | 数据库名 / 账号 / 密码 | `fileshare` / `fileshare` / `fileshare_pass` |
| `MYSQL_ROOT_PASSWORD` | MySQL root 口令 | `fileshare_root` |

> ⚠️ 上线前请务必修改默认数据库口令（`.env` 已被 `.gitignore` 忽略，不会提交）。

## 默认账号

首次启动会自动种入一个演示管理员（幂等，重复启动不会重复插入）：

| 账号 | 密码 | 角色 |
|---|---|---|
| `admin@fileshare.local` | `admin123` | admin |

> 由后端启动时经 `schema/admin_seed.sql` 灌入。**生产环境请务必修改密码或删除此账号。**

如需把其它注册用户提升为管理员：

```bash
docker compose exec mysql mysql -ufileshare -pfileshare_pass fileshare \
  -e "UPDATE user SET role='admin' WHERE email='你的注册邮箱';"
```

## 常见问题

- **`localhost` 无响应但 `127.0.0.1` 正常（WSL2）**：浏览器把 `localhost` 解析成 IPv6 `::1`，被 `wslrelay.exe` 占用，而 Docker 端口转发绑在 IPv4。直接用 `http://127.0.0.1/`，或改 `WEB_PORT=8080`。
- **backend 显示 unhealthy**：首次启动要等它自动建表，约 10~60 秒；仍不行看 `docker compose logs backend`。
- **国内拉镜像慢/失败**：按上文设 `REGISTRY_PREFIX` 切镜像站。
- **数据在哪**：MySQL 与上传文件分别落在命名卷 `mysql-data`、`file-storage` 里，`docker compose down`（不带 `-v`）不会丢；`down -v` 会清空。

更多细节（Office 预览、内存/镜像体积优化、探针说明、踩坑记录）见 [`README-docker.md`](README-docker.md)。
