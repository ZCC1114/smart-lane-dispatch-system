"use client";

import { Client } from "@stomp/stompjs";
import { useQueryClient } from "@tanstack/react-query";
import { startTransition, useEffect } from "react";
import SockJS from "sockjs-client";
import { useAuthStore } from "@/stores/auth-store";

const WS_ENDPOINT =
  process.env.NEXT_PUBLIC_WS_ENDPOINT ??
  (process.env.NODE_ENV === "development" ? "http://localhost:8080/ws" : "/ws");

export function LiveUpdatesBridge() {
  const queryClient = useQueryClient();
  const token = useAuthStore((state) => state.token);

  useEffect(() => {
    if (!token) {
      return undefined;
    }

    const client = new Client({
      reconnectDelay: 4000,
      webSocketFactory: () => new SockJS(WS_ENDPOINT),
    });

    client.onConnect = () => {
      client.subscribe("/topic/operations", () => {
        startTransition(() => {
          queryClient.invalidateQueries({ queryKey: ["dashboard"] });
          queryClient.invalidateQueries({ queryKey: ["lanes"] });
          queryClient.invalidateQueries({ queryKey: ["logs"] });
          queryClient.invalidateQueries({ queryKey: ["blacklist"] });
        });
      });
    };

    client.activate();

    return () => {
      client.deactivate();
    };
  }, [queryClient, token]);

  return null;
}
