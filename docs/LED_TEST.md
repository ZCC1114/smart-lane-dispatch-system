# LED 显示屏测试工具

基于仰邦（Onbon）BX 五代/六代 SDK 实现的 LED 显示屏联调工具，支持通过网页直接给显示屏发送多段彩色文本。

## 功能

- **TCP 直连发送**：后端通过 Java SDK 直连控制卡，无需额外软件。
- **多段文本**：支持同时发送多段文字，每段独立设置颜色和字号。
- **整屏换行显示**：多段文字按垂直区域从上到下同时展示，而非轮播。
- **五代/六代兼容**：自动适配仰邦 BX-5xxx 和 BX-6xxx 系列控制卡。
- **六代型号选择**：六代卡支持 Bx6E / Bx6M / Bx6Q 型号切换。

## 技术栈

| 层级 | 技术 |
|------|------|
| 前端 | Next.js 16 + React + TypeScript + Tailwind CSS |
| 后端 | Spring Boot 3 + 仰邦 BX SDK（Java） |
| 通信 | TCP 直连控制卡默认端口 5005 |

## 文件结构

```
server/
├── lib/                                    # 仰邦 SDK jar 包（本地依赖）
│   ├── bx05-0.5.0-SNAPSHOT.jar
│   ├── bx06-0.6.5-SNAPSHOT.jar
│   └── ...
├── pom.xml                                 # 已添加 system scope SDK 依赖
└── src/main/java/com/smartlane/dispatch/
    ├── controller/LedTestController.java     # REST API: /api/screen/led-test/send
    └── device/led/LedScreenService.java      # SDK 封装、节目发送逻辑

web/
└── app/led-test/page.tsx                   # 测试页面 UI
```

## 配置说明

### 1. 显示屏网络配置

确保电脑与显示屏处于同一网段。例如：

- 显示屏 IP：`192.168.0.31`
- 显示屏端口：`5005`（仰邦默认端口）
- 本机 IP：`192.168.0.xxx`

> 如果不知道控制卡端口，可用 `nc -zv 192.168.0.31 5005` 测试连通性。

### 2. 控制卡代际与型号

| 代际 | 常见型号 | 页面选择 |
|------|---------|---------|
| 五代 | BX-5E1、BX-5M1 等 | 五代卡（BX-5xxx） |
| 六代 | BX-6E、BX-6M、BX-6Q 等 | 六代卡（BX-6xxx） |

若不确定型号，先尝试 **六代 Bx6E**（默认），失败后再试其他型号或五代。

### 3. 后端 SDK 依赖

SDK jar 包已放置于 `server/lib/`，`pom.xml` 中通过 `system` scope 引用，
并配置了 `includeSystemScope=true` 以支持打包。

## 使用方式

### 启动服务

```bash
# 1. 启动后端（端口 8080）
cd server
./mvnw spring-boot:run

# 2. 启动前端（端口 3000）
cd web
npm run dev
```

### 打开测试页

浏览器访问：

```
http://localhost:3000/led-test
```

### 发送文本到显示屏

1. **连接参数**：填写显示屏 IP、端口、选择代际和型号。
2. **添加文本段**：
   - 点击 **"+ 添加一段文本"** 增加段落。
   - 每段可输入文字、拖动字号滑块、点击色块选择颜色。
   - 用 ↑ ↓ 调整段落上下顺序。
3. 点击 **"发送到显示屏"**。

发送成功后，显示屏会立即覆盖原有节目，按段落从上到下同时显示。

### 支持的颜色

红色、绿色、蓝色、黄色、青色、品红、白色、黑色、橙色、粉色。

## 注意事项

- 发送成功后原有节目会被覆盖，如需恢复请重新发送或使用厂商软件。
- 字号范围限制为 **8 ~ 64** 像素，超出范围会自动截断。
- 若连接失败，请检查防火墙是否放行控制卡端口，或确认 IP/端口是否正确。
- 后端日志（`LedScreenService`）会输出 SDK 初始化状态和连接详情，可用于排查。
