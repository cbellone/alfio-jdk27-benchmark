#!/bin/sh
# Starts alf.io with the archive/cache of the image. "exec": java gets the SIGTERM of "docker stop".
# ALFIO_STARTUP_OVERRIDE replaces the startup options, e.g. with -XX:AOTCacheOutput for the workload training.
case "$ALFIO_STARTUP" in
  appcds) startup="-XX:SharedArchiveFile=alfio.jsa" ;;
  aot) startup="-XX:AOTCache=alfio.aot" ;;
  *) startup="" ;;
esac
if [ -n "$ALFIO_STARTUP_OVERRIDE" ]; then
  startup="$ALFIO_STARTUP_OVERRIDE"
fi
exec /opt/jdk/bin/java $ALFIO_JVM_OPTS $ALFIO_JAVA_OPTS $ALFIO_PERFORMANCE_OPTS --add-modules jdk.jfr $startup -jar alfio-boot.jar
