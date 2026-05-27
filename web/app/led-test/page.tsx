"use client";

import { useState } from "react";
import { Activity, ArrowDown, ArrowUp, Monitor, Plus, RefreshCw, Send, Terminal, Trash2 } from "lucide-react";
import { cn } from "@/lib/utils";

const API_BASE_URL =
  process.env.NEXT_PUBLIC_API_BASE_URL ??
  (process.env.NODE_ENV === "development" ? "http://localhost:8080/api" : "/api");
const SITE_LED_IP = "172.17.2.70";
const SITE_SCREEN_WIDTH = 192;
const SITE_SCREEN_HEIGHT = 96;
const SITE_COLUMNS = 2;
const SITE_ROWS = 6;
const BUSINESS_ROWS = 4;
const SITE_FONT_SIZE = 15;

const COLORS = [
  { value: "RED", label: "红色", bg: "bg-red-500" },
  { value: "GREEN", label: "绿色", bg: "bg-green-500" },
  { value: "BLUE", label: "蓝色", bg: "bg-blue-500" },
  { value: "YELLOW", label: "黄色", bg: "bg-yellow-400" },
  { value: "CYAN", label: "青色", bg: "bg-cyan-400" },
  { value: "MAGENTA", label: "品红", bg: "bg-fuchsia-500" },
  { value: "WHITE", label: "白色", bg: "bg-white border border-slate-300" },
  { value: "BLACK", label: "黑色", bg: "bg-slate-900" },
  { value: "ORANGE", label: "橙色", bg: "bg-orange-500" },
  { value: "PINK", label: "粉色", bg: "bg-pink-400" },
];

interface Segment {
  id: number;
  text: string;
  fontSize: number;
  color: string;
}

interface DispatchTicket {
  id: string;
  plate: string;
  assignedLaneId: string | null;
  assignedLaneName: string | null;
}

interface ScreenBoardResponse {
  guideAssignments?: DispatchTicket[];
  waitingAssignments?: DispatchTicket[];
  recentDispatches?: DispatchTicket[];
}

interface LedSendRequest {
  ip: string;
  port: number;
  generation: "5" | "6";
  model: string;
  screenWidth: number;
  screenHeight: number;
  columns: number;
  rows: number;
  segments: Array<{
    text: string;
    fontSize: number;
    color: string;
  }>;
}

type SendMode = "program" | "dynamic";

let nextId = 1;

function sampleSegments() {
  return Array.from({ length: SITE_COLUMNS * SITE_ROWS }, (_, index) => ({
    id: nextId++,
    text: `苏B${String(index + 1).padStart(5, "0")}-${(index % 11) + 1}车道`,
    fontSize: SITE_FONT_SIZE,
    color: "RED",
  }));
}

function businessListSegments() {
  return [
    { id: nextId++, text: "苏B00001 驶入 1车道", fontSize: 14, color: "RED" },
    { id: nextId++, text: "苏B00002 驶入 2车道", fontSize: 14, color: "RED" },
    { id: nextId++, text: "苏B00003 驶入 3车道", fontSize: 14, color: "RED" },
    { id: nextId++, text: "请按照车道指示进行停车等待！", fontSize: 11, color: "RED" },
  ];
}

function businessHighlightSegments() {
  return [
    { id: nextId++, text: "苏B00001", fontSize: 22, color: "RED" },
    { id: nextId++, text: "驶入", fontSize: 12, color: "RED" },
    { id: nextId++, text: "1车道", fontSize: 22, color: "RED" },
    { id: nextId++, text: "请按照车道指示进行停车等待！", fontSize: 11, color: "RED" },
  ];
}

function laneText(ticket: DispatchTicket) {
  const source = ticket.assignedLaneName || ticket.assignedLaneId || "";
  const matched = source.match(/\d+/);
  return matched ? `${Number.parseInt(matched[0], 10)}车道` : "待分配";
}

function guideText(ticket: DispatchTicket) {
  return `${ticket.plate}-${laneText(ticket)}`;
}

function uniqueTickets(tickets: DispatchTicket[]) {
  const seen = new Set<string>();
  return tickets.filter((ticket) => {
    const key = ticket.id || `${ticket.plate}-${ticket.assignedLaneId ?? ticket.assignedLaneName ?? ""}`;
    if (seen.has(key)) {
      return false;
    }
    seen.add(key);
    return Boolean(ticket.plate && (ticket.assignedLaneId || ticket.assignedLaneName));
  });
}

export default function LedTestPage() {
  const [ip, setIp] = useState(SITE_LED_IP);
  const [port, setPort] = useState("5005");
  const [generation, setGeneration] = useState<"5" | "6">("6");
  const [model, setModel] = useState("Bx6E");
  const [screenWidth, setScreenWidth] = useState(String(SITE_SCREEN_WIDTH));
  const [screenHeight, setScreenHeight] = useState(String(SITE_SCREEN_HEIGHT));
  const [columns, setColumns] = useState(String(SITE_COLUMNS));
  const [rows, setRows] = useState(String(SITE_ROWS));
  const [segments, setSegments] = useState<Segment[]>(sampleSegments);
  const [sendMode, setSendMode] = useState<SendMode>("program");
  const [loading, setLoading] = useState(false);
  const [loadingBoard, setLoadingBoard] = useState(false);
  const [result, setResult] = useState<string | null>(null);
  const [lastSendPayload, setLastSendPayload] = useState<string | null>(null);
  const [status, setStatus] = useState<"idle" | "success" | "error">("idle");

  function addSegment() {
    setSegments((prev) => [
      ...prev,
      { id: nextId++, text: "", fontSize: 12, color: "RED" },
    ]);
  }

  function applySitePreset() {
    setSendMode("program");
    setIp(SITE_LED_IP);
    setScreenWidth(String(SITE_SCREEN_WIDTH));
    setScreenHeight(String(SITE_SCREEN_HEIGHT));
    setColumns(String(SITE_COLUMNS));
    setRows(String(SITE_ROWS));
    setSegments(sampleSegments());
  }

  function applyBusinessListPreset() {
    setSendMode("dynamic");
    setIp(SITE_LED_IP);
    setGeneration("6");
    setModel("Bx6E");
    setScreenWidth(String(SITE_SCREEN_WIDTH));
    setScreenHeight(String(SITE_SCREEN_HEIGHT));
    setColumns("1");
    setRows(String(BUSINESS_ROWS));
    setSegments(businessListSegments());
  }

  function applyBusinessHighlightPreset() {
    setSendMode("dynamic");
    setIp(SITE_LED_IP);
    setGeneration("6");
    setModel("Bx6E");
    setScreenWidth(String(SITE_SCREEN_WIDTH));
    setScreenHeight(String(SITE_SCREEN_HEIGHT));
    setColumns("1");
    setRows(String(BUSINESS_ROWS));
    setSegments(businessHighlightSegments());
  }

  function removeSegment(id: number) {
    setSegments((prev) => prev.filter((s) => s.id !== id));
  }

  function moveSegment(id: number, direction: -1 | 1) {
    setSegments((prev) => {
      const index = prev.findIndex((s) => s.id === id);
      if (index < 0) return prev;
      const newIndex = index + direction;
      if (newIndex < 0 || newIndex >= prev.length) return prev;
      const next = [...prev];
      [next[index], next[newIndex]] = [next[newIndex], next[index]];
      return next;
    });
  }

  function updateSegment(id: number, patch: Partial<Segment>) {
    setSegments((prev) => prev.map((s) => (s.id === id ? { ...s, ...patch } : s)));
  }

  async function loadGuideAssignments() {
    setLoadingBoard(true);
    setResult(null);
    setStatus("idle");
    try {
      const response = await fetch(`${API_BASE_URL}/screen/board`);
      if (!response.ok) {
        throw new Error(`大屏数据读取失败：${response.status}`);
      }
      const payload = (await response.json()) as ScreenBoardResponse;
      const capacity = Math.max(1, (Number.parseInt(columns, 10) || SITE_COLUMNS) * (Number.parseInt(rows, 10) || SITE_ROWS));
      const tickets = uniqueTickets([
        ...(payload.guideAssignments ?? []),
        ...(payload.waitingAssignments ?? []),
        ...(payload.recentDispatches ?? []),
      ]).slice(0, capacity);
      if (tickets.length === 0) {
        setResult("当前没有可发送的大屏引导数据");
        setStatus("error");
        return;
      }
      setSegments(
        tickets.map((ticket) => ({
          id: nextId++,
          text: guideText(ticket),
          fontSize: SITE_FONT_SIZE,
          color: "RED",
        })),
      );
      setResult(`已读取 ${tickets.length} 条总入口引导数据`);
      setStatus("success");
    } catch (error) {
      setResult(error instanceof Error ? error.message : "大屏数据读取失败");
      setStatus("error");
    } finally {
      setLoadingBoard(false);
    }
  }

  async function handleSend() {
    const validSegments = segments.filter((s) => s.text.trim());
    if (validSegments.length === 0) {
      setResult("请至少输入一段文本");
      setStatus("error");
      return;
    }
    if (sendMode === "dynamic" && generation !== "6") {
      setResult("动态区长连接只支持六代控制卡");
      setStatus("error");
      return;
    }
    setLoading(true);
    setResult(null);
    setStatus("idle");
    try {
      const payloadSegments = sendMode === "dynamic" ? validSegments.slice(0, BUSINESS_ROWS) : validSegments;
      const requestPayload: LedSendRequest = {
        ip: ip.trim(),
        port: Number.parseInt(port, 10) || 5005,
        generation,
        model,
        screenWidth: Number.parseInt(screenWidth, 10) || SITE_SCREEN_WIDTH,
        screenHeight: Number.parseInt(screenHeight, 10) || SITE_SCREEN_HEIGHT,
        columns: sendMode === "dynamic" ? 1 : Number.parseInt(columns, 10) || SITE_COLUMNS,
        rows: sendMode === "dynamic" ? BUSINESS_ROWS : Number.parseInt(rows, 10) || SITE_ROWS,
        segments: payloadSegments.map((s) => ({
          text: s.text.trim(),
          fontSize: s.fontSize,
          color: s.color,
        })),
      };
      const requestBody = JSON.stringify(requestPayload);
      setLastSendPayload(requestBody);
      const endpoint = sendMode === "dynamic" ? "send-dynamic" : "send";
      const response = await fetch(`${API_BASE_URL}/screen/led-test/${endpoint}`, {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: requestBody,
      });
      const payload = (await response.json()) as { result: string };
      setResult(payload.result);
      setStatus(payload.result.startsWith("发送成功") ? "success" : "error");
    } catch (error) {
      setResult(error instanceof Error ? error.message : "请求失败");
      setStatus("error");
    } finally {
      setLoading(false);
    }
  }

  const previewColumns = sendMode === "dynamic" ? 1 : Math.max(1, Number.parseInt(columns, 10) || SITE_COLUMNS);
  const previewRows = sendMode === "dynamic" ? BUSINESS_ROWS : Math.max(1, Number.parseInt(rows, 10) || SITE_ROWS);
  const previewCapacity = previewColumns * previewRows;

  return (
    <main className="min-h-screen bg-slate-100 px-4 py-5 text-slate-900 sm:px-6">
      <div className="mx-auto flex max-w-3xl flex-col gap-4">
        <header className="flex flex-col gap-3 border-b border-slate-200 pb-4 lg:flex-row lg:items-end lg:justify-between">
          <div>
            <div className="mb-2 flex items-center gap-2 text-xs font-bold uppercase text-blue-700">
              <Monitor className="size-4" />
              LED 显示屏联调
            </div>
            <h1 className="text-2xl font-bold tracking-tight text-slate-950">仰邦 BX 显示屏测试台</h1>
            <p className="mt-2 max-w-2xl text-sm text-slate-600">
              通过后端 SDK 直接给仰邦五代/六代控制卡发送节目文本。支持多段文本，每段可独立设置颜色和字号。
            </p>
          </div>
          <div
            className={cn(
              "inline-flex w-fit items-center gap-2 rounded-md px-3 py-2 text-sm font-bold",
              status === "success"
                ? "bg-emerald-100 text-emerald-700"
                : status === "error"
                  ? "bg-rose-100 text-rose-700"
                  : "bg-amber-100 text-amber-700"
            )}
          >
            <Activity className="size-4" />
            {status === "success" ? "发送成功" : status === "error" ? "发送异常" : "等待操作"}
          </div>
        </header>

        <section className="grid gap-4">
          <div className="rounded-lg border border-slate-200 bg-white p-4 shadow-sm">
            <h2 className="mb-3 flex items-center gap-2 text-sm font-bold">
              <Terminal className="size-4 text-blue-600" />
              连接参数
            </h2>
            <div className="grid gap-3 sm:grid-cols-2">
              <div className="grid gap-1 text-xs font-semibold text-slate-600 sm:col-span-2">
                发送方式
                <div className="inline-flex w-fit overflow-hidden rounded-md border border-slate-200 bg-slate-50 p-1">
                  <button
                    type="button"
                    onClick={() => setSendMode("program")}
                    className={cn(
                      "rounded px-3 py-1.5 text-xs font-bold transition",
                      sendMode === "program"
                        ? "bg-white text-blue-700 shadow-sm"
                        : "text-slate-500 hover:text-slate-800"
                    )}
                  >
                    完整节目
                  </button>
                  <button
                    type="button"
                    onClick={() => {
                      setSendMode("dynamic");
                      setGeneration("6");
                    }}
                    className={cn(
                      "rounded px-3 py-1.5 text-xs font-bold transition",
                      sendMode === "dynamic"
                        ? "bg-white text-emerald-700 shadow-sm"
                        : "text-slate-500 hover:text-slate-800"
                    )}
                  >
                    动态区长连接
                  </button>
                </div>
              </div>
              <label className="grid gap-1 text-xs font-semibold text-slate-600">
                显示屏 IP
                <input
                  value={ip}
                  onChange={(e) => setIp(e.target.value)}
                  className="rounded-md border border-slate-200 bg-white px-3 py-2 font-mono text-sm text-slate-900 outline-none focus:border-blue-500"
                />
              </label>
              <label className="grid gap-1 text-xs font-semibold text-slate-600">
                端口
                <input
                  value={port}
                  onChange={(e) => setPort(e.target.value)}
                  className="rounded-md border border-slate-200 bg-white px-3 py-2 font-mono text-sm text-slate-900 outline-none focus:border-blue-500"
                />
              </label>
              <label className="grid gap-1 text-xs font-semibold text-slate-600">
                控制卡代际
                <select
                  value={generation}
                  onChange={(e) => setGeneration(e.target.value as "5" | "6")}
                  className="rounded-md border border-slate-200 bg-white px-3 py-2 text-sm text-slate-900 outline-none focus:border-blue-500"
                >
                  <option value="6">六代卡（BX-6xxx）</option>
                  <option value="5">五代卡（BX-5xxx）</option>
                </select>
              </label>
              {generation === "6" ? (
                <label className="grid gap-1 text-xs font-semibold text-slate-600">
                  六代型号
                  <select
                    value={model}
                    onChange={(e) => setModel(e.target.value)}
                    className="rounded-md border border-slate-200 bg-white px-3 py-2 text-sm text-slate-900 outline-none focus:border-blue-500"
                  >
                    <option value="Bx6E">Bx6E（默认）</option>
                    <option value="Bx6M">Bx6M</option>
                    <option value="Bx6Q">Bx6Q</option>
                  </select>
                </label>
              ) : (
                <div />
              )}
              <label className="grid gap-1 text-xs font-semibold text-slate-600">
                屏幕宽度
                <input
                  value={screenWidth}
                  onChange={(e) => setScreenWidth(e.target.value)}
                  className="rounded-md border border-slate-200 bg-white px-3 py-2 font-mono text-sm text-slate-900 outline-none focus:border-blue-500"
                />
              </label>
              <label className="grid gap-1 text-xs font-semibold text-slate-600">
                屏幕高度
                <input
                  value={screenHeight}
                  onChange={(e) => setScreenHeight(e.target.value)}
                  className="rounded-md border border-slate-200 bg-white px-3 py-2 font-mono text-sm text-slate-900 outline-none focus:border-blue-500"
                />
              </label>
              <label className="grid gap-1 text-xs font-semibold text-slate-600">
                列数
                <input
                  value={columns}
                  onChange={(e) => setColumns(e.target.value)}
                  className="rounded-md border border-slate-200 bg-white px-3 py-2 font-mono text-sm text-slate-900 outline-none focus:border-blue-500"
                />
              </label>
              <label className="grid gap-1 text-xs font-semibold text-slate-600">
                行数
                <input
                  value={rows}
                  onChange={(e) => setRows(e.target.value)}
                  className="rounded-md border border-slate-200 bg-white px-3 py-2 font-mono text-sm text-slate-900 outline-none focus:border-blue-500"
                />
              </label>
            </div>
            <div className="mt-3 flex flex-wrap gap-2">
              <button
                type="button"
                onClick={applySitePreset}
                className="inline-flex items-center justify-center gap-2 rounded-md border border-blue-200 bg-blue-50 px-3 py-2 text-xs font-bold text-blue-700 hover:bg-blue-100"
              >
                <Monitor className="size-3.5" />
                现场大屏 192x96
              </button>
              <button
                type="button"
                onClick={applyBusinessListPreset}
                className="inline-flex items-center justify-center gap-2 rounded-md border border-emerald-200 bg-emerald-50 px-3 py-2 text-xs font-bold text-emerald-700 hover:bg-emerald-100"
              >
                <Monitor className="size-3.5" />
                业务列表 1x4
              </button>
              <button
                type="button"
                onClick={applyBusinessHighlightPreset}
                className="inline-flex items-center justify-center gap-2 rounded-md border border-amber-200 bg-amber-50 px-3 py-2 text-xs font-bold text-amber-700 hover:bg-amber-100"
              >
                <Monitor className="size-3.5" />
                高亮引导 1x4
              </button>
              <button
                type="button"
                onClick={loadGuideAssignments}
                disabled={loadingBoard}
                className="inline-flex items-center justify-center gap-2 rounded-md border border-emerald-200 bg-emerald-50 px-3 py-2 text-xs font-bold text-emerald-700 hover:bg-emerald-100 disabled:opacity-50"
              >
                <RefreshCw className={cn("size-3.5", loadingBoard && "animate-spin")} />
                {loadingBoard ? "读取中..." : "读取总入口数据"}
              </button>
            </div>
          </div>

          <div className="rounded-lg border border-slate-200 bg-white p-4 shadow-sm">
            <h2 className="mb-3 flex items-center gap-2 text-sm font-bold">
              <Send className="size-4 text-emerald-600" />
              发送内容
            </h2>
            <div className="grid gap-3">
              <div className="rounded-md border border-slate-200 bg-slate-950 p-3">
                <div
                  className="grid gap-px overflow-hidden rounded border border-slate-700 bg-slate-800"
                  style={{
                    gridTemplateColumns: `repeat(${previewColumns}, minmax(0, 1fr))`,
                  }}
                >
                  {Array.from({ length: previewCapacity }).map((_, index) => (
                    <div
                      key={index}
                      className="flex min-h-10 items-center justify-center bg-black px-2 py-2 text-center font-mono text-base font-black text-red-500"
                    >
                      {segments[index]?.text || ""}
                    </div>
                  ))}
                </div>
              </div>

              {segments.map((segment, index) => (
                <div
                  key={segment.id}
                  className="relative rounded-md border border-slate-200 bg-slate-50 p-3"
                >
                  <div className="mb-2 flex items-center justify-between">
                    <span className="text-xs font-bold text-slate-500">第 {index + 1} 段</span>
                    <div className="flex items-center gap-1">
                      <button
                        type="button"
                        onClick={() => moveSegment(segment.id, -1)}
                        disabled={index === 0}
                        className="grid size-6 place-items-center rounded text-slate-500 hover:bg-slate-200 disabled:opacity-30"
                      >
                        <ArrowUp className="size-3.5" />
                      </button>
                      <button
                        type="button"
                        onClick={() => moveSegment(segment.id, 1)}
                        disabled={index === segments.length - 1}
                        className="grid size-6 place-items-center rounded text-slate-500 hover:bg-slate-200 disabled:opacity-30"
                      >
                        <ArrowDown className="size-3.5" />
                      </button>
                      <button
                        type="button"
                        onClick={() => removeSegment(segment.id)}
                        disabled={segments.length <= 1}
                        className="grid size-6 place-items-center rounded text-rose-500 hover:bg-rose-100 disabled:opacity-30"
                      >
                        <Trash2 className="size-3.5" />
                      </button>
                    </div>
                  </div>

                  <div className="grid gap-3 sm:grid-cols-[1fr_100px_160px]">
                    <label className="grid gap-1 text-xs font-semibold text-slate-600">
                      文本内容
                      <input
                        value={segment.text}
                        onChange={(e) => updateSegment(segment.id, { text: e.target.value })}
                        placeholder="输入文字"
                        className="rounded-md border border-slate-200 bg-white px-3 py-2 text-sm text-slate-900 outline-none focus:border-emerald-500"
                      />
                    </label>
                    <label className="grid gap-1 text-xs font-semibold text-slate-600">
                      字号
                      <div className="flex items-center gap-2">
                        <input
                          type="range"
                          min={8}
                          max={120}
                          value={segment.fontSize}
                          onChange={(e) =>
                            updateSegment(segment.id, { fontSize: Number.parseInt(e.target.value, 10) })
                          }
                          className="h-2 w-full cursor-pointer appearance-none rounded-lg bg-slate-200 accent-emerald-600"
                        />
                        <span className="w-6 text-right font-mono text-sm font-bold text-slate-700">
                          {segment.fontSize}
                        </span>
                      </div>
                    </label>
                    <label className="grid gap-1 text-xs font-semibold text-slate-600">
                      颜色
                      <div className="flex flex-wrap gap-1.5">
                        {COLORS.map((c) => (
                          <button
                            key={c.value}
                            type="button"
                            onClick={() => updateSegment(segment.id, { color: c.value })}
                            title={c.label}
                            className={cn(
                              "size-6 rounded-full transition-transform",
                              c.bg,
                              segment.color === c.value
                                ? "ring-2 ring-offset-1 ring-emerald-500 scale-110"
                                : "hover:scale-105"
                            )}
                          />
                        ))}
                      </div>
                    </label>
                  </div>
                </div>
              ))}

              <button
                type="button"
                onClick={addSegment}
                className="inline-flex items-center justify-center gap-2 rounded-md border border-dashed border-slate-300 bg-white px-4 py-2 text-sm font-bold text-slate-600 hover:border-emerald-400 hover:text-emerald-600"
              >
                <Plus className="size-4" />
                添加一段文本
              </button>

              <button
                type="button"
                onClick={handleSend}
                disabled={loading}
                className="inline-flex items-center justify-center gap-2 rounded-md bg-emerald-600 px-4 py-2 text-sm font-bold text-white hover:bg-emerald-500 disabled:opacity-50"
              >
                <Send className="size-4" />
                {loading ? "发送中..." : sendMode === "dynamic" ? "动态区长连接发送" : "发送到显示屏"}
              </button>
            </div>
          </div>

          {result ? (
            <div
              className={cn(
                "rounded-lg border p-4 shadow-sm",
                status === "success"
                  ? "border-emerald-200 bg-emerald-50 text-emerald-900"
                  : "border-rose-200 bg-rose-50 text-rose-900"
              )}
            >
              <h3 className="mb-1 text-xs font-bold uppercase opacity-70">返回结果</h3>
              <p className="text-sm font-semibold">{result}</p>
            </div>
          ) : null}

          {lastSendPayload ? (
            <div className="rounded-lg border border-slate-200 bg-white p-4 shadow-sm">
              <h3 className="mb-2 flex items-center gap-2 text-xs font-bold uppercase text-slate-500">
                <Terminal className="size-3.5 text-slate-500" />
                原始发送报文
              </h3>
              <pre className="max-h-80 overflow-auto rounded-md border border-slate-200 bg-slate-950 p-3 text-xs leading-5 text-emerald-100">
                <code>{lastSendPayload}</code>
              </pre>
            </div>
          ) : null}

          <div className="rounded-lg border border-slate-200 bg-white p-4 shadow-sm">
            <h2 className="mb-2 text-sm font-bold text-slate-700">使用说明</h2>
            <ul className="list-inside list-disc space-y-1 text-xs text-slate-600">
              <li>现场大屏按控制卡逻辑尺寸 192x96 发送，2 列 6 行时单格为 96x16。</li>
              <li>动态区长连接模式按业务大屏 1 列 4 行发送，只取前 4 段内容并复用控制卡连接。</li>
              <li>现场格式为 2 列 6 行，每条内容按“车牌-车道”展示，例如“苏B12345-1车道”。</li>
              <li>点击“读取总入口数据”会从大屏接口读取总入口抓拍后的推荐车道数据。</li>
              <li>点击色块切换颜色，当前选中的颜色会带有绿色边框。</li>
              <li>确保本机 IP 与显示屏 172.17.2.70 处于同一网段，且没有防火墙拦截。</li>
            </ul>
          </div>
        </section>
      </div>
    </main>
  );
}
