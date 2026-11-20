#!/usr/bin/env bash
# Build manual PRS Auto tanpa Gradle (dipakai saat jaringan Gradle bermasalah).
# Alat: kotlinc standalone + Android SDK build-tools (aapt2, d8, zipalign, apksigner).
# Hasil: build-manual/prs-auto-debug.apk (sudah di-align + di-sign debug).
set -euo pipefail

ROOT="$(cd "$(dirname "$0")" && pwd)"
SDK="${ANDROID_HOME:-$HOME/android-sdk}"
BT="$SDK/build-tools/34.0.0"
PLATFORM="$SDK/platforms/android-34/android.jar"
KOTLINC="${KOTLINC:-$HOME/kotlin/kotlinc/bin/kotlinc}"
STDLIB="$HOME/kotlin/kotlinc/lib/kotlin-stdlib.jar"
OUT="$ROOT/build-manual"

echo "== 1. compile resources (aapt2 compile) =="
rm -rf "$OUT"
mkdir -p "$OUT/compiled_res" "$OUT/gen" "$OUT/classes" "$OUT/dex"
"$BT/aapt2" compile --dir "$ROOT/app/src/main/res" -o "$OUT/compiled_res.zip"

echo "== 2. link resources + manifest (aapt2 link) =="
"$BT/aapt2" link -o "$OUT/app-unsigned.apk" \
  --manifest "$ROOT/app/src/main/AndroidManifest.xml" \
  -I "$PLATFORM" \
  --java "$OUT/gen" \
  --auto-add-overlay \
  --min-sdk-version 26 --target-sdk-version 34 \
  --version-code 1 --version-name 1.0.0 \
  "$OUT/compiled_res.zip"

echo "== 3. compile Kotlin + R.java (kotlinc) =="
"$KOTLINC" -classpath "$PLATFORM" \
  -jvm-target 17 \
  -d "$OUT/classes" \
  "$OUT/gen/com/luna/prsauto/R.java" \
  "$ROOT/app/src/main/java/com/luna/prsauto/"*.kt

echo "== 4. dex (d8) =="
"$BT/d8" --output "$OUT/dex" --lib "$PLATFORM" \
  --min-api 26 \
  $(find "$OUT/classes" -name '*.class') "$STDLIB"

echo "== 5. paketkan classes.dex ke APK =="
cp "$OUT/app-unsigned.apk" "$OUT/app-aligned.apk"
python3 - "$OUT/app-aligned.apk" "$OUT/dex/classes.dex" <<'PY'
import sys, zipfile
apk, dex = sys.argv[1], sys.argv[2]
with zipfile.ZipFile(apk, 'a', zipfile.ZIP_DEFLATED) as z:
    z.write(dex, 'classes.dex')
print("added classes.dex")
PY

echo "== 6. zipalign =="
"$BT/zipalign" -f 4 "$OUT/app-aligned.apk" "$OUT/prs-auto-debug.apk"

echo "== 7. keystore debug + sign (apksigner) =="
if [ ! -f "$OUT/debug.keystore" ]; then
  keytool -genkeypair -keystore "$OUT/debug.keystore" -alias androiddebugkey \
    -keyalg RSA -keysize 2048 -validity 10000 \
    -storepass android -keypass android \
    -dname "CN=Android Debug,O=Android,C=US" >/dev/null 2>&1
fi
"$BT/apksigner" sign --ks "$OUT/debug.keystore" --ks-pass pass:android \
  --key-pass pass:android --ks-key-alias androiddebugkey \
  --out "$OUT/prs-auto-debug-signed.apk" "$OUT/prs-auto-debug.apk"
mv "$OUT/prs-auto-debug-signed.apk" "$OUT/prs-auto-debug.apk"

echo "== 8. verifikasi =="
"$BT/apksigner" verify --print-certs "$OUT/prs-auto-debug.apk"
"$BT/aapt2" dump badging "$OUT/prs-auto-debug.apk" | head -12
ls -la "$OUT/prs-auto-debug.apk"
echo "SELESAI: $OUT/prs-auto-debug.apk"
