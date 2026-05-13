"use client";

import { create } from "zustand";
import { createJSONStorage, persist } from "zustand/middleware";
import type { AuthResponse, UserSession } from "@/lib/types";

type AuthState = {
  token: string | null;
  expiresAt: string | null;
  user: UserSession | null;
  setSession: (payload: AuthResponse) => void;
  clearSession: () => void;
};

export const useAuthStore = create<AuthState>()(
  persist(
    (set) => ({
      token: null,
      expiresAt: null,
      user: null,
      setSession: ({ token, expiresAt, user }) => {
        set({ token, expiresAt, user });
      },
      clearSession: () => {
        set({ token: null, expiresAt: null, user: null });
      },
    }),
    {
      name: "smart-lane-auth-v2",
      storage: createJSONStorage(() => localStorage),
    },
  ),
);
