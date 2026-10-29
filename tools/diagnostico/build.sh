#!/bin/sh
# Builds the read-only diagnostics APK -> tools/diagnostico/build/Diagnostico.apk
# Meant for CI (Linux); same requirements and signing variables as ../../build.sh:
#   ANDROID_HOME, BUILD_TOOLS, JAVA_HOME, and optionally KEYSTORE / KEYSTORE_PASS / KEY_ALIAS.
set -e
cd "$(dirname "$0")"

die() { echo "error: $*" >&2; exit 1; }

SDK=${ANDROID_HOME:-$ANDROID_SDK_ROOT}
[ -n "$SDK" ] && [ -d "$SDK" ] || die "set ANDROID_HOME"
JAR=$SDK/platforms/${ANDROID_PLATFORM:-android-33}/android.jar
[ -f "$JAR" ] || die "missing $JAR"
[ -n "$BUILD_TOOLS" ] || BUILD_TOOLS=$(ls "$SDK/build-tools" | sort -t. -k1,1n -k2,2n -k3,3n | tail -n 1)
BT=$SDK/build-tools/$BUILD_TOOLS
[ -n "$JAVA_HOME" ] || JAVA_HOME=$(dirname "$(dirname "$(command -v javac)")")
JDK=$JAVA_HOME/bin

KEYSTORE=${KEYSTORE:-$HOME/.android/debug.keystore}
KEYSTORE_PASS=${KEYSTORE_PASS:-android}
KEY_ALIAS=${KEY_ALIAS:-androiddebugkey}
if [ ! -f "$KEYSTORE" ]; then
    mkdir -p "$(dirname "$KEYSTORE")"
    "$JDK/keytool" -genkeypair -keystore "$KEYSTORE" -storepass "$KEYSTORE_PASS" \
        -keypass "$KEYSTORE_PASS" -alias "$KEY_ALIAS" -keyalg RSA -keysize 2048 -validity 10000 \
        -dname "CN=Android Debug,O=Android,C=US"
fi

rm -rf build && mkdir -p build/classes build/dex

"$BT/aapt2" link -I "$JAR" --manifest AndroidManifest.xml \
    --min-sdk-version 21 --target-sdk-version 22 -o build/unsigned.apk
"$JDK/javac" -nowarn -Xlint:-options --release 8 -encoding UTF-8 \
    -classpath "$JAR" -d build/classes $(find src -name '*.java')
"$BT/d8" --min-api 21 --lib "$JAR" --output build/dex $(find build/classes -name '*.class')
(cd build/dex && "$JDK/jar" uf ../unsigned.apk classes.dex)
"$BT/zipalign" -f 4 build/unsigned.apk build/aligned.apk
"$BT/apksigner" sign --ks "$KEYSTORE" --ks-pass "pass:$KEYSTORE_PASS" --ks-key-alias "$KEY_ALIAS" \
    --out build/Diagnostico.apk build/aligned.apk

echo "APK: tools/diagnostico/build/Diagnostico.apk"
