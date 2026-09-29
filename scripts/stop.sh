#!/usr/bin/env bash
# FileArk 停止：优先用 PID 文件，失效则按 jar 名兜底查找。
set -u

PID_FILE="$HOME/.fileark/server.pid"

stopped() { echo "FileArk 已停止"; }

if [[ -f "$PID_FILE" ]]; then
  PID=$(cat "$PID_FILE")
  if kill "$PID" 2>/dev/null; then
    for _ in $(seq 1 10); do
      kill -0 "$PID" 2>/dev/null || break
      sleep 0.5
    done
    kill -9 "$PID" 2>/dev/null || true
    stopped
    exit 0
  fi
fi

# PID 文件失效：按进程名兜底
PIDS=$(pgrep -f "fileark-web-0.1.0-SNAPSHOT.jar" || true)
if [[ -n "$PIDS" ]]; then
  kill $PIDS 2>/dev/null || true
  stopped
else
  echo "没有正在运行的 FileArk 进程"
fi
