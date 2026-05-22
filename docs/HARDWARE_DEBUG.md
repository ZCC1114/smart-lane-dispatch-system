# 出租车蓄车池硬件联调测试手册

## 一、系统架构与设备清单

```
┌─────────────────────────────────────────────────────────────┐
│                        后端服务 (Spring Boot)                  │
│  ┌─────────────┐  ┌─────────────┐  ┌─────────────────────┐  │
│  │ REST API    │  │ MQTT Gateway│  │ WebSocket Broadcast │  │
│  │ /api/...    │  │ SimpleMqtt  │  │ /topic/operations   │  │
│  └──────┬──────┘  └──────┬──────┘  └──────────┬──────────┘  │
└─────────┼────────────────┼────────────────────┼─────────────┘
          │                │                    │
          │         ┌──────┴──────┐            │
          │         │ MQTT Broker │            │
          │         │ (Mosquitto) │            │
          │         └──────┬──────┘            │
          │                │                    │
    [总入口MF摄像头]        │              [司机大屏]
    MQTT上报               │              /screen
                         │
        ┌────────────────┼────────────────┐
        │                │                │
   [Smart Camera]    [DIDO模块]      [REST模拟]
   (1-11车道入口)    (红绿灯/地感)    (人工联调)
```

| 设备 | 数量 | 通信方式 | 作用 |
|------|------|----------|------|
| 总入口 MF 摄像头 | 1 | MQTT | 抓拍进入蓄车池的车辆，生成推荐车道 |
| Smart Camera | 11 | MQTT | 1-11 车道入口车牌识别、车道计数、地感检测 |
| DIDO模块 | N | MQTT | 红绿灯继电器控制 |
| 司机大屏 | 1 | HTTP/WebSocket | 显示车辆推荐车道 |

---

## 二、阶段 0：环境准备

### 2.1 部署 MQTT Broker

推荐使用 **Mosquitto**（轻量稳定）或 **EMQX**（功能丰富）。

**安装 Mosquitto：**

```bash
# macOS
brew install mosquitto
brew services start mosquitto

# Ubuntu
sudo apt install mosquitto mosquitto-clients
sudo systemctl start mosquitto

# Docker
docker run -d -p 1883:1883 -p 9001:9001 eclipse-mosquitto
```

**验证 Broker 运行：**

```bash
# 订阅测试主题
mosquitto_sub -h 127.0.0.1 -p 1883 -u jcadmin -P 'jcadmin@12345' -t "test/hello"

# 另开终端，发布消息
mosquitto_pub -h 127.0.0.1 -p 1883 -u jcadmin -P 'jcadmin@12345' -t "test/hello" -m "broker ok"

# 如果订阅端收到 "broker ok"，Broker 正常
```

### 2.2 准备设备清单表

向硬件厂商索取以下信息，填入表格：

| 车道 | laneId | cameraDevId | didoDeviceId | 入口绿灯继电器 | 出口地感输入 | 备注 |
|------|--------|-------------|--------------|----------------|--------------|------|
| 1号车道 | L01 | 18030023526b | DIDO01 | A01 | B01 | 示例 |
| 2号车道 | L02 | <2号车道Smart Camera设备码> | DIDO01 | A02 | B02 | |
| ... | | | | | | |

### 2.3 后端配置

在 `server/src/main/resources/application.properties` 末尾追加（根据实际设备表修改）：

```properties
# ============================================================
# 启用 MQTT 网关（开发/联调时从 mock 切到 mqtt）
# ============================================================
app.device.gateway=mqtt
app.device.mqtt.enabled=true
app.device.mqtt.host=192.168.1.100      # 你的 MQTT Broker IP
app.device.mqtt.port=1883
app.device.mqtt.client-id=smart-lane-dispatch-system
app.device.mqtt.username=jcadmin
app.device.mqtt.password=jcadmin@12345
app.device.dido.exit-trigger-enabled=false

# 总入口 MF 摄像头，只负责蓄车池入口预分配
app.device.parking-mf.yard-entry-sn=<总入口MF设备SN>
app.device.parking-mf.yard-entry-group-id=<总入口MF报文data.groupId>
app.device.parking-mf.yard-entry-device-no=<总入口MF报文data.deviceNo>

# ============================================================
# 车道与设备绑定（示例配了2条，实际配11条）
# ============================================================
app.device.lanes[0].lane-id=L01
app.device.lanes[0].camera-dev-id=18030023526b
app.device.lanes[0].entry-dido-device-id=DIDO-ENTRY-01
app.device.lanes[0].exit-dido-device-id=DIDO-EXIT-01
app.device.lanes[0].entry-green-relay=A01
app.device.lanes[0].exit-green-relay=A01
app.device.lanes[0].exit-trigger-input-key=B01

app.device.lanes[1].lane-id=L02
app.device.lanes[1].camera-dev-id=SMART-CAM-02
app.device.lanes[1].entry-dido-device-id=DIDO-ENTRY-01
app.device.lanes[1].exit-dido-device-id=DIDO-EXIT-01
app.device.lanes[1].entry-green-relay=A02
app.device.lanes[1].exit-green-relay=A02
app.device.lanes[1].exit-trigger-input-key=B02
```

`app.device.dido.exit-trigger-enabled` 默认保持 `false`。只有确认出口 DIDO 输入极性和边沿稳定后，才改为 `true`，否则会把刚入场车辆误写出场时间。

**重启后端**，观察日志：

```
MQTT device gateway connected to 192.168.1.100:1883
MQTT device gateway indexed 11 lane bindings
```

如果看到 `connected` 且 `indexed N lane bindings`，MQTT 连接成功。

---

## 三、阶段 1：DIDO 红绿灯模块联调（最简单，先调它）

### 3.1 测试目标

验证后端能正确控制车道入口/出口的红绿灯。

### 3.2 DIDO 协议说明

- **下发 Topic**：`/device/{didoDeviceId}/get`
- **上报 Topic**：`/device/{didoDeviceId}/update`
- **继电器值**：
  - `100000` = 断开（红灯）
  - `110000` = 持续吸合（绿灯）
  - `900000+N` = 脉冲 N 毫秒（如 `900500` = 脉冲 500ms）

### 3.3 联调步骤

**Step 1：在后端手动切换车道信号灯**

打开工作人员后台 -> 信号灯控制 `/signals` -> 选择 1 号车道 -> 点击"入口绿灯"。

**Step 2：在 MQTT 客户端监听下发消息**

```bash
mosquitto_sub -h 192.168.1.100 -p 1883 -u jcadmin -P 'jcadmin@12345' -t "/device/DIDO-ENTRY-01/get"
```

**预期结果**（收到 JSON）：

```json
{"A01": 110000, "res": "dido-12345"}
```

- 入口 DIDO 的 A01 吸合 -> 1 号车道入口绿灯亮
- 出口 DIDO 的出口灯和地感不在这个 Topic 中

**Step 3：模拟 DIDO 继电器状态反馈**

```bash
mosquitto_pub -h 192.168.1.100 -p 1883 -u jcadmin -P 'jcadmin@12345' -t "/device/DIDO-ENTRY-01/update" -m '{
  "A01": 110000
}'
```

**验证**：后端日志应出现 `DIDO 继电器状态已反馈`，且前端信号灯状态变为 SYNCED。

### 3.4 常见问题

| 现象 | 原因 | 解决 |
|------|------|------|
| 没收到 MQTT 消息 | Topic 不匹配 | 检查 `entry-dido-device-id` / `exit-dido-device-id` 与 Topic 中的设备 ID 是否一致 |
| DIDO 不响应 | 继电器键名不对 | 确认 `entry-red-relay` 等配置与实际硬件口对应 |
| 红绿灯反了 | 继电器逻辑反了 | 检查 `resolveSignalFromRelayFeedback` 逻辑，或交换 red/green 配置 |

---

## 四、阶段 2：总入口 MF 联调

### 4.1 测试目标

验证总入口 MF 摄像头抓拍后，后端能生成蓄车池预分配记录。1-11 车道入口不使用 MF，车道入口联调见下一节 Smart Camera。

### 4.2 MF 协议说明

- **上报 Topic**：`/{sn}/mf/up`
- **下发 Topic**：`/{sn}/mf/down`
- **关键 cmd**：
  - `heartbeat` - 心跳
  - `plateResult` - 车牌识别结果
  - `plateResultResp` - 抓拍确认（下发）

### 4.3 联调步骤

**Step 1：模拟心跳（验证设备在线）**

```bash
mosquitto_pub -h 192.168.1.100 -p 1883 -u jcadmin -P 'jcadmin@12345' -t "/00E02721A3A7/mf/up" -m '{
  "cmd": "heartbeat",
  "sn": "00E02721A3A7",
  "timestamp": 1713936000000,
  "data": {
    "deviceStatus": [
      {"deviceNo": "09K2900202441623", "network": "online"}
    ]
  }
}'
```

**验证**：后端识别该设备为总入口 MF，且不会把它绑定到 L01-L11 的车道在线状态。

**Step 2：模拟车牌识别（车辆进入蓄车池总入口）**

```bash
mosquitto_pub -h 192.168.1.100 -p 1883 -u jcadmin -P 'jcadmin@12345' -t "/00E02721A3A7/mf/up" -m '{
  "cmd": "plateResult",
  "sn": "00E02721A3A7",
  "msgId": "test-001",
  "timestamp": 1778568817063,
  "timezone": "Asia/Shanghai",
  "data": {
    "groupId": "9QHZNII",
    "deviceNo": "09K2900202441623",
    "plateNo": "苏B3R89T",
    "parkingTime": "2026-05-12 14:53:36"
  }
}'
```

**预期结果**：

1. 司机大屏待入道列表出现该车牌和推荐车道
2. 调度后台出现对应预分配记录
3. L01-L11 的车道车辆数不会因为总入口 MF 抓拍直接增加

**Step 3：验证抓拍确认下发**

```bash
mosquitto_sub -h 192.168.1.100 -p 1883 -u jcadmin -P 'jcadmin@12345' -t "/00E02721A3A7/mf/down"
```

**预期收到**：

```json
{"cmd":"plateResultResp","msgId":"...","timestamp":...,"sn":"00E02721A3A7","data":{"groupId":"9QHZNII","deviceNo":"09K2900202441623","success":true}}
```

### 4.4 常见问题

| 现象 | 原因 | 解决 |
|------|------|------|
| plateResult 无响应 | 总入口 MF 匹配失败 | 检查 `APP_DEVICE_PARKING_MF_YARD_ENTRY_SN`、`APP_DEVICE_PARKING_MF_YARD_ENTRY_GROUP_ID`、`APP_DEVICE_PARKING_MF_YARD_ENTRY_DEVICE_NO` |
| 车道车辆数被增加 | 设备被误当作车道入口 | 确认配置中没有 L01-L11 的 MF 绑定项，车道只填 `APP_DEVICE_Lxx_CAMERA_DEV_ID` |
| 下发 Topic 没消息 | Topic 模板不一致 | 检查 `APP_DEVICE_PARKING_MF_DOWN_TOPIC_TEMPLATE` |

---

## 五、阶段 3：智能相机(计数报警相机)联调

### 5.1 测试目标

验证出口车辆计数 + 地感在位检测。

### 5.2 智能相机协议说明

- **上报 Topic**：`/device/{cameraDevId}/update`
- **遗嘱 Topic**：`/device/{cameraDevId}/will`
- **下发 Topic**：`/device/{cameraDevId}/get`
- **关键 cmd**：
  - `heartbeat` - 心跳
  - `passCount` - 进出计数
  - `getHaveCarRsp` - 在位检测响应
  - `devAlarm` - 报警事件

### 5.3 联调步骤

**Step 1：模拟心跳**

```bash
mosquitto_pub -h 192.168.1.100 -p 1883 -u jcadmin -P 'jcadmin@12345' -t "/device/SMART-CAM-01/update" -m '{
  "cmd": "heartbeat",
  "devId": "SMART-CAM-01",
  "utcTs": 1713936000000
}'
```

**Step 2：模拟车辆出场计数**

假设当前车道 1 有 3 辆车，现在出去了 1 辆：

```bash
mosquitto_pub -h 192.168.1.100 -p 1883 -u jcadmin -P 'jcadmin@12345' -t "/device/SMART-CAM-01/update" -m '{
  "cmd": "passCount",
  "devId": "SMART-CAM-01",
  "msgId": "pc-001",
  "utcTs": 1713936500000,
  "inCount": 3,
  "outCount": 1
}'
```

**预期结果**：

1. 后端日志：`pass_count_delta` -> `vehicleCount` 从 3 减到 2
2. `EntryLog` 最早的一条记录被标记 `exitTime`
3. 前端该车道的占用数减 1

**再次发送**（再出去 2 辆，清空车道）：

```bash
mosquitto_pub -h 192.168.1.100 -p 1883 -u jcadmin -P 'jcadmin@12345' -t "/device/SMART-CAM-01/update" -m '{
  "cmd": "passCount",
  "devId": "SMART-CAM-01",
  "msgId": "pc-002",
  "utcTs": 1713936600000,
  "inCount": 3,
  "outCount": 3
}'
```

**预期结果**：

1. `vehicleCount` 变为 0
2. 如果该车道是当前出口开放车道，后端自动 `advanceExitLane`，切换到下一条出口车道
3. 前端显示新的出口开放车道

**Step 3：模拟地感在位检测**

```bash
mosquitto_pub -h 192.168.1.100 -p 1883 -u jcadmin -P 'jcadmin@12345' -t "/device/SMART-CAM-01/update" -m '{
  "cmd": "getHaveCarRsp",
  "devId": "SMART-CAM-01",
  "content": {
    "haveCar": 0
  },
  "utcTs": 1713936700000
}'
```

**预期结果**：后端 `lane_presence_polled`，如果该车道没有在场车辆，清空 `vehicleCount` 和 `currentPlate`。

### 5.4 常见问题

| 现象 | 原因 | 解决 |
|------|------|------|
| passCount 不计数 | outCount 没有增加 | 智能相机计数是累计值，outCount 必须比上次大 |
| 计数不准 | 相机重启后计数归零 | 代码中用 `counterDelta` 处理了归零逻辑：`current >= previous ? current - previous : current` |
| 出口不切换 | vehicleCount 没到 0 | 检查 `passCount` 的 `outCount` 是否正确增加 |

---

## 六、阶段 4：总入口摄像头联调

### 6.1 测试目标

验证蓄车池总入口抓拍 -> 系统推荐车道 -> 大屏显示。

### 6.2 联调步骤

总入口摄像头优先走 MQTT。MF 协议摄像头上报 `/{sn}/mf/up`，后端通过 `APP_DEVICE_PARKING_MF_YARD_ENTRY_*` 判断它是总入口设备，并生成预分配记录。REST API 仍可作为人工模拟入口。

**Step 1：配置总入口 MF 摄像头**

```dotenv
APP_DEVICE_PARKING_MF_YARD_ENTRY_SN=<总入口MF设备SN>
APP_DEVICE_PARKING_MF_YARD_ENTRY_GROUP_ID=<总入口MF报文data.groupId，可为空>
APP_DEVICE_PARKING_MF_YARD_ENTRY_DEVICE_NO=<总入口MF报文data.deviceNo>
```

**Step 2：模拟或监听总入口 MQTT 抓拍**

```bash
mosquitto_pub -h 192.168.1.100 -p 1883 -u jcadmin -P 'jcadmin@12345' -t "/00E02721A3A7/mf/up" -m '{
  "cmd": "plateResult",
  "sn": "00E02721A3A7",
  "msgId": "yard-mf-001",
  "timestamp": 1778568817063,
  "timezone": "Asia/Shanghai",
  "data": {
    "deviceNo": "09K2900202441623",
    "groupId": "9QHZNII",
    "plateNo": "苏B88888",
    "parkingTime": "2026-05-12 14:53:36"
  }
}'
```

**可选：调用总入口抓拍 API 做软件模拟**

```bash
curl -X POST http://localhost:8080/api/integration/yard-entries \
  -H "Authorization: Bearer <你的token>" \
  -H "Content-Type: application/json" \
  -d '{
    "plate": "苏B88888",
    "vehicleType": "出租车",
    "source": "YARD_CAMERA",
    "capturedAt": "2024-04-24T12:20:00+08:00"
  }'
```

**预期结果**：

```json
{
  "id": "DSP-XXXX",
  "plate": "苏B88888",
  "status": "ASSIGNED",
  "assignedLaneId": "L01",
  "assignedLaneName": "出租车蓄车道 01",
  "notes": "大屏指引前往 出租车蓄车道 01"
}
```

**Step 3：验证司机大屏**

打开 `http://localhost:3000/screen`，应该看到：

- 待入道车辆列表中出现 "苏B88888 -> 出租车蓄车道 01"

**Step 4：车辆按推荐进入车道**

```bash
curl -X POST http://localhost:8080/api/integration/vehicle-entries \
  -H "Authorization: Bearer <你的token>" \
  -H "Content-Type: application/json" \
  -d '{
    "laneId": "L01",
    "plate": "苏B88888",
    "vehicleType": "出租车",
    "source": "LANE_CAMERA",
    "entryTime": "2024-04-24T12:21:00+08:00"
  }'
```

**预期结果**：

- `DispatchTicket.status` 变为 `ENTERED`
- 车道 1 的 `vehicleCount` +1

**Step 4：模拟错道（不按推荐进入）**

再发一个总入口抓拍给 L01，然后车辆进入 L02：

```bash
# 总入口推荐 L01
curl -X POST http://localhost:8080/api/integration/yard-entries \
  -H "Authorization: Bearer <你的token>" \
  -H "Content-Type: application/json" \
  -d '{"plate": "苏C99999", "capturedAt": "2024-04-24T12:22:00+08:00"}'

# 车辆实际进入 L02
curl -X POST http://localhost:8080/api/integration/vehicle-entries \
  -H "Authorization: Bearer <你的token>" \
  -H "Content-Type: application/json" \
  -d '{"laneId": "L02", "plate": "苏C99999", "entryTime": "2024-04-24T12:23:00+08:00"}'
```

**预期结果**：

- `DispatchTicket.status` = `ENTERED_MISMATCH`
- `notes` = "司机未按屏显进入推荐车道"

---

## 七、阶段 5：端到端场景测试

### 场景 A：正常入场 -> 出场全流程

```
Step 1: 日清（清空上一班次数据）
  -> POST /api/dispatch/daily-reset

Step 2: 总入口抓拍
  -> POST /api/integration/yard-entries {plate: "苏A11111"}
  -> 预期：推荐 L01，大屏显示

Step 3: 车辆进入 L01
  -> 模拟 MF plateResult 或 POST /api/integration/vehicle-entries
  -> 预期：L01 vehicleCount=1

Step 4: L01 满员后继续入场
  -> 重复 Step 2~3，直到 L01 vehicleCount == capacity
  -> 预期：系统自动 advanceEntryLane，下一个车辆被推荐到 L02

Step 5: L01 车辆出场
  -> 模拟智能相机 passCount (outCount++)
  -> 预期：L01 vehicleCount 减少

Step 6: L01 全部清空
  -> 继续出场直到 vehicleCount=0
  -> 预期：如果 L01 是当前出口车道，自动 advanceExitLane
```

### 场景 B：一边进一边出

```
前提：L01 是当前入口开放车道，也是当前出口开放车道

Step 1: 车辆进入 L01
  -> vehicleCount 从 0 -> 1

Step 2: 车辆驶出 L01
  -> 智能相机 passCount outCount=1
  -> vehicleCount 从 1 -> 0

Step 3: 同时有车辆进入
  -> 如果 vehicleCount + reservedCount < capacity，入口保持开放
  -> 如果满了，入口自动切换到 L02
```

### 场景 C：入口满了自动切换

```
前提：L01 capacity=5，当前 vehicleCount=4，reservedCount=1

Step 1: 总入口再抓拍一辆车
  -> POST /api/integration/yard-entries
  -> L01 vehicleCount(4) + reservedCount(1) + 新预留(1) = 6 > capacity(5)
  -> 系统 advanceEntryLane，新车辆被推荐到 L02
```

---

## 八、MQTT 联调辅助脚本

把常用的 MQTT 测试命令整理成一个脚本，保存为 `hardware-test.sh`：

```bash
#!/bin/bash
BROKER="192.168.1.100"
PORT="1883"
USERNAME="jcadmin"
PASSWORD="jcadmin@12345"

# ---------- DIDO ----------
pub_dido() {
  mosquitto_pub -h $BROKER -p $PORT -u "$USERNAME" -P "$PASSWORD" -t "/device/DIDO-EXIT-01/update" -m "$1"
}

# ---------- 总入口 MF 摄像头 ----------
pub_mf_heartbeat() {
  mosquitto_pub -h $BROKER -p $PORT -u "$USERNAME" -P "$PASSWORD" -t "/00E02721A3A7/mf/up" -m '{
    "cmd":"heartbeat","sn":"00E02721A3A7","timestamp":'$(date +%s000)',
    "data":{"deviceStatus":[{"deviceNo":"09K2900202441623","network":"online"}]}
  }'
}

pub_mf_plate() {
  mosquitto_pub -h $BROKER -p $PORT -u "$USERNAME" -P "$PASSWORD" -t "/00E02721A3A7/mf/up" -m '{
    "cmd":"plateResult","sn":"00E02721A3A7","msgId":"test-'$(date +%s)'",
    "data":{"groupId":"9QHZNII","deviceNo":"09K2900202441623","plateNo":"'$1'","parkingTime":"'$(date "+%Y-%m-%d %H:%M:%S")'"}
  }'
}

# ---------- 智能相机 ----------
pub_cam_heartbeat() {
  mosquitto_pub -h $BROKER -p $PORT -u "$USERNAME" -P "$PASSWORD" -t "/device/SMART-CAM-01/update" -m '{
    "cmd":"heartbeat","devId":"SMART-CAM-01","utcTs":'$(date +%s000)'
  }'
}

pub_cam_pass() {
  mosquitto_pub -h $BROKER -p $PORT -u "$USERNAME" -P "$PASSWORD" -t "/device/SMART-CAM-01/update" -m '{
    "cmd":"passCount","devId":"SMART-CAM-01","msgId":"pc-'$(date +%s)'",
    "inCount":3,"outCount":'$1'
  }'
}

pub_cam_havecar() {
  mosquitto_pub -h $BROKER -p $PORT -u "$USERNAME" -P "$PASSWORD" -t "/device/SMART-CAM-01/update" -m '{
    "cmd":"getHaveCarRsp","devId":"SMART-CAM-01",
    "content":{"haveCar":'$1'},"utcTs":'$(date +%s000)'
  }'
}

# 使用示例：
# ./hardware-test.sh pub_mf_plate 苏A12345
# ./hardware-test.sh pub_cam_pass 2
# ./hardware-test.sh pub_cam_havecar 0

$1 "$2"
```

---

## 九、联调检查清单

| 检查项 | 停车相机 | 智能相机 | DIDO | 总入口 |
|--------|---------|---------|------|--------|
| 网络连通 (ping) | [ ] | [ ] | [ ] | [ ] |
| MQTT 连接成功 | [ ] | [ ] | [ ] | N/A |
| 心跳上报正常 | [ ] | [ ] | [ ] | N/A |
| 数据上报触发业务 | [ ] | [ ] | [ ] | [ ] |
| 后端下发指令正常 | [ ] | N/A | [ ] | N/A |
| 前端状态同步 | [ ] | [ ] | [ ] | [ ] |
| 大屏显示正确 | N/A | N/A | N/A | [ ] |

---

## 十、常见问题速查

**Q1：后端日志显示 "MQTT 网关未启用"**

> 检查 `app.device.gateway=mqtt` 和 `app.device.mqtt.enabled=true`

**Q2：后端连不上 MQTT Broker**

> 检查 Broker IP/端口、防火墙、后端服务器与 Broker 的网络连通性

**Q3：设备上报了消息但后端没反应**

> 检查 Topic 格式是否匹配。特别注意 `+/mf/up` 中的 `+` 是通配符，实际 Topic 中必须有对应的 `mfSn`

**Q4：车道状态一直是 OFFLINE**

> 检查设备心跳是否正常上报，或手动调用 `POST /api/integration/lane-sensors` 模拟传感器数据

**Q5：红绿灯控制下发成功但设备没动作**

> 检查 DIDO 的继电器键名（A01/A02...）是否与硬件实际接线口一致

**Q6：大屏没有显示推荐车辆**

> 确认 `/api/screen/board` 接口能正常返回数据，且大屏页面轮询间隔正常（3秒）
