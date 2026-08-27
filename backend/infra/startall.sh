#!/usr/bin/env bash
#
# ai-auto-testing-backend / infra / startall.sh
#
# Appium instance'larini, Selenium Grid hub'ini ve node'larini, O AN ACIK
# olan emulator/simulator'leri OTOMATIK BULARAK ayaga kaldirir.
#
# Onemli: hicbir emulator/simulator ID'si burada hardcoded degildir. Bu
# yuzden bu script HERKESIN kendi bilgisayarinda calisir -- her kisi kendi
# emulator/simulator'unu acar, script'i calistirir, script "su an hangi
# cihazlar acik" diye bulup otomatik onlara baglanir.
#
# Kullanim:
#   ./infra/startall.sh          # bulunan tum cihazlar icin Appium + Grid ayaga kaldirir
#   ./infra/stopall.sh           # hepsini durdurur
#
# On kosul (bkz. README.md "Kurulum" bolumu):
#   - En az bir Android emulator VEYA (macOS'ta) bir iOS Simulator acik olmali
#   - appium, adb (Android SDK platform-tools) PATH'te olmali
#   - java kurulu olmali, Selenium Server jar'i indirilmis olmali
#     (jar'in yolunu SELENIUM_SERVER_JAR ortam degiskeniyle verebilir ya da
#      dogrudan bu infra/ klasorunun icine kopyalayabilirsiniz)
#
set -euo pipefail

# ---------------------------------------------------------------------------
# Ayarlanabilir degiskenler (env var ile override edilebilir)
# ---------------------------------------------------------------------------
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
BACKEND_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
APP_PROPS="${APP_PROPS:-$BACKEND_DIR/src/main/resources/application.properties}"

RUN_DIR="$SCRIPT_DIR/.run"
LOG_DIR="$RUN_DIR/logs"
PID_FILE="$RUN_DIR/pids"
mkdir -p "$LOG_DIR"
: > "$PID_FILE"

# Selenium Server jar: once env var, sonra infra/ altinda, sonra ev dizininde ara
SELENIUM_SERVER_JAR="${SELENIUM_SERVER_JAR:-}"
if [[ -z "$SELENIUM_SERVER_JAR" ]]; then
  SELENIUM_SERVER_JAR="$(ls "$SCRIPT_DIR"/selenium-server-*.jar 2>/dev/null | head -n1 || true)"
fi
if [[ -z "$SELENIUM_SERVER_JAR" ]]; then
  SELENIUM_SERVER_JAR="$(find "$HOME/Downloads" "$HOME" -maxdepth 3 -iname 'selenium-server-*.jar' 2>/dev/null | head -n1 || true)"
fi

props_get() {
  # application.properties'ten "key=value" oku (yorum satirlarini/bos degerleri atla)
  local key="$1" default="$2" val
  val="$(grep -E "^${key}=" "$APP_PROPS" 2>/dev/null | tail -n1 | cut -d'=' -f2- || true)"
  if [[ -z "$val" ]]; then echo "$default"; else echo "$val"; fi
}

port_of() {
  # http://127.0.0.1:4723 -> 4723
  echo "$1" | sed -E 's#.*:([0-9]+)/?$#\1#'
}

ANDROID_LOCAL_PORT="$(port_of "$(props_get appium.server-url http://127.0.0.1:4723)")"
IOS_LOCAL_PORT="$(port_of "$(props_get appium.ios-server-url http://127.0.0.1:4727)")"
GRID_PORT="$(port_of "$(props_get appium.grid-url http://127.0.0.1:4444)")"
NODE_PORT_BASE=5555

log()  { echo "[startall] $*"; }
warn() { echo "[startall][UYARI] $*" >&2; }
die()  { echo "[startall][HATA] $*" >&2; exit 1; }

command -v appium >/dev/null 2>&1 || die "appium bulunamadi. 'npm install -g appium' calistirin."
command -v java   >/dev/null 2>&1 || die "java bulunamadi. Grid hub/node icin JDK gerekli."
command -v curl   >/dev/null 2>&1 || die "curl bulunamadi."
[[ -n "$SELENIUM_SERVER_JAR" ]] || die "selenium-server-*.jar bulunamadi. SELENIUM_SERVER_JAR ortam degiskeniyle yolunu belirtin ya da jar'i infra/ klasorune kopyalayin."

# ---------------------------------------------------------------------------
# 1) O an acik cihazlari otomatik bul
# ---------------------------------------------------------------------------
ANDROID_UDIDS=()
if command -v adb >/dev/null 2>&1; then
  while IFS= read -r line; do
    [[ -n "$line" ]] && ANDROID_UDIDS+=("$line")
  done < <(adb devices | awk 'NR>1 && $2=="device" {print $1}')
else
  warn "adb bulunamadi, Android cihazlari atlaniyor."
fi

IOS_UDID=""
if [[ "$(uname)" == "Darwin" ]] && command -v xcrun >/dev/null 2>&1; then
  IOS_UDID="$(xcrun simctl list devices booted | grep -oE '[0-9A-F-]{36}' | head -n1 || true)"
fi

if [[ ${#ANDROID_UDIDS[@]} -eq 0 && -z "$IOS_UDID" ]]; then
  die "Acik hicbir emulator/simulator bulunamadi. Once Android Studio'dan bir emulator ya da (macOS) bir iOS Simulator baslatin, sonra bu script'i tekrar calistirin."
fi

log "Bulunan Android cihazlar: ${ANDROID_UDIDS[*]:-yok}"
log "Bulunan iOS simulator:    ${IOS_UDID:-yok}"

# ---------------------------------------------------------------------------
# 2) Her cihaz icin bir Appium instance baslat
# ---------------------------------------------------------------------------
wait_ready() {
  local url="$1" name="$2" tries=60
  until curl -sf "$url/status" >/dev/null 2>&1; do
    tries=$((tries - 1))
    if [[ $tries -le 0 ]]; then
      die "$name zamaninda ayaga kalkmadi ($url). Log dosyalarina bakin: $LOG_DIR"
    fi
    sleep 1
  done
  log "$name hazir -> $url"
}

start_appium() {
  local udid="$1" port="$2" name="$3"
  log "Appium baslatiliyor: $name (udid=$udid, port=$port)"
  nohup appium --port "$port" \
    --default-capabilities "{\"appium:udid\":\"$udid\"}" \
    --allow-cors \
    > "$LOG_DIR/appium-$name.log" 2>&1 &
  echo "$!" >> "$PID_FILE"
}

NODE_RELAYS=()   # "appiumPort|platformName|automationName|name" satirlari
next_port=$ANDROID_LOCAL_PORT

idx=0
for udid in "${ANDROID_UDIDS[@]+"${ANDROID_UDIDS[@]}"}"; do
  if [[ $idx -eq 0 ]]; then
    port=$ANDROID_LOCAL_PORT   # ilk android -> application.properties'teki local port (parallel:false icin de ayni instance kullanilir)
  else
    next_port=$((next_port + 1))
    while [[ "$next_port" == "$IOS_LOCAL_PORT" || "$next_port" == "$GRID_PORT" ]]; do
      next_port=$((next_port + 1))
    done
    port=$next_port
  fi
  name="android-$idx"
  start_appium "$udid" "$port" "$name"
  NODE_RELAYS+=("$port|Android|UiAutomator2|$name")
  idx=$((idx + 1))
done

if [[ -n "$IOS_UDID" ]]; then
  start_appium "$IOS_UDID" "$IOS_LOCAL_PORT" "ios-0"
  NODE_RELAYS+=("$IOS_LOCAL_PORT|IOS|XCUITest|ios-0")
fi

for relay in "${NODE_RELAYS[@]}"; do
  port="${relay%%|*}"
  wait_ready "http://127.0.0.1:$port" "Appium($port)"
done

# ---------------------------------------------------------------------------
# 3) Selenium Grid hub'ini baslat
# ---------------------------------------------------------------------------
log "Grid hub baslatiliyor (port $GRID_PORT)"
nohup java -jar "$SELENIUM_SERVER_JAR" hub --port "$GRID_PORT" \
  > "$LOG_DIR/hub.log" 2>&1 &
echo "$!" >> "$PID_FILE"
wait_ready "http://127.0.0.1:$GRID_PORT" "Grid hub"

# ---------------------------------------------------------------------------
# 4) Her Appium instance'i icin bir Grid node baslat (toml BURADA, calisma
#    aninda uretilir -- repo'da statik node*.toml dosyasi tutmaya gerek yok)
# ---------------------------------------------------------------------------
node_port=$NODE_PORT_BASE
for relay in "${NODE_RELAYS[@]}"; do
  IFS='|' read -r appium_port platform automation name <<< "$relay"
  toml="$RUN_DIR/node-$name.toml"
  cat > "$toml" <<EOF
[server]
port = $node_port

[relay]
url = "http://localhost:$appium_port"
status-endpoint = "/status"
configs = ["1", "{\"platformName\": \"$platform\", \"appium:automationName\": \"$automation\"}"]
EOF
  log "Grid node baslatiliyor: $name (relay -> $appium_port, node port $node_port)"
  nohup java -jar "$SELENIUM_SERVER_JAR" node --config "$toml" --hub "http://localhost:$GRID_PORT" \
    > "$LOG_DIR/node-$name.log" 2>&1 &
  echo "$!" >> "$PID_FILE"
  node_port=$((node_port + 1))
done

log ""
log "Hepsi ayakta. Ozet:"
log "  Grid UI:        http://localhost:$GRID_PORT/ui"
log "  Android local:  http://127.0.0.1:$ANDROID_LOCAL_PORT  (application.properties: appium.server-url)"
if [[ -n "$IOS_UDID" ]]; then
  log "  iOS local:      http://127.0.0.1:$IOS_LOCAL_PORT  (application.properties: appium.ios-server-url)"
fi
log "  Loglar:         $LOG_DIR"
log "  Durdurmak icin: ./infra/stopall.sh"
