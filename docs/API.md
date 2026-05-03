# 接口说明

后端默认基址:

- 本地开发: `http://localhost:8080/api`
- Compose/Nginx: `http://localhost:3002/api`
- 本地开发直连后端: `http://localhost:8080/api`

在线文档:

- Swagger UI: `/swagger-ui.html`
- OpenAPI JSON: `/v3/api-docs`
- 健康检查: `/actuator/health`

## 鉴权

- 除 `POST /api/auth/login` 之外，其余接口均需 `Authorization: Bearer <token>`
- 系统默认不再写入演示账号，登录用户必须来自真实数据库
- 角色:
  - `ADMIN`: 全部接口
  - `DISPATCHER`: 调度、信号灯
  - `VIEWER`: 只读总览、车道、日志

## 接口列表

| 方法 | 路径 | 角色 | 说明 |
| --- | --- | --- | --- |
| POST | `/api/auth/login` | 公开 | 用户登录，返回 JWT 与用户信息 |
| GET | `/api/dashboard` | 登录即可 | 获取首页总览、吞吐趋势与车道摘要 |
| GET | `/api/lanes` | 登录即可 | 获取全部车道实时状态 |
| GET | `/api/dispatch/board` | 登录即可 | 获取当前入口车道、出口车道、大屏待入道列表与最近调度记录 |
| GET | `/api/logs` | 登录即可 | 车辆入场记录查询，支持 `query/status/laneId/entryTimeFrom/entryTimeTo` |
| GET | `/api/blacklist` | `ADMIN` | 黑名单检索 |
| POST | `/api/blacklist` | `ADMIN` | 新增黑名单 |
| PUT | `/api/blacklist/{id}` | `ADMIN` | 更新黑名单 |
| DELETE | `/api/blacklist/{id}` | `ADMIN` | 删除黑名单 |
| POST | `/api/signals/{laneId}` | `ADMIN`,`DISPATCHER` | 手动覆盖信号灯与车道模式 |
| POST | `/api/signals/restore-auto` | `ADMIN`,`DISPATCHER` | 恢复全域自动联动 |
| POST | `/api/signals/lockdown` | `ADMIN`,`DISPATCHER` | 执行全域锁死 |
| POST | `/api/dispatch/manual` | `ADMIN`,`DISPATCHER` | 发起人工调度 |
| POST | `/api/dispatch/daily-reset` | `ADMIN`,`DISPATCHER` | 执行上班日清，清空在场数据并重新打开首条入口/出口车道 |
| POST | `/api/integration/yard-entries` | `ADMIN`,`DISPATCHER` | 接收蓄车池总入口抓拍事件，生成大屏推荐车道并占用预留名额 |
| POST | `/api/integration/vehicle-entries` | `ADMIN`,`DISPATCHER` | 接收具体车道入口抓拍事件，完成实际入道与车牌核验 |

## 示例

```bash
curl http://localhost:8080/api/dashboard \
  -H "Authorization: Bearer <token>"
```
