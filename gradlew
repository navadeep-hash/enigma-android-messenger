#!/bin/sh
APP_BASE_NAME=`basename "$0"`
APP_HOME=$( cd -- "$( dirname -- "$0" )" >/dev/null 2>&1 && pwd )
DEFAULT_JVM_OPTS="-Xmx64m -Xms64m"
CLASSPATH=$APP_HOME/gradle/wrapper/gradle-wrapper.jar
if [ ! -f "$CLASSPATH" ]; then
    mkdir -p "$APP_HOME/gradle/wrapper"
    curl -sLo "$CLASSPATH" https://raw.githubusercontent.com/gradle/gradle/v8.7.0/gradle/wrapper/gradle-wrapper.jar || \
    wget -qO "$CLASSPATH" https://raw.githubusercontent.com/gradle/gradle/v8.7.0/gradle/wrapper/gradle-wrapper.jar
fi
exec java $DEFAULT_JVM_OPTS -Dorg.gradle.appname=$APP_BASE_NAME -classpath "$CLASSPATH" org.gradle.wrapper.GradleWrapperMain "$@"
