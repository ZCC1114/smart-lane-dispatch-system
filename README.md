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
- 黑名单新增、编辑、删除与命中核验
- 入口/出口信号灯手动控制与全域自动恢复
- 手动修正、优先放行与人工调度指令
- 总入口抓拍预分配、大屏推荐车道、实际入道核验与错道留痕
- 上班日清按钮：清空前一班次在场数据并重新打开首条入口/出口车道
- 车牌识别、计数报警相机、DIDO 红绿灯模块 MQTT 对接
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

前端推荐 Node 版本为 `v23.4.0`，仓库根目录已写入 `.nvmrc`。后端本地默认连接 MySQL，不再使用 H2 内存库或 `create-drop`。

首次在新电脑运行前请先确认：

- 已安装 `Node.js 23.x`
- 已安装 `JDK 21+`
- 已启动本机 MySQL，默认连接 `127.0.0.1:3306/smart_lane_dispatch`
- 本地开发若不使用 Docker，前端默认访问 `http://localhost:8080` 后端
- 首次运行前执行 `deploy/mysql/init` 下的初始化 SQL；默认账号为 `admin / Admin@123`

```bash
nvm use

mysql -h 127.0.0.1 -uroot -p < deploy/mysql/init/01-bootstrap.sql
mysql -h 127.0.0.1 -uroot -p < deploy/mysql/init/02-schema.sql
mysql -h 127.0.0.1 -uroot -p < deploy/mysql/init/03-seed.sql

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

## 现场配置填写

生产或现场联调时，优先只改仓库根目录的 `.env` 文件。不要直接改 `server/src/main/resources/application.properties`，它只是读取 `.env` 里的环境变量。

### 1. 基础服务配置

```dotenv
APP_HTTP_PORT=3002
APP_PUBLIC_HOST=<服务器局域网IP，例如192.168.124.3>
APP_CORS_ALLOWED_ORIGINS=http://<服务器局域网IP>:3002,http://localhost:3002,http://127.0.0.1:3002,http://localhost:3000,http://127.0.0.1:3000
APP_JWT_SECRET=<生产环境随机长密钥，不要用示例值>

MYSQL_ROOT_PASSWORD=<MySQL root密码>
MYSQL_USER=smartlane
MYSQL_PASSWORD=<业务数据库密码>
```

如果所有服务都用 `compose.yaml` 里的内置 Mosquitto，MQTT 主机保持 `mqtt`：

```dotenv
APP_DEVICE_GATEWAY=mqtt
APP_DEVICE_MQTT_ENABLED=true
APP_DEVICE_MQTT_HOST=mqtt
APP_DEVICE_MQTT_PORT=1883
APP_DEVICE_MQTT_USERNAME=
APP_DEVICE_MQTT_PASSWORD=
```

如果改用外部 MQTT Broker，再把 `APP_DEVICE_MQTT_HOST` 改成外部 Broker 的 IP。

### 2. 总入口 MF 摄像头

总入口使用 MF 协议，Topic 是 `/{sn}/mf/up`。根据现场报文填写下面 3 个值：

```dotenv
APP_DEVICE_PARKING_MF_YARD_ENTRY_SN=<Topic里的sn>
APP_DEVICE_PARKING_MF_YARD_ENTRY_GROUP_ID=<报文data.groupId>
APP_DEVICE_PARKING_MF_YARD_ENTRY_DEVICE_NO=<报文data.deviceNo>
```

以当前测试通过的设备为例：

```dotenv
APP_DEVICE_PARKING_MF_YARD_ENTRY_SN=00E02721A3A7
APP_DEVICE_PARKING_MF_YARD_ENTRY_GROUP_ID=9QHZNII
APP_DEVICE_PARKING_MF_YARD_ENTRY_DEVICE_NO=22K5000202407828
```

### 3. 1-11 车道 Smart Camera

1-11 车道不用 MF，只填每条车道的 Smart Camera 设备码，也就是 `/device/{cameraDevId}/update` 里的 `{cameraDevId}` 或报文里的 `devId`。

以 `18030023526b` 作为 1 号车道入口摄像头为例：

```dotenv
APP_DEVICE_L01_CAMERA_DEV_ID=18030023526b
```

现场 11 条车道完整填写模板：

```dotenv
APP_DEVICE_L01_CAMERA_DEV_ID=<1号车道Smart Camera设备码，例如18030023526b>
APP_DEVICE_L02_CAMERA_DEV_ID=<2号车道Smart Camera设备码>
APP_DEVICE_L03_CAMERA_DEV_ID=<3号车道Smart Camera设备码>
APP_DEVICE_L04_CAMERA_DEV_ID=<4号车道Smart Camera设备码>
APP_DEVICE_L05_CAMERA_DEV_ID=<5号车道Smart Camera设备码>
APP_DEVICE_L06_CAMERA_DEV_ID=<6号车道Smart Camera设备码>
APP_DEVICE_L07_CAMERA_DEV_ID=<7号车道Smart Camera设备码>
APP_DEVICE_L08_CAMERA_DEV_ID=<8号车道Smart Camera设备码>
APP_DEVICE_L09_CAMERA_DEV_ID=<9号车道Smart Camera设备码>
APP_DEVICE_L10_CAMERA_DEV_ID=<10号车道Smart Camera设备码>
APP_DEVICE_L11_CAMERA_DEV_ID=<11号车道Smart Camera设备码>
```

注意：不要再填写 `APP_DEVICE_Lxx_MF_*`，车道入口没有这类配置。

### 4. DIDO 红绿灯和地感

现场按 2 台 CX/DIDO 设备控制 11 条车道：

- 入口 CX/DIDO：控制 11 条车道入口灯
- 出口 CX/DIDO：控制 11 条车道出口灯，同时 IN 口接收出口地感信号

```dotenv
APP_DEVICE_SHARED_ENTRY_DIDO_DEVICE_ID=<入口DIDO设备ID，例如DIDO-ENTRY-01>
APP_DEVICE_SHARED_ENTRY_DIDO_HOST=<入口DIDO IP，例如192.168.1.18>
APP_DEVICE_SHARED_ENTRY_DIDO_PORT=8080

APP_DEVICE_SHARED_EXIT_DIDO_DEVICE_ID=<出口DIDO设备ID，例如DIDO-EXIT-01>
APP_DEVICE_SHARED_EXIT_DIDO_HOST=<出口DIDO IP，例如192.168.1.19>
APP_DEVICE_SHARED_EXIT_DIDO_PORT=8080
```

默认接线规则：

- 入口 DIDO 的 `A01-A11`：1-11 号车道入口绿灯继电器
- 出口 DIDO 的 `A01-A11`：1-11 号车道出口绿灯继电器
- 出口 DIDO 的 `B01-B11`：1-11 号车道出口地感输入

当前按单继电器灯控：继电器吸合表示绿灯，继电器关闭表示红灯。如果现场红灯、绿灯是两个独立继电器，再额外填写 `APP_DEVICE_Lxx_ENTRY_RED_RELAY` 或 `APP_DEVICE_Lxx_EXIT_RED_RELAY`。

如果现场接线一致，下面这些默认值不用改：

```dotenv
APP_DEVICE_L01_ENTRY_GREEN_RELAY=A01
APP_DEVICE_L01_EXIT_GREEN_RELAY=A01
APP_DEVICE_L01_EXIT_TRIGGER_INPUT_KEY=B01

APP_DEVICE_L02_ENTRY_GREEN_RELAY=A02
APP_DEVICE_L02_EXIT_GREEN_RELAY=A02
APP_DEVICE_L02_EXIT_TRIGGER_INPUT_KEY=B02

APP_DEVICE_L03_ENTRY_GREEN_RELAY=A03
APP_DEVICE_L03_EXIT_GREEN_RELAY=A03
APP_DEVICE_L03_EXIT_TRIGGER_INPUT_KEY=B03

APP_DEVICE_L04_ENTRY_GREEN_RELAY=A04
APP_DEVICE_L04_EXIT_GREEN_RELAY=A04
APP_DEVICE_L04_EXIT_TRIGGER_INPUT_KEY=B04

APP_DEVICE_L05_ENTRY_GREEN_RELAY=A05
APP_DEVICE_L05_EXIT_GREEN_RELAY=A05
APP_DEVICE_L05_EXIT_TRIGGER_INPUT_KEY=B05

APP_DEVICE_L06_ENTRY_GREEN_RELAY=A06
APP_DEVICE_L06_EXIT_GREEN_RELAY=A06
APP_DEVICE_L06_EXIT_TRIGGER_INPUT_KEY=B06

APP_DEVICE_L07_ENTRY_GREEN_RELAY=A07
APP_DEVICE_L07_EXIT_GREEN_RELAY=A07
APP_DEVICE_L07_EXIT_TRIGGER_INPUT_KEY=B07

APP_DEVICE_L08_ENTRY_GREEN_RELAY=A08
APP_DEVICE_L08_EXIT_GREEN_RELAY=A08
APP_DEVICE_L08_EXIT_TRIGGER_INPUT_KEY=B08

APP_DEVICE_L09_ENTRY_GREEN_RELAY=A09
APP_DEVICE_L09_EXIT_GREEN_RELAY=A09
APP_DEVICE_L09_EXIT_TRIGGER_INPUT_KEY=B09

APP_DEVICE_L10_ENTRY_GREEN_RELAY=A10
APP_DEVICE_L10_EXIT_GREEN_RELAY=A10
APP_DEVICE_L10_EXIT_TRIGGER_INPUT_KEY=B10

APP_DEVICE_L11_ENTRY_GREEN_RELAY=A11
APP_DEVICE_L11_EXIT_GREEN_RELAY=A11
APP_DEVICE_L11_EXIT_TRIGGER_INPUT_KEY=B11
```

如果现场 DIDO 的 IP、端口、设备 ID 或接线口不一样，只改对应值。

### 5. 修改后重启

`.env` 改完后重启服务让配置生效：

```bash
docker compose down
docker compose up -d --build
```

查看后端和 MQTT 日志：

```bash
docker compose logs -f server
docker compose exec mqtt mosquitto_sub -h 127.0.0.1 -p 1883 -t '#' -v
```

## 说明

- 默认开发模式使用 H2；Compose 部署模式使用 `MySQL + Redis + Nginx`
- 系统默认不再生成演示账号、车道、日志或黑名单数据
- 如果数据库为空，接口会返回空结果；登录需要数据库中预先存在真实用户，或显式启用 bootstrap admin
- 如需运维引导管理员，可通过 `APP_BOOTSTRAP_ADMIN_*` 环境变量显式创建受保护管理员账号
- bootstrap admin 的启用方式、轮换和保护规则见 `docs/DEPLOY.md`
- 接口清单见 `docs/API.md`
- 部署说明见 `docs/DEPLOY.md`
- 后端测试已通过：`./mvnw test`
- 前端构建与静态检查已通过：`npm run build`、`npm run lint`

## 临时修改本机电脑 ip （DIDO设备）
sudo networksetup -setmanual "Wi-Fi" 192.168.0.100 255.255.255.0 192.168.0.1

## 恢复自动获取ip
sudo networksetup -setdhcp "Wi-Fi"


## 临时修改本机电脑 ip （摄像头）
sudo ifconfig en0 alias 192.168.55.101 255.255.255.0
