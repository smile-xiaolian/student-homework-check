# 学生作业提交与查重系统

第一轮迭代的可运行工程骨架，覆盖用户登录、课程与花名册、作业发布、文件/在线文本提交、异步查重、查重报告、教师评审、成绩发布、通知和管理员基础功能。

## 技术架构

| 模块 | 技术 | 默认端口 |
| --- | --- | --- |
| Web 前端 | Vue 3、TypeScript、Vite、Element Plus、Pinia | 8088（容器）/ 5173（开发） |
| 教学服务 | Java 21、Spring Boot 3、MyBatis-Plus、Flyway、JWT | 8080 |
| 查重 Worker | Python 3.12、psycopg 3、boto3 | 无公开端口 |
| 数据库 | PostgreSQL 16，`app` 与 `audit` schema | 5432 |
| 对象存储 | MinIO，S3 兼容接口 | 9000 / 9001 |

查重队列使用数据库任务表、租约和 `FOR UPDATE SKIP LOCKED`，不依赖 Redis 或消息中间件。第一轮不启用互联网库、文献库、压缩包查重和小组提交。

## 目录结构

```text
backend/     Spring Boot 教学服务与数据库迁移
engine/      Python 查重 Worker、算法与单元测试
web-app/     Vue 3 前端
ops/         PostgreSQL 初始化脚本
scripts/     本地运维辅助脚本
.github/     CI 流水线
```

## 一键启动

前置条件：Docker Desktop（含 Docker Compose）已安装并启动。

```powershell
Copy-Item .env.example .env
docker compose up -d --build
docker compose ps
```

也可以使用运维脚本完成启动与健康等待：

```powershell
.\scripts\compose-up.ps1
.\scripts\smoke-test.ps1
```

首次构建需要下载镜像和依赖。服务就绪后可访问：

- Web 应用：<http://localhost:8088>
- API：<http://localhost:8080/api/v1>
- Swagger UI：<http://localhost:8080/swagger-ui.html>
- 健康检查：<http://localhost:8080/actuator/health>
- MinIO 控制台：<http://localhost:9001>

### 日常启动 / 重启 / 停止

镜像构建完成后，日常操作只需：

```powershell
# 开机后启动系统（无需 --build，秒级启动）
docker compose up -d

# 查看各服务状态，全部应为 Up（web-app 依赖 teaching-service 健康后启动）
docker compose ps

# 系统级重启（保留数据）
docker compose restart

# 停止系统（数据保留在 Docker 卷中，下次启动不变）
docker compose down

# 改动代码后重新构建并启动
docker compose up -d --build
```

> 端口提示：本项目占用 5432（PostgreSQL）、9000/9001（MinIO）、8080（API）、8088（Web）。
> 同目录下的 student-homework-check 项目同样使用 5432，两套系统不能同时运行：
> 切换前先 `docker compose down` 停掉本系统，或 `docker compose -p homework-check stop` 暂停对方。

查看日志或停止服务：

```powershell
docker compose logs -f teaching-service check-worker
docker compose down
```

如需连同数据库与对象存储数据一起清理，执行 `docker compose down -v`。该操作会删除全部本地演示数据。

## 演示账号

| 角色 | 登录名 | 密码 |
| --- | --- | --- |
| 管理员 | `admin` | `Admin@123` |
| 教师 | `teacher01` 或 `T2026001` | `Teacher@123` |
| 助教 | `assistant01` 或 `A2026001` | `Assistant@123` |
| 学生 | `2023001001` | `Student@123` |

演示课程为《数据库系统原理》，邀请码 `DB2026FALL`。共享环境部署前必须修改演示密码、`JWT_SECRET`、数据库密码和 MinIO 密码，或将 `APP_DEMO_DATA` 设为 `false`。

## 本地开发

先启动基础设施：

```powershell
docker compose up -d postgres minio minio-init
```

### 后端

需要 JDK 21 与 Maven 3.9+。PowerShell 示例：

```powershell
$env:DB_URL='jdbc:postgresql://localhost:5432/edu_submission?stringtype=unspecified'
$env:DB_USERNAME='edu_app'
$env:DB_PASSWORD='change-me-postgres'
$env:STORAGE_ENDPOINT='http://localhost:9000'
$env:STORAGE_ACCESS_KEY='edu_minio'
$env:STORAGE_SECRET_KEY='change-me-minio-password'
Set-Location backend
mvn spring-boot:run
```

Flyway 会在启动时自动创建表和系统参数，并写入演示数据。生产环境请使用独立迁移账号并关闭演示数据。

### 前端

需要 Node.js 22 与 pnpm 10+：

```powershell
Set-Location web-app
pnpm install --frozen-lockfile
pnpm dev
```

开发服务器已代理 `/api` 与 `/actuator` 到 `http://localhost:8080`。

### 查重 Worker

需要 Python 3.12：

```powershell
Set-Location engine
python -m venv .venv
.\.venv\Scripts\Activate.ps1
pip install -r requirements.txt
$env:DATABASE_URL='postgresql://edu_app:change-me-postgres@localhost:5432/edu_submission'
$env:STORAGE_ENDPOINT='http://localhost:9000'
$env:STORAGE_ACCESS_KEY='edu_minio'
$env:STORAGE_SECRET_KEY='change-me-minio-password'
python -m checker.worker
```

## 校验命令

```powershell
Set-Location backend
mvn -B package

Set-Location ..\web-app
pnpm build

Set-Location ..\engine
python -m unittest discover -s tests -v
```

后端健康检查：

```powershell
Invoke-RestMethod http://localhost:8080/actuator/health
Invoke-RestMethod http://localhost:8080/api/v1/system/health
```

## 关键配置

| 变量 | 说明 | 默认值 |
| --- | --- | --- |
| `JWT_SECRET` | JWT 签名密钥，至少 32 字符 | 仅开发默认值 |
| `APP_CORS_ORIGINS` | 允许的前端来源 | `http://localhost:5173,http://localhost:8088` |
| `UPLOAD_MAX_FILE_SIZE` | 单文件上限 | `100MB` |
| `UPLOAD_MAX_REQUEST_SIZE` | 单请求上限 | `110MB` |
| `STORAGE_ENDPOINT` | S3/MinIO 地址 | `http://localhost:9000` |
| `APP_DEMO_DATA` | 是否写入演示数据 | `true` |
| `CHECK_POLL_SECONDS` | Worker 空闲轮询间隔 | `2` |
| `CHECK_LEASE_SECONDS` | 查重任务租约时长 | `120` |

前端 Nginx 已将请求体上限同步为 110MB；调整上传限制时需同时修改 Nginx、Spring Multipart 和系统参数 `file.max_size_bytes`。

## 第一轮功能状态

已完成：登录与 RBAC、课程与花名册、作业发布与查重配置、量规、文件/在线文本提交、多版本与生效版本、迟交标记与扣分、文件类型和大小校验、异步查重、文本/代码相似度、报告查询与校验、评审草稿与发布、站内通知、管理员参数与审计查询、评审队列和提交进度。

后续轮次：助教评审分配、课程公告、作业附件、压缩包查重、预查重、互联网/文献库、申诉延期、成绩分布与课程总成绩、批量下载和批量评分。

## 故障排查

- `minio` 镜像拉取失败（denied / not found）：MinIO 已于 2025 年 10 月从 Docker Hub 和 Quay 撤回社区镜像，本仓库已改用 Chainguard 免费镜像 `cgr.dev/chainguard/minio`（同一 MinIO 服务器二进制）。若 `cgr.dev` 也无法访问，可改用其他 S3 兼容对象存储并同步调整 `STORAGE_ENDPOINT` 等配置。
- `teaching-service` 未就绪：先查看 `docker compose logs postgres minio minio-init teaching-service`，确认数据库健康且 MinIO 桶初始化完成。
- 上传返回 413：检查前端反向代理 `client_max_body_size`、`UPLOAD_MAX_REQUEST_SIZE` 与系统参数 `file.max_size_bytes`。
- 查重任务一直排队：确认 `check-worker` 正在运行，并检查数据库连接与对象存储凭据。
- 浏览器登录后接口 401：确认 `JWT_SECRET` 未在服务运行期间更换，且允许来源包含当前前端地址。

