#!/bin/sh

# Cortex Gradle Wrapper
# Licensed under Apache 2.0

set -e

APP_BASE_NAME=`basename "$0"`
APP_HOME=`cd "\`dirname \"$0\"\`" > /dev/null && pwd`

if [ -f "$APP_HOME/gradle/wrapper/gradle-wrapper.jar" ]; then
    CLASSPATH="$APP_HOME/gradle/wrapper/gradle-wrapper.jar"
    exec java -Xmx64m -Dorg.gradle.appname="$APP_BASE_NAME" -classpath "$CLASSPATH" org.gradle.wrapper.GradleWrapperMain "$@"
elif command -v gradle >/dev/null 2>&1; then
    exec gradle "$@"
else
    echo "Gradle not found. Please install Gradle or download gradle-wrapper.jar" >&2
    exit 1
fi
