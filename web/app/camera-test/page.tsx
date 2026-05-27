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
type MonitorMode = "single" | "all";

interface LogEntry {
  id: string;
  time: string;
  direction: LogDirection;
  topic: string;
  payload: string;
  laneLabel?: string;
}

const DEFAULT_BROKER_HOST = process.env.NEXT_PUBLIC_DEVICE_MQTT_HOST ?? "127.0.0.1";
const DEFAULT_WS_HOST = process.env.NEXT_PUBLIC_MQTT_WS_HOST ?? DEFAULT_BROKER_HOST;
const DEFAULT_CAMERA_ID = "18030023535D";
const DEFAULT_MF_SN = "00E02721A3A7";
const DEFAULT_MF_GROUP_ID = "9QHZNII";
const DEFAULT_MF_DEVICE_NO = "09K2900202441623";
const DEFAULT_PLATE = "苏B3T530";
const DEFAULT_MF_PLATE_COLOR = "BLUE";
const LANE_CAMERA_IDS = [
  { laneId: "L01", label: "1号车道", devId: "18030023535D" },
  { laneId: "L02", label: "2号车道", devId: "1803002352FD" },
  { laneId: "L03", label: "3号车道", devId: "180300235361" },
  { laneId: "L04", label: "4号车道", devId: "18030023526B" },
  { laneId: "L05", label: "5号车道", devId: "180300235302" },
  { laneId: "L06", label: "6号车道", devId: "180300235265" },
  { laneId: "L07", label: "7号车道", devId: "1803002353CD" },
  { laneId: "L08", label: "8号车道", devId: "180300235396" },
  { laneId: "L09", label: "9号车道", devId: "18030023525B" },
  { laneId: "L10", label: "10号车道", devId: "18030023526C" },
  { laneId: "L11", label: "11号车道", devId: "1803002353D4" },
];

function currentBrowserHost(fallback: string) {
  if (typeof window === "undefined") {
    return fallback;
  }
  return window.location.hostname || fallback;
}

function nowText() {
  return new Date().toLocaleTimeString("zh-CN", { hour12: false });
}

function genId() {
  return Math.random().toString(36).slice(2, 9);
}

function formatCameraTime(date = new Date()) {
  const pad = (value: number) => String(value).padStart(2, "0");
  return [
    date.getFullYear(),
    "-",
    pad(date.getMonth() + 1),
    "-",
    pad(date.getDate()),
    " ",
    pad(date.getHours()),
    ":",
    pad(date.getMinutes()),
    ":",
    pad(date.getSeconds()),
  ].join("");
}

function displayPayload(payload: Buffer | Uint8Array | string) {
  if (typeof payload === "string") {
    return payload;
  }
  const text = Buffer.from(payload).toString("utf8").trim();
  return text || Buffer.from(payload).toString("hex").toUpperCase();
}

function deviceIdFromTopic(topic: string) {
  const match = /^\/device\/([^/]+)\/(?:get|update|will)$/.exec(topic);
  return match?.[1] ?? "";
}

function laneLabelForDeviceId(deviceId: string) {
  const lane = LANE_CAMERA_IDS.find((item) => item.devId.toUpperCase() === deviceId.toUpperCase());
  return lane ? `${lane.label} ${lane.laneId}` : "";
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

function buildParkingMfPlateResult(sn: string, groupId: string, deviceNo: string, plate: string, plateColor: string) {
  const timestamp = Date.now();
  return JSON.stringify(
    {
      cmd: "plateResult",
      sn,
      msgId: `mf-plate-${timestamp}`,
      timestamp,
      timezone: "Asia/Shanghai",
      data: {
        groupId,
        deviceNo,
        plateNo: plate,
        plateColor,
        parkingTime: formatCameraTime(),
        uploadTime: timestamp,
        realTime: true,
      },
    },
    null,
    0,
  );
}

function buildParkingMfHeartbeat(sn: string, groupId: string, deviceNo: string) {
  const timestamp = Date.now();
  return JSON.stringify(
    {
      cmd: "heartbeat",
      sn,
      msgId: `mf-heartbeat-${timestamp}`,
      timestamp,
      timezone: "Asia/Shanghai",
      data: {
        deviceStatus: [
          {
            groupId,
            deviceNo,
            network: "online",
            checkTime: formatCameraTime(),
          },
        ],
      },
    },
    null,
    0,
  );
}

export default function CameraTestPage() {
  const [brokerHost, setBrokerHost] = useState(() => currentBrowserHost(DEFAULT_BROKER_HOST));
  const [mqttPort] = useState("1883");
  const [wsHost, setWsHost] = useState(() => currentBrowserHost(DEFAULT_WS_HOST));
  const [wsPort, setWsPort] = useState("9001");
  const [monitorMode, setMonitorMode] = useState<MonitorMode>("single");
  const [cameraId, setCameraId] = useState(DEFAULT_CAMERA_ID);
  const [mfSn, setMfSn] = useState(DEFAULT_MF_SN);
  const [mfGroupId, setMfGroupId] = useState(DEFAULT_MF_GROUP_ID);
  const [mfDeviceNo, setMfDeviceNo] = useState(DEFAULT_MF_DEVICE_NO);
  const [mfPlate, setMfPlate] = useState(DEFAULT_PLATE);
  const [mfPlateColor, setMfPlateColor] = useState(DEFAULT_MF_PLATE_COLOR);
  const [customPayload, setCustomPayload] = useState("");
  const [client, setClient] = useState<MqttClient | null>(null);
  const [connState, setConnState] = useState<"idle" | "connecting" | "connected" | "error">("idle");
  const [message, setMessage] = useState("");
  const [logs, setLogs] = useState<LogEntry[]>([]);

  const normalizedCameraId = cameraId.trim() || DEFAULT_CAMERA_ID;
  const downTopic = `/device/${normalizedCameraId}/get`;
  const upTopic = `/device/${normalizedCameraId}/update`;
  const willTopic = `/device/${normalizedCameraId}/will`;
  const normalizedMfSn = mfSn.trim() || DEFAULT_MF_SN;
  const normalizedMfGroupId = mfGroupId.trim() || DEFAULT_MF_GROUP_ID;
  const normalizedMfDeviceNo = mfDeviceNo.trim() || DEFAULT_MF_DEVICE_NO;
  const normalizedMfPlate = mfPlate.trim() || DEFAULT_PLATE;
  const normalizedMfPlateColor = mfPlateColor.trim() || DEFAULT_MF_PLATE_COLOR;
  const mfUpTopic = `/${normalizedMfSn}/mf/up`;
  const mfDownTopic = `/${normalizedMfSn}/mf/down`;
  const topicFilters = useMemo(
    () =>
      monitorMode === "all"
        ? ["/device/+/update", "/device/+/will", mfUpTopic, mfDownTopic]
        : [downTopic, upTopic, willTopic, mfUpTopic, mfDownTopic],
    [downTopic, mfDownTopic, mfUpTopic, monitorMode, upTopic, willTopic],
  );

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
      addLog({ direction: "system", topic: topicFilters.join(", "), payload: "已订阅摄像头 MQTT Topic" });
    });

    nextClient.on("message", (topic, payload) => {
      const topicDeviceId = deviceIdFromTopic(topic);
      const laneLabel = topicDeviceId ? laneLabelForDeviceId(topicDeviceId) : "";
      if (monitorMode === "all" && topicDeviceId && !laneLabel) {
        return;
      }
      addLog({
        direction: topic === downTopic || topic === mfDownTopic ? "tx" : "rx",
        topic,
        payload: displayPayload(payload),
        laneLabel: laneLabel || (topicDeviceId ? `未映射 ${topicDeviceId}` : undefined),
      });
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
  }, [addLog, client?.connected, downTopic, mfDownTopic, monitorMode, topicFilters, wsHost, wsPort]);

  const disconnect = useCallback(() => {
    client?.end(true);
    setClient(null);
    setConnState("idle");
    setMessage("MQTT WebSocket 已断开");
  }, [client]);

  const switchMonitorMode = useCallback(
    (mode: MonitorMode) => {
      if (mode === monitorMode) {
        return;
      }
      client?.end(true);
      setClient(null);
      setConnState("idle");
      setMonitorMode(mode);
      setMessage("监听模式已切换，请重新连接 MQTT Broker");
    },
    [client, monitorMode],
  );

  const publishJsonToTopic = useCallback(
    (topic: string, payload: string, direction: LogDirection, label?: string) => {
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
      client.publish(topic, payload);
      addLog({ direction, topic, payload: label ? `${label}: ${payload}` : payload });
      setMessage(`已发送到 ${topic}`);
    },
    [addLog, client],
  );

  const publishSmartCommand = useCallback(
    (payload: string, label?: string) => publishJsonToTopic(downTopic, payload, "tx", label),
    [downTopic, publishJsonToTopic],
  );

  const publishMfUp = useCallback(
    (payload: string, label?: string) => publishJsonToTopic(mfUpTopic, payload, "rx", label),
    [mfUpTopic, publishJsonToTopic],
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
  const latestSn = typeof latestRxJson?.sn === "string" ? latestRxJson.sn : latestDevId;
  const latestContent =
    latestRxJson && typeof latestRxJson.content === "object" && latestRxJson.content !== null
      ? (latestRxJson.content as Record<string, unknown>)
      : null;
  const latestData =
    latestRxJson && typeof latestRxJson.data === "object" && latestRxJson.data !== null
      ? (latestRxJson.data as Record<string, unknown>)
      : null;
  const latestPlate =
    typeof latestContent?.plateNum === "string"
      ? latestContent.plateNum
      : typeof latestContent?.plateNumVDC === "string"
        ? latestContent.plateNumVDC
        : typeof latestData?.plateNo === "string"
          ? latestData.plateNo
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
    ["监听模式", monitorMode === "all" ? "1-11号入口相机" : "单台相机"],
    ["相机 devId", monitorMode === "all" ? "全部车道入口" : normalizedCameraId],
    ["下发 Topic", downTopic],
    ["上报 Topic", monitorMode === "all" ? "/device/+/update" : upTopic],
    ["遗嘱 Topic", monitorMode === "all" ? "/device/+/will" : willTopic],
    ["总入口 SN", normalizedMfSn],
    ["总入口上报", mfUpTopic],
    ["总入口确认", mfDownTopic],
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
              Camera MQTT
            </div>
            <h1 className="text-2xl font-bold tracking-tight text-slate-950">摄像头 MQTT 测试页</h1>
            <p className="mt-2 max-w-3xl text-sm text-slate-600">
              支持 1-11 号车道 Smart Camera 和总入口 MF 车牌识别摄像头。
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
                <div className="grid gap-1 text-xs font-semibold text-slate-600">
                  监听模式
                  <div className="grid grid-cols-2 gap-2">
                    <button
                      type="button"
                      onClick={() => switchMonitorMode("single")}
                      className={cn(
                        "rounded-md border px-3 py-2 text-sm font-bold",
                        monitorMode === "single"
                          ? "border-blue-600 bg-blue-600 text-white"
                          : "border-slate-200 bg-white text-slate-700 hover:bg-slate-50",
                      )}
                    >
                      单台相机
                    </button>
                    <button
                      type="button"
                      onClick={() => switchMonitorMode("all")}
                      className={cn(
                        "rounded-md border px-3 py-2 text-sm font-bold",
                        monitorMode === "all"
                          ? "border-blue-600 bg-blue-600 text-white"
                          : "border-slate-200 bg-white text-slate-700 hover:bg-slate-50",
                      )}
                    >
                      1-11号入口
                    </button>
                  </div>
                </div>
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
                    车道相机 devId
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
                {monitorMode === "all" ? (
                  <div className="grid grid-cols-2 gap-2 rounded-md bg-slate-50 p-2 text-[11px] sm:grid-cols-3">
                    {LANE_CAMERA_IDS.map((lane) => (
                      <div key={lane.laneId} className="min-w-0 rounded border border-slate-200 bg-white px-2 py-1">
                        <div className="font-bold text-slate-700">{lane.label}</div>
                        <div className="truncate font-mono text-slate-500">{lane.devId}</div>
                      </div>
                    ))}
                  </div>
                ) : null}
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
                <Camera className="size-4 text-emerald-600" />
                总入口 MF
              </h2>
              <div className="grid gap-3">
                <label className="grid gap-1 text-xs font-semibold text-slate-600">
                  设备 SN
                  <input
                    value={mfSn}
                    onChange={(event) => setMfSn(event.target.value)}
                    className="rounded-md border border-slate-200 bg-white px-3 py-2 font-mono text-sm text-slate-900 outline-none focus:border-blue-500"
                  />
                </label>
                <div className="grid grid-cols-2 gap-2">
                  <label className="grid gap-1 text-xs font-semibold text-slate-600">
                    groupId
                    <input
                      value={mfGroupId}
                      onChange={(event) => setMfGroupId(event.target.value)}
                      className="rounded-md border border-slate-200 bg-white px-3 py-2 font-mono text-sm text-slate-900 outline-none focus:border-blue-500"
                    />
                  </label>
                  <label className="grid gap-1 text-xs font-semibold text-slate-600">
                    deviceNo
                    <input
                      value={mfDeviceNo}
                      onChange={(event) => setMfDeviceNo(event.target.value)}
                      className="rounded-md border border-slate-200 bg-white px-3 py-2 font-mono text-sm text-slate-900 outline-none focus:border-blue-500"
                    />
                  </label>
                </div>
                <div className="grid grid-cols-[1fr_120px] gap-2">
                  <label className="grid gap-1 text-xs font-semibold text-slate-600">
                    测试车牌
                    <input
                      value={mfPlate}
                      onChange={(event) => setMfPlate(event.target.value)}
                      className="rounded-md border border-slate-200 bg-white px-3 py-2 font-mono text-sm text-slate-900 outline-none focus:border-blue-500"
                    />
                  </label>
                  <label className="grid gap-1 text-xs font-semibold text-slate-600">
                    车牌颜色
                    <select
                      value={mfPlateColor}
                      onChange={(event) => setMfPlateColor(event.target.value)}
                      className="rounded-md border border-slate-200 bg-white px-3 py-2 text-sm text-slate-900 outline-none focus:border-blue-500"
                    >
                      <option value="BLUE">蓝牌</option>
                      <option value="GREEN">绿牌</option>
                    </select>
                  </label>
                </div>
                <div className="grid grid-cols-2 gap-2">
                  <button
                    type="button"
                    onClick={() =>
                      publishMfUp(
                        buildParkingMfPlateResult(normalizedMfSn, normalizedMfGroupId, normalizedMfDeviceNo, normalizedMfPlate, normalizedMfPlateColor),
                        "总入口抓拍",
                      )
                    }
                    disabled={connState !== "connected"}
                    className="inline-flex items-center justify-center gap-2 rounded-md bg-emerald-600 px-3 py-2 text-sm font-bold text-white hover:bg-emerald-500 disabled:opacity-50"
                  >
                    <Send className="size-4" />
                    抓拍上报
                  </button>
                  <button
                    type="button"
                    onClick={() => publishMfUp(buildParkingMfHeartbeat(normalizedMfSn, normalizedMfGroupId, normalizedMfDeviceNo), "总入口心跳")}
                    disabled={connState !== "connected"}
                    className="inline-flex items-center justify-center gap-2 rounded-md border border-slate-200 bg-white px-3 py-2 text-sm font-bold text-slate-700 hover:bg-slate-50 disabled:opacity-50"
                  >
                    <Radio className="size-4" />
                    心跳上报
                  </button>
                </div>
                <div className="grid gap-1 text-xs">
                  <div className="grid grid-cols-[64px_1fr] gap-2">
                    <span className="text-slate-500">上报</span>
                    <span className="truncate font-mono font-semibold">{mfUpTopic}</span>
                  </div>
                  <div className="grid grid-cols-[64px_1fr] gap-2">
                    <span className="text-slate-500">确认</span>
                    <span className="truncate font-mono font-semibold">{mfDownTopic}</span>
                  </div>
                </div>
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
                  <span className="text-slate-500">最近设备</span>
                  <span className="font-mono font-semibold">{latestSn}</span>
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
                      onClick={() => publishSmartCommand(buildCameraCommand(normalizedCameraId, item.cmd), item.label)}
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
                  onClick={() => publishSmartCommand(customPayload, "自定义命令")}
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
                  {topicFilters.map((topic, index) => (
                    <div key={topic} className="grid grid-cols-[78px_1fr] gap-2">
                      <span className="text-slate-500">订阅{index + 1}</span>
                      <span className="truncate font-mono font-semibold">{topic}</span>
                    </div>
                  ))}
                </div>
              </div>

              <div className="rounded-lg border border-slate-200 bg-white p-4 shadow-sm">
                <h2 className="mb-3 flex items-center gap-2 text-sm font-bold">
                  <CheckCircle2 className="size-4 text-emerald-600" />
                  联调提示
                </h2>
                <div className="grid gap-2 text-xs leading-relaxed text-slate-600">
                  <div>1. 这套设备按《计数报警.docx》协议，上报主题是 `/device/{`devId`}/update`。</div>
                  <div>2. 总入口 MF 上报主题是 `/{`sn`}/mf/up`，后台确认主题是 `/{`sn`}/mf/down`。</div>
                  <div>3. 车牌号重点看 Smart Camera 的 `content.plateNum`，或 MF 的 `data.plateNo`。</div>
                  <div>4. 总入口要生成大屏数据，`.env` 里的总入口 MF SN/groupId/deviceNo 需要和上报一致。</div>
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
                          <div className="flex min-w-0 flex-wrap items-center gap-2">
                            {log.laneLabel ? (
                              <span className="rounded bg-cyan-100 px-1.5 py-0.5 font-bold text-cyan-700">{log.laneLabel}</span>
                            ) : null}
                            <span className="truncate font-mono font-semibold text-slate-800">{log.topic}</span>
                          </div>
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
