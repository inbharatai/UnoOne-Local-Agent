#!/usr/bin/env bash
# Collection only, never a device-agent task runner. Usage: ... SERIAL APK OUTPUT [SECONDS=600]
set -euo pipefail
SERIAL=${1:?serial required}; APK=${2:?apk required}; OUT=${3:?output required}; SECONDS_TO_SAMPLE=${4:-600}
[[ "$SECONDS_TO_SAMPLE" =~ ^[0-9]+$ ]] || exit 2
mkdir -p "$OUT"
ADB=(adb -s "$SERIAL")
command -v adb >/dev/null
"${ADB[@]}" get-state > "$OUT/adb-state.txt"
sha256sum "$APK" > "$OUT/apk-sha256.txt"
printf 'Install only with explicit operator consent: adb -s %q install -r %q\nNo accessibility or runtime permissions are automatically granted.\n' "$SERIAL" "$APK" | tee "$OUT/install-instructions.txt"
"${ADB[@]}" shell getprop > "$OUT/getprop.txt"
"${ADB[@]}" shell dumpsys package com.unoone.agent > "$OUT/package.txt"
"${ADB[@]}" shell pm path com.unoone.agent > "$OUT/installed-apk-path.txt"
"${ADB[@]}" shell settings get secure enabled_accessibility_services > "$OUT/accessibility-before.txt"
"${ADB[@]}" shell settings get global airplane_mode_on > "$OUT/airplane-observation.txt"
"${ADB[@]}" shell dumpsys connectivity > "$OUT/connectivity-before.txt"
"${ADB[@]}" shell dumpsys netstats > "$OUT/netstats-before.txt" 2>&1 || true
"${ADB[@]}" logcat -v threadtime > "$OUT/logcat.txt" 2>&1 & LOGPID=$!
trap 'kill "$LOGPID" 2>/dev/null || true' EXIT
printf 'Operator: manually run sustained-50 tasks. Collector does NOT launch or score them.\n'
for ((t=0;t<=SECONDS_TO_SAMPLE;t+=10)); do
 date -u +%FT%TZ >> "$OUT/samples.txt"
 for what in 'meminfo com.unoone.agent' thermalservice battery; do
  printf '\n=== %s ===\n' "$what" >> "$OUT/samples.txt"
  "${ADB[@]}" shell dumpsys $what >> "$OUT/samples.txt" 2>&1 || printf 'UNAVAILABLE\n' >> "$OUT/samples.txt"
 done
 ((t<SECONDS_TO_SAMPLE)) && sleep 10 || true
done
"${ADB[@]}" logcat -b crash -d -v threadtime > "$OUT/crash.txt" 2>&1 || true
"${ADB[@]}" shell dumpsys activity exit-info com.unoone.agent > "$OUT/exit-info.txt" 2>&1 || true
"${ADB[@]}" shell dumpsys dropbox --print data_app_anr > "$OUT/anr.txt" 2>&1 || true
"${ADB[@]}" shell dumpsys connectivity > "$OUT/connectivity-after.txt"
"${ADB[@]}" shell dumpsys netstats > "$OUT/netstats-after.txt" 2>&1 || true
printf 'PENDING: manual task evidence, runtime/model identity, installed APK hash comparison and connection-capture review required.\n' > "$OUT/qualification.txt"
