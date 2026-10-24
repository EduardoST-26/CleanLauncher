#!/bin/sh
# Builds the launcher APK without Gradle and installs it on the radio.
set -e
cd "$(dirname "$0")"

SDK=/c/Users/pc/AppData/Local/Android/Sdk
BT=$SDK/build-tools/36.0.0
JAR=$SDK/platforms/android-33/android.jar
JDK="/c/Program Files/Android/Android Studio1/jbr/bin"
export JAVA_HOME="${JDK%/bin}"
ADB=$SDK/platform-tools/adb.exe
RADIO=${RADIO:-192.168.1.15:5555}

rm -rf build && mkdir -p build/gen build/classes build/dex

"$BT/aapt2.exe" compile --dir res -o build/res.zip
"$BT/aapt2.exe" link -I "$JAR" --manifest AndroidManifest.xml \
    --min-sdk-version 25 --target-sdk-version 28 \
    --java build/gen -o build/unsigned.apk build/res.zip

"$JDK/javac.exe" -nowarn --release 8 -encoding UTF-8 \
    -classpath "$JAR" -d build/classes \
    $(find src build/gen -name '*.java')

"$BT/d8.bat" --min-api 25 --lib "$JAR" --output build/dex $(find build/classes -name '*.class')

(cd build/dex && "$JDK/jar.exe" uf ../unsigned.apk classes.dex)
"$BT/zipalign.exe" -f 4 build/unsigned.apk build/aligned.apk
"$BT/apksigner.bat" sign --ks ~/.android/debug.keystore --ks-pass pass:android \
    --out build/CarLauncher.apk build/aligned.apk

echo "APK: build/CarLauncher.apk"

if [ "$1" = "install" ]; then
    "$ADB" -s "$RADIO" install -r build/CarLauncher.apk
fi
