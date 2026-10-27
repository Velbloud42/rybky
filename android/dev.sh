#!/bin/sh
# Sestaví aplikaci, nahraje ji do všech připojených telefonů a spustí. Data (přihlášení) zůstanou.
set -e
cd "$(dirname "$0")"
export JAVA_HOME="${JAVA_HOME:-$HOME/android-studio/jbr}"
ADB="$HOME/Android/Sdk/platform-tools/adb"
./gradlew -q installDebug
# Název Wi-Fi zařízení obsahuje mezeru, proto čtení po řádcích.
"$ADB" devices | awk -F'\t' 'NR > 1 && $2 == "device" { print $1 }' | while IFS= read -r device; do
    "$ADB" -s "$device" reverse tcp:8000 tcp:8000 </dev/null >/dev/null
    "$ADB" -s "$device" shell am start -S -n cz.rybky/.MainActivity </dev/null >/dev/null
    echo "Nahráno: $device"
done
