#!/usr/bin/env sh
##############################################################################
##
## Copyright by the original Gradle source (generated helper script)
##
##############################################################################

APP_HOME="$(cd "$(dirname "$0")" && pwd)"
APP_NAME="$(basename "$0")"
APP_BASE_NAME=${APP_NAME%.sh}

DEFAULT_JVM_OPTS=""
CLASS_PATH="$APP_HOME/gradle/wrapper/gradle-wrapper.jar:$APP_HOME/gradle/wrapper/gradle-wrapper-shared.jar:$APP_HOME/gradle/wrapper/gradle-cli.jar"
WILDFLY_JAVA_OPTS=""

while true; do
  case "$1" in
    --jvmargs)
      shift
      WILDFLY_JAVA_OPTS="$1"
      shift
      ;;
    --no-daemon)
      shift
      ;;
    *)
      break
      ;;
  esac
done

if [ -n "$WILDFLY_JAVA_OPTS" ]; then
  DEFAULT_JVM_OPTS="$DEFAULT_JVM_OPTS $WILDFLY_JAVA_OPTS"
fi

exec java $DEFAULT_JVM_OPTS -classpath "$CLASS_PATH" org.gradle.wrapper.GradleWrapperMain "$@"
