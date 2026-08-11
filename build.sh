#!/usr/bin/env bash
# 构建 TVBox 可加载的 spider jar (内含 classes.dex)
# 需要: JDK 8+ (javac) 与 Android SDK (build-tools 里的 d8 + platform 的 android.jar)
# 本地用: export ANDROID_HOME=/path/to/Sdk 后运行 ./build.sh
# CI 里由 GitHub Actions 自动装好 SDK 再调用本脚本
set -e

OUT_JAR="${OUT_JAR:-zy2.jar}"
ROOT="$(cd "$(dirname "$0")" && pwd)"

# ---- 自动探测 Android SDK ----------------------------------------------
: "${ANDROID_HOME:=${ANDROID_SDK_ROOT:-$HOME/Android/Sdk}}"
if [ ! -d "$ANDROID_HOME" ]; then
  echo "ERROR: 找不到 Android SDK，请设置 ANDROID_HOME"; exit 1
fi
# 取最新的 build-tools 目录
BUILD_TOOLS="$(ls -d "$ANDROID_HOME"/build-tools/*/ 2>/dev/null | sort -V | tail -1)"
# 取最新的 platform 的 android.jar
ANDROID_JAR="$(ls "$ANDROID_HOME"/platforms/*/android.jar 2>/dev/null | sort -V | tail -1)"
D8="${BUILD_TOOLS%/}/d8"; [ -x "$D8" ] || D8="${BUILD_TOOLS%/}/d8.bat"
if [ -z "$BUILD_TOOLS" ] || [ -z "$ANDROID_JAR" ] || [ ! -e "$D8" ]; then
  echo "ERROR: 缺少 build-tools(d8) 或 platform(android.jar)"; exit 1
fi
echo "SDK      = $ANDROID_HOME"
echo "d8       = $D8"
echo "android  = $ANDROID_JAR"
# -----------------------------------------------------------------------

CLASSES="$ROOT/classes"; DEXDIR="$ROOT/dex"
rm -rf "$CLASSES" "$DEXDIR"; mkdir -p "$CLASSES" "$DEXDIR"

echo "==> 1/3 javac 编译 (stub + 源码, 仅用于产出 class)"
find "$ROOT/src" "$ROOT/stub" -name '*.java' > "$ROOT/sources.txt"
javac -source 8 -target 8 -encoding UTF-8 \
      -classpath "$ANDROID_JAR" \
      -d "$CLASSES" @"$ROOT/sources.txt"

echo "==> 2/3 d8 只把业务类转成 dex (不含 stub 基类)"
BIZ_CLASSES=$(find "$CLASSES/com/github/catvod/spider" -name '*.class')
"$D8" --min-api 21 --lib "$ANDROID_JAR" --output "$DEXDIR" $BIZ_CLASSES

echo "==> 3/3 打包 jar (classes.dex)"
( cd "$DEXDIR" && jar cf "$ROOT/$OUT_JAR" classes.dex )

rm -f "$ROOT/sources.txt"
echo "完成: $ROOT/$OUT_JAR"
