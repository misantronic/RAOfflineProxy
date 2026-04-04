#!/bin/sh
# Gradle wrapper script — generated stub. Run ./gradlew to trigger download.
# See https://docs.gradle.org/current/userguide/gradle_wrapper.html

APP_NAME="Gradle"
APP_BASE_NAME=$(basename "$0")
APP_HOME=$(cd "$(dirname "$0")" && pwd)

CLASSPATH="$APP_HOME/gradle/wrapper/gradle-wrapper.jar"

exec java -classpath "$CLASSPATH" org.gradle.wrapper.GradleWrapperMain "$@"
