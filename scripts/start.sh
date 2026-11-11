#!/usr/bin/env bash
# FileArk 一键启动：已在运行则直接打开浏览器；未运行则启动并等待就绪。
# 用法：scripts/start.sh
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
JAR="$SCRIPT_DIR/../fileark-web/target/fileark-web-0.1.0-SNAPSHOT.jar"
PORT=8080
URL="http://localhost:$PORT"
PID_FILE="$HOME/.fileark/server.pid"

if [[ ! -f "$JAR" ]]; then
  echo "缺少 $JAR —— 请先执行:  (cd fileark-ui && npm install && npm run build) && ./mvnw -DskipTests package" >&2
  exit 1
fi

open_browser() {
  if command -v open >/dev/null 2>&1; then open "$URL"
  elif command -v xdg-open >/dev/null 2>&1; then xdg-open "$URL"
  else echo "请在浏览器打开 $URL"; fi
}

if curl -s -m 2 "$URL/api/scans" >/dev/null 2>&1; then
  echo "FileArk 已在运行：$URL"
  open_browser
  exit 0
fi

mkdir -p "$HOME/.fileark"
nohup java -Xmx1g -jar "$JAR" > "$HOME/.fileark/server.log" 2>&1 &
echo $! > "$PID_FILE"
echo "启动中（PID $(cat "$PID_FILE")）..."

for _ in $(seq 1 30); do
  sleep 1
  if curl -s -m 2 "$URL/api/scans" >/dev/null 2>&1; then
    echo "✓ 就绪：${URL}（日志: ~/.fileark/server.log，停止: scripts/stop.sh）"
    open_browser
    exit 0
  fi
done

echo "✗ 60 秒内未就绪，请查看日志: tail -50 ~/.fileark/server.log" >&2
exit 1
