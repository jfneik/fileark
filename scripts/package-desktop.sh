#!/usr/bin/env bash
# FileArk 桌面打包脚本：用 JDK 自带 jpackage 生成免装 JRE 的本机应用。
# 用法：先完成 README 快速开始的前两步，再执行本脚本。
# 产物：dist/FileArk.app（macOS app-image）或同名 deb/msi（Linux/Windows 需按平台调整 --type）。
set -euo pipefail

APP_VERSION="1.2.0"
OUT="dist"
STAGE="dist/stage"

# 自动取 spring-boot repackage 后的主 jar（*.jar.original 不会匹配该通配）
JAR="$(ls -1 fileark-web/target/fileark-web-*.jar 2>/dev/null | head -1)"

if [[ -z "$JAR" ]]; then
  echo "缺少 fileark-web/target/fileark-web-*.jar，请先执行：./mvnw -DskipTests package" >&2
  exit 1
fi

# 运行期所需模块：spring boot web + sqlite jdbc（含 native lib）+ jackson 等。
# 实测补充过 java.xml / java.logging / jdk.crypto.ec；用 jdeps 可进一步缩减。
MODULES="java.base,java.sql,java.naming,java.management,java.instrument,java.security.jgss,java.security.sasl,java.xml,java.logging,java.desktop,jdk.unsupported,jdk.crypto.ec"

if [[ ! -f "$JAR" ]]; then
  echo "缺少 $JAR，请先执行：./mvnw -DskipTests package" >&2
  exit 1
fi

# 只把主 jar 放进 staging，避免把 repackage 产生的 *.jar.original 一并打进镜像
rm -rf "$STAGE"
mkdir -p "$STAGE"
cp "$JAR" "$STAGE/"

mkdir -p "$OUT"

case "$(uname -s)" in
  Darwin)
    jpackage \
      --type app-image \
      --name FileArk \
      --app-version "$APP_VERSION" \
      --input "$STAGE" \
      --main-jar "$(basename "$JAR")" \
      --java-options "-Xmx1g" \
      --add-modules "$MODULES" \
      --dest "$OUT"
    ;;
  Linux)
    jpackage \
      --type deb \
      --name fileark \
      --app-version "$APP_VERSION" \
      --input "$STAGE" \
      --main-jar "$(basename "$JAR")" \
      --java-options "-Xmx1g" \
      --add-modules "$MODULES" \
      --dest "$OUT"
    ;;
  *)
    echo "当前平台请参考 jpackage 文档调整 --type（msi/exe）。" >&2
    exit 1
    ;;
esac

echo "打包完成：$OUT/"
