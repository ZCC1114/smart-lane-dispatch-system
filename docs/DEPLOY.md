# 部署说明

Ubuntu 25.10 生产服务器的完整 Docker 部署、启动、设备配置和运维手册见: [UBUNTU_25_10_DOCKER_DEPLOY.md](./UBUNTU_25_10_DOCKER_DEPLOY.md)。

## 方式一: 本地开发

1. 启动后端:

```bash
./mvnw spring-boot:run
```

2. 启动前端:

```bash
npm install
npm run dev
```

## 方式二: Docker Compose

首次使用:

```bash
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

新电脑常见前置条件:

- 已安装 `Docker` 与 `docker compose`
- 本地开发模式需要 `Node.js 23.x`
- 本地开发模式需要 `JDK 21+`
- `./mvnw` 首次执行会联网下载 Maven 发行包

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
- `APP_DEVICE_GATEWAY`
- `APP_DEVICE_MQTT_ENABLED`
- `APP_DEVICE_MQTT_HOST`
- `APP_DEVICE_MQTT_PORT`
- `APP_DEVICE_MQTT_CLIENT_ID`
- `APP_DEVICE_MQTT_USERNAME`
- `APP_DEVICE_MQTT_PASSWORD`

## 设备 MQTT 对接

后端默认使用 `mock` 设备网关，不会连接现场设备。现场联调时开启 MQTT 网关:

```bash
APP_DEVICE_GATEWAY=mqtt \
APP_DEVICE_MQTT_ENABLED=true \
APP_DEVICE_MQTT_HOST=192.168.1.10 \
APP_DEVICE_MQTT_PORT=1883 \
APP_DEVICE_MQTT_USERNAME='mqtt-user' \
APP_DEVICE_MQTT_PASSWORD='mqtt-password' \
APP_DEVICE_DIDO_PAYLOAD_MODE=hex-a1 \
APP_DEVICE_SHARED_ENTRY_DIDO_DEVICE_ID=DIDO-ENTRY-01 \
APP_DEVICE_SHARED_ENTRY_DIDO_HOST=192.168.1.18 \
APP_DEVICE_SHARED_ENTRY_DIDO_PORT=8080 \
APP_DEVICE_SHARED_EXIT_DIDO_DEVICE_ID=DIDO-EXIT-01 \
APP_DEVICE_SHARED_EXIT_DIDO_HOST=192.168.1.19 \
APP_DEVICE_SHARED_EXIT_DIDO_PORT=8080 \
SPRING_PROFILES_ACTIVE=mysql \
./mvnw spring-boot:run
```

已接入的协议:

- `车牌识别.pdf`: 总入口 MF 摄像头订阅 `/{sn}/mf/up`，通过 `APP_DEVICE_PARKING_MF_YARD_ENTRY_*` 配置后生成预分配记录；下发 `plateResultResp` 到 `/{sn}/mf/down`。
- `计数报警.docx`: 1-11 车道入口 Smart Camera 订阅 `/device/{cameraDevId}/update` 和 `/device/{cameraDevId}/will`，每条车道通过 `APP_DEVICE_Lxx_CAMERA_DEV_ID` 绑定；处理 `heartbeat`、`devAlarm`、`passCount`、`getHaveCarRsp`；定时下发 `getHaveCar`，启动后可下发 `getVerInfo`。
- `dido模块.pdf`: 下发继电器红绿灯控制，默认使用普通吸合/断开命令 `110000` / `100000`；读取 DIDO 输入状态并可按 `presenceInputKey` 同步车道是否有车。

当前现场按 2 台 CX/DIDO 设备绑定:

- 入口 DIDO: `L01-L11` 共用 `APP_DEVICE_SHARED_ENTRY_DIDO_DEVICE_ID`，`entry-green-relay` 默认映射为 `A01-A11`
- 出口 DIDO: `L01-L11` 共用 `APP_DEVICE_SHARED_EXIT_DIDO_DEVICE_ID`，`exit-green-relay` 默认映射为 `A01-A11`
- 出口 DIDO: `exit-trigger-input-key` 默认映射为 `B01-B11`，用于接收出口地感 IN 信号
- 单灯模式下: 对应车道 green relay 吸合 = 绿灯，关闭 = 红灯

修改 `.env` 或容器环境变量后，需要重启 `server` 容器/进程让新配置生效。

车道与设备绑定需要按现场设备编号配置。`application.properties` 示例:

```properties
app.device.gateway=mqtt
app.device.mqtt.enabled=true
app.device.mqtt.host=192.168.1.10
app.device.mqtt.port=1883
app.device.mqtt.username=mqtt-user
app.device.mqtt.password=mqtt-password

app.device.parking-mf.yard-entry-sn=MF-YARD-SN
app.device.parking-mf.yard-entry-group-id=YARD-GROUP
app.device.parking-mf.yard-entry-device-no=YARD-CAMERA-NO

app.device.lanes[0].lane-id=L01
app.device.lanes[0].camera-dev-id=SMART-CAM-01
app.device.lanes[0].entry-dido-device-id=DIDO-ENTRY-01
app.device.lanes[0].exit-dido-device-id=DIDO-EXIT-01
app.device.lanes[0].entry-green-relay=A01
app.device.lanes[0].exit-green-relay=A01
app.device.lanes[0].exit-trigger-input-key=B01
```

11 条车道需要配置 `app.device.lanes[0]` 到 `app.device.lanes[10]`。如果现场 MQTT Topic 与文档不同，可以覆盖:

```properties
app.device.parking-mf.up-topic-filter=/+/mf/up
app.device.parking-mf.down-topic-template=/{mfSn}/mf/down
app.device.smart-camera.up-topic-filter=/device/+/update
app.device.smart-camera.will-topic-filter=/device/+/will
app.device.smart-camera.down-topic-template=/device/{cameraDevId}/get
app.device.dido.up-topic-filter=/device/+/update
app.device.dido.down-topic-template=/device/{didoDeviceId}/get
```

红绿灯目前按两态处理: `GREEN` 表示通行，其他状态下发红灯。DIDO 如需脉冲模式，可设置 `app.device.dido.relay-mode=pulse_ms`，默认 `ordinary` 更适合持续点亮红/绿灯。

### 蓄车池新流程说明

- 总入口抓拍相机负责识别刚进入场地、尚未进入车道的出租车车牌
- 后端按当前入口开放车道和预留名额生成推荐车道，并通过 `/api/dispatch/board` 提供给大屏或岗亭终端
- 具体车道入口抓拍相机负责核验车辆是否进入了推荐车道；若司机未按屏显进入，会记录 `ENTERED_MISMATCH`
- 出口放行不再按全局 FIFO 算法，而是只认当前出口开放车道；该车道车辆全部驶空后，才切到下一条出口车道
- 当前实现默认预留名额保留 `2` 分钟；总入口抓拍分配车道后，满 2 分钟仍未被任何车道入口摄像头识别，会生成未进车道告警并释放预分配。可通过 `app.dispatch.assignment-reserve-minutes` 调整

### 红绿灯状态与数据库边界

- 数据库只保存业务数据和调度配置，例如 `lanes` 的容量、在场车辆数、车道模式，以及 `dispatch_configs` 的入口开放顺序、当前入口/出口活动车道
- `lanes` 不保存当前实时红绿灯状态；页面接口中的 `entrySignal`、`exitSignal`、`ledMessage`、`ledStatus` 是后端运行时状态，不是数据库字段
- 后端根据入口/出口顺序配置计算目标灯态，并向 DIDO 下发控制命令
- `mock` 网关会立即把目标灯态模拟成设备反馈，便于本地演示
- `mqtt` 网关以 DIDO 上报的继电器反馈作为真实灯态；如果只收到控制响应但没有继电器状态上报，页面不会把该响应当作最终灯态
- 车牌识别、计数相机、DIDO 状态等设备消息到达后，后端会即时更新对应业务记录或运行时状态，例如总入口预分配记录、实际入道记录、车道车辆数、传感器在线状态和红绿灯反馈

## 数据初始化

- `deploy/mysql/init/01-bootstrap.sql` 用于创建默认数据库
- `deploy/mysql/init/02-schema.sql` 用于创建业务表、索引与约束
- `deploy/mysql/init/03-seed.sql` 用于初始化 `admin` 管理员、`L01` 到 `L11` 车道数据，以及默认入口/出口顺序配置
- 后端默认使用 `spring.jpa.hibernate.ddl-auto=validate`，只校验表结构，不会自动建表或自动改表
- 数据库为空时，需要先手动执行上述 SQL 脚本；否则后端会因表结构缺失启动失败
- 初始化后的默认登录账号为 `admin / Admin@123`，正式交付前应立即改密或替换为现场账号
- 如需运维兜底管理员，可显式开启 `APP_BOOTSTRAP_ADMIN_ENABLED=true`，系统会创建或重置一个受保护 `ADMIN` 账号

本地 MySQL 手动初始化示例:

```bash
mysql -h 127.0.0.1 -uroot -p < deploy/mysql/init/01-bootstrap.sql
mysql -h 127.0.0.1 -uroot -p < deploy/mysql/init/02-schema.sql
mysql -h 127.0.0.1 -uroot -p < deploy/mysql/init/03-seed.sql
```

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
APP_BOOTSTRAP_ADMIN_DISPLAY_NAME='系统超级管理员' \
APP_BOOTSTRAP_ADMIN_STATION='总控中心' \
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
