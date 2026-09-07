#!/usr/bin/env bash
# ============================================================
#  Интеграционные тесты диспетчера команд: компилируется ВЕСЬ код
#  приложения (включая CommandEngine и сгенерированный R-класс) и на
#  обычной JVM прогоняется настоящий CommandEngine.execute().
#
#  Это ловит конфликты между разделами: «раздели 100 на 4» должно
#  остаться арифметикой, а «раздели 9000 на троих» — делением счёта.
#
#  Быстрые тесты чистой логики — в scripts/test-logic.sh.
#  Требуется тот же тулчейн, что и для scripts/build-apk.sh.
# ============================================================
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"

JAVA_BIN="${JARVIS_JAVA:-/opt/jdk/jdk4py/java-runtime/bin/java}"
BT="${JARVIS_BT:-/opt/psdk/tools/linux}"
AJAR="${JARVIS_ANDROID_JAR:-/opt/psdk/34/public/android.jar}"
KJAR="${JARVIS_KOTLIN_JAR:-/opt/detekt/detekt-cli-1.23.8/lib/detekt-cli-1.23.8-all.jar}"

OUT="$ROOT/app/build/commandtest"
rm -rf "$OUT"; mkdir -p "$OUT/classes" "$OUT/gen"

echo "==> [1/4] извлекаю kotlin-stdlib из тулчейна"
python3 - "$KJAR" "$OUT/kotlin-stdlib.jar" <<'PY'
import sys, zipfile
src, dst = sys.argv[1], sys.argv[2]
pref = ("kotlin/", "org/jetbrains/annotations/", "META-INF/kotlin-stdlib")
with zipfile.ZipFile(src) as zi, zipfile.ZipFile(dst, "w", zipfile.ZIP_DEFLATED) as zo:
    for i in zi.infolist():
        if i.filename.startswith(pref):
            zo.writestr(i, zi.read(i.filename))
print("ok")
PY

echo "==> [2/4] aapt2: ресурсы (нужны, чтобы сгенерировать R-класс)"
"$JAVA_BIN" -cp "$BT/lib/d8.jar" com.android.tools.r8.A2 \
  compile --dir "$ROOT/app/res" -o "$OUT/resources.zip" 2>/dev/null \
  || "$BT/bin/aapt2" compile --dir "$ROOT/app/res" -o "$OUT/resources.zip"
"$BT/bin/aapt2" link -o "$OUT/base.apk" -I "$AJAR" \
  --manifest "$ROOT/app/AndroidManifest.xml" -R "$OUT/resources.zip" \
  --emit-ids "$OUT/ids.txt" --auto-add-overlay

python3 - "$OUT/ids.txt" "$OUT/gen/R.kt" <<'PY'
import sys
ids_path, out_path = sys.argv[1], sys.argv[2]
groups = {}
for line in open(ids_path, encoding="utf-8"):
    line = line.strip()
    if not line or "=" not in line:
        continue
    name, val = [p.strip() for p in line.split("=", 1)]
    if ":" in name:
        name = name.split(":", 1)[1]
    typ, _, nm = name.partition("/")
    nm = nm.replace(".", "_")
    groups.setdefault(typ, []).append((nm, val))
with open(out_path, "w", encoding="utf-8") as f:
    f.write("package kz.jarvis.app\n\n// Автогенерировано scripts/test-commands.sh\nobject R {\n")
    for typ, items in groups.items():
        f.write(f"    object {typ} {{\n")
        for nm, val in items:
            f.write(f"        val {nm}: Int = {val}.toInt()\n")
        f.write("    }\n")
    f.write("}\n")
print("R-класс:", ", ".join(f"{k}({len(v)})" for k, v in groups.items()))
PY

echo "==> [3/4] kotlinc: весь код приложения + CommandTest (это займёт ~минуту)"
SRC_FILES=$(find "$ROOT/app/src" -name '*.kt' | sort; echo "$OUT/gen/R.kt"; echo "$ROOT/tools/CommandTest.kt")
JAVA_TOOL_OPTIONS="-Dfile.encoding=UTF-8" "$JAVA_BIN" -Xmx1g -cp "$KJAR" \
  org.jetbrains.kotlin.cli.jvm.K2JVMCompiler \
  -no-jdk -nowarn -jvm-target 1.8 \
  -classpath "$AJAR:$OUT/kotlin-stdlib.jar" \
  -d "$OUT/classes" $SRC_FILES 2>&1 | grep -v "^WARNING" | grep -v "jansi" | grep -v "native" || true

if [ -z "$(find "$OUT/classes" -name 'CommandTestKt.class')" ]; then
  echo "ОШИБКА: компиляция не дала CommandTestKt.class" >&2; exit 1
fi

echo "==> [4/4] запуск тестов диспетчера команд"
"$JAVA_BIN" -Dfile.encoding=UTF-8 -cp "$OUT/classes:$OUT/kotlin-stdlib.jar:$AJAR" \
  kz.jarvis.tools.CommandTestKt
