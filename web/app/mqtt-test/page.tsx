"use client";

import { useCallback, useMemo, useState } from "react";
import { Buffer } from "buffer";
import mqtt, { type MqttClient } from "mqtt";
import {
  Activity,
  Cable,
  Link2Off,
  Radio,
  Server,
  Send,
  Terminal,
  Wifi,
  Clipboard,
  CheckCircle2,
  AlertCircle,
  MessageSquare,
  Hash,
} from "lucide-react";
import { cn } from "@/lib/utils";

type LogDirection = "tx" | "rx" | "system";

interface LogEntry {
  id: string;
  time: string;
  direction: LogDirection;
  topic: string;
  payload: string;
}

interface ConnectionInfo {
  label: string;
  value: string;
  copyable?: boolean;
}

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
  const buffer = Buffer.from(payload);
  const text = buffer.toString("utf8").trim();
  if (text && /^[\u0009\u000a\u000d\u0020-\u007e\u4e00-\u9fff{}[\]":,._+\-/\\\dA-Za-z]+$/.test(text)) {
    return text;
  }
  return buffer.toString("hex").toUpperCase();
}

const BROKER_HOST = typeof window !== "undefined"
  ? window.location.hostname
  : "localhost";

export default function MqttTestPage() {
  const [wsHost, setWsHost] = useState(BROKER_HOST);
  const [wsPort, setWsPort] = useState("9001");
  const [subscribeTopic, setSubscribeTopic] = useState("#");
  const [publishTopic, setPublishTopic] = useState("");
  const [publishPayload, setPublishPayload] = useState("");
  const [client, setClient] = useState<MqttClient | null>(null);
  const [connState, setConnState] = useState<"idle" | "connecting" | "connected" | "error">("idle");
  const [message, setMessage] = useState("");
  const [logs, setLogs] = useState<LogEntry[]>([]);
  const [activeTopics, setActiveTopics] = useState<Set<string>>(new Set());

  const addLog = useCallback((entry: Omit<LogEntry, "id" | "time">) => {
    setLogs((current) => [
      {
        id: genId(),
        time: nowText(),
        ...entry,
      },
      ...current.slice(0, 199),
    ]);
  }, []);

  const connect = useCallback(() => {
    if (client?.connected) {
      return;
    }
    setConnState("connecting");
    setMessage("");
    const nextClient = mqtt.connect(`ws://${wsHost}:${wsPort}`, {
      clientId: `mqtt-test-${genId()}`,
      clean: true,
      connectTimeout: 5000,
      reconnectPeriod: 0,
    });

    nextClient.on("connect", () => {
      setClient(nextClient);
      setConnState("connected");
      setMessage("MQTT Broker 已连接");
      nextClient.subscribe(subscribeTopic);
      addLog({ direction: "system", topic: subscribeTopic, payload: `已订阅 Topic: ${subscribeTopic}` });
    });

    nextClient.on("message", (topic, payload) => {
      setActiveTopics((prev) => {
        const next = new Set(prev);
        next.add(topic);
        return next;
      });
      addLog({ direction: "rx", topic, payload: displayPayload(payload) });
    });

    nextClient.on("error", (error) => {
      setConnState("error");
      setMessage(error.message);
      addLog({ direction: "system", topic: "mqtt", payload: `连接错误: ${error.message}` });
      nextClient.end(true);
      setClient(null);
    });

    nextClient.on("close", () => {
      setClient((current) => (current === nextClient ? null : current));
      setConnState((current) => (current === "connected" ? "idle" : current));
    });
  }, [addLog, client?.connected, subscribeTopic, wsHost, wsPort]);

  const disconnect = useCallback(() => {
    client?.end(true);
    setClient(null);
    setConnState("idle");
    setMessage("MQTT Broker 已断开");
    setActiveTopics(new Set());
  }, [client]);

  const publish = useCallback(() => {
    if (!client?.connected) {
      setMessage("请先连接 MQTT Broker");
      return;
    }
    if (!publishTopic.trim()) {
      setMessage("请输入要发布的 Topic");
      return;
    }
    try {
      client.publish(publishTopic.trim(), publishPayload);
      addLog({
        direction: "tx",
        topic: publishTopic.trim(),
        payload: publishPayload || "(空 payload)",
      });
      setMessage(`已发布到 ${publishTopic.trim()}`);
    } catch (error) {
      setMessage(error instanceof Error ? error.message : "发布失败");
    }
  }, [addLog, client, publishPayload, publishTopic]);

  const connectionInfo: ConnectionInfo[] = useMemo(
    () => [
      { label: "Broker 地址", value: BROKER_HOST, copyable: true },
      { label: "TCP 端口", value: "1883", copyable: true },
      { label: "WebSocket 端口", value: "9001", copyable: true },
      { label: "用户名", value: "无（匿名）", copyable: false },
      { label: "密码", value: "无", copyable: false },
      { label: "协议版本", value: "MQTT 3.1.1", copyable: false },
    ],
    []
  );

  const copyToClipboard = useCallback((text: string) => {
    navigator.clipboard?.writeText(text);
    setMessage("已复制到剪贴板");
  }, []);

  return (
    <main className="min-h-screen bg-slate-100 px-4 py-5 text-slate-900 sm:px-6">
      <div className="mx-auto flex max-w-7xl flex-col gap-4">
        <header className="flex flex-col gap-3 border-b border-slate-200 pb-4 lg:flex-row lg:items-end lg:justify-between">
          <div>
            <div className="mb-2 flex items-center gap-2 text-xs font-bold uppercase text-blue-700">
              <Radio className="size-4" />
              MQTT 设备联调
            </div>
            <h1 className="text-2xl font-bold tracking-tight text-slate-950">MQTT 设备测试台</h1>
            <p className="mt-2 max-w-3xl text-sm text-slate-600">
              本页用于检测 MQTT Broker 运行状态，监听设备上报消息，并可手动向设备发送指令。
              请将你的盒子设备配置为连接下方显示的 Broker 地址和端口。
            </p>
          </div>
          <div
            className={cn(
              "inline-flex w-fit items-center gap-2 rounded-md px-3 py-2 text-sm font-bold",
              connState === "connected"
                ? "bg-emerald-100 text-emerald-700"
                : connState === "error"
                  ? "bg-rose-100 text-rose-700"
                  : connState === "connecting"
                    ? "bg-blue-100 text-blue-700"
                    : "bg-amber-100 text-amber-700",
            )}
          >
            {connState === "connected" ? (
              <CheckCircle2 className="size-4" />
            ) : connState === "error" ? (
              <AlertCircle className="size-4" />
            ) : (
              <Activity className="size-4" />
            )}
            {connState === "connected"
              ? "WebSocket 已连接"
              : connState === "connecting"
                ? "连接中..."
                : connState === "error"
                  ? "连接失败"
                  : "未连接"}
          </div>
        </header>

        <section className="grid gap-4 xl:grid-cols-[380px_1fr]">
          <div className="grid gap-4">
            {/* Broker 连接信息 */}
            <div className="rounded-lg border border-slate-200 bg-white p-4 shadow-sm">
              <h2 className="mb-3 flex items-center gap-2 text-sm font-bold">
                <Server className="size-4 text-blue-600" />
                MQTT Broker 连接信息
              </h2>
              <p className="mb-3 text-xs text-slate-500">
                请在你的盒子设备中配置以下参数来连接本机的 MQTT 服务：
              </p>
              <div className="grid gap-2">
                {connectionInfo.map(({ label, value, copyable }) => (
                  <div key={label} className="grid grid-cols-[100px_1fr_28px] items-center gap-2 text-xs">
                    <span className="text-slate-500">{label}</span>
                    <span className="truncate rounded bg-slate-50 px-2 py-1 font-mono font-semibold text-slate-900">
                      {value}
                    </span>
                    {copyable && (
                      <button
                        type="button"
                        onClick={() => copyToClipboard(value)}
                        className="flex items-center justify-center rounded p-1 hover:bg-slate-100"
                        title="复制"
                      >
                        <Clipboard className="size-3.5 text-slate-400" />
                      </button>
                    )}
                  </div>
                ))}
              </div>
              <button
                type="button"
                onClick={() => {
                  const text = connectionInfo.map(({ label, value }) => `${label}: ${value}`).join("\n");
                  copyToClipboard(text);
                }}
                className="mt-3 inline-flex items-center gap-2 rounded-md border border-slate-200 bg-white px-3 py-2 text-xs font-bold text-slate-700 hover:bg-slate-50"
              >
                <Clipboard className="size-3.5" />
                复制全部配置
              </button>
            </div>

            {/* WebSocket 连接设置 */}
            <div className="rounded-lg border border-slate-200 bg-white p-4 shadow-sm">
              <h2 className="mb-3 flex items-center gap-2 text-sm font-bold">
                <Wifi className="size-4 text-blue-600" />
                页面连接设置
              </h2>
              <div className="grid gap-3">
                <div className="grid grid-cols-[1fr_96px] gap-2">
                  <label className="grid gap-1 text-xs font-semibold text-slate-600">
                    WebSocket 地址
                    <input
                      value={wsHost}
                      onChange={(e) => setWsHost(e.target.value)}
                      className="rounded-md border border-slate-200 bg-white px-3 py-2 font-mono text-sm text-slate-900 outline-none focus:border-blue-500"
                    />
                  </label>
                  <label className="grid gap-1 text-xs font-semibold text-slate-600">
                    端口
                    <input
                      value={wsPort}
                      onChange={(e) => setWsPort(e.target.value)}
                      className="rounded-md border border-slate-200 bg-white px-3 py-2 font-mono text-sm text-slate-900 outline-none focus:border-blue-500"
                    />
                  </label>
                </div>
                <label className="grid gap-1 text-xs font-semibold text-slate-600">
                  订阅 Topic
                  <div className="flex gap-2">
                    <input
                      value={subscribeTopic}
                      onChange={(e) => setSubscribeTopic(e.target.value)}
                      className="flex-1 rounded-md border border-slate-200 bg-white px-3 py-2 font-mono text-sm text-slate-900 outline-none focus:border-blue-500"
                      placeholder="# 表示订阅所有 Topic"
                    />
                  </div>
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
                {message ? (
                  <div className="rounded-md bg-slate-50 px-3 py-2 text-xs font-semibold text-slate-700">
                    {message}
                  </div>
                ) : null}
              </div>
            </div>

            {/* 活跃 Topic 列表 */}
            <div className="rounded-lg border border-slate-200 bg-white p-4 shadow-sm">
              <h2 className="mb-3 flex items-center gap-2 text-sm font-bold">
                <Hash className="size-4 text-violet-600" />
                已活跃 Topic
                <span className="ml-auto rounded-full bg-violet-100 px-2 py-0.5 text-xs text-violet-700">
                  {activeTopics.size}
                </span>
              </h2>
              {activeTopics.size === 0 ? (
                <div className="text-center text-xs text-slate-400 py-4">暂无设备上报消息</div>
              ) : (
                <div className="max-h-[200px] overflow-auto space-y-1">
                  {Array.from(activeTopics).map((topic) => (
                    <div
                      key={topic}
                      className="flex items-center gap-2 rounded bg-violet-50 px-2 py-1.5 text-xs font-mono text-violet-800"
                    >
                      <MessageSquare className="size-3 shrink-0" />
                      <span className="truncate">{topic}</span>
                    </div>
                  ))}
                </div>
              )}
            </div>
          </div>

          <div className="grid gap-4">
            {/* 消息发布 */}
            <div className="rounded-lg border border-slate-200 bg-white p-4 shadow-sm">
              <h2 className="mb-3 flex items-center gap-2 text-sm font-bold">
                <Send className="size-4 text-emerald-600" />
                向设备发送消息
              </h2>
              <div className="grid gap-3">
                <label className="grid gap-1 text-xs font-semibold text-slate-600">
                  目标 Topic
                  <input
                    value={publishTopic}
                    onChange={(e) => setPublishTopic(e.target.value)}
                    className="rounded-md border border-slate-200 bg-white px-3 py-2 font-mono text-sm text-slate-900 outline-none focus:border-emerald-500"
                    placeholder="例如: /device/DIDO-01/get"
                  />
                </label>
                <label className="grid gap-1 text-xs font-semibold text-slate-600">
                  Payload 内容
                  <textarea
                    value={publishPayload}
                    onChange={(e) => setPublishPayload(e.target.value)}
                    className="rounded-md border border-slate-200 bg-white px-3 py-2 font-mono text-sm text-slate-900 outline-none focus:border-emerald-500"
                    rows={3}
                    placeholder='例如: {"A01": 110000}'
                  />
                </label>
                <button
                  type="button"
                  onClick={publish}
                  disabled={connState !== "connected"}
                  className="inline-flex w-fit items-center gap-2 rounded-md bg-emerald-600 px-4 py-2 text-sm font-bold text-white hover:bg-emerald-500 disabled:opacity-50"
                >
                  <Send className="size-4" />
                  发送消息
                </button>
              </div>
            </div>

            {/* 消息日志 */}
            <div className="rounded-lg border border-slate-200 bg-white shadow-sm">
              <div className="flex items-center justify-between border-b border-slate-200 px-4 py-3">
                <h2 className="flex items-center gap-2 text-sm font-bold">
                  <Terminal className="size-4 text-blue-600" />
                  消息日志
                </h2>
                <div className="flex items-center gap-2">
                  <span className="text-xs text-slate-500">共 {logs.length} 条</span>
                  <button
                    type="button"
                    onClick={() => setLogs([])}
                    className="rounded-md border border-slate-200 px-2 py-1 text-xs font-bold text-slate-600 hover:bg-slate-50"
                  >
                    清空
                  </button>
                </div>
              </div>
              <div className="max-h-[500px] overflow-auto">
                {logs.length === 0 ? (
                  <div className="px-4 py-8 text-center text-sm text-slate-500">
                    暂无消息，连接成功后设备上报的数据会显示在这里
                  </div>
                ) : (
                  <div className="divide-y divide-slate-100">
                    {logs.map((log) => (
                      <div
                        key={log.id}
                        className="grid gap-1 px-4 py-3 text-xs md:grid-cols-[78px_82px_1fr] md:items-start"
                      >
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
                          <pre className="mt-1 whitespace-pre-wrap break-all font-mono text-[11px] leading-relaxed text-slate-600">
                            {log.payload}
                          </pre>
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
