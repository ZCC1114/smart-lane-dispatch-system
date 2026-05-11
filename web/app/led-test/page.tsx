"use client";

import { useState } from "react";
import { Activity, Monitor, Plus, Send, Terminal, Trash2, ArrowUp, ArrowDown } from "lucide-react";
import { cn } from "@/lib/utils";

const API_BASE_URL = process.env.NEXT_PUBLIC_API_BASE_URL ?? "http://localhost:8080/api";

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

let nextId = 1;

export default function LedTestPage() {
  const [ip, setIp] = useState("192.168.0.31");
  const [port, setPort] = useState("5005");
  const [generation, setGeneration] = useState<"5" | "6">("6");
  const [model, setModel] = useState("Bx6E");
  const [segments, setSegments] = useState<Segment[]>([
    { id: nextId++, text: "测试文本", fontSize: 12, color: "RED" },
  ]);
  const [loading, setLoading] = useState(false);
  const [result, setResult] = useState<string | null>(null);
  const [status, setStatus] = useState<"idle" | "success" | "error">("idle");

  function addSegment() {
    setSegments((prev) => [
      ...prev,
      { id: nextId++, text: "", fontSize: 12, color: "RED" },
    ]);
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

  async function handleSend() {
    const validSegments = segments.filter((s) => s.text.trim());
    if (validSegments.length === 0) {
      setResult("请至少输入一段文本");
      setStatus("error");
      return;
    }
    setLoading(true);
    setResult(null);
    setStatus("idle");
    try {
      const response = await fetch(`${API_BASE_URL}/screen/led-test/send`, {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({
          ip: ip.trim(),
          port: Number.parseInt(port, 10) || 5005,
          generation,
          model,
          segments: validSegments.map((s) => ({
            text: s.text.trim(),
            fontSize: s.fontSize,
            color: s.color,
          })),
        }),
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
            </div>
          </div>

          <div className="rounded-lg border border-slate-200 bg-white p-4 shadow-sm">
            <h2 className="mb-3 flex items-center gap-2 text-sm font-bold">
              <Send className="size-4 text-emerald-600" />
              发送内容
            </h2>
            <div className="grid gap-3">
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
                          max={64}
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
                {loading ? "发送中..." : "发送到显示屏"}
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

          <div className="rounded-lg border border-slate-200 bg-white p-4 shadow-sm">
            <h2 className="mb-2 text-sm font-bold text-slate-700">使用说明</h2>
            <ul className="list-inside list-disc space-y-1 text-xs text-slate-600">
              <li>默认端口为 5005，已自动填入。如果连接失败，请确认控制卡实际端口。</li>
              <li>每段文本可独立设置字号和颜色，多段文本会在显示屏上依次显示。</li>
              <li>点击色块切换颜色，当前选中的颜色会带有绿色边框。</li>
              <li>确保本机 IP 与显示屏 192.168.0.31 处于同一网段，且没有防火墙拦截。</li>
            </ul>
          </div>
        </section>
      </div>
    </main>
  );
}
