#!/usr/bin/env bash
#
# wlc-gpio-matrix.sh — characterize the wireless-charging (Qi) pad GPIOs so the
# client's pad/guest detection can be made correct in every charging state.
#
# Two customer_gpio lines drive WLC on this hardware:
#   gpio27  — guest-detection line the client reads   (1=guest, 0=idle, floats=?)
#   gpio127 — charging enable the client writes        (1=charging on, 0=off)
#
# The bug this maps: with charging ENABLED + a device placed, gpio27 sits STABLE
# at 1 and the client reports "guest on pad" correctly. With charging DISABLED
# (gpio127=0) + the same device placed, gpio27 starts TOGGLING, and the client's
# "a moving line means no pad" heuristic (classifyWlc) misreports "no pad" even
# though a device is sitting on the pad. classifyWlc never looks at gpio127, so it
# cannot tell "pad genuinely absent" from "pad present but charging disabled".
#
# This script walks the full matrix — {charging on, charging off} x {device
# placed, device removed} — sampling gpio27's signature (stable 0, stable 1, or
# toggling with an edge count + high-duty) in each cell. It automates gpio127 and
# asks YOU to place/remove the device. The printed table is the ground truth for
# rewriting classifyWlc to be charging-state aware.
#
#   Usage:  ./wlc-gpio-matrix.sh [device-serial] [--samples N]
#
# Needs: adb, a rooted (adb root) debug build, the customer_gpio sepolicy that
# lets sysfs be read/written (already present on debug-GMS).

set -uo pipefail

# ── config ───────────────────────────────────────────────────────────────────
GPIO_DIR="${GPIO_DIR:-/sys/devices/platform/soc/soc:customer_gpio}"
G27="$GPIO_DIR/gpio27"     # guest-detection (read)
G127="$GPIO_DIR/gpio127"   # charging enable (write)
SAMPLES="${SAMPLES:-150}"  # gpio27 reads per burst (dense, back-to-back)
SETTLE_MS="${SETTLE_MS:-500}" # pause after a gpio127 change before sampling

SERIAL=""
while [ $# -gt 0 ]; do
    case "$1" in
        --samples) SAMPLES="$2"; shift 2 ;;
        --samples=*) SAMPLES="${1#*=}"; shift ;;
        -h|--help) sed -n '2,30p' "$0"; exit 0 ;;
        -*) echo "unknown option: $1" >&2; exit 1 ;;
        *) SERIAL="$1"; shift ;;
    esac
done

ADB="adb"
[ -n "$SERIAL" ] && ADB="adb -s $SERIAL"

# ── adb / gpio helpers ───────────────────────────────────────────────────────
ash() { $ADB shell "$@"; }

require_device() {
    if ! $ADB shell true >/dev/null 2>&1; then
        echo "error: no device reachable over adb (serial=${SERIAL:-auto})" >&2; exit 1
    fi
    # Root is required to write gpio127 / read the sysfs node.
    if [ "$($ADB shell id -u 2>/dev/null | tr -d '\r')" != "0" ]; then
        echo "adb is not root — running 'adb root'..." >&2
        $ADB root >/dev/null 2>&1; sleep 2
        if [ "$($ADB shell id -u 2>/dev/null | tr -d '\r')" != "0" ]; then
            echo "error: could not get root (need a userdebug/eng build)" >&2; exit 1
        fi
    fi
}

read27() { $ADB shell "cat '$G27'" 2>/dev/null | tr -d '\r\n\000'; }
read127() { $ADB shell "cat '$G127'" 2>/dev/null | tr -d '\r\n\000'; }
write127() { $ADB shell "echo $1 > '$G127'" >/dev/null 2>&1; }

# sample27: burst-read gpio27 $SAMPLES times back-to-back on-device (one adb hop),
# echo the raw sequence of 0/1 chars.
sample27() {
    $ADB shell "P='$G27'; i=0; o=''; while [ \$i -lt $SAMPLES ]; do o=\"\$o\$(cat \$P 2>/dev/null)\"; i=\$((i+1)); done; echo \$o" 2>/dev/null | tr -d '\r\n '
}

# classify: read a 0/1 sequence on stdin arg, print "LABEL|edges|highpct|len".
classify() {
    local seq="$1" len n1 edges prev c i label high
    len=${#seq}
    if [ "$len" -eq 0 ] || echo "$seq" | grep -q '[^01]'; then
        echo "UNREADABLE|0|0|$len"; return
    fi
    n1=$(printf '%s' "$seq" | tr -cd '1' | wc -c)
    edges=0; prev=""
    for (( i=0; i<len; i++ )); do
        c="${seq:$i:1}"
        [ -n "$prev" ] && [ "$c" != "$prev" ] && edges=$((edges+1))
        prev="$c"
    done
    high=$(( n1 * 100 / len ))
    if [ "$edges" -eq 0 ]; then
        [ "$high" -ge 50 ] && label="STABLE-1" || label="STABLE-0"
    else
        label="TOGGLING"
    fi
    echo "$label|$edges|$high|$len"
}

# ── restore original charging state on exit ──────────────────────────────────
ORIG127=""
cleanup() {
    if [ -n "$ORIG127" ]; then
        echo; echo "restoring gpio127=$ORIG127 (original charging state)..."
        write127 "$ORIG127"
    fi
}
trap cleanup EXIT INT TERM

pause_for() { # prompt the operator to perform a physical action
    echo
    echo ">>> $1"
    read -r -p "    press Enter when done... " _
}

measure() { # $1=human label -> prints a matrix row, records signature into RESULTS
    local tag="$1" raw sig
    raw="$(sample27)"
    sig="$(classify "$raw")"
    local label edges high len
    IFS='|' read -r label edges high len <<<"$sig"
    printf '  %-34s gpio27: %-9s (edges=%-3s high=%s%%)\n' "$tag" "$label" "$edges" "$high"
    RESULTS+=("$tag|$label|$edges|$high")
}

# ── run ──────────────────────────────────────────────────────────────────────
require_device
ORIG127="$(read127)"; [ -z "$ORIG127" ] && ORIG127="1"
echo "=============================================================="
echo " WLC GPIO matrix   device='${SERIAL:-<auto>}'   samples/burst=$SAMPLES"
echo " gpio27=$(read27) gpio127=$ORIG127 (will be restored on exit)"
echo "=============================================================="

declare -a RESULTS=()

for placement in "removed" "placed"; do
    if [ "$placement" = "removed" ]; then
        pause_for "REMOVE the device from the charging pad (pad empty)."
    else
        pause_for "PLACE the device on the charging pad."
    fi
    echo "  --- device $placement ---"
    for ch in 1 0; do
        write127 "$ch"
        $ADB shell "sleep 0.$(printf '%03d' "$SETTLE_MS")" 2>/dev/null || sleep 0.5
        [ "$ch" = "1" ] && cs="charging ON " || cs="charging OFF"
        measure "gpio127=$ch ($cs) / $placement"
    done
done

# ── optional true "no pad" reference ─────────────────────────────────────────
echo
echo "Optional: to capture the genuine 'pad hardware absent' signature, physically"
echo "disconnect the Qi pad now, if you can."
read -r -p "    type 'y' + Enter once the pad is disconnected, or just Enter to skip: " ans
if [ "$ans" = "y" ] || [ "$ans" = "Y" ]; then
    for ch in 1 0; do
        write127 "$ch"; sleep 0.5
        [ "$ch" = "1" ] && cs="charging ON " || cs="charging OFF"
        measure "gpio127=$ch ($cs) / PAD ABSENT"
    done
fi

# ── summary + interpretation ─────────────────────────────────────────────────
echo
echo "=============================================================="
echo " MATRIX"
echo "=============================================================="
printf ' %-38s %-10s %s\n' "condition" "gpio27" "edges/high"
for row in "${RESULTS[@]}"; do
    IFS='|' read -r tag label edges high <<<"$row"
    printf ' %-38s %-10s %s/%s%%\n' "$tag" "$label" "$edges" "$high"
done

echo
echo "READING:"
echo " * gpio127=1 + placed  should be STABLE-1  (guest on pad, correct today)"
echo " * gpio127=1 + removed should be STABLE-0  (idle)"
echo " * gpio127=0 + placed  is the bug: if it shows TOGGLING, the current"
echo "   classifyWlc reports '2 = no pad' though a device is on the pad."
echo " * Compare gpio127=0/placed vs PAD ABSENT: if their signatures differ,"
echo "   gpio127 state (or a brief enable-pulse) can disambiguate them."
echo
echo "LIKELY FIX (validate against the table above):"
echo " When charging is disabled and pad/guest status is needed, momentarily set"
echo " gpio127=1, take a STABLE read of gpio27, then restore gpio127 to its prior"
echo " value. classifyWlc must consult gpio127 instead of treating any moving line"
echo " as 'no pad'."
