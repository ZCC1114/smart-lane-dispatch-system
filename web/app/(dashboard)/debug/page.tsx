"use client";

import { useCallback, useState } from "react";
import { Buffer } from "buffer";
import mqtt from "mqtt";
import {
  Activity,
  Car,
  CheckCircle2,
  ClipboardList,
  Cpu,
  Link as LinkIcon,
  Link2Off,
  Monitor,
  Network,
  Plus,
  Radio,
  Send,
  Server,
  Settings,
  Terminal,
  Trash2,
  Wifi,
} from "lucide-react";
import { mqttAuthOptions } from "@/lib/mqtt-config";
import { cn } from "@/lib/utils";

/* ------------------------------------------------------------------ */
/*  Types                                                             */
/* ------------------------------------------------------------------ */

interface TestDevice {
  id: string;
  laneId: string;
  laneName: string;
  mfSn: string;
  mfGroupId: string;
  mfDeviceNo: string;
  cameraDevId: string;
  didoDeviceId: string;
}

interface LogEntry {
  id: string;
  ts: string;
  direction: "tx" | "rx" | "tap";
  deviceId: string;
  topic: string;
  payload: string;
}

type MqttDidoMode = "json" | "hex-a1" | "hex-a3";

/* ------------------------------------------------------------------ */
/*  Helpers                                                           */
/* ------------------------------------------------------------------ */

function nowTime() {
  return new Date().toLocaleTimeString("zh-CN", { hour12: false });
}

function genId() {
  return Math.random().toString(36).substring(2, 8).toUpperCase();
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
    throw new Error("Invalid HEX command");
  }
  return Buffer.from(cleaned, "hex");
}

const DEFAULT_DEVICES: TestDevice[] = [
  {
    id: genId(),
    laneId: "L01",
    laneName: "1号车道",
    mfSn: "MF001",
    mfGroupId: "1",
    mfDeviceNo: "CAM01",
    cameraDevId: "18030023526b",
    didoDeviceId: "DIDO-01",
  },
  {
    id: genId(),
    laneId: "L02",
    laneName: "2号车道",
    mfSn: "MF002",
    mfGroupId: "2",
    mfDeviceNo: "CAM02",
    cameraDevId: "SMART-CAM-02",
    didoDeviceId: "DIDO-02",
  },
];

/* ------------------------------------------------------------------ */
/*  Message builders                                                  */
/* ------------------------------------------------------------------ */

function buildMfHeartbeat(dev: TestDevice) {
  return JSON.stringify({
    cmd: "heartbeat",
    sn: dev.mfSn,
    timestamp: Date.now(),
    data: {
      deviceStatus: [{ deviceNo: dev.mfDeviceNo, network: "online" }],
    },
  });
}

function buildMfPlate(dev: TestDevice, plate: string) {
  return JSON.stringify({
    cmd: "plateResult",
    sn: dev.mfSn,
    msgId: `test-${Date.now()}`,
    timestamp: Date.now(),
    data: {
      groupId: dev.mfGroupId,
      deviceNo: dev.mfDeviceNo,
      plateNo: plate,
      parkingTime: new Date().toISOString(),
    },
  });
}

function buildCamHeartbeat(dev: TestDevice) {
  return JSON.stringify({
    cmd: "heartbeat",
    devId: dev.cameraDevId,
    utcTs: Date.now(),
  });
}

function buildCamPlate(dev: TestDevice, plate: string) {
  return JSON.stringify({
    cmd: "devAlarm",
    msgId: `plate-${Date.now()}`,
    devId: dev.cameraDevId,
    parkId: "123456",
    devIp: "192.168.55.100",
    utcTs: Math.floor(Date.now() / 1000),
    content: {
      alarmType: 1,
      plateNum: plate,
      alarmTime: new Date().toISOString(),
      inOut: "in",
    },
  });
}

function buildCamPassCount(dev: TestDevice, outCount: number) {
  return JSON.stringify({
    cmd: "passCount",
    devId: dev.cameraDevId,
    msgId: `pc-${Date.now()}`,
    utcTs: Date.now(),
    inCount: 3,
    outCount,
  });
}

function buildCamHaveCar(dev: TestDevice, haveCar: boolean) {
  return JSON.stringify({
    cmd: "getHaveCarRsp",
    devId: dev.cameraDevId,
    content: { haveCar: haveCar ? 1 : 0 },
    utcTs: Date.now(),
  });
}

function buildDidoFeedback(
  dev: TestDevice,
  entryGreen: boolean,
  exitGreen: boolean
) {
  return JSON.stringify({
    A01: entryGreen ? 100000 : 110000,
    A02: entryGreen ? 110000 : 100000,
    A03: exitGreen ? 100000 : 110000,
    A04: exitGreen ? 110000 : 100000,
  });
}

function buildMqttDidoRelay(relay: string, on: boolean, mode: MqttDidoMode) {
  if (mode === "hex-a1") {
    return buildMqttDidoA1Hex(relay, on);
  }
  if (mode === "hex-a3") {
    return buildMqttDidoA3Hex(relay, on);
  }
  return JSON.stringify({
    [relay]: on ? 110000 : 100000,
    res: `m${Date.now() % 100000000}`,
  });
}

function buildMqttDidoA1Hex(relay: string, on: boolean) {
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

function buildMqttDidoA3Hex(relay: string, on: boolean) {
  const index = relayIndex(relay);
  const group = Math.floor((index - 1) / 8);
  const bit = 1 << ((index - 1) % 8);
  const command = Array.from({ length: 20 }, () => 0);
  command[0] = 0xcc;
  command[1] = 0xdd;
  command[2] = 0xa3;
  command[3] = 0x01;
  command[4 + (5 - group)] = on ? bit : 0;
  command[10 + (5 - group)] = bit;
  command[18] = 0xdd;
  command[19] = 0xcc;
  return command.map(byteHex).join("");
}

function buildMqttDidoReadAll() {
  return JSON.stringify({
    readall: 0,
    res: `r${Date.now() % 100000000}`,
  });
}

function buildMqttDidoEnableRemoteConfig() {
  return "4D9301010101A1000000";
}

function buildMqttDidoEnableRelayUpload() {
  return "4D930101010AA1000000";
}

const FLOW_STEPS = [
  {
    title: "纭缃戠粶",
    desc: "电脑和 CX-5216E 在同一网段，先 ping 设备 IP。",
    icon: Network,
  },
  {
    title: "鍚姩 Broker",
    desc: "电脑开放 MQTT TCP 1883；本页面走 WebSocket，设备连电脑 LAN IP:1883。",
    icon: Server,
  },
  {
    title: "閰嶇疆璁惧",
    desc: "在设备里填写 Broker IP、端口、设备 ID、上报和下发 Topic。",
    icon: Settings,
  },
  {
    title: "鎵嬪姩鎺х户鐢靛櫒",
    desc: "先只测 A01 吸合/断开，确认硬件动作和接线方向。",
    icon: Cpu,
  },
  {
    title: "鍚庣涓嬪彂",
    desc: "后台信号灯控制会向 /device/{didoId}/get 下发继电器命令。",
    icon: Send,
  },
  {
    title: "鍙嶉鍚屾",
    desc: "设备向 /device/{didoId}/update 上报状态，页面日志应能看到反馈。",
    icon: CheckCircle2,
  },
];

function CommandBlock({ children }: { children: React.ReactNode }) {
  return (
    <pre className="overflow-x-auto rounded-lg border border-slate-200 bg-slate-950 px-3 py-2 text-[11px] leading-5 text-slate-100">
      {children}
    </pre>
  );
}

/* ------------------------------------------------------------------ */
/*  Components                                                        */
/* ------------------------------------------------------------------ */

export default function DebugPage() {
  const [brokerHost, setBrokerHost] = useState("127.0.0.1");
  const [brokerPort, setBrokerPort] = useState("9001");
  const [client, setClient] = useState<mqtt.MqttClient | null>(null);
  const [connState, setConnState] = useState<"idle" | "connecting" | "connected" | "error">("idle");
  const [connError, setConnError] = useState<string>("");

  const [devices, setDevices] = useState<TestDevice[]>(DEFAULT_DEVICES);
  const [selectedDeviceId, setSelectedDeviceId] = useState<string>(DEFAULT_DEVICES[0]?.id ?? "");
  const [template, setTemplate] = useState<string>("mf-heartbeat");
  const [paramPlate, setParamPlate] = useState("鑻廇12345");
  const [paramOutCount, setParamOutCount] = useState(1);
  const [paramHaveCar, setParamHaveCar] = useState(true);
  const [paramEntryGreen, setParamEntryGreen] = useState(true);
  const [paramExitGreen, setParamExitGreen] = useState(false);
  const [mqttDidoRelay, setMqttDidoRelay] = useState("A01");
  const [mqttDidoMode, setMqttDidoMode] = useState<MqttDidoMode>("json");
  const [mqttDidoMessage, setMqttDidoMessage] = useState("");
  const [logs, setLogs] = useState<LogEntry[]>([]);

  /* MQTT connect / disconnect */
  const connect = useCallback(() => {
    if (client) return;
    setConnState("connecting");
    setConnError("");

    const url = `ws://${brokerHost}:${brokerPort}`;
    const c = mqtt.connect(url, {
      clientId: `debug-client-${genId()}`,
      reconnectPeriod: 0,
      connectTimeout: 5000,
      ...mqttAuthOptions(),
    });
    let opened = false;
    let lastError = "";

    c.on("connect", () => {
      opened = true;
      setConnState("connected");
      setConnError("");
      // Subscribe to real device upstream topics and backend downstream topics.
      devices.forEach((dev) => {
        c.subscribe(`/${dev.mfSn}/mf/up`);
        c.subscribe(`/${dev.mfSn}/mf/down`);
        c.subscribe(`/device/${dev.cameraDevId}/update`);
        c.subscribe(`/device/${dev.cameraDevId}/will`);
        c.subscribe(`/device/${dev.cameraDevId}/get`);
        c.subscribe(`/device/${dev.didoDeviceId}/update`);
        c.subscribe(`/device/${dev.didoDeviceId}/get`);
      });
    });

    c.on("error", (err) => {
      lastError = err.message;
      setConnState("error");
      setConnError(err.message);
    });

    c.on("close", () => {
      if (opened) {
        setConnState("idle");
      } else {
        setConnState("error");
        setConnError(lastError || "MQTT WebSocket connection failed. Please confirm the broker and WS port 9001 are running.");
      }
      setClient(null);
    });

    c.on("message", (topic, payload) => {
      const text = payload.toString();
      let deviceId = "";
      const direction: LogEntry["direction"] = topic.endsWith("/get") ? "tap" : "rx";
      const d = devices.find(
        (dev) =>
          topic.includes(dev.mfSn) ||
          topic.includes(dev.cameraDevId) ||
          topic.includes(dev.didoDeviceId)
      );
      if (d) deviceId = d.laneName;
      setLogs((prev) => [
        {
          id: genId(),
          ts: nowTime(),
          direction,
          deviceId: deviceId || topic,
          topic,
          payload: text.length > 500 ? text.slice(0, 500) + "..." : text,
        },
        ...prev,
      ].slice(0, 200));
    });

    setClient(c);
  }, [brokerHost, brokerPort, client, devices]);

  const disconnect = useCallback(() => {
    if (client) {
      client.end();
      setClient(null);
      setConnState("idle");
    }
  }, [client]);

  /* Send message */
  const send = useCallback(() => {
    if (!client || connState !== "connected") return;
    const dev = devices.find((d) => d.id === selectedDeviceId);
    if (!dev) return;

    let topic = "";
    let payload = "";

    switch (template) {
      case "mf-heartbeat":
        topic = `/${dev.mfSn}/mf/up`;
        payload = buildMfHeartbeat(dev);
        break;
      case "mf-plate":
        topic = `/${dev.mfSn}/mf/up`;
        payload = buildMfPlate(dev, paramPlate);
        break;
      case "cam-heartbeat":
        topic = `/device/${dev.cameraDevId}/update`;
        payload = buildCamHeartbeat(dev);
        break;
      case "cam-plate":
        topic = `/device/${dev.cameraDevId}/update`;
        payload = buildCamPlate(dev, paramPlate);
        break;
      case "cam-pass":
        topic = `/device/${dev.cameraDevId}/update`;
        payload = buildCamPassCount(dev, paramOutCount);
        break;
      case "cam-havecar":
        topic = `/device/${dev.cameraDevId}/update`;
        payload = buildCamHaveCar(dev, paramHaveCar);
        break;
      case "dido-feedback":
        topic = `/device/${dev.didoDeviceId}/update`;
        payload = buildDidoFeedback(dev, paramEntryGreen, paramExitGreen);
        break;
    }

    if (topic && payload) {
      client.publish(topic, payload);
      setLogs((prev) => [
        {
          id: genId(),
          ts: nowTime(),
          direction: "tx" as const,
          deviceId: dev.laneName,
          topic,
          payload: payload.length > 500 ? payload.slice(0, 500) + "..." : payload,
        },
        ...prev,
      ].slice(0, 200));
    }
  }, [
    client,
    connState,
    devices,
    selectedDeviceId,
    template,
    paramPlate,
    paramOutCount,
    paramHaveCar,
    paramEntryGreen,
    paramExitGreen,
  ]);

  /* Device management */
  const addDevice = () => {
    const n = devices.length + 1;
    const newDev: TestDevice = {
      id: genId(),
      laneId: `L${String(n).padStart(2, "0")}`,
      laneName: `${n}号车道`,
      mfSn: `MF${String(n).padStart(3, "0")}`,
      mfGroupId: String(n),
      mfDeviceNo: `CAM${String(n).padStart(2, "0")}`,
      cameraDevId: `SMART-CAM-${String(n).padStart(2, "0")}`,
      didoDeviceId: `DIDO-${String(n).padStart(2, "0")}`,
    };
    setDevices((prev) => [...prev, newDev]);
    if (!selectedDeviceId) setSelectedDeviceId(newDev.id);
  };

  const removeDevice = (id: string) => {
    setDevices((prev) => prev.filter((d) => d.id !== id));
    if (selectedDeviceId === id) {
      setSelectedDeviceId(devices.find((d) => d.id !== id)?.id ?? "");
    }
  };

  const updateDevice = (id: string, field: keyof TestDevice, value: string) => {
    setDevices((prev) => prev.map((d) => (d.id === id ? { ...d, [field]: value } : d)));
  };

  const selectedDevice = devices.find((d) => d.id === selectedDeviceId);
  const didoDownTopic = selectedDevice ? `/device/${selectedDevice.didoDeviceId}/get` : "/device/DIDO-01/get";
  const didoUpTopic = selectedDevice ? `/device/${selectedDevice.didoDeviceId}/update` : "/device/DIDO-01/update";
  const mqttTcpPort = "1883";
  const deviceBrokerHost =
    brokerHost === "127.0.0.1" || brokerHost.toLowerCase() === "localhost"
      ? "鐢佃剳鐨?192.168.0.x 鍦板潃"
      : brokerHost;
  const didoDeviceConfig = [
    ["璁惧渚?Broker", deviceBrokerHost],
    ["TCP 绔彛", mqttTcpPort],
    ["璁惧 ID", selectedDevice?.didoDeviceId ?? "DIDO-01"],
    ["涓嬪彂 Topic", didoDownTopic],
    ["涓婃姤 Topic", didoUpTopic],
  ];

  const publishMqttDido = useCallback(
    (payload: string, options?: { binaryHex?: boolean }) => {
      if (!client || connState !== "connected" || !selectedDevice) {
        setMqttDidoMessage("Connect to the MQTT broker and select a DIDO device first.");
        return;
      }
      let publishPayload: string | Buffer = payload;
      if (options?.binaryHex) {
        try {
          publishPayload = hexToBuffer(payload);
        } catch (error) {
          setMqttDidoMessage(error instanceof Error ? error.message : "Invalid HEX command");
          return;
        }
      }
      client.publish(didoDownTopic, publishPayload);
      setMqttDidoMessage(
        options?.binaryHex
          ? `宸插彂閫佷簩杩涘埗 HEX 鍒?${didoDownTopic}`
          : `宸插彂閫佸埌 ${didoDownTopic}`
      );
      setLogs((prev) => [
        {
          id: genId(),
          ts: nowTime(),
          direction: "tx" as const,
          deviceId: `${selectedDevice.laneName} MQTT DIDO`,
          topic: didoDownTopic,
          payload: options?.binaryHex ? `${payload}  (binary)` : payload,
        },
        ...prev,
      ].slice(0, 200));
    },
    [client, connState, didoDownTopic, selectedDevice]
  );

  return (
    <div className="flex flex-col gap-4">
      {/* Page title + broker config */}
      <div className="flex flex-col gap-4 sm:flex-row sm:items-center sm:justify-between">
        <div>
          <h1 className="text-xl font-bold text-slate-900">MQTT 硬件调试台</h1>
          <p className="text-sm text-slate-500">妯℃嫙璁惧鏀跺彂淇″彿锛屽疄鏃惰仈璋冩祴璇</p>
        </div>

        <div className="flex flex-wrap items-center gap-3">
          <label className="flex items-center gap-2 text-sm">
            <span className="text-slate-500">Broker</span>
            <input
              value={brokerHost}
              onChange={(e) => setBrokerHost(e.target.value)}
              disabled={connState === "connected"}
              className="w-32 rounded-lg border border-slate-200 bg-white px-3 py-1.5 text-sm text-slate-900 outline-none focus:border-blue-500 disabled:opacity-50"
            />
          </label>
          <label className="flex items-center gap-2 text-sm">
            <span className="text-slate-500">WS Port</span>
            <input
              value={brokerPort}
              onChange={(e) => setBrokerPort(e.target.value)}
              disabled={connState === "connected"}
              className="w-16 rounded-lg border border-slate-200 bg-white px-3 py-1.5 text-sm text-slate-900 outline-none focus:border-blue-500 disabled:opacity-50"
            />
          </label>
          {connState === "connected" ? (
            <button
              onClick={disconnect}
              className="inline-flex items-center gap-1.5 rounded-lg border border-rose-200 bg-rose-50 px-4 py-2 text-sm font-semibold text-rose-600 transition hover:bg-rose-100"
            >
              <Link2Off className="size-4" />
              鏂紑
            </button>
          ) : (
            <button
              onClick={connect}
              disabled={connState === "connecting"}
              className="inline-flex items-center gap-1.5 rounded-lg bg-blue-600 px-4 py-2 text-sm font-semibold text-white transition hover:bg-blue-500 disabled:opacity-60"
            >
              <LinkIcon className="size-4" />
              {connState === "connecting" ? "连接中" : "连接"}
            </button>
          )}
          {connState === "connected" && (
            <span className="inline-flex items-center gap-1.5 rounded-full bg-emerald-50 px-2.5 py-1 text-xs font-bold text-emerald-600">
              <Wifi className="size-3" />
              宸茶繛鎺?
            </span>
          )}
          {connState === "error" && <span className="text-xs text-rose-500">{connError}</span>}
        </div>
      </div>

      {/* Visual test flow */}
      <section className="rounded-xl border border-slate-200 bg-white p-4 shadow-sm">
        <div className="mb-4 flex flex-col gap-2 lg:flex-row lg:items-center lg:justify-between">
          <div>
            <h2 className="flex items-center gap-2 text-sm font-bold text-slate-900">
              <ClipboardList className="size-4 text-blue-500" />
              DIDO 瀹炵墿鑱旇皟娴佺▼
            </h2>
            <p className="mt-1 text-xs text-slate-500">
              鎸?1 鍒?6 鍋氥€傚厛娴嬪崟涓户鐢靛櫒锛屽啀鎺ョ孩缁跨伅璐熻浇锛屾渶鍚庣敤鍚庡彴淇″彿鐏〉闈㈤獙璇佸悗绔笅鍙戙€?
            </p>
          </div>
          <div className="flex flex-wrap items-center gap-2 text-xs">
            <span className="rounded-full bg-slate-100 px-2.5 py-1 font-semibold text-slate-600">
              椤甸潰 Broker: {brokerHost}:{brokerPort}
            </span>
            <span className="rounded-full bg-slate-100 px-2.5 py-1 font-semibold text-slate-600">
              璁惧: {selectedDevice?.didoDeviceId ?? "-"}
            </span>
            <span
              className={cn(
                "rounded-full px-2.5 py-1 font-semibold",
                connState === "connected" ? "bg-emerald-50 text-emerald-700" : "bg-amber-50 text-amber-700"
              )}
            >
              页面 MQTT: {connState === "connected" ? "已连接" : "未连接"}
            </span>
          </div>
        </div>

        <div className="grid gap-3 xl:grid-cols-[1.1fr_0.9fr]">
          <div className="grid gap-2 md:grid-cols-3">
            {FLOW_STEPS.map((step, index) => {
              const Icon = step.icon;
              const active =
                (index === 1 && connState === "connected") ||
                (index === 2 && Boolean(selectedDevice)) ||
                (index === 5 && logs.some((log) => log.topic === didoUpTopic || log.topic === didoDownTopic));
              return (
                <div
                  key={step.title}
                  className={cn(
                    "rounded-lg border p-3",
                    active ? "border-blue-300 bg-blue-50" : "border-slate-200 bg-slate-50"
                  )}
                >
                  <div className="mb-2 flex items-center gap-2">
                    <span
                      className={cn(
                        "grid size-6 place-items-center rounded-full text-[11px] font-bold",
                        active ? "bg-blue-600 text-white" : "bg-white text-slate-500"
                      )}
                    >
                      {index + 1}
                    </span>
                    <Icon className={cn("size-4", active ? "text-blue-600" : "text-slate-500")} />
                    <span className="text-xs font-bold text-slate-900">{step.title}</span>
                  </div>
                  <p className="text-[11px] leading-5 text-slate-500">{step.desc}</p>
                </div>
              );
            })}
          </div>

          <div className="grid gap-3 lg:grid-cols-2 xl:grid-cols-1">
            <div className="rounded-lg border border-blue-200 bg-blue-50 p-3">
              <h3 className="mb-2 flex items-center gap-2 text-xs font-bold text-slate-800">
                <CheckCircle2 className="size-3.5 text-blue-600" />
                鏈〉鎿嶄綔椤哄簭
              </h3>
              <div className="space-y-1.5 text-[11px] leading-5 text-slate-600">
                <p>1. Broker 濉數鑴?IP锛學S Port 濉?Broker 鐨?WebSocket 绔彛锛岀偣鈥滆繛鎺モ€濄€</p>
                <p>2. 宸︿晶閫夎溅閬擄紝纭 didoId 鍜岃澶囬噷鐨勮澶?ID 涓€鑷淬€</p>
                <p>3. 鎵撳紑鈥滀俊鍙风伅鎺у埗鈥濆垏鎹?1 鍙疯溅閬撳叆鍙ｇ豢鐏紝鍙充晶搴旀敹鍒板悗绔笅鍙戙€</p>
                <p>4. 鐪熷疄璁惧浼氳嚜宸变笂鎶ュ弽棣堬紱娌℃湁瀹炵墿鏃跺啀鐢ㄢ€淒IDO鍙嶉鈥濇ā鏉挎ā鎷熶笂鎶ャ€</p>
              </div>
            </div>

            <div className="rounded-lg border border-slate-200 bg-slate-50 p-3">
              <h3 className="mb-2 flex items-center gap-2 text-xs font-bold text-slate-800">
                <Settings className="size-3.5 text-blue-500" />
                CX-5216E 璁惧渚у～鍐?
              </h3>
              <div className="grid gap-1">
                {didoDeviceConfig.map(([label, value]) => (
                  <div key={label} className="grid grid-cols-[82px_1fr] gap-2 text-[11px]">
                    <span className="text-slate-500">{label}</span>
                    <span className="truncate font-mono font-semibold text-slate-800">{value}</span>
                  </div>
                ))}
              </div>
            </div>

            <div className="rounded-lg border border-slate-200 bg-slate-50 p-3">
              <h3 className="mb-2 flex items-center gap-2 text-xs font-bold text-slate-800">
                <Terminal className="size-3.5 text-blue-500" />
                鎵嬪姩缁х數鍣ㄦ祴璇曞懡浠?
              </h3>
              <CommandBlock>
{`# A01 鍚稿悎
mosquitto_pub -h ${brokerHost} -p ${mqttTcpPort} -t "${didoDownTopic}" -m '{"A01":110000,"res":"manual-on"}'

# A01 鏂紑
mosquitto_pub -h ${brokerHost} -p ${mqttTcpPort} -t "${didoDownTopic}" -m '{"A01":100000,"res":"manual-off"}'`}
              </CommandBlock>
            </div>
          </div>
        </div>
      </section>

      {/* MQTT DIDO control */}
      <section className="rounded-xl border border-emerald-200 bg-emerald-50/70 p-4 shadow-sm">
        <div className="mb-3 flex flex-col gap-2 lg:flex-row lg:items-center lg:justify-between">
          <div>
            <h2 className="flex items-center gap-2 text-sm font-bold text-slate-900">
              <Wifi className="size-4 text-emerald-600" />
              MQTT DIDO 鎺у埗妯″紡
            </h2>
            <p className="mt-1 text-xs text-slate-600">
              閫氳繃 Broker 涓嬪彂 JSON 鎴?HEX 鎸囦护鎺у埗 DO 鍙ｏ紱DIDO 闇€瑕佽闃?{didoDownTopic}锛屼笂鎶?{didoUpTopic}銆?
            </p>
            <p className="mt-1 text-[11px] text-slate-500">
              HEX 浼氭寜浜岃繘鍒?payload 鍙戦€侊紱Broker 鏀跺埌 /get 鍙唬琛ㄤ笅鍙戞垚鍔燂紝鍙湁 /update 鎵嶆槸璁惧鍙嶉銆?
            </p>
          </div>
          <div
            className={cn(
              "rounded-full px-2.5 py-1 text-[11px] font-bold",
              connState === "connected" ? "bg-emerald-100 text-emerald-700" : "bg-amber-100 text-amber-700"
            )}
          >
            MQTT: {connState === "connected" ? "已连接" : "未连接"}
          </div>
        </div>

        <div className="grid gap-3 xl:grid-cols-[1fr_1fr]">
          <div className="grid gap-3 md:grid-cols-[1fr_130px_150px_auto_auto_auto]">
            <label className="block">
              <span className="mb-1 block text-[11px] font-semibold text-slate-600">涓嬪彂 Topic</span>
              <input
                value={didoDownTopic}
                readOnly
                className="w-full rounded-lg border border-emerald-200 bg-white px-3 py-2 font-mono text-xs text-slate-900 outline-none"
              />
            </label>
            <label className="block">
              <span className="mb-1 block text-[11px] font-semibold text-slate-600">DO 鍙</span>
              <select
                value={mqttDidoRelay}
                onChange={(event) => setMqttDidoRelay(event.target.value)}
                className="w-full rounded-lg border border-emerald-200 bg-white px-3 py-2 text-sm text-slate-900 outline-none focus:border-emerald-500"
              >
                {Array.from({ length: 16 }).map((_, index) => {
                  const relay = `A${String(index + 1).padStart(2, "0")}`;
                  return (
                    <option key={relay} value={relay}>
                      {relay}
                    </option>
                  );
                })}
              </select>
            </label>
            <label className="block">
              <span className="mb-1 block text-[11px] font-semibold text-slate-600">涓嬪彂鏍煎紡</span>
              <select
                value={mqttDidoMode}
                onChange={(event) => setMqttDidoMode(event.target.value as MqttDidoMode)}
                className="w-full rounded-lg border border-emerald-200 bg-white px-3 py-2 text-sm text-slate-900 outline-none focus:border-emerald-500"
              >
                <option value="json">JSON 鎺у埗</option>
                <option value="hex-a1">HEX A1</option>
                <option value="hex-a3">HEX A3</option>
              </select>
            </label>
            <button
              onClick={() =>
                publishMqttDido(buildMqttDidoRelay(mqttDidoRelay, true, mqttDidoMode), {
                  binaryHex: mqttDidoMode !== "json",
                })
              }
              disabled={connState !== "connected" || !selectedDevice}
              className="self-end rounded-lg bg-emerald-600 px-4 py-2 text-sm font-bold text-white transition hover:bg-emerald-500 disabled:opacity-50"
            >
              鎵撳紑
            </button>
            <button
              onClick={() =>
                publishMqttDido(buildMqttDidoRelay(mqttDidoRelay, false, mqttDidoMode), {
                  binaryHex: mqttDidoMode !== "json",
                })
              }
              disabled={connState !== "connected" || !selectedDevice}
              className="self-end rounded-lg bg-rose-600 px-4 py-2 text-sm font-bold text-white transition hover:bg-rose-500 disabled:opacity-50"
            >
              鍏抽棴
            </button>
            <button
              onClick={() => publishMqttDido(buildMqttDidoReadAll())}
              disabled={connState !== "connected" || !selectedDevice}
              className="self-end rounded-lg border border-emerald-200 bg-white px-4 py-2 text-sm font-bold text-emerald-700 transition hover:bg-emerald-50 disabled:opacity-50"
            >
              璇荤姸鎬?
            </button>
          </div>

          <div className="grid gap-3 md:grid-cols-2 xl:col-span-2">
            <button
              onClick={() => publishMqttDido(buildMqttDidoEnableRemoteConfig(), { binaryHex: true })}
              disabled={connState !== "connected" || !selectedDevice}
              className="rounded-lg border border-amber-200 bg-amber-50 px-3 py-2 text-xs font-bold text-amber-700 transition hover:bg-amber-100 disabled:opacity-50"
            >
              鍚敤杩滅▼閰嶇疆
              <span className="mt-1 block font-mono text-[10px] font-semibold">4D9301010101A1000000</span>
            </button>
            <button
              onClick={() => publishMqttDido(buildMqttDidoEnableRelayUpload(), { binaryHex: true })}
              disabled={connState !== "connected" || !selectedDevice}
              className="rounded-lg border border-amber-200 bg-amber-50 px-3 py-2 text-xs font-bold text-amber-700 transition hover:bg-amber-100 disabled:opacity-50"
            >
              鍚敤缁х數鍣ㄧ姸鎬佷笂鎶?
              <span className="mt-1 block font-mono text-[10px] font-semibold">4D930101010AA1000000</span>
            </button>
          </div>

          <div className="rounded-lg border border-emerald-200 bg-white p-3">
            <div className="mb-2 text-xs font-bold text-slate-800">MQTT 娴嬭瘯璇存槑</div>
            <div className="grid gap-1 text-[11px] text-slate-600">
              <div>
                璁惧璁㈤槄锛?
                <span className="font-mono text-slate-900">{didoDownTopic}</span>
              </div>
              <div>
                璁惧涓婃姤锛?
                <span className="font-mono text-slate-900">{didoUpTopic}</span>
              </div>
              <div className="font-semibold text-amber-700">
                鏃ュ織閲岀殑 /get 鏄笅鍙戝洖鏄撅紱鍙湁 /update 鎵嶆槸璁惧鐪熷疄鍙嶉銆?
              </div>
              <div>
                鎵撳紑绀轰緥锛?
                <span className="font-mono text-slate-900">
                  {buildMqttDidoRelay(mqttDidoRelay, true, mqttDidoMode)}
                </span>
              </div>
              <div>
                鍏抽棴绀轰緥锛?
                <span className="font-mono text-slate-900">
                  {buildMqttDidoRelay(mqttDidoRelay, false, mqttDidoMode)}
                </span>
              </div>
              {mqttDidoMessage ? <div className="font-semibold text-emerald-700">{mqttDidoMessage}</div> : null}
            </div>
          </div>
        </div>
      </section>

      {/* Main panels */}
      <div className="flex h-[calc(100vh-520px)] min-h-[360px] flex-1 gap-4 overflow-hidden">
        {/* Left: Device list */}
        <aside className="flex w-80 shrink-0 flex-col rounded-xl border border-slate-200 bg-white shadow-sm">
          <div className="flex items-center justify-between border-b border-slate-100 px-4 py-3">
            <h2 className="text-sm font-bold text-slate-800">璁惧鍒楄〃</h2>
            <button
              onClick={addDevice}
              className="inline-flex items-center gap-1 rounded-lg bg-slate-100 px-2.5 py-1.5 text-xs font-semibold text-slate-700 transition hover:bg-slate-200"
            >
              <Plus className="size-3.5" />
              娣诲姞
            </button>
          </div>
          <div className="flex-1 space-y-2 overflow-y-auto p-3">
            {devices.map((dev) => (
              <div
                key={dev.id}
                onClick={() => setSelectedDeviceId(dev.id)}
                className={cn(
                  "cursor-pointer rounded-lg border p-3 transition",
                  selectedDeviceId === dev.id
                    ? "border-blue-400 bg-blue-50"
                    : "border-slate-200 bg-slate-50 hover:border-slate-300"
                )}
              >
                <div className="flex items-center justify-between">
                  <span className="text-sm font-bold text-slate-800">{dev.laneName}</span>
                  <button
                    onClick={(e) => {
                      e.stopPropagation();
                      removeDevice(dev.id);
                    }}
                    className="text-slate-400 transition hover:text-rose-500"
                  >
                    <Trash2 className="size-3.5" />
                  </button>
                </div>
                <div className="mt-2 space-y-1 text-[10px] text-slate-500">
                  <div className="grid grid-cols-[60px_1fr] gap-1">
                    <span>laneId</span>
                    <input
                      value={dev.laneId}
                      onChange={(e) => updateDevice(dev.id, "laneId", e.target.value)}
                      className="rounded border border-slate-200 bg-white px-1.5 py-0.5 text-[10px] text-slate-900 outline-none focus:border-blue-500"
                    />
                  </div>
                  <div className="grid grid-cols-[60px_1fr] gap-1">
                    <span>mfSn</span>
                    <input
                      value={dev.mfSn}
                      onChange={(e) => updateDevice(dev.id, "mfSn", e.target.value)}
                      className="rounded border border-slate-200 bg-white px-1.5 py-0.5 text-[10px] text-slate-900 outline-none focus:border-blue-500"
                    />
                  </div>
                  <div className="grid grid-cols-[60px_1fr] gap-1">
                    <span>camId</span>
                    <input
                      value={dev.cameraDevId}
                      onChange={(e) => updateDevice(dev.id, "cameraDevId", e.target.value)}
                      className="rounded border border-slate-200 bg-white px-1.5 py-0.5 text-[10px] text-slate-900 outline-none focus:border-blue-500"
                    />
                  </div>
                  <div className="grid grid-cols-[60px_1fr] gap-1">
                    <span>didoId</span>
                    <input
                      value={dev.didoDeviceId}
                      onChange={(e) => updateDevice(dev.id, "didoDeviceId", e.target.value)}
                      className="rounded border border-slate-200 bg-white px-1.5 py-0.5 text-[10px] text-slate-900 outline-none focus:border-blue-500"
                    />
                  </div>
                </div>
              </div>
            ))}
            {devices.length === 0 && (
              <p className="text-center text-xs text-slate-400">鐐瑰嚮&quot;娣诲姞&quot;鍒涘缓娴嬭瘯璁惧</p>
            )}
          </div>
        </aside>

        {/* Center: Send panel */}
        <section className="flex min-w-0 flex-1 flex-col rounded-xl border border-slate-200 bg-white p-5 shadow-sm">
          <h2 className="mb-4 flex items-center gap-2 text-sm font-bold text-slate-800">
            <Send className="size-4 text-blue-500" />
            鍙戦€佷俊鍙凤紙妯℃嫙璁惧涓婃姤锛?
          </h2>

          <div className="space-y-4">
            {/* Device select */}
            <div>
              <label className="mb-1.5 block text-xs text-slate-500">鐩爣璁惧</label>
              <select
                value={selectedDeviceId}
                onChange={(e) => setSelectedDeviceId(e.target.value)}
                className="w-full rounded-lg border border-slate-200 bg-white px-3 py-2 text-sm text-slate-900 outline-none focus:border-blue-500"
              >
                {devices.map((dev) => (
                  <option key={dev.id} value={dev.id}>
                    {dev.laneName} ({dev.laneId})
                  </option>
                ))}
              </select>
            </div>

            {/* Template select */}
            <div>
              <label className="mb-1.5 block text-xs text-slate-500">娑堟伅妯℃澘</label>
              <div className="grid grid-cols-2 gap-2 sm:grid-cols-3">
                {[
                  { key: "mf-heartbeat", label: "MF 蹇冭烦", icon: Radio },
                  { key: "mf-plate", label: "MF 杞︾墝璇嗗埆", icon: Car },
                  { key: "cam-heartbeat", label: "鐩告満蹇冭烦", icon: Radio },
                  { key: "cam-plate", label: "鍏ュ彛鐩告満杞︾墝", icon: Car },
                  { key: "cam-pass", label: "鐩告満璁℃暟(蹇界暐)", icon: Monitor },
                  { key: "cam-havecar", label: "鍦ㄤ綅鍝嶅簲(蹇界暐)", icon: Activity },
                  { key: "dido-feedback", label: "DIDO鍙嶉", icon: Cpu },
                ].map((t) => (
                  <button
                    key={t.key}
                    onClick={() => setTemplate(t.key)}
                    className={cn(
                      "flex items-center gap-2 rounded-lg border px-3 py-2 text-xs font-semibold transition",
                      template === t.key
                        ? "border-blue-400 bg-blue-50 text-blue-700"
                        : "border-slate-200 bg-slate-50 text-slate-600 hover:border-slate-300"
                    )}
                  >
                    <t.icon className="size-3.5" />
                    {t.label}
                  </button>
                ))}
              </div>
            </div>

            {/* Params */}
            <div className="rounded-lg border border-slate-200 bg-slate-50 p-4">
              {(template === "mf-plate" || template === "cam-plate") && (
                <label className="block">
                  <span className="mb-1.5 block text-xs text-slate-500">杞︾墝鍙</span>
                  <input
                    value={paramPlate}
                    onChange={(e) => setParamPlate(e.target.value)}
                    className="w-full rounded-lg border border-slate-200 bg-white px-3 py-2 text-sm text-slate-900 outline-none focus:border-blue-500"
                  />
                </label>
              )}

              {template === "cam-pass" && (
                <label className="block">
                  <span className="mb-1.5 block text-xs text-slate-500">鍑哄満璁℃暟 (outCount)</span>
                  <input
                    type="number"
                    min={0}
                    value={paramOutCount}
                    onChange={(e) => setParamOutCount(Number(e.target.value))}
                    className="w-full rounded-lg border border-slate-200 bg-white px-3 py-2 text-sm text-slate-900 outline-none focus:border-blue-500"
                  />
                </label>
              )}

              {template === "cam-havecar" && (
                <label className="flex items-center gap-3">
                  <input
                    type="checkbox"
                    checked={paramHaveCar}
                    onChange={(e) => setParamHaveCar(e.target.checked)}
                    className="size-4 rounded border-slate-300 text-blue-600 focus:ring-blue-500"
                  />
                  <span className="text-sm text-slate-800">鏈夎溅鍦ㄤ綅 (haveCar=1)</span>
                </label>
              )}

              {template === "dido-feedback" && (
                <div className="space-y-3">
                  <label className="flex items-center gap-3">
                    <input
                      type="checkbox"
                      checked={paramEntryGreen}
                      onChange={(e) => setParamEntryGreen(e.target.checked)}
                      className="size-4 rounded border-slate-300 text-blue-600 focus:ring-blue-500"
                    />
                    <span className="text-sm text-slate-800">鍏ュ彛缁跨伅</span>
                  </label>
                  <label className="flex items-center gap-3">
                    <input
                      type="checkbox"
                      checked={paramExitGreen}
                      onChange={(e) => setParamExitGreen(e.target.checked)}
                      className="size-4 rounded border-slate-300 text-blue-600 focus:ring-blue-500"
                    />
                    <span className="text-sm text-slate-800">鍑哄彛缁跨伅</span>
                  </label>
                </div>
              )}

              {template === "mf-heartbeat" && (
                <p className="text-xs text-slate-500">鍙戦€?MF 蹇冭烦锛宲ayload 鑷姩鐢熸垚銆</p>
              )}
              {template === "cam-heartbeat" && (
                <p className="text-xs text-slate-500">鍙戦€佹櫤鑳界浉鏈哄績璺筹紝payload 鑷姩鐢熸垚銆</p>
              )}
              {template === "cam-plate" && (
                <p className="mt-2 text-xs text-slate-500">
                  妯℃嫙鍏ュ彛鐩告満鐪熷疄 devAlarm 涓婃姤锛涘悗绔彧鐧昏鍏ュ満璁板綍锛屼笉鍙備笌鍑哄満璁℃暟銆?
                </p>
              )}
            </div>

            {/* Preview */}
            <div>
              <label className="mb-1.5 block text-xs text-slate-500">鐩爣 Topic</label>
              <div className="rounded-lg border border-slate-200 bg-slate-50 px-3 py-2 font-mono text-xs text-slate-600">
                {selectedDevice && (
                  <span>
                    {template.startsWith("mf")
                      ? `/${selectedDevice.mfSn}/mf/up`
                      : template.startsWith("cam")
                        ? `/device/${selectedDevice.cameraDevId}/update`
                        : `/device/${selectedDevice.didoDeviceId}/update`}
                  </span>
                )}
              </div>
            </div>

            <button
              onClick={send}
              disabled={connState !== "connected" || !selectedDevice}
              className="inline-flex w-full items-center justify-center gap-2 rounded-lg bg-blue-600 py-2.5 text-sm font-bold text-white transition hover:bg-blue-500 disabled:opacity-50"
            >
              <Send className="size-4" />
              鍙戦€?
            </button>
          </div>
        </section>

        {/* Right: MQTT logs */}
        <section className="flex min-h-0 w-[420px] shrink-0 flex-col rounded-xl border border-slate-200 bg-white shadow-sm">
          <div className="flex items-center justify-between border-b border-slate-100 px-4 py-3">
            <h2 className="text-sm font-bold text-slate-800">MQTT 淇″彿鏃ュ織锛堟渶鏂板湪涓婏級</h2>
            <button
              onClick={() => setLogs([])}
              className="text-xs text-slate-400 transition hover:text-slate-600"
            >
              娓呯┖
            </button>
          </div>
          <div className="min-h-0 flex-1 basis-0 space-y-2 overflow-y-scroll p-3">
            {logs.length === 0 && (
              <p className="py-8 text-center text-xs text-slate-400">绛夊緟鎺ユ敹娑堟伅鈥</p>
            )}
            {logs.map((log) => (
              <div
                key={log.id}
                className={cn(
                  "rounded-lg border p-3 text-xs",
                  log.direction === "tx"
                    ? "border-blue-200 bg-blue-50"
                    : log.direction === "tap"
                      ? "border-amber-200 bg-amber-50"
                      : "border-emerald-200 bg-emerald-50"
                )}
              >
                <div className="mb-1.5 flex items-center justify-between">
                  <div className="flex items-center gap-2">
                    <span
                      className={cn(
                        "rounded px-1.5 py-0.5 text-[10px] font-bold",
                        log.direction === "tx"
                          ? "bg-blue-100 text-blue-700"
                          : log.direction === "tap"
                            ? "bg-amber-100 text-amber-700"
                            : "bg-emerald-100 text-emerald-700"
                      )}
                    >
                      {log.direction === "tx" ? "发送" : log.direction === "tap" ? "下发回显" : "设备反馈"}
                    </span>
                    <span className="font-semibold text-slate-800">{log.deviceId}</span>
                  </div>
                  <span className="text-[10px] text-slate-400">{log.ts}</span>
                </div>
                <div className="mb-1 font-mono text-[10px] text-slate-500">{log.topic}</div>
                <pre className="overflow-x-auto whitespace-pre-wrap break-all rounded bg-slate-100 p-2 text-[10px] text-slate-700">
                  {log.payload}
                </pre>
              </div>
            ))}
          </div>
        </section>
      </div>
    </div>
  );
}


