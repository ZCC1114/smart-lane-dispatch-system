"use client";

import { useCallback, useMemo, useState } from "react";
import { Buffer } from "buffer";
import mqtt, { type MqttClient } from "mqtt";
import {
  Activity,
  Cable,
  CheckCircle2,
  Clipboard,
  Cpu,
  Link2Off,
  PlugZap,
  Radio,
  RefreshCw,
  Send,
  Server,
  Settings,
  Terminal,
  Wifi,
} from "lucide-react";
import { MQTT_PASSWORD, MQTT_USERNAME, mqttAuthOptions } from "@/lib/mqtt-config";
import { cn } from "@/lib/utils";

type MqttMode = "json" | "hex-a1" | "hex-a3";
type LogDirection = "tx" | "rx" | "system";

interface LogEntry {
  id: string;
  time: string;
  direction: LogDirection;
  topic: string;
  payload: string;
}

const DEFAULT_DEVICE_HOST = process.env.NEXT_PUBLIC_DEVICE_MQTT_HOST ?? "192.168.1.45";
const DEFAULT_WS_HOST = process.env.NEXT_PUBLIC_MQTT_WS_HOST ?? "127.0.0.1";
const REMOTE_CONFIG_HEX = "4D9301010101A1000000";
const RELAY_UPLOAD_HEX = "4D930101010AA1000000";

function nowText() {
  return new Date().toLocaleTimeString("zh-CN", { hour12: false });
}

function genId() {
  return Math.random().toString(36).slice(2, 9);
}

function relayIndex(relay: string) {
  const value = Number(relay.replace(/\D/g, ""));
  return Number.isFinite(value) ? Math.max(1, Math.min(value, 16)) : 1;
}

function byteHex(value: number) {
  return (value & 0xff).toString(16).padStart(2, "0").toUpperCase();
}

function hexToBuffer(hex: string) {
  const cleaned = hex.replace(/\s+/g, "");
  if (!/^[0-9a-fA-F]+$/.test(cleaned) || cleaned.length % 2 !== 0) {
    throw new Error("HEX 格式不正确");
  }
  return Buffer.from(cleaned, "hex");
}

function buildA1Hex(relay: string, on: boolean) {
  const index = relayIndex(relay);
  const mask = 1 << (index - 1);
  const state = on ? mask : 0;
  return [
    "CC",
    "DD",
    "A1",
    "01",
    byteHex(state >> 8),
    byteHex(state),
    byteHex(mask >> 8),
    byteHex(mask),
    "A4",
    "48",
  ].join("");
}

function buildA3Hex(relay: string, on: boolean) {
  const index = relayIndex(relay);
  const group = Math.floor((index - 1) / 8);
  const bit = 1 << ((index - 1) % 8);
  const bytes = Array.from({ length: 20 }, () => 0);
  bytes[0] = 0xcc;
  bytes[1] = 0xdd;
  bytes[2] = 0xa3;
  bytes[3] = 0x01;
  bytes[4 + (5 - group)] = on ? bit : 0;
  bytes[10 + (5 - group)] = bit;
  bytes[18] = 0xdd;
  bytes[19] = 0xcc;
  return bytes.map(byteHex).join("");
}

function buildRelayPayload(relay: string, on: boolean, mode: MqttMode, res?: string) {
  if (mode === "hex-a1") {
    return buildA1Hex(relay, on);
  }
  if (mode === "hex-a3") {
    return buildA3Hex(relay, on);
  }
  return JSON.stringify(
    {
      [relay]: on ? 110000 : 100000,
      res: res ?? `${on ? "on" : "off"}-${Date.now()}`,
    },
    null,
    0,
  );
}

function displayPayload(payload: Buffer | Uint8Array | string) {
  if (typeof payload === "string") {
    return payload;
  }
  const buffer = Buffer.from(payload);
  const text = buffer.toString("utf8").trim();
  if (text && /^[\u0009\u000a\u000d\u0020-\u007e\u4e00-\u9fff{}[\]":,._+\-/\\\dA-Za-z]+$/.test(text)) {
    return text;
  }
  return buffer.toString("hex").toUpperCase();
}

export default function Cx6eTestPage() {
  const [deviceHost, setDeviceHost] = useState(DEFAULT_DEVICE_HOST);
  const [mqttPort, setMqttPort] = useState("1883");
  const [wsHost, setWsHost] = useState(DEFAULT_WS_HOST);
  const [wsPort, setWsPort] = useState("9001");
  const [deviceId, setDeviceId] = useState("DIDO-01");
  const [relay, setRelay] = useState("A01");
  const [mode, setMode] = useState<MqttMode>("json");
  const [client, setClient] = useState<MqttClient | null>(null);
  const [connState, setConnState] = useState<"idle" | "connecting" | "connected" | "error">("idle");
  const [message, setMessage] = useState("");
  const [logs, setLogs] = useState<LogEntry[]>([]);

  const normalizedDeviceId = deviceId.trim() || "DIDO-01";
  const downTopic = `/device/${normalizedDeviceId}/get`;
  const upTopic = `/device/${normalizedDeviceId}/update`;
  const topicFilters = useMemo(() => [downTopic, upTopic], [downTopic, upTopic]);
  const isHex = mode !== "json";

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
      clientId: `cx6e-test-${genId()}`,
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
      addLog({ direction: "system", topic: topicFilters.join(", "), payload: "已订阅页面下发与上报 Topic" });
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

  const publishRaw = useCallback(
    (payload: string, options?: { binaryHex?: boolean; label?: string }) => {
      if (!client?.connected) {
        setMessage("请先连接 MQTT Broker");
        return;
      }
      try {
        const data = options?.binaryHex ? hexToBuffer(payload) : payload;
        client.publish(downTopic, data);
        addLog({
          direction: "tx",
          topic: downTopic,
          payload: options?.label ? `${options.label}: ${payload}` : payload,
        });
        setMessage(`已发送到 ${downTopic}`);
      } catch (error) {
        setMessage(error instanceof Error ? error.message : "发送失败");
      }
    },
    [addLog, client, downTopic],
  );

  const relayPayload = useMemo(() => buildRelayPayload(relay, true, mode, "preview"), [mode, relay]);
  const configRows = [
    ["服务器地址", deviceHost],
    ["MQTT 端口", mqttPort],
    ["设备 ID", deviceId],
    ["下发 Topic", downTopic],
    ["上报 Topic", upTopic],
    ["用户名", MQTT_USERNAME || "无（匿名）"],
    ["密码", MQTT_USERNAME ? MQTT_PASSWORD : "无"],
  ];

  return (
    <main className="min-h-screen bg-slate-100 px-4 py-5 text-slate-900 sm:px-6">
      <div className="mx-auto flex max-w-7xl flex-col gap-4">
        <header className="flex flex-col gap-3 border-b border-slate-200 pb-4 lg:flex-row lg:items-end lg:justify-between">
          <div>
            <div className="mb-2 flex items-center gap-2 text-xs font-bold uppercase text-blue-700">
              <Cpu className="size-4" />
              CX-6E MQTT 联调
            </div>
            <h1 className="text-2xl font-bold tracking-tight text-slate-950">CX-6E 设备测试台</h1>
            <p className="mt-2 max-w-3xl text-sm text-slate-600">
              本页直接连接本机 MQTT Broker，通过 `/device/{deviceId}/get` 给设备发送继电器控制信号，并监听
              `/device/{deviceId}/update` 上报反馈。
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
                官方配置软件参数
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
                    value={deviceHost}
                    onChange={(event) => setDeviceHost(event.target.value)}
                    className="rounded-md border border-slate-200 bg-white px-3 py-2 font-mono text-sm text-slate-900 outline-none focus:border-blue-500"
                  />
                </label>
                <label className="grid gap-1 text-xs font-semibold text-slate-600">
                  MQTT TCP 端口
                  <input
                    value={mqttPort}
                    onChange={(event) => setMqttPort(event.target.value)}
                    className="rounded-md border border-slate-200 bg-white px-3 py-2 font-mono text-sm text-slate-900 outline-none focus:border-blue-500"
                  />
                </label>
                <div className="grid grid-cols-[1fr_96px] gap-2">
                  <label className="grid gap-1 text-xs font-semibold text-slate-600">
                    页面连接地址
                    <input
                      value={wsHost}
                      onChange={(event) => setWsHost(event.target.value)}
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
                <label className="grid gap-1 text-xs font-semibold text-slate-600">
                  设备 ID
                  <input
                    value={deviceId}
                    onChange={(event) => setDeviceId(event.target.value)}
                    className="rounded-md border border-slate-200 bg-white px-3 py-2 font-mono text-sm text-slate-900 outline-none focus:border-blue-500"
                  />
                </label>
                <div className="grid grid-cols-2 gap-2">
                  <button
                    type="button"
                    onClick={connect}
                    disabled={connState === "connected" || connState === "connecting"}
                    className="inline-flex items-center justify-center gap-2 rounded-md bg-blue-600 px-3 py-2 text-sm font-bold text-white hover:bg-blue-500 disabled:opacity-50"
                  >
                    <Cable className="size-4" />
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
          </div>

          <div className="grid gap-4">
            <div className="rounded-lg border border-slate-200 bg-white p-4 shadow-sm">
              <div className="mb-4 flex flex-col gap-2 lg:flex-row lg:items-center lg:justify-between">
                <h2 className="flex items-center gap-2 text-sm font-bold">
                  <PlugZap className="size-4 text-emerald-600" />
                  DO 口控制
                </h2>
                <div className="font-mono text-xs text-slate-500">{downTopic}</div>
              </div>
              <div className="grid gap-3 lg:grid-cols-[120px_150px_1fr]">
                <label className="grid gap-1 text-xs font-semibold text-slate-600">
                  DO 口
                  <select
                    value={relay}
                    onChange={(event) => setRelay(event.target.value)}
                    className="rounded-md border border-slate-200 bg-white px-3 py-2 text-sm text-slate-900 outline-none focus:border-emerald-500"
                  >
                    {Array.from({ length: 16 }).map((_, index) => {
                      const value = `A${String(index + 1).padStart(2, "0")}`;
                      return (
                        <option key={value} value={value}>
                          {value}
                        </option>
                      );
                    })}
                  </select>
                </label>
                <label className="grid gap-1 text-xs font-semibold text-slate-600">
                  下发格式
                  <select
                    value={mode}
                    onChange={(event) => setMode(event.target.value as MqttMode)}
                    className="rounded-md border border-slate-200 bg-white px-3 py-2 text-sm text-slate-900 outline-none focus:border-emerald-500"
                  >
                    <option value="json">JSON</option>
                    <option value="hex-a1">HEX A1</option>
                    <option value="hex-a3">HEX A3</option>
                  </select>
                </label>
                <div className="grid grid-cols-2 gap-2 self-end md:grid-cols-4">
                  <button
                    type="button"
                    onClick={() => publishRaw(buildRelayPayload(relay, true, mode), { binaryHex: isHex })}
                    disabled={connState !== "connected"}
                    className="inline-flex items-center justify-center gap-2 rounded-md bg-emerald-600 px-3 py-2 text-sm font-bold text-white hover:bg-emerald-500 disabled:opacity-50"
                  >
                    <CheckCircle2 className="size-4" />
                    吸合
                  </button>
                  <button
                    type="button"
                    onClick={() => publishRaw(buildRelayPayload(relay, false, mode), { binaryHex: isHex })}
                    disabled={connState !== "connected"}
                    className="inline-flex items-center justify-center gap-2 rounded-md bg-rose-600 px-3 py-2 text-sm font-bold text-white hover:bg-rose-500 disabled:opacity-50"
                  >
                    <PlugZap className="size-4" />
                    断开
                  </button>
                  <button
                    type="button"
                    onClick={() => publishRaw(JSON.stringify({ readall: 0, res: `read-${Date.now()}` }))}
                    disabled={connState !== "connected" || mode !== "json"}
                    className="inline-flex items-center justify-center gap-2 rounded-md border border-slate-200 bg-white px-3 py-2 text-sm font-bold text-slate-700 hover:bg-slate-50 disabled:opacity-50"
                  >
                    <RefreshCw className="size-4" />
                    读状态
                  </button>
                  <button
                    type="button"
                    onClick={() => setLogs([])}
                    className="inline-flex items-center justify-center gap-2 rounded-md border border-slate-200 bg-white px-3 py-2 text-sm font-bold text-slate-700 hover:bg-slate-50"
                  >
                    <Terminal className="size-4" />
                    清空
                  </button>
                </div>
              </div>
              <div className="mt-4 rounded-md border border-slate-200 bg-slate-50 p-3">
                <div className="mb-2 text-xs font-bold text-slate-700">当前吸合 payload</div>
                <pre className="max-h-28 overflow-auto break-all font-mono text-xs leading-relaxed text-slate-900">{relayPayload}</pre>
              </div>
            </div>

            <div className="grid gap-4 lg:grid-cols-2">
              <div className="rounded-lg border border-amber-200 bg-amber-50 p-4 shadow-sm">
                <h2 className="mb-3 flex items-center gap-2 text-sm font-bold text-amber-900">
                  <Settings className="size-4" />
                  CX HEX 辅助指令
                </h2>
                <div className="grid gap-2">
                  <button
                    type="button"
                    onClick={() => publishRaw(REMOTE_CONFIG_HEX, { binaryHex: true, label: "启用远程配置" })}
                    disabled={connState !== "connected"}
                    className="rounded-md border border-amber-200 bg-white px-3 py-2 text-left text-xs font-bold text-amber-800 hover:bg-amber-100 disabled:opacity-50"
                  >
                    启用远程配置
                    <span className="mt-1 block font-mono text-[11px]">{REMOTE_CONFIG_HEX}</span>
                  </button>
                  <button
                    type="button"
                    onClick={() => publishRaw(RELAY_UPLOAD_HEX, { binaryHex: true, label: "启用继电器状态上报" })}
                    disabled={connState !== "connected"}
                    className="rounded-md border border-amber-200 bg-white px-3 py-2 text-left text-xs font-bold text-amber-800 hover:bg-amber-100 disabled:opacity-50"
                  >
                    启用继电器状态上报
                    <span className="mt-1 block font-mono text-[11px]">{RELAY_UPLOAD_HEX}</span>
                  </button>
                </div>
              </div>

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
                    <span className="text-slate-500">页面订阅</span>
                    <span className="truncate font-mono font-semibold">{topicFilters.join(", ")}</span>
                  </div>
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
