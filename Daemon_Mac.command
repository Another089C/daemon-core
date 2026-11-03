#!/bin/bash
WORK_DIR="$(cd "$(dirname "$0")" && pwd)"
cd "$WORK_DIR"

# Limpiar cualquier archivo del Escritorio de la Mac
rm -f "$HOME/Desktop/Daemon AI.command" "$HOME/Desktop/Daemon_Mac.command"

if [ -f "$WORK_DIR/build/libs/daemon-0.0.1-SNAPSHOT.jar" ]; then
    JAR_FILE="$WORK_DIR/build/libs/daemon-0.0.1-SNAPSHOT.jar"
else
    JAR_FILE="$WORK_DIR/daemon.jar"
fi

JAVA_BIN="$(which java)"
if [ -w "/Applications" ]; then
    APP_DIR="/Applications/Daemon AI.app"
else
    mkdir -p "$HOME/Applications"
    APP_DIR="$HOME/Applications/Daemon AI.app"
fi

mkdir -p "$APP_DIR/Contents/MacOS"
cat <<EOF > "$APP_DIR/Contents/Info.plist"
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
    <key>CFBundleExecutable</key>
    <string>DaemonAI</string>
    <key>CFBundleIdentifier</key>
    <string>com.cristian.daemon.ai</string>
    <key>CFBundleName</key>
    <string>Daemon AI</string>
    <key>CFBundlePackageType</key>
    <string>APPL</string>
    <key>LSUIElement</key>
    <true/>
</dict>
</plist>
EOF

cat <<EOF > "$APP_DIR/Contents/MacOS/DaemonAI"
#!/bin/bash
cd "$WORK_DIR"
pgrep -f "daemon-0.0.1-SNAPSHOT.jar|daemon.jar" >/dev/null && exit 0
exec "$JAVA_BIN" -Xms32m -Xmx128m -XX:+UseSerialGC -jar "$JAR_FILE"
EOF
chmod +x "$APP_DIR/Contents/MacOS/DaemonAI"

# Configurar Inicio Automatico al prender la Mac (LaunchAgents)
mkdir -p "$HOME/Library/LaunchAgents"
cat <<EOF > "$HOME/Library/LaunchAgents/com.cristian.daemon.ai.plist"
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
    <key>Label</key>
    <string>com.cristian.daemon.ai</string>
    <key>ProgramArguments</key>
    <array>
        <string>$APP_DIR/Contents/MacOS/DaemonAI</string>
    </array>
    <key>WorkingDirectory</key>
    <string>$WORK_DIR</string>
    <key>RunAtLoad</key>
    <true/>
</dict>
</plist>
EOF

open "$APP_DIR"
