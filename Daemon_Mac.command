#!/bin/bash
cd "$(dirname "$0")"
if [ -f "build/libs/daemon-0.0.1-SNAPSHOT.jar" ]; then
    JAR_FILE="build/libs/daemon-0.0.1-SNAPSHOT.jar"
else
    JAR_FILE="daemon.jar"
fi
nohup java -Xms32m -Xmx128m -XX:+UseSerialGC -jar "$JAR_FILE" >/dev/null 2>&1 &
