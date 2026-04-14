# 智行车道调度系统

基于项目说明文档、技术文档和本地 Figma MCP 设计稿实现的完整前后端项目。当前仓库包含：

- `web/`: `Next.js 16 + React 19 + TypeScript + Tailwind CSS 4 + TanStack Query + Zustand`
- `server/`: `Spring Boot 3.5 + Spring Security + JPA + MySQL/H2 + Redis + WebSocket`
- `deploy/`: `Nginx + MySQL 初始化脚本`
- `docs/`: 接口与部署文档
- `scripts/`: 一键启动/停止脚本

## 功能覆盖

- 登录与基础角色权限控制
- 车道状态总览与 LED 联动展示
- 车辆入场记录查询与条件检索
- 黑名单新增、编辑、删除与预警联动
- 入口/出口信号灯手动控制与全域自动恢复
- 手动修正、优先放行与人工调度指令
- 预警中心查看与处置
- WebSocket 实时刷新前端数据
- Swagger/OpenAPI 在线接口文档
- Docker Compose 一键部署

## 目录结构

```text
smart-lane-dispatch-system/
├─ web/
│  ├─ app/
│  ├─ components/
│  ├─ lib/
│  ├─ providers/
│  └─ stores/
├─ server/
│  ├─ src/main/java/com/smartlane/dispatch/
│  │  ├─ config/
│  │  ├─ controller/
│  │  ├─ device/
│  │  ├─ dto/
│  │  ├─ entity/
│  │  ├─ security/
│  │  ├─ service/
│  │  └─ repository/
│  └─ src/main/resources/
├─ deploy/
├─ docs/
├─ scripts/
└─ README.md
```

## 本地运行

前端推荐 Node 版本为 `v23.4.0`，仓库根目录已写入 `.nvmrc`。后端支持 H2 与 MySQL，但默认不再注入任何演示数据。

首次在新电脑运行前请先确认：

- 已安装 `Node.js 23.x`
- 已安装 `JDK 21+`
- 本地开发若不使用 Docker，前端默认访问 `http://localhost:8080` 后端
- 如果数据库为空，系统不会自动生成普通业务数据；需要先导入真实用户，或显式开启 bootstrap admin

```bash
nvm use

cd server
./mvnw spring-boot:run

cd ../web
npm install
npm run dev
```

前端默认地址：`http://localhost:3000`

后端默认地址：`http://localhost:8080`

接口文档：`http://localhost:8080/swagger-ui.html`

## Docker Compose

```bash
cp .env.example .env
./scripts/start-stack.sh
```

统一访问入口默认是：`http://localhost:3002`

- 前端：`http://localhost:3002`
- 后端 API：`http://localhost:3002/api`
- Swagger：`http://localhost:3002/swagger-ui.html`
- 健康检查：`http://localhost:3002/actuator/health`

## 说明

- 默认开发模式使用 H2；Compose 部署模式使用 `MySQL + Redis + Nginx`
- 系统默认不再生成演示账号、车道、日志、黑名单或预警数据
- 如果数据库为空，接口会返回空结果；登录需要数据库中预先存在真实用户，或显式启用 bootstrap admin
- 如需运维引导管理员，可通过 `APP_BOOTSTRAP_ADMIN_*` 环境变量显式创建受保护管理员账号
- bootstrap admin 的启用方式、轮换和保护规则见 `docs/DEPLOY.md`
- 接口清单见 `docs/API.md`
- 部署说明见 `docs/DEPLOY.md`
- 后端测试已通过：`./mvnw test`
- 前端构建与静态检查已通过：`npm run build`、`npm run lint`
