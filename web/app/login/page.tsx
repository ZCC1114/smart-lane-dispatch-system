"use client";

import { useMutation } from "@tanstack/react-query";
import { Activity, ArrowRight, Lock, ShieldCheck, User } from "lucide-react";
import { useRouter } from "next/navigation";
import { FormEvent, useEffect, useState } from "react";
import { api } from "@/lib/api";
import { useAuthStore } from "@/stores/auth-store";

export default function LoginPage() {
  const router = useRouter();
  const token = useAuthStore((state) => state.token);
  const setSession = useAuthStore((state) => state.setSession);
  const [formData, setFormData] = useState({ username: "", password: "" });
  const [error, setError] = useState("");

  const loginMutation = useMutation({
    mutationFn: () => api.login(formData.username.trim(), formData.password),
    onSuccess: (payload) => {
      setSession(payload);
      router.replace("/");
    },
    onError: (mutationError: Error) => {
      setError(mutationError.message);
    },
  });

  useEffect(() => {
    if (token) {
      router.replace("/");
    }
  }, [router, token]);

  function handleSubmit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    setError("");
    loginMutation.mutate();
  }

  return (
    <main className="relative flex min-h-screen items-center justify-center overflow-hidden bg-[var(--bg-canvas)] p-6">
      <div className="pointer-events-none absolute inset-0 opacity-80">
        <div className="absolute left-1/4 top-1/4 h-1/2 w-1/2 rotate-45 border border-blue-200/70" />
        <div className="absolute left-1/3 top-1/3 h-1/3 w-1/3 -rotate-12 border border-cyan-200/70" />
      </div>

      <div className="relative z-10 w-full max-w-md rounded-sm border border-[var(--border-soft)] bg-white/92 p-10 shadow-[0_24px_70px_rgba(15,23,42,0.10)] backdrop-blur">
        <div className="mb-10 text-center">
          <div className="mx-auto mb-6 flex size-16 items-center justify-center rounded-sm bg-blue-600 shadow-lg shadow-blue-500/20">
            <Activity className="size-10 text-white" />
          </div>
          <h1 className="text-2xl font-bold tracking-tight text-slate-900">出租车智能调度系统</h1>
          <p className="mt-2 text-sm text-slate-500">登录后进入调度控制台</p>
        </div>

        <form onSubmit={handleSubmit} className="space-y-6">
          <label className="block space-y-2">
            <span className="flex items-center text-[10px] font-bold uppercase tracking-[0.28em] text-slate-500">
              <User className="mr-2 size-3 text-blue-500" />
              用户名
            </span>
            <input
              value={formData.username}
              onChange={(event) => setFormData((current) => ({ ...current, username: event.target.value }))}
              className="w-full rounded-sm border border-slate-200 bg-slate-50 px-4 py-3 text-sm text-slate-900 outline-none transition placeholder:text-slate-400 focus:border-blue-500"
              placeholder="请输入登录账号"
              required
            />
          </label>

          <label className="block space-y-2">
            <span className="flex items-center text-[10px] font-bold uppercase tracking-[0.28em] text-slate-500">
              <Lock className="mr-2 size-3 text-blue-500" />
              登录密码
            </span>
            <input
              type="password"
              value={formData.password}
              onChange={(event) => setFormData((current) => ({ ...current, password: event.target.value }))}
              className="w-full rounded-sm border border-slate-200 bg-slate-50 px-4 py-3 text-sm text-slate-900 outline-none transition placeholder:text-slate-400 focus:border-blue-500"
              placeholder="请输入认证密码"
              required
            />
          </label>

          <label className="flex cursor-pointer items-center space-x-2">
            <input type="checkbox" className="size-4 rounded-sm border-slate-300 bg-white text-blue-600 focus:ring-0 focus:ring-offset-0" />
            <span className="text-xs text-slate-500">记住登录状态</span>
          </label>

          {error ? <p className="text-sm text-rose-600">{error}</p> : null}

          <button
            type="submit"
            disabled={loginMutation.isPending}
            className="group flex w-full items-center justify-center space-x-3 rounded-sm bg-blue-600 py-4 text-sm font-bold uppercase tracking-[0.2em] text-white shadow-xl shadow-blue-500/20 transition-all hover:bg-blue-500 disabled:cursor-not-allowed disabled:opacity-70"
          >
            <ShieldCheck className="size-4" />
            <span>{loginMutation.isPending ? "登录中..." : "登录系统"}</span>
            <ArrowRight className="size-4 transition-transform group-hover:translate-x-1" />
          </button>
        </form>

      </div>
    </main>
  );
}
