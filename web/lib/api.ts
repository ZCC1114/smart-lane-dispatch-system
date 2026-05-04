"use client";

import { useAuthStore } from "@/stores/auth-store";
import type {
  AuthResponse,
  BlacklistPayload,
  BlacklistRecord,
  DashboardPayload,
  DispatchBoard,
  DispatchConfig,
  DispatchRuntimeState,
  EntryLog,
  LaneSnapshot,
  ManualDispatchRequest,
  ScreenEvent,
  SignalOverrideRequest,
} from "@/lib/types";

const API_BASE_URL = process.env.NEXT_PUBLIC_API_BASE_URL ?? "http://localhost:8080/api";

class ApiError extends Error {
  constructor(
    message: string,
    public status: number,
  ) {
    super(message);
  }
}

async function request<T>(path: string, init?: RequestInit) {
  const token = useAuthStore.getState().token;
  const headers = new Headers(init?.headers);
  headers.set("Content-Type", "application/json");
  if (token) {
    headers.set("Authorization", `Bearer ${token}`);
  }

  const response = await fetch(`${API_BASE_URL}${path}`, {
    ...init,
    headers,
  });

  if (response.status === 401) {
    useAuthStore.getState().clearSession();
  }

  if (!response.ok) {
    const errorText = await response.text();
    throw new ApiError(normalizeErrorMessage(errorText, response.status), response.status);
  }

  if (response.status === 204) {
    return undefined as T;
  }

  return (await response.json()) as T;
}

function normalizeErrorMessage(errorText: string, status: number) {
  if (status === 403) {
    return "当前账号没有权限执行该操作";
  }

  if (!errorText) {
    if (status === 401) {
      return "登录已失效，请重新登录";
    }
    return "请求失败";
  }

  try {
    const payload = JSON.parse(errorText) as { message?: string; error?: string };
    return payload.message ?? payload.error ?? errorText;
  } catch {
    return errorText;
  }
}

function buildQuery(params: Record<string, string | undefined>) {
  const search = new URLSearchParams();
  Object.entries(params).forEach(([key, value]) => {
    if (value) {
      search.set(key, value);
    }
  });
  const query = search.toString();
  return query ? `?${query}` : "";
}

export const api = {
  login(username: string, password: string) {
    return request<AuthResponse>("/auth/login", {
      method: "POST",
      body: JSON.stringify({ username, password }),
    });
  },
  getDashboard() {
    return request<DashboardPayload>("/dashboard");
  },
  getLanes() {
    return request<LaneSnapshot[]>("/lanes");
  },
  getLogs(filters: { query?: string; status?: string; laneId?: string; entryTimeFrom?: string; entryTimeTo?: string }) {
    return request<EntryLog[]>(`/logs${buildQuery(filters)}`);
  },
  getScreenEvents(filters: { type?: string; occurredAtFrom?: string; occurredAtTo?: string; includeHandled?: string }) {
    return request<ScreenEvent[]>(`/screen/events${buildQuery(filters)}`);
  },
  getBlacklist(query?: string) {
    return request<BlacklistRecord[]>(`/blacklist${buildQuery({ query })}`);
  },
  createBlacklist(payload: BlacklistPayload) {
    return request<BlacklistRecord>("/blacklist", {
      method: "POST",
      body: JSON.stringify(payload),
    });
  },
  updateBlacklist(id: string, payload: BlacklistPayload) {
    return request<BlacklistRecord>(`/blacklist/${id}`, {
      method: "PUT",
      body: JSON.stringify(payload),
    });
  },
  deleteBlacklist(id: string) {
    return request<void>(`/blacklist/${id}`, {
      method: "DELETE",
    });
  },
  updateSignal(payload: SignalOverrideRequest) {
    return request<LaneSnapshot>(`/signals/${payload.laneId}`, {
      method: "POST",
      body: JSON.stringify(payload),
    });
  },
  restoreAutoControl() {
    return request<void>("/signals/restore-auto", { method: "POST" });
  },
  globalLockdown() {
    return request<void>("/signals/lockdown", { method: "POST" });
  },
  dispatch(payload: ManualDispatchRequest) {
    return request<void>("/dispatch/manual", {
      method: "POST",
      body: JSON.stringify(payload),
    });
  },
  getDispatchConfig() {
    return request<DispatchConfig>("/dispatch/config");
  },
  getDispatchBoard() {
    return request<DispatchBoard>("/dispatch/board");
  },
  updateDispatchConfig(payload: DispatchConfig) {
    return request<DispatchConfig>("/dispatch/config", {
      method: "PUT",
      body: JSON.stringify(payload),
    });
  },
  updateDispatchRuntime(payload: DispatchRuntimeState) {
    return request<DispatchConfig>("/dispatch/runtime", {
      method: "PUT",
      body: JSON.stringify(payload),
    });
  },
  dailyReset() {
    return request<DispatchConfig>("/dispatch/daily-reset", {
      method: "POST",
    });
  },
  updateLaneCapacity(laneId: string, capacity: number) {
    return request<LaneSnapshot>(`/lanes/${laneId}/capacity`, {
      method: "PUT",
      body: JSON.stringify({ capacity }),
    });
  },
};
