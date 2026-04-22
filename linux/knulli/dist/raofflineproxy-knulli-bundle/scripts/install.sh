#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
BASE_DIR="/userdata/system/raofflineproxy"
APP_DIR="${BASE_DIR}/app"
BIN_DIR="${BASE_DIR}/bin"
TOOLS_DIR="/userdata/roms/tools"

mkdir -p "${APP_DIR}"
mkdir -p "${BIN_DIR}"
mkdir -p "${TOOLS_DIR}"

cp -r "${SCRIPT_DIR}/app/"* "${APP_DIR}/"

cp "${SCRIPT_DIR}/scripts/launcher-raofflineproxy" "${BIN_DIR}/raofflineproxy"
cp "${SCRIPT_DIR}/scripts/launcher-raofflineproxy-ui" "${BIN_DIR}/raofflineproxy-ui"
cp "${SCRIPT_DIR}/scripts/launcher-raofflineproxy-start" "${BIN_DIR}/raofflineproxy-start"
cp "${SCRIPT_DIR}/scripts/launcher-raofflineproxy-stop" "${BIN_DIR}/raofflineproxy-stop"
cp "${SCRIPT_DIR}/scripts/launcher-raofflineproxy-status" "${BIN_DIR}/raofflineproxy-status"

chmod +x "${BIN_DIR}/raofflineproxy"
chmod +x "${BIN_DIR}/raofflineproxy-ui"
chmod +x "${BIN_DIR}/raofflineproxy-start"
chmod +x "${BIN_DIR}/raofflineproxy-stop"
chmod +x "${BIN_DIR}/raofflineproxy-status"

cat > "${TOOLS_DIR}/RAOfflineProxy Start.sh" <<'EOF'
#!/bin/sh
exec /userdata/system/raofflineproxy/bin/raofflineproxy-ui start
EOF

cat > "${TOOLS_DIR}/RAOfflineProxy Stop.sh" <<'EOF'
#!/bin/sh
exec /userdata/system/raofflineproxy/bin/raofflineproxy-ui stop
EOF

cat > "${TOOLS_DIR}/RAOfflineProxy Status.sh" <<'EOF'
#!/bin/sh
exec /userdata/system/raofflineproxy/bin/raofflineproxy-ui status
EOF

chmod +x "${TOOLS_DIR}/RAOfflineProxy Start.sh"
chmod +x "${TOOLS_DIR}/RAOfflineProxy Stop.sh"
chmod +x "${TOOLS_DIR}/RAOfflineProxy Status.sh"

cat > "${TOOLS_DIR}/gamelist.xml" <<'EOF'
<?xml version="1.0"?>
<gameList>
	<game>
		<path>./RAOfflineProxy Start.sh</path>
		<name>RAOfflineProxy Start</name>
		<desc>Patch RetroArch config to use the local achievements proxy.</desc>
		<genre>Settings</genre>
		<lang>en</lang>
	</game>
	<game>
		<path>./RAOfflineProxy Stop.sh</path>
		<name>RAOfflineProxy Stop</name>
		<desc>Revert the RetroArch proxy patch.</desc>
		<genre>Settings</genre>
		<lang>en</lang>
	</game>
	<game>
		<path>./RAOfflineProxy Status.sh</path>
		<name>RAOfflineProxy Status</name>
		<desc>Show current RetroArch proxy patch status.</desc>
		<genre>Settings</genre>
		<lang>en</lang>
	</game>
</gameList>
EOF

echo "RAOfflineProxy KNULLI bundle installed."
echo "Restart EmulationStation to refresh Tools entries."
