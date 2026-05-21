"use client";

import { Buffer } from "buffer";
import { useCallback, useMemo, useState } from "react";
import mqtt, { type MqttClient } from "mqtt";
import {
  Activity,
  Camera,
  CheckCircle2,
  Clipboard,
  Eye,
  FileJson,
  Link2Off,
  Radio,
  RefreshCw,
  Send,
  Server,
  Terminal,
  Wifi,
} from "lucide-react";
import { MQTT_PASSWORD, MQTT_USERNAME, mqttAuthOptions } from "@/lib/mqtt-config";
import { cn, formatPlateDisplay } from "@/lib/utils";

type LogDirection = "tx" | "rx" | "system";
type CameraCommand = "getVerInfo" | "getHaveCar" | "getVideo" | "clearCount";

interface LogEntry {
  id: string;
  time: string;
  direction: LogDirection;
  topic: string;
  payload: string;
}

const DEFAULT_BROKER_HOST = process.env.NEXT_PUBLIC_DEVICE_MQTT_HOST ?? "192.168.55.10";
const DEFAULT_WS_HOST = process.env.NEXT_PUBLIC_MQTT_WS_HOST ?? "127.0.0.1";
const DEFAULT_CAMERA_ID = "18030023526b";

function nowText() {
  return new Date().toLocaleTimeString("zh-CN", { hour12: false });
}

function genId() {
  return Math.random().toString(36).slice(2, 9);
}

function displayPayload(payload: Buffer | Uint8Array | string) {
  if (typeof payload === "string") {
    return payload;
  }
  const text = Buffer.from(payload).toString("utf8").trim();
  return text || Buffer.from(payload).toString("hex").toUpperCase();
}

function buildCameraCommand(cameraId: string, cmd: CameraCommand, content: Record<string, unknown> = {}) {
  return JSON.stringify(
    {
      cmd,
      msgId: `${cmd}-${Date.now()}`,
      devId: cameraId,
      utcTs: Math.floor(Date.now() / 1000),
      content,
    },
    null,
    0,
  );
}

export default function CameraTestPage() {
  const [brokerHost, setBrokerHost] = useState(DEFAULT_BROKER_HOST);
  const [mqttPort] = useState("1883");
  const [wsHost, setWsHost] = useState(DEFAULT_WS_HOST);
  const [wsPort, setWsPort] = useState("9001");
  const [cameraId, setCameraId] = useState(DEFAULT_CAMERA_ID);
  const [customPayload, setCustomPayload] = useState("");
  const [client, setClient] = useState<MqttClient | null>(null);
  const [connState, setConnState] = useState<"idle" | "connecting" | "connected" | "error">("idle");
  const [message, setMessage] = useState("");
  const [logs, setLogs] = useState<LogEntry[]>([]);

  const normalizedCameraId = cameraId.trim() || DEFAULT_CAMERA_ID;
  const downTopic = `/device/${normalizedCameraId}/get`;
  const upTopic = `/device/${normalizedCameraId}/update`;
  const willTopic = `/device/${normalizedCameraId}/will`;
  const topicFilters = useMemo(() => [downTopic, upTopic, willTopic], [downTopic, upTopic, willTopic]);

  const addLog = useCallback((entry: Omit<LogEntry, "id" | "time">) => {
    setLogs((current) => [
      {
        id: genId(),
        time: nowText(),
        ...entry,
      },
      ...current.slice(0, 119),
    ]);
  }, []);

  const connect = useCallback(() => {
    if (client?.connected) {
      return;
    }
    setConnState("connecting");
    setMessage("");
    const nextClient = mqtt.connect(`ws://${wsHost}:${wsPort}`, {
      clientId: `camera-test-${genId()}`,
      clean: true,
      connectTimeout: 5000,
      reconnectPeriod: 0,
      ...mqttAuthOptions(),
    });

    nextClient.on("connect", () => {
      setClient(nextClient);
      setConnState("connected");
      setMessage("MQTT WebSocket 已连接");
      nextClient.subscribe(topicFilters);
      addLog({ direction: "system", topic: topicFilters.join(", "), payload: "已订阅相机上下行 Topic" });
    });

    nextClient.on("message", (topic, payload) => {
      addLog({ direction: topic === downTopic ? "tx" : "rx", topic, payload: displayPayload(payload) });
    });

    nextClient.on("error", (error) => {
      setConnState("error");
      setMessage(error.message);
      addLog({ direction: "system", topic: "mqtt", payload: error.message });
      nextClient.end(true);
      setClient(null);
    });

    nextClient.on("close", () => {
      setClient((current) => (current === nextClient ? null : current));
      setConnState((current) => (current === "connected" ? "idle" : current));
    });
  }, [addLog, client?.connected, downTopic, topicFilters, wsHost, wsPort]);

  const disconnect = useCallback(() => {
    client?.end(true);
    setClient(null);
    setConnState("idle");
    setMessage("MQTT WebSocket 已断开");
  }, [client]);

  const publishJson = useCallback(
    (payload: string, label?: string) => {
      if (!client?.connected) {
        setMessage("请先连接 MQTT Broker");
        return;
      }
      try {
        JSON.parse(payload);
      } catch {
        setMessage("JSON 格式不正确");
        return;
      }
      client.publish(downTopic, payload);
      addLog({ direction: "tx", topic: downTopic, payload: label ? `${label}: ${payload}` : payload });
      setMessage(`已发送到 ${downTopic}`);
    },
    [addLog, client, downTopic],
  );

  const latestRxJson = useMemo(() => {
    for (const log of logs) {
      if (log.direction !== "rx") {
        continue;
      }
      try {
        return JSON.parse(log.payload) as Record<string, unknown>;
      } catch {
        continue;
      }
    }
    return null;
  }, [logs]);

  const latestCmd = typeof latestRxJson?.cmd === "string" ? latestRxJson.cmd : "--";
  const latestDevId = typeof latestRxJson?.devId === "string" ? latestRxJson.devId : "--";
  const latestContent =
    latestRxJson && typeof latestRxJson.content === "object" && latestRxJson.content !== null
      ? (latestRxJson.content as Record<string, unknown>)
      : null;
  const latestPlate =
    typeof latestContent?.plateNum === "string"
      ? latestContent.plateNum
      : typeof latestContent?.plateNumVDC === "string"
        ? latestContent.plateNumVDC
        : "--";
  const latestIp = typeof latestRxJson?.devIp === "string" ? latestRxJson.devIp : "--";
  const latestAlarmType = typeof latestContent?.alarmType === "string" || typeof latestContent?.alarmType === "number"
    ? String(latestContent?.alarmType)
    : "--";

  const configRows = [
    ["Broker 地址", brokerHost],
    ["MQTT 端口", mqttPort],
    ["用户名", MQTT_USERNAME || "无（匿名）"],
    ["密码", MQTT_USERNAME ? MQTT_PASSWORD : "无"],
    ["相机 devId", normalizedCameraId],
    ["下发 Topic", downTopic],
    ["上报 Topic", upTopic],
    ["遗嘱 Topic", willTopic],
  ];

  const commandPresets: Array<{ label: string; cmd: CameraCommand; icon: typeof Radio }> = [
    { label: "读取版本", cmd: "getVerInfo", icon: Radio },
    { label: "读取在位", cmd: "getHaveCar", icon: Eye },
    { label: "读取视频", cmd: "getVideo", icon: Camera },
    { label: "清空计数", cmd: "clearCount", icon: RefreshCw },
  ];

  return (
    <main className="min-h-screen bg-slate-100 px-4 py-5 text-slate-900 sm:px-6">
      <div className="mx-auto flex max-w-7xl flex-col gap-4">
        <header className="flex flex-col gap-3 border-b border-slate-200 pb-4 lg:flex-row lg:items-end lg:justify-between">
          <div>
            <div className="mb-2 flex items-center gap-2 text-xs font-bold uppercase text-blue-700">
              <Camera className="size-4" />
              Smart Camera MQTT
            </div>
            <h1 className="text-2xl font-bold tracking-tight text-slate-950">计数报警 MQTT 测试页</h1>
            <p className="mt-2 max-w-3xl text-sm text-slate-600">
              本页按《计数报警.docx》协议联调，重点验证 `heartbeat`、`devAlarm`、`passCount`，以及
              `/device/{`devId`}/get` 下发的相机指令。
            </p>
          </div>
          <div
            className={cn(
              "inline-flex w-fit items-center gap-2 rounded-md px-3 py-2 text-sm font-bold",
              connState === "connected"
                ? "bg-emerald-100 text-emerald-700"
                : connState === "error"
                  ? "bg-rose-100 text-rose-700"
                  : "bg-amber-100 text-amber-700",
            )}
          >
            <Activity className="size-4" />
            {connState === "connected" ? "Broker 已连接" : connState === "connecting" ? "连接中" : "Broker 未连接"}
          </div>
        </header>

        <section className="grid gap-4 xl:grid-cols-[380px_1fr]">
          <div className="grid gap-4">
            <div className="rounded-lg border border-slate-200 bg-white p-4 shadow-sm">
              <h2 className="mb-3 flex items-center gap-2 text-sm font-bold">
                <Server className="size-4 text-blue-600" />
                协议摘要
              </h2>
              <div className="grid gap-2">
                {configRows.map(([label, value]) => (
                  <div key={label} className="grid grid-cols-[92px_1fr] items-center gap-2 text-xs">
                    <span className="text-slate-500">{label}</span>
                    <span className="truncate rounded bg-slate-50 px-2 py-1 font-mono font-semibold text-slate-900">{value}</span>
                  </div>
                ))}
              </div>
              <button
                type="button"
                onClick={() => {
                  const text = configRows.map(([label, value]) => `${label}: ${value}`).join("\n");
                  navigator.clipboard?.writeText(text);
                  setMessage("配置参数已复制");
                }}
                className="mt-3 inline-flex items-center gap-2 rounded-md border border-slate-200 bg-white px-3 py-2 text-xs font-bold text-slate-700 hover:bg-slate-50"
              >
                <Clipboard className="size-3.5" />
                复制配置
              </button>
            </div>

            <div className="rounded-lg border border-slate-200 bg-white p-4 shadow-sm">
              <h2 className="mb-3 flex items-center gap-2 text-sm font-bold">
                <Wifi className="size-4 text-blue-600" />
                连接设置
              </h2>
              <div className="grid gap-3">
                <label className="grid gap-1 text-xs font-semibold text-slate-600">
                  设备侧 Broker 地址
                  <input
                    value={brokerHost}
                    onChange={(event) => setBrokerHost(event.target.value)}
                    className="rounded-md border border-slate-200 bg-white px-3 py-2 font-mono text-sm text-slate-900 outline-none focus:border-blue-500"
                  />
                </label>
                <label className="grid gap-1 text-xs font-semibold text-slate-600">
                  页面连接地址
                  <input
                    value={wsHost}
                    onChange={(event) => setWsHost(event.target.value)}
                    className="rounded-md border border-slate-200 bg-white px-3 py-2 font-mono text-sm text-slate-900 outline-none focus:border-blue-500"
                  />
                </label>
                <div className="grid grid-cols-[1fr_96px] gap-2">
                  <label className="grid gap-1 text-xs font-semibold text-slate-600">
                    相机 devId
                    <input
                      value={cameraId}
                      onChange={(event) => setCameraId(event.target.value)}
                      className="rounded-md border border-slate-200 bg-white px-3 py-2 font-mono text-sm text-slate-900 outline-none focus:border-blue-500"
                    />
                  </label>
                  <label className="grid gap-1 text-xs font-semibold text-slate-600">
                    WS 端口
                    <input
                      value={wsPort}
                      onChange={(event) => setWsPort(event.target.value)}
                      className="rounded-md border border-slate-200 bg-white px-3 py-2 font-mono text-sm text-slate-900 outline-none focus:border-blue-500"
                    />
                  </label>
                </div>
                <div className="grid grid-cols-2 gap-2">
                  <button
                    type="button"
                    onClick={connect}
                    disabled={connState === "connected" || connState === "connecting"}
                    className="inline-flex items-center justify-center gap-2 rounded-md bg-blue-600 px-3 py-2 text-sm font-bold text-white hover:bg-blue-500 disabled:opacity-50"
                  >
                    <Wifi className="size-4" />
                    连接
                  </button>
                  <button
                    type="button"
                    onClick={disconnect}
                    disabled={!client}
                    className="inline-flex items-center justify-center gap-2 rounded-md border border-slate-200 bg-white px-3 py-2 text-sm font-bold text-slate-700 hover:bg-slate-50 disabled:opacity-50"
                  >
                    <Link2Off className="size-4" />
                    断开
                  </button>
                </div>
                {message ? <div className="rounded-md bg-slate-50 px-3 py-2 text-xs font-semibold text-slate-700">{message}</div> : null}
              </div>
            </div>

            <div className="rounded-lg border border-slate-200 bg-white p-4 shadow-sm">
              <h2 className="mb-3 flex items-center gap-2 text-sm font-bold">
                <FileJson className="size-4 text-blue-600" />
                最近状态
              </h2>
              <div className="grid gap-2 text-xs">
                <div className="grid grid-cols-[88px_1fr] gap-2">
                  <span className="text-slate-500">最近命令</span>
                  <span className="font-mono font-semibold">{latestCmd}</span>
                </div>
                <div className="grid grid-cols-[88px_1fr] gap-2">
                  <span className="text-slate-500">最近 devId</span>
                  <span className="font-mono font-semibold">{latestDevId}</span>
                </div>
                <div className="grid grid-cols-[88px_1fr] gap-2">
                  <span className="text-slate-500">最近车牌</span>
                  <span className="font-mono font-semibold">{formatPlateDisplay(latestPlate) || latestPlate}</span>
                </div>
                <div className="grid grid-cols-[88px_1fr] gap-2">
                  <span className="text-slate-500">alarmType</span>
                  <span className="font-mono font-semibold">{latestAlarmType}</span>
                </div>
                <div className="grid grid-cols-[88px_1fr] gap-2">
                  <span className="text-slate-500">设备 IP</span>
                  <span className="font-mono font-semibold">{latestIp}</span>
                </div>
              </div>
            </div>
          </div>

          <div className="grid gap-4">
            <div className="rounded-lg border border-slate-200 bg-white p-4 shadow-sm">
              <div className="mb-4 flex flex-col gap-2 lg:flex-row lg:items-center lg:justify-between">
                <h2 className="flex items-center gap-2 text-sm font-bold">
                  <Send className="size-4 text-emerald-600" />
                  相机命令
                </h2>
                <div className="font-mono text-xs text-slate-500">{downTopic}</div>
              </div>

              <div className="grid gap-3 md:grid-cols-2 xl:grid-cols-4">
                {commandPresets.map((item) => {
                  const Icon = item.icon;
                  return (
                    <button
                      key={item.cmd}
                      type="button"
                      onClick={() => publishJson(buildCameraCommand(normalizedCameraId, item.cmd), item.label)}
                      disabled={connState !== "connected"}
                      className="inline-flex items-center justify-center gap-2 rounded-md border border-slate-200 bg-white px-3 py-3 text-sm font-bold text-slate-700 hover:bg-slate-50 disabled:opacity-50"
                    >
                      <Icon className="size-4" />
                      {item.label}
                    </button>
                  );
                })}
              </div>

              <div className="mt-4 rounded-md border border-slate-200 bg-slate-50 p-3">
                <div className="mb-2 text-xs font-bold text-slate-700">命令示例</div>
                <pre className="overflow-auto break-all font-mono text-xs leading-relaxed text-slate-900">
                  {buildCameraCommand(normalizedCameraId, "getVerInfo")}
                </pre>
              </div>
            </div>

            <div className="rounded-lg border border-slate-200 bg-white p-4 shadow-sm">
              <h2 className="mb-3 flex items-center gap-2 text-sm font-bold">
                <Terminal className="size-4 text-blue-600" />
                自定义 JSON
              </h2>
              <textarea
                value={customPayload}
                onChange={(event) => setCustomPayload(event.target.value)}
                placeholder={buildCameraCommand(normalizedCameraId, "getHaveCar")}
                className="min-h-44 w-full rounded-md border border-slate-200 bg-white px-3 py-3 font-mono text-xs text-slate-900 outline-none focus:border-blue-500"
              />
              <div className="mt-3 flex gap-2">
                <button
                  type="button"
                  onClick={() => publishJson(customPayload, "自定义命令")}
                  disabled={connState !== "connected" || !customPayload.trim()}
                  className="inline-flex items-center justify-center gap-2 rounded-md bg-blue-600 px-4 py-2 text-sm font-bold text-white hover:bg-blue-500 disabled:opacity-50"
                >
                  <Send className="size-4" />
                  发送 JSON
                </button>
                <button
                  type="button"
                  onClick={() => setCustomPayload("")}
                  className="inline-flex items-center justify-center gap-2 rounded-md border border-slate-200 bg-white px-4 py-2 text-sm font-bold text-slate-700 hover:bg-slate-50"
                >
                  <Terminal className="size-4" />
                  清空
                </button>
              </div>
            </div>

            <div className="grid gap-4 lg:grid-cols-2">
              <div className="rounded-lg border border-slate-200 bg-white p-4 shadow-sm">
                <h2 className="mb-3 flex items-center gap-2 text-sm font-bold">
                  <Radio className="size-4 text-blue-600" />
                  Topic
                </h2>
                <div className="grid gap-2 text-xs">
                  <div className="grid grid-cols-[78px_1fr] gap-2">
                    <span className="text-slate-500">下发</span>
                    <span className="truncate font-mono font-semibold">{downTopic}</span>
                  </div>
                  <div className="grid grid-cols-[78px_1fr] gap-2">
                    <span className="text-slate-500">上报</span>
                    <span className="truncate font-mono font-semibold">{upTopic}</span>
                  </div>
                  <div className="grid grid-cols-[78px_1fr] gap-2">
                    <span className="text-slate-500">遗嘱</span>
                    <span className="truncate font-mono font-semibold">{willTopic}</span>
                  </div>
                </div>
              </div>

              <div className="rounded-lg border border-slate-200 bg-white p-4 shadow-sm">
                <h2 className="mb-3 flex items-center gap-2 text-sm font-bold">
                  <CheckCircle2 className="size-4 text-emerald-600" />
                  联调提示
                </h2>
                <div className="grid gap-2 text-xs leading-relaxed text-slate-600">
                  <div>1. 这套设备按《计数报警.docx》协议，上报主题是 `/device/{`devId`}/update`。</div>
                  <div>2. 在线状态一般看 `heartbeat`，计数看 `passCount`。</div>
                  <div>3. 车牌号重点看 `devAlarm` 的 `content.plateNum`、`content.plateNumVDC`。</div>
                  <div>4. 如果只收到 `passCount` 没收到 `devAlarm`，说明设备还没把车牌事件往 MQTT 发出来。</div>
                </div>
              </div>
            </div>

            <div className="rounded-lg border border-slate-200 bg-white shadow-sm">
              <div className="flex items-center justify-between border-b border-slate-200 px-4 py-3">
                <h2 className="flex items-center gap-2 text-sm font-bold">
                  <Send className="size-4 text-blue-600" />
                  MQTT 日志
                </h2>
                <span className="text-xs text-slate-500">最新在上</span>
              </div>
              <div className="max-h-[360px] overflow-auto">
                {logs.length === 0 ? (
                  <div className="px-4 py-8 text-center text-sm text-slate-500">暂无消息</div>
                ) : (
                  <div className="divide-y divide-slate-100">
                    {logs.map((log) => (
                      <div key={log.id} className="grid gap-1 px-4 py-3 text-xs md:grid-cols-[78px_82px_1fr] md:items-start">
                        <span className="font-mono text-slate-500">{log.time}</span>
                        <span
                          className={cn(
                            "w-fit rounded px-2 py-0.5 font-bold",
                            log.direction === "tx"
                              ? "bg-blue-100 text-blue-700"
                              : log.direction === "rx"
                                ? "bg-emerald-100 text-emerald-700"
                                : "bg-slate-100 text-slate-600",
                          )}
                        >
                          {log.direction.toUpperCase()}
                        </span>
                        <div className="min-w-0">
                          <div className="truncate font-mono font-semibold text-slate-800">{log.topic}</div>
                          <pre className="mt-1 whitespace-pre-wrap break-all font-mono text-[11px] leading-relaxed text-slate-600">{log.payload}</pre>
                        </div>
                      </div>
                    ))}
                  </div>
                )}
              </div>
            </div>
          </div>
        </section>
      </div>
    </main>
  );
}
