#!/bin/sh
# 不用 Gradle：直接用 Android SDK 的 aapt2 / javac / d8 / apksigner 打出 APK。
# 需要：JDK 17+，Android SDK（platforms;android-35 与 build-tools;35.0.0）。
set -eu

VERSION_NAME=0.1.1
VERSION_CODE=2
API=35
BUILD_TOOLS=35.0.0

here=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
root=$(dirname "$here")
SDK=${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/Library/Android/sdk}}
BT="$SDK/build-tools/$BUILD_TOOLS"
JAR="$SDK/platforms/android-$API/android.jar"
if [ -z "${JAVA_HOME:-}" ]; then
  for j in /opt/homebrew/opt/openjdk@21 /opt/homebrew/opt/openjdk@17 /opt/homebrew/opt/openjdk; do
    [ -x "$j/bin/javac" ] && JAVA_HOME=$j && break
  done
fi
[ -n "${JAVA_HOME:-}" ] && [ -x "$JAVA_HOME/bin/javac" ] || { echo "找不到 JDK（需要 17+），请设置 JAVA_HOME" >&2; exit 1; }
export JAVA_HOME
[ -f "$JAR" ] || { echo "缺少 $JAR，先安装 platforms;android-$API" >&2; exit 1; }
[ -x "$BT/aapt2" ] || { echo "缺少 build-tools $BUILD_TOOLS" >&2; exit 1; }

out="$here/build"
rm -rf "$out"
mkdir -p "$out/gen" "$out/classes" "$out/dex" "$root/dist"

"$BT/aapt2" compile --dir "$here/res" -o "$out/res.zip"
"$BT/aapt2" link -o "$out/base.apk" -I "$JAR" \
  --manifest "$here/AndroidManifest.xml" \
  --min-sdk-version 30 --target-sdk-version "$API" \
  --version-code "$VERSION_CODE" --version-name "$VERSION_NAME" \
  --java "$out/gen" "$out/res.zip"

find "$here/src" "$out/gen" -name '*.java' > "$out/sources.txt"
"$JAVA_HOME/bin/javac" -encoding UTF-8 -source 8 -target 8 -Xlint:-options -bootclasspath "$JAR:$BT/core-lambda-stubs.jar" \
  -d "$out/classes" @"$out/sources.txt"

find "$out/classes" -name '*.class' > "$out/classes.txt"
"$BT/d8" --min-api 30 --lib "$JAR" --output "$out/dex" @"$out/classes.txt"

cp "$out/base.apk" "$out/unsigned.apk"
(cd "$out/dex" && zip -q -X "$out/unsigned.apk" classes.dex)
"$BT/zipalign" -f -p 4 "$out/unsigned.apk" "$out/aligned.apk"

# 签名钥匙放在仓库外；同一把钥匙签出来的新版本才能覆盖安装旧版本。
KEYDIR=${ANDROID_USE_KEYDIR:-$HOME/.config/android-use}
KS="$KEYDIR/apk-signing.jks"
if [ ! -f "$KS" ]; then
  mkdir -p "$KEYDIR" && chmod 700 "$KEYDIR"
  "$JAVA_HOME/bin/keytool" -genkeypair -keystore "$KS" -storepass android-use -keypass android-use \
    -alias android-use -keyalg RSA -keysize 2048 -validity 10000 -dname "CN=Android Use" >/dev/null 2>&1
  chmod 600 "$KS"
fi
apk="$root/dist/android-use-$VERSION_NAME.apk"
"$BT/apksigner" sign --ks "$KS" --ks-pass pass:android-use --key-pass pass:android-use \
  --ks-key-alias android-use --out "$apk" "$out/aligned.apk"
"$BT/apksigner" verify "$apk"
rm -f "$apk.idsig"
echo "$apk"
