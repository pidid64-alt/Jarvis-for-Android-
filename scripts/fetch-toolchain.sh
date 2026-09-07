#!/usr/bin/env bash
# Скачивает тулчейн для оффлайн-сборки из публичных Git-зеркал (работает даже там,
# где заблокированы Maven Central / Google Maven):
#   1. OpenJDK (Temurin 17)      — пакет jdk4py с PyPI
#   2. Android build-tools       — зеркало AOSP prebuilts на GitHub
#   3. android.jar (API 34)      — то же зеркало
#   4. kotlin-compiler (fat-jar) — копия дистрибутива detekt-cli на GitHub
# После запуска скрипта становится доступен scripts/build-apk.sh
set -euo pipefail
OPT=/opt
mkdir -p $OPT 2>/dev/null || { echo "Нужен доступ к $OPT (sudo mkdir -p $OPT && sudo chown \$USER $OPT)"; exit 1; }

echo "==> [1/4] Java 17 (jdk4py с PyPI)"
mkdir -p $OPT/jdk && cd /tmp
pip download jdk4py==17.0.9.2 --no-deps -d /tmp/jdk4py-dl -q || pip download jdk4py --no-deps -d /tmp/jdk4py-dl -q
unzip -q -o /tmp/jdk4py-dl/jdk4py-*.whl -d $OPT/jdk
export JAVA_HOME=$OPT/jdk/jdk4py/java-runtime
$JAVA_HOME/bin/java -version

echo "==> [2/4] Android build-tools + android.jar (зеркало AOSP)"
git clone --depth 1 --filter=blob:none --sparse https://github.com/msft-mirror-aosp/platform.prebuilts.sdk $OPT/psdk
cd $OPT/psdk
git sparse-checkout set --no-cone '/tools/linux/**' '/34/public/android.jar'
git checkout HEAD

echo "==> [3/4] Kotlin-compiler fat-jar"
git clone --depth 1 --filter=blob:none --sparse https://github.com/gabrielhuav/PolitecnicoOpenWorld $OPT/detekt
cd $OPT/detekt
git sparse-checkout set --no-cone '/detekt-cli-1.23.8/lib/detekt-cli-1.23.8-all.jar'
git checkout HEAD

echo "==> [4/4] Проверка"
$JAVA_HOME/bin/java -cp $OPT/psdk/tools/linux/lib/d8.jar com.android.tools.r8.D8 --version | head -1
$OPT/psdk/tools/linux/bin/aapt2 version 2>&1 | head -1
ls -la $OPT/psdk/34/public/android.jar $OPT/detekt/detekt-cli-1.23.8/lib/detekt-cli-1.23.8-all.jar
echo "Готово. Теперь: scripts/build-apk.sh"
