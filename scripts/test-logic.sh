#!/usr/bin/env bash
# ============================================================
#  Быстрые тесты «чистой» логики (без Android-эмулятора).
#  Компилируются те же самые исходники, что попадают в APK:
#    app/src/kz/jarvis/app/{MathEngine,WakeWords,TimeParse,Timezones,
#                           Units,LifeCalc,DateFacts,TextTools,Randoms,
#                           Persona,Providers,LlmRequests,VoiceProfile,
#                           ResearchPlan,Deck,WebSearch}.kt
#  + тестовая обёртка tools/LogicTest.kt. Выполняется на JVM.
#
#  Требуется тот же тулчейн, что и для scripts/build-apk.sh
#  (см. scripts/fetch-toolchain.sh).
# ============================================================
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"

JAVA_BIN="${JARVIS_JAVA:-/opt/jdk/jdk4py/java-runtime/bin/java}"
AJAR="${JARVIS_ANDROID_JAR:-/opt/psdk/34/public/android.jar}"
KJAR="${JARVIS_KOTLIN_JAR:-/opt/detekt/detekt-cli-1.23.8/lib/detekt-cli-1.23.8-all.jar}"

OUT="$ROOT/app/build/logictest"
rm -rf "$OUT"; mkdir -p "$OUT/classes"

echo "==> [1/3] извлекаю kotlin-stdlib из тулчейна"
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

echo "==> [2/3] kotlinc: чистая логика + LogicTest"
JAVA_TOOL_OPTIONS="-Dfile.encoding=UTF-8" "$JAVA_BIN" -Xmx1g -cp "$KJAR" \
  org.jetbrains.kotlin.cli.jvm.K2JVMCompiler \
  -no-jdk -nowarn -jvm-target 1.8 \
  -classpath "$AJAR:$OUT/kotlin-stdlib.jar" \
  -d "$OUT/classes" \
  "$ROOT/app/src/kz/jarvis/app/MathEngine.kt" \
  "$ROOT/app/src/kz/jarvis/app/WakeWords.kt" \
  "$ROOT/app/src/kz/jarvis/app/TimeParse.kt" \
  "$ROOT/app/src/kz/jarvis/app/Timezones.kt" \
  "$ROOT/app/src/kz/jarvis/app/Units.kt" \
  "$ROOT/app/src/kz/jarvis/app/LifeCalc.kt" \
  "$ROOT/app/src/kz/jarvis/app/DateFacts.kt" \
  "$ROOT/app/src/kz/jarvis/app/TextTools.kt" \
  "$ROOT/app/src/kz/jarvis/app/Randoms.kt" \
  "$ROOT/app/src/kz/jarvis/app/Persona.kt" \
  "$ROOT/app/src/kz/jarvis/app/Providers.kt" \
  "$ROOT/app/src/kz/jarvis/app/LlmRequests.kt" \
  "$ROOT/app/src/kz/jarvis/app/VoiceProfile.kt" \
  "$ROOT/app/src/kz/jarvis/app/ResearchPlan.kt" \
  "$ROOT/app/src/kz/jarvis/app/Deck.kt" \
  "$ROOT/app/src/kz/jarvis/app/WebSearch.kt" \
  "$ROOT/tools/LogicTest.kt" 2>&1 | grep -v "^WARNING" | grep -v "jansi" | grep -v "native" || true

if [ -z "$(find "$OUT/classes" -name '*.class')" ]; then
  echo "ОШИБКА: компиляция не дала class-файлов" >&2; exit 1
fi

echo "==> [3/3] запуск тестов"
"$JAVA_BIN" -Dfile.encoding=UTF-8 -cp "$OUT/classes:$OUT/kotlin-stdlib.jar" kz.jarvis.tools.LogicTestKt
