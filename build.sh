#!/bin/bash
# Build the AppsForMyCar EVCam fork (org.ex2.evcam) outside OneDrive (OneDrive locks build dirs).
# APK: ~/build/evcam/app/build/outputs/apk/release/app-release.apk
set -e
SRC="$(cd "$(dirname "$0")" && pwd)"
DST="${EVCAM_BUILD_DIR:-$HOME/build/evcam}"
mkdir -p "$DST"
(cd "$SRC" && tar --exclude=./app/build --exclude=./.gradle --exclude=./build -cf - .) | (cd "$DST" && tar -xf -)
cd "$DST"
export JAVA_HOME="${JAVA_HOME_17:-$HOME/tools/jdk17}"
echo "sdk.dir=$(cygpath -m "$LOCALAPPDATA/Android/Sdk" 2>/dev/null || echo "$HOME/AppData/Local/Android/Sdk")" > local.properties
"$JAVA_HOME/bin/java" -Xmx64m -cp gradle/wrapper/gradle-wrapper.jar org.gradle.wrapper.GradleWrapperMain ${@:-assembleRelease} --no-daemon
echo "APK: $DST/app/build/outputs/apk/release/app-release.apk"
