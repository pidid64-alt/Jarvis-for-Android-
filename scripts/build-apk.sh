#!/usr/bin/env bash
# ============================================================
#  Оффлайн-сборка APK «Джарвис» БЕЗ Gradle и БЕЗ Android SDK.
#  Тулчейн берётся из окружения (см. scripts/fetch-toolchain.sh):
#    JARVIS_JAVA      — путь к Java 17+ (bin/java)
#    JARVIS_BT        — build-tools (bin/aapt2, bin/zipalign, lib/d8.jar, lib/apksigner.jar)
#    JARVIS_ANDROID_JAR — android.jar (API 26+)
#    JARVIS_KOTLIN_JAR — fat-jar с kotlin-compiler (класс org.jetbrains.kotlin.cli.jvm.K2JVMCompiler)
# ============================================================
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"

JAVA_BIN="${JARVIS_JAVA:-/opt/jdk/jdk4py/java-runtime/bin/java}"
BT="${JARVIS_BT:-/opt/psdk/tools/linux}"
AJAR="${JARVIS_ANDROID_JAR:-/opt/psdk/34/public/android.jar}"
KJAR="${JARVIS_KOTLIN_JAR:-/opt/detekt/detekt-cli-1.23.8/lib/detekt-cli-1.23.8-all.jar}"

OUT="$ROOT/app/build/offline"
RELEASES="$ROOT/releases"
ABI_MIN=26

echo "==> [1/7] aapt2: компиляция ресурсов"
rm -rf "$OUT"
mkdir -p "$OUT/classes" "$OUT/dex" "$OUT/gen" "$RELEASES"
"$JAVA_BIN" -cp "$BT/lib/d8.jar" com.android.tools.r8.A2 \
  compile --dir "$ROOT/app/res" -o "$OUT/resources.zip" 2>/dev/null \
  || "$BT/bin/aapt2" compile --dir "$ROOT/app/res" -o "$OUT/resources.zip"

echo "==> [2/8] aapt2: линковка ресурсов + Manifest"
"$BT/bin/aapt2" link \
  -o "$OUT/base.apk" \
  -I "$AJAR" \
  --manifest "$ROOT/app/AndroidManifest.xml" \
  -R "$OUT/resources.zip" \
  --emit-ids "$OUT/ids.txt" \
  --auto-add-overlay

echo "==> [3/8] генерация R.kt из карты ресурсов"
python3 - "$OUT/ids.txt" "$OUT/gen/R.kt" <<'PY'
import sys
ids_path, out_path = sys.argv[1], sys.argv[2]
groups = {}
for line in open(ids_path, encoding="utf-8"):
    line = line.strip()
    if not line or "=" not in line:
        continue
    name, val = [p.strip() for p in line.split("=", 1)]
    if ":" in name:                      # формат: pkg:type/name
        name = name.split(":", 1)[1]
    typ, _, nm = name.partition("/")
    nm = nm.replace(".", "_")
    groups.setdefault(typ, []).append((nm, val))
with open(out_path, "w", encoding="utf-8") as f:
    f.write("package kz.jarvis.app\n\n// Автогенерировано scripts/build-apk.sh — не редактировать\nobject R {\n")
    for typ, items in groups.items():
        f.write(f"    object {typ} {{\n")
        for nm, val in items:
            f.write(f"        val {nm}: Int = {val}.toInt()\n")
        f.write("    }\n")
    f.write("}\n")
print("R-класс:", ", ".join(f"{k}({len(v)})" for k, v in groups.items()))
PY

echo "==> [4/8] kotlinc: компиляция Kotlin (это займёт ~минуту)"
CP="$AJAR:$KJAR"
SRC_FILES=$(find "$ROOT/app/src" -name '*.kt' | sort; echo "$OUT/gen/R.kt")
JAVA_TOOL_OPTIONS="-Dfile.encoding=UTF-8" "$JAVA_BIN" -Xmx1g -cp "$KJAR" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler \
  -no-jdk -nowarn -jvm-target 1.8 \
  -classpath "$CP" \
  -d "$OUT/classes" $SRC_FILES 2>&1 | grep -v "^WARNING" | grep -v "jansi" | grep -v "native" || true

if [ -z "$(find "$OUT/classes" -name '*.class')" ]; then
  echo "ОШИБКА: компиляция не дала class-файлов" >&2; exit 1
fi

echo "==> [4/7] дексирование (d8 + kotlin-stdlib)"
STDLIB="$OUT/kotlin-stdlib.jar"
python3 - "$KJAR" "$STDLIB" <<'PY'
import sys, zipfile
src, dst = sys.argv[1], sys.argv[2]
pref = ("kotlin/", "org/jetbrains/annotations/", "META-INF/kotlin-stdlib")
with zipfile.ZipFile(src) as zi, zipfile.ZipFile(dst, "w", zipfile.ZIP_DEFLATED) as zo:
    for i in zi.infolist():
        if i.filename.startswith(pref):
            zo.writestr(i, zi.read(i.filename))
PY
CLSFILES=$(find "$OUT/classes" -name '*.class')
"$JAVA_BIN" -Xmx1g -cp "$BT/lib/d8.jar" com.android.tools.r8.D8 \
  --release --lib "$AJAR" --min-api $ABI_MIN \
  --output "$OUT/dex" $CLSFILES "$STDLIB"

echo "==> [5/7] сборка неподписанного APK"
python3 - "$OUT/base.apk" "$OUT/dex" "$OUT/unsigned.apk" <<'PY'
import sys, zipfile
base, dexdir, out = sys.argv[1], sys.argv[2], sys.argv[3]
import os
with zipfile.ZipFile(base) as zi, zipfile.ZipFile(out, "w", zipfile.ZIP_DEFLATED) as zo:
    for i in zi.infolist():
        zo.writestr(i, zi.read(i.filename))
    n = 1
    for f in sorted(os.listdir(dexdir)):
        if f.endswith(".dex"):
            with open(os.path.join(dexdir, f), "rb") as fh:
                zo.writestr(f, fh.read())
            n += 1
print("dex-файлов:", n - 1)
PY

echo "==> [6/7] zipalign"
"$BT/bin/zipalign" -f 4 "$OUT/unsigned.apk" "$OUT/aligned.apk"

echo "==> [8/8] подпись (debug-ключ)"
KS_DIR="$ROOT/keystore"; KS="$KS_DIR/jarvis.jks"
if [ ! -f "$KS" ]; then
  mkdir -p "$KS_DIR"
  "$JAVA_BIN" sun.security.tools.keytool.Main -genkeypair -keystore "$KS" \
    -storepass jarvis25 -keypass jarvis25 -alias jarvis \
    -keyalg RSA -keysize 2048 -validity 10950 \
    -dname "CN=Jarvis Debug, O=Jarvis, C=KZ" 2>/dev/null \
  || "$JAVA_BIN" -cp "$KJAR" sun.security.tools.keytool.Main -genkeypair -keystore "$KS" \
    -storepass jarvis25 -keypass jarvis25 -alias jarvis \
    -keyalg RSA -keysize 2048 -validity 10950 \
    -dname "CN=Jarvis Debug, O=Jarvis, C=KZ"
  echo "Создан новый ключ: $KS"
fi
"$JAVA_BIN" -cp "$BT/lib/apksigner.jar" com.android.apksigner.ApkSignerTool sign \
  --ks "$KS" --ks-pass pass:jarvis25 --ks-key-alias jarvis \
  --out "$RELEASES/Jarvis-v1.0.apk" "$OUT/aligned.apk"
"$JAVA_BIN" -cp "$BT/lib/apksigner.jar" com.android.apksigner.ApkSignerTool verify --print-certs "$RELEASES/Jarvis-v1.0.apk" | head -3

echo ""
echo "✅ ГОТОВО: $RELEASES/Jarvis-v1.0.apk"
ls -la "$RELEASES/Jarvis-v1.0.apk"
