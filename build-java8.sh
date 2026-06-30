#!/usr/bin/env bash
set -euo pipefail

PROJECT_DIR="$(cd "$(dirname "$0")" && pwd)"
cd "$PROJECT_DIR"

JAVA_VER_LINE="$(java -version 2>&1 | head -n 1)"
JAVAC_VER_LINE="$(javac -version 2>&1 | head -n 1)"

if ! echo "$JAVA_VER_LINE" | grep -Eq '"1\.8(\.|_|")|"8(\.|")'; then
  echo "[ERROR] Current java is not Java 8: $JAVA_VER_LINE"
  exit 1
fi

if ! echo "$JAVAC_VER_LINE" | grep -Eq 'javac (1\.8(\.|_)|8\.)'; then
  echo "[ERROR] Current javac is not Java 8: $JAVAC_VER_LINE"
  exit 1
fi

echo "[INFO] Java check passed."
echo "[INFO] $JAVA_VER_LINE"
echo "[INFO] $JAVAC_VER_LINE"

rm -rf out target
mkdir -p out/classes target

javac -encoding UTF-8 -source 1.8 -target 1.8 -d out/classes $(find src/main/java -name '*.java')
cp -R src/main/resources/* out/classes/

cat > out/MANIFEST.MF <<'EOF'
Manifest-Version: 1.0
Main-Class: com.ossfilebrowse.plus.Main
EOF

jar cfm target/OSSFileBrowse-plus.jar out/MANIFEST.MF -C out/classes .

echo "[OK] Build success: target/OSSFileBrowse-plus.jar"
