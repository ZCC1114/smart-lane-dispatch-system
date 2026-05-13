#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

cd "$ROOT_DIR"

if [[ ! -f .env && -f .env.example ]]; then
  cp .env.example .env
fi

if [[ "$(uname -s)" == "Darwin" ]] && command -v scutil >/dev/null 2>&1; then
  if [[ -z "${HTTPS_PROXY:-}${https_proxy:-}" ]]; then
    HTTPS_ENABLE="$(scutil --proxy | awk '$1 == "HTTPSEnable" {print $3; exit}')"
    HTTPS_HOST="$(scutil --proxy | awk '$1 == "HTTPSProxy" {print $3; exit}')"
    HTTPS_PORT="$(scutil --proxy | awk '$1 == "HTTPSPort" {print $3; exit}')"
    if [[ "$HTTPS_ENABLE" == "1" && -n "$HTTPS_HOST" && -n "$HTTPS_PORT" ]]; then
      export HTTPS_PROXY="http://${HTTPS_HOST}:${HTTPS_PORT}"
      export https_proxy="$HTTPS_PROXY"
      export HTTP_PROXY="${HTTP_PROXY:-$HTTPS_PROXY}"
      export http_proxy="${http_proxy:-$HTTPS_PROXY}"
      echo "Using macOS system HTTPS proxy for Docker pulls: $HTTPS_PROXY"
    fi
  fi

  if [[ -z "${ALL_PROXY:-}${all_proxy:-}" ]]; then
    SOCKS_ENABLE="$(scutil --proxy | awk '$1 == "SOCKSEnable" {print $3; exit}')"
    SOCKS_HOST="$(scutil --proxy | awk '$1 == "SOCKSProxy" {print $3; exit}')"
    SOCKS_PORT="$(scutil --proxy | awk '$1 == "SOCKSPort" {print $3; exit}')"
    if [[ "$SOCKS_ENABLE" == "1" && -n "$SOCKS_HOST" && -n "$SOCKS_PORT" ]]; then
      export ALL_PROXY="socks5://${SOCKS_HOST}:${SOCKS_PORT}"
      export all_proxy="$ALL_PROXY"
    fi
  fi
fi

docker compose up -d --build
