#!/bin/zsh
# Waits for a Quest to connect via USB, installs the current v2.0 receiver APK,
# and identifies which headset it is by serial against the known fleet roster.
# Run this once per headset — plug in Quest, run, unplug when done.

set -u

ADB="/Volumes/1tb /claude code projects /android-sdk/platform-tools/adb"
APK="/Volumes/1tb /claude code projects /Projects/cloud-visualizer-quest-receiver/app/build/outputs/apk/debug/app-debug.apk"

typeset -A FLEET
FLEET[2G0YC5ZF7V01LY]=Lips
FLEET[2G0YC5ZFB800DM]=Andre
FLEET[340YC10GC50P8Y]=Sigil
FLEET[340YC10GC40X21]=Doom
FLEET[2G0YC5ZG2S015Z]=Psychedelic
FLEET[1WMHHB68RB2085]=Humbles
FLEET[2G0YC1ZG2N072J]=Fabaz

echo "Waiting for Quest to connect…"
"$ADB" wait-for-device

SERIAL=$("$ADB" get-serialno 2>/dev/null | tr -d '\r')
NAME=${FLEET[$SERIAL]:-"UNKNOWN"}

echo "Detected: $NAME ($SERIAL)"
echo "Installing v2.0 receiver APK…"

"$ADB" -s "$SERIAL" install -r "$APK"

INSTALLED=$("$ADB" -s "$SERIAL" shell dumpsys package com.fascinatedbyeverything.cloudvisualizerquestreceiver 2>/dev/null | grep versionName | head -1 | tr -d '\r' | awk -F= '{print $2}')

echo
echo "=========================================="
echo "  $NAME ($SERIAL) — versionName=$INSTALLED"
echo "=========================================="
