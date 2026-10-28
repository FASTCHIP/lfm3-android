#!/usr/bin/env bash
# Сборка APK без Gradle: aapt2 -> javac -> d8 -> zip -> zipalign -> apksigner.
#
# Требуется:
#   JDK 21                                                  (JAVA_HOME)
#   Android SDK: platforms;android-34, build-tools;34.0.0   (ANDROID_SDK)
#   llama.cpp, собранный под Android arm64 (см. README)     (LLAMA_BUILD)
#
# Пример:
#   export JAVA_HOME=/c/jdk/jdk-21
#   export ANDROID_SDK=/c/sdk
#   export LLAMA_BUILD=/c/llama.cpp/build-android
#   bash scripts/build_apk.sh
set -e

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
JAVA_HOME="${JAVA_HOME:?укажите JAVA_HOME (JDK 21)}"
ANDROID_SDK="${ANDROID_SDK:?укажите ANDROID_SDK}"
LLAMA_BUILD="${LLAMA_BUILD:?укажите LLAMA_BUILD — каталог bin с собранным llama.cpp}"

# aapt2/d8/javac — нативные exe: они не понимают MSYS-пути вида /c/..., переводим в C:/...
if command -v cygpath >/dev/null 2>&1; then
  ROOT="$(cygpath -m "$ROOT")"
  JAVA_HOME="$(cygpath -m "$JAVA_HOME")"
  ANDROID_SDK="$(cygpath -m "$ANDROID_SDK")"
  LLAMA_BUILD="$(cygpath -m "$LLAMA_BUILD")"
fi

BT="$ANDROID_SDK/build-tools/34.0.0"
JAR="$ANDROID_SDK/platforms/android-34/android.jar"
JAVAC="$JAVA_HOME/bin/javac"
KEYTOOL="$JAVA_HOME/bin/keytool"
# на Windows в build-tools лежат .bat-обёртки вместо бинарников
if [ -f "$BT/d8.bat" ]; then
  D8="$BT/d8.bat"; SIGNER="$BT/apksigner.bat"; ZIPALIGN="$BT/zipalign.exe"
else
  D8="$BT/d8"; SIGNER="$BT/apksigner"; ZIPALIGN="$BT/zipalign"
fi
[ -x "$BT/aapt2.exe" ] && AAPT2="$BT/aapt2.exe" || AAPT2="$BT/aapt2"

BLD="$ROOT/build"
LIBS="$BLD/libs/arm64-v8a"
mkdir -p "$BLD/classes" "$BLD/dex" "$LIBS"

# движок: llama-server кладём как lib*.so, чтобы Android распаковал его в nativeLibraryDir
for f in libllama.so libggml.so libggml-base.so libggml-cpu.so libllama-common.so \
         libllama-server-impl.so libmtmd.so; do
  cp "$LLAMA_BUILD/$f" "$LIBS/"
done
cp "$LLAMA_BUILD/llama-server" "$LIBS/libllamaserver.so"
chmod 755 "$LIBS/libllamaserver.so"

"$AAPT2" link -o "$BLD/base.apk" -I "$JAR" \
  --manifest "$ROOT/app/AndroidManifest.xml" -A "$ROOT/app/assets" \
  --min-sdk-version 29 --target-sdk-version 34 \
  --version-code "${VERSION_CODE:-1}" --version-name "${VERSION_NAME:-1.0}"

"$JAVAC" -source 17 -target 17 -nowarn -classpath "$JAR" \
  -d "$BLD/classes" $(find "$ROOT/app/src" -name '*.java') 2>&1 \
  | grep -vE "bootstrap class path|deprecat" || true

"$D8" --lib "$JAR" --min-api 29 --output "$BLD/dex" $(find "$BLD/classes" -name '*.class')

python - "$BLD/base.apk" "$BLD/dex/classes.dex" "$BLD/libs" <<'PY'
import sys, zipfile, os
apk, dex, libs = sys.argv[1], sys.argv[2], sys.argv[3]
with zipfile.ZipFile(apk, 'a', zipfile.ZIP_DEFLATED) as z:
    z.write(dex, 'classes.dex')
    for root, _, files in os.walk(libs):
        for f in files:
            p = os.path.join(root, f)
            z.write(p, 'lib/' + os.path.relpath(p, libs).replace('\\', '/'))
PY

"$ZIPALIGN" -f -p 4 "$BLD/base.apk" "$BLD/aligned.apk"
KS="$ROOT/debug.keystore"
if [ ! -f "$KS" ]; then
  "$KEYTOOL" -genkeypair -keystore "$KS" -storepass android -keypass android \
    -alias app -keyalg RSA -keysize 2048 -validity 10000 -dname "CN=LFM3" >/dev/null 2>&1
fi
"$SIGNER" sign --ks "$KS" --ks-pass pass:android --key-pass pass:android \
  --out "$BLD/LFM3.apk" "$BLD/aligned.apk"
"$SIGNER" verify "$BLD/LFM3.apk"
ls -lh "$BLD/LFM3.apk"