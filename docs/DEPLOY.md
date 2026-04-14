# 部署说明

## 方式一: 本地开发

1. 启动后端:

```bash
cd /Users/muleng/Workspace/smart-lane-dispatch-system/server
./mvnw spring-boot:run
```

2. 启动前端:

```bash
cd /Users/muleng/Workspace/smart-lane-dispatch-system/web
npm install
npm run dev
```

## 方式二: Docker Compose

首次使用:

```bash
cd /Users/muleng/Workspace/smart-lane-dispatch-system
cp .env.example .env
./scripts/start-stack.sh
```

停止:

```bash
./scripts/stop-stack.sh
```

默认地址:

- 前端入口: `http://localhost:3002`
- Swagger: `http://localhost:3002/swagger-ui.html`
- 健康检查: `http://localhost:3002/actuator/health`

## 组件说明

- `mysql`: 业务持久化
- `redis`: 仪表盘缓存
- `server`: Spring Boot API + WebSocket
- `web`: Next.js 前端
- `nginx`: 统一入口，转发 `/`、`/api`、`/ws`

## 环境变量

可在根目录 `.env` 中调整:

- `APP_HTTP_PORT`
- `MYSQL_PORT`
- `MYSQL_ROOT_PASSWORD`
- `MYSQL_DATABASE`
- `MYSQL_USER`
- `MYSQL_PASSWORD`
- `REDIS_PORT`
- `APP_BOOTSTRAP_ADMIN_ENABLED`
- `APP_BOOTSTRAP_ADMIN_USERNAME`
- `APP_BOOTSTRAP_ADMIN_PASSWORD`
- `APP_BOOTSTRAP_ADMIN_DISPLAY_NAME`
- `APP_BOOTSTRAP_ADMIN_STATION`

## 数据初始化

- `deploy/mysql/init/01-bootstrap.sql` 用于初始化默认数据库
- 后端默认不会写入任何演示账号或业务演示数据
- 数据库为空时，业务列表返回空数组；登录接口只有在用户表存在真实账号时才会成功
- 如需运维兜底管理员，可显式开启 `APP_BOOTSTRAP_ADMIN_ENABLED=true`，系统会创建或重置一个受保护 `ADMIN` 账号

## Bootstrap Admin 使用方法

适用场景:

- 首次交付后数据库为空，暂时没有任何可登录管理员
- 现有管理员遗失凭据，需要安全恢复后台访问
- 需要在受控窗口内重置引导管理员密码

启用方式:

```bash
APP_BOOTSTRAP_ADMIN_ENABLED=true \
APP_BOOTSTRAP_ADMIN_USERNAME='your-admin' \
APP_BOOTSTRAP_ADMIN_PASSWORD='your-strong-password' \
APP_BOOTSTRAP_ADMIN_DISPLAY_NAME='Bootstrap Admin' \
APP_BOOTSTRAP_ADMIN_STATION='System Control' \
SPRING_PROFILES_ACTIVE=mysql \
./mvnw spring-boot:run
```

行为说明:

- 当 `APP_BOOTSTRAP_ADMIN_ENABLED=true` 时，系统启动阶段会创建或重置该账号
- 该账号会被写入 `user_accounts` 表，角色固定为 `ADMIN`
- 该账号会被标记为 `system_protected=1`
- 如果仅开启开关但未提供用户名或密码，服务会直接启动失败，避免生成空口令账号

登录方式:

- 与普通用户完全一致，直接通过 `POST /api/auth/login` 登录
- 当前交付环境的实际账号口令应通过安全渠道单独交付，不应写入仓库文档、代码或 `.env.example`

## 运维规则

口令管理:

- bootstrap admin 密码必须通过环境变量或密钥管理系统注入
- 不要把真实口令提交到 Git、部署脚本、前端配置或共享文档
- 建议首次接管后立即轮换为专用运维口令

轮换规则:

- 需要重置密码时，使用新的 `APP_BOOTSTRAP_ADMIN_PASSWORD` 再次启动服务
- 启动成功后，该账号的密码哈希会被覆盖更新

停用规则:

- 日常运行建议关闭 `APP_BOOTSTRAP_ADMIN_ENABLED`
- 关闭后不会再自动重置该账号，但数据库中已有账号仍会保留
- 若后续需要彻底禁用，应由数据库管理员或后续用户管理接口执行人工停用/改密

保护规则:

- 该账号在数据库中带有 `system_protected` 标记
- 当前项目没有用户删除接口，因此现阶段不会被前端或现有 API 删除
- 未来如果新增用户管理能力，必须显式校验 `system_protected`，禁止普通流程删除或降权该账号

审计建议:

- 启用 bootstrap admin 应作为受控运维动作登记
- 建议记录启用时间、操作人、目标环境、账号名和轮换时间
- 不要在日志中打印明文密码
