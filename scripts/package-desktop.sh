#!/usr/bin/env bash
# FileArk 桌面打包脚本：用 JDK 自带 jpackage 生成免装 JRE 的本机应用。
# 用法：先完成 README 快速开始的前两步，再执行本脚本。
# 产物：dist/FileArk-1.0.dmg（macOS）或同名 deb/msi（Linux/Windows 需按平台调整 --type）。
set -euo pipefail

APP_VERSION="1.0.0"
JAR="fileark-web/target/fileark-web-0.1.0-SNAPSHOT.jar"
OUT="dist"

# 运行期所需模块：spring boot web + sqlite jdbc（native lib）+ jackson 等，
# 用 jdeps 精确分析过之后可以缩减；这里取常用超集，稳妥优先。
MODULES="java.base,java.sql,java.naming,java.management,java.instrument,java.security.jgss,jdk.unsupported,java.desktop"

if [[ ! -f "$JAR" ]]; then
  echo "缺少 $JAR，请先执行：./mvnw -DskipTests package" >&2
  exit 1
fi

mkdir -p "$OUT"

case "$(uname -s)" in
  Darwin)
    jpackage \
      --type dmg \
      --name FileArk \
      --app-version "$APP_VERSION" \
      --input "$(dirname "$JAR")" \
      --main-jar "$(basename "$JAR")" \
      --java-options "-Xmx512m" \
      --add-modules "$MODULES" \
      --dest "$OUT"
    ;;
  Linux)
    jpackage \
      --type deb \
      --name fileark \
      --app-version "$APP_VERSION" \
      --input "$(dirname "$JAR")" \
      --main-jar "$(basename "$JAR")" \
      --java-options "-Xmx512m" \
      --add-modules "$MODULES" \
      --dest "$OUT"
    ;;
  *)
    echo "当前平台请参考 jpackage 文档调整 --type（msi/exe）。" >&2
    exit 1
    ;;
esac

echo "打包完成：$OUT/"
