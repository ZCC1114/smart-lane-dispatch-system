# CX-6E MQTT 接入说明

这台设备通过 MQTT 接入时，推荐让设备连接到本项目启动的 Mosquitto Broker，然后后端向设备订阅的 Topic 下发继电器控制信号。

## 1. 本地启动 Broker

不使用 Docker 时，直接启动项目内置的 Node MQTT Broker:

```bash
cd web
npm run mqtt:broker
```

端口:

- `1883`: 设备和后端使用的 MQTT TCP 端口
- `9001`: 前端硬件调试页使用的 MQTT WebSocket 端口

测试页面:

```text
http://localhost:3000/cx6e-test
```

Docker Compose 里也保留了 Mosquitto 服务，但现场本地联调优先使用上面的 `npm run mqtt:broker`。

本机当前给设备填写的服务器地址应使用电脑的局域网 IP。Windows 下执行:

```powershell
ipconfig
```

找到和设备同一网段的 IPv4，例如 `192.168.1.45`。

## 2. CX-6E 官方配置软件填写

在官方 MQTT 对接网络参数配置软件里按现场值填写:

```text
服务器地址 / Broker Host: 电脑局域网 IP，例如 192.168.1.45
服务器端口 / Broker Port: 1883
用户名 / 密码: 留空，除非现场 Broker 开了认证
设备 ID / Client ID / Device ID: 现场自定义，例如 DIDO-01
订阅 Topic / 下发 Topic: /device/DIDO-01/get
发布 Topic / 上报 Topic: /device/DIDO-01/update
```

如果设备软件要求 Topic 不同，不需要改代码，覆盖配置即可:

```properties
app.device.dido.down-topic-template=/your/down/{didoDeviceId}
app.device.dido.up-topic-filter=/your/up/+
```

## 3. 后端配置

本地直启时，后端 MQTT 地址填 `127.0.0.1`:

```dotenv
APP_DEVICE_GATEWAY=mqtt
APP_DEVICE_MQTT_ENABLED=true
APP_DEVICE_MQTT_HOST=127.0.0.1
APP_DEVICE_MQTT_PORT=1883

APP_DEVICE_DIDO_PAYLOAD_MODE=json

APP_DEVICE_L01_DIDO_DEVICE_ID=DIDO-01
APP_DEVICE_L01_ENTRY_RED_RELAY=A01
APP_DEVICE_L01_ENTRY_GREEN_RELAY=A02
APP_DEVICE_L01_EXIT_RED_RELAY=A03
APP_DEVICE_L01_EXIT_GREEN_RELAY=A04
```

车道和 CX-6E 绑定示例:

```properties
app.device.lanes[0].lane-id=L01
app.device.lanes[0].dido-device-id=DIDO-01
app.device.lanes[0].entry-red-relay=A01
app.device.lanes[0].entry-green-relay=A02
app.device.lanes[0].exit-red-relay=A03
app.device.lanes[0].exit-green-relay=A04
```

如果 CX-6E 不是 JSON 控制，而是 MQTT 透传原生 HEX 指令，把模式改成:

```dotenv
APP_DEVICE_DIDO_PAYLOAD_MODE=hex-a1
```

可选值:

- `json`: 下发 `{"A01":110000,"res":"..."}`，适合支持 JSON 继电器协议的 DIDO 设备
- `hex-a1`: 下发 `CC DD A1 ... A4 48` 二进制 payload，适合 CX 系列基础继电器控制
- `hex-a3`: 下发 `CC DD A3 ... DD CC` 二进制 payload，适合现场要求场景指令时使用

如果现场需要设备先开启远程配置或继电器状态主动上报，可打开:

```dotenv
APP_DEVICE_DIDO_ENABLE_REMOTE_CONFIG_ON_CONNECT=true
APP_DEVICE_DIDO_ENABLE_RELAY_UPLOAD_ON_CONNECT=true
```

## 4. 手动验证

监听设备上报:

```bash
mosquitto_sub -h 192.168.1.45 -p 1883 -t "/device/DIDO-01/update" -v
```

JSON 模式手动吸合 A01:

```bash
mosquitto_pub -h 192.168.1.45 -p 1883 -t "/device/DIDO-01/get" -m '{"A01":110000,"res":"manual-on"}'
```

JSON 模式手动断开 A01:

```bash
mosquitto_pub -h 192.168.1.45 -p 1883 -t "/device/DIDO-01/get" -m '{"A01":100000,"res":"manual-off"}'
```

也可以打开系统的硬件调试页，使用 MQTT DIDO 控制模式测试 JSON、HEX A1、HEX A3 三种下发方式。
