#!/usr/bin/env bash
# Rebuilds Duo Launcher with the iPhone-style App Library page.
#
# Needs: java 17+, python3, and in $TOOLS:
#   apktool.jar, jadx/lib/jadx-*-all.jar (provides D8 + apksig),
#   android-all.jar (Robolectric android-all, used as android.jar), sign/SignApk.class
#
# Usage: mod/build.sh [work-dir]
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
TOOLS="${TOOLS:-/opt/tools}"
WORK="${1:-$ROOT/build}"
APK_IN="$ROOT/original/DuoLauncher-0.15.0-beta01-release.apk"
APK_OUT="$ROOT/dist/DuoLauncher-0.15.0-beta01-applibrary.apk"
JADX_JAR="$(ls "$TOOLS"/jadx/lib/jadx-*-all.jar | head -1)"
ANDROID_JAR="$TOOLS/android-all.jar"
KEYSTORE="${KEYSTORE:-$TOOLS/sign/duo.p12}"
KEYPASS="${KEYPASS:-duolauncher}"

rm -rf "$WORK" && mkdir -p "$WORK"

echo "== decode"
java -jar "$TOOLS/apktool.jar" d -f "$APK_IN" -o "$WORK/dec" >/dev/null

echo "== compile mod"
mkdir -p "$WORK/stubs" "$WORK/classes" "$WORK/dex"
javac -nowarn -source 11 -target 11 -cp "$ANDROID_JAR" -d "$WORK/stubs" \
  $(find "$ROOT/mod/stubs" -name '*.java') 2>&1 | grep -v '^warning' || true
javac -nowarn -source 11 -target 11 -cp "$ANDROID_JAR:$WORK/stubs" -d "$WORK/classes" \
  $(find "$ROOT/mod/src" -name '*.java') 2>&1 | grep -v '^warning' || true
test -f "$WORK/classes/com/jake/duolauncher/applib/AppLibraryView.class"
(cd "$WORK/stubs" && jar cf ../stubs.jar .)
java -cp "$JADX_JAR" com.android.tools.r8.D8 --release --min-api 31 \
  --lib "$ANDROID_JAR" --classpath "$WORK/stubs.jar" --output "$WORK/dex" \
  $(find "$WORK/classes" -name '*.class')

echo "== hook library page (G1/s5 -> AppLibrary.content)"
python3 -I "$ROOT/mod/patch.py" "$WORK/dec"

echo "== build, align, sign"
java -jar "$TOOLS/apktool.jar" b "$WORK/dec" -o "$WORK/unsigned.apk" >/dev/null
# Add the mod as a second dex (the app's own code stays in classes.dex).
python3 -I -c 'import sys,zipfile; z=zipfile.ZipFile(sys.argv[1],"a"); z.write(sys.argv[2],"classes2.dex",zipfile.ZIP_STORED); z.close()' \
  "$WORK/unsigned.apk" "$WORK/dex/classes.dex"
python3 -I "$ROOT/mod/zipalign.py" "$WORK/unsigned.apk" "$WORK/aligned.apk"
mkdir -p "$(dirname "$APK_OUT")"
java -cp "$JADX_JAR:$TOOLS/sign" SignApk "$KEYSTORE" "$KEYPASS" "$WORK/aligned.apk" "$APK_OUT"
echo "== done: $APK_OUT"
