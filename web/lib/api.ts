"use client";

import { useAuthStore } from "@/stores/auth-store";
import type {
  AlertItem,
  AuthResponse,
  BlacklistPayload,
  BlacklistRecord,
  DashboardPayload,
  EntryLog,
  LaneSnapshot,
  ManualDispatchRequest,
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
    throw new ApiError(errorText || "请求失败", response.status);
  }

  if (response.status === 204) {
    return undefined as T;
  }

  return (await response.json()) as T;
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
  getAlerts() {
    return request<AlertItem[]>("/alerts");
  },
  resolveAlert(alertId: string) {
    return request<void>(`/alerts/${alertId}/resolve`, { method: "POST" });
  },
  getLogs(filters: { query?: string; status?: string; laneId?: string }) {
    return request<EntryLog[]>(`/logs${buildQuery(filters)}`);
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
};
