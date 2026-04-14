import type { UserRole } from "@/lib/types";

export function canAccessBlacklist(role: UserRole | undefined) {
  return role === "ADMIN";
}

export function canOperateSignals(role: UserRole | undefined) {
  return role === "ADMIN" || role === "DISPATCHER";
}

export function canDispatch(role: UserRole | undefined) {
  return role === "ADMIN" || role === "DISPATCHER";
}

export function canResolveAlerts(role: UserRole | undefined) {
  return role === "ADMIN" || role === "DISPATCHER";
}

export function canViewPath(role: UserRole | undefined, pathname: string) {
  if (!role) return false;
  if (pathname === "/" || pathname.startsWith("/entries") || pathname.startsWith("/alerts")) {
    return true;
  }
  if (pathname.startsWith("/signals") || pathname.startsWith("/dispatch")) {
    return canDispatch(role);
  }
  if (pathname.startsWith("/blacklist")) {
    return canAccessBlacklist(role);
  }
  return true;
}
