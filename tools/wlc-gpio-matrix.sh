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
    # Root is NOT required — some builds expose the customer_gpio nodes to the shell
    # user directly. Try to elevate best-effort (helps on userdebug/eng), but don't
    # fail on it; gate on whether gpio27 is actually readable instead.
    if [ "$($ADB shell id -u 2>/dev/null | tr -d '\r')" != "0" ]; then
        $ADB root >/dev/null 2>&1 && sleep 2
    fi
    local v
    v="$($ADB shell "cat '$G27'" 2>/dev/null | tr -d '\r\n\000 ')"
    if [ "$v" != "0" ] && [ "$v" != "1" ]; then
        echo "error: cannot read gpio27 at $G27 (got '${v:-<empty>}')." >&2
        echo "       Wrong path? override with GPIO_DIR=..., or the node needs root/sepolicy." >&2
        exit 1
    fi
    # Warn (don't fail) if the charging-enable line can't be written — the matrix still
    # runs, but the gpio127 cells won't actually toggle. Probe by writing gpio127's own
    # current value back (no state change).
    local c127; c127="$($ADB shell "cat '$G127'" 2>/dev/null | tr -d '\r\n\000 ')"
    if ! $ADB shell "echo ${c127:-1} > '$G127'" >/dev/null 2>&1; then
        echo "warning: cannot write gpio127 at $G127 — charging on/off cells may not change." >&2
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
# EXIT does the restore; INT/TERM just exit, which fires the EXIT trap once. Trapping
# INT to a handler that only restores would return control to the interrupted `read`
# and never actually quit — Ctrl-C would look like it does nothing.
trap cleanup EXIT
trap 'exit 130' INT TERM

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
    printf '  %-42s gpio27: %-9s (edges=%-3s high=%s%%)\n' "$tag" "$label" "$edges" "$high"
    RESULTS+=("$tag|$label|$edges|$high")
}

# ── run ──────────────────────────────────────────────────────────────────────
require_device
ORIG127="$(read127)"; [ -z "$ORIG127" ] && ORIG127="1"

# Dual output: everything from here also lands in a report file you can share.
REPORT="${REPORT_FILE:-./wlc-matrix-$(printf '%s' "${SERIAL:-auto}" | tr -c 'A-Za-z0-9._-' '_').txt}"
: > "$REPORT" 2>/dev/null || REPORT=/dev/null
p()  { echo "$*"; [ "$REPORT" != /dev/null ] && echo "$*" >>"$REPORT"; }
pf() { local s; s="$(printf "$@")"; echo "$s"; [ "$REPORT" != /dev/null ] && echo "$s" >>"$REPORT"; }

# Canonical starting state: charging ON (gpio127=1) with the device PLACED. Set
# gpio127 deterministically now, before the first prompt. Restored on exit.
write127 1
$ADB shell "sleep 0.$(printf '%03d' "$SETTLE_MS")" 2>/dev/null || sleep 0.5
p "=============================================================="
p " WLC GPIO matrix   device='${SERIAL:-<auto>}'   samples/burst=$SAMPLES"
p " start: gpio127=1 (charging ON), device PLACED"
p " (original gpio127=$ORIG127 restored on exit; report -> $REPORT)"
p "=============================================================="

declare -a RESULTS=()
declare -A V EDG HI   # keyed "CHG|PAD|PLACED" -> label / edges / high

# Sample gpio27 for one cell and record it under a canonical key.
emit() { # $1=CHG(ON/OFF) $2=PAD(CONN/DISC) $3=PLACED(YES/NO)
    local raw sig label edges high len k="$1|$2|$3"
    raw="$(sample27)"; sig="$(classify "$raw")"
    IFS='|' read -r label edges high len <<<"$sig"
    pf '  chg=%-3s pad=%-4s placed=%-3s  ->  gpio27: %-9s (edges=%-3s high=%s%%)' \
        "$1" "$2" "$3" "$label" "$edges" "$high"
    RESULTS+=("chg=$1 pad=$2 placed=$3|$label|$edges|$high")
    V["$k"]="$label"; EDG["$k"]="$edges"; HI["$k"]="$high"
}

# One stage = one physical reconfiguration; charging (gpio127) is toggled 1->0
# automatically within each, so every stage yields two cells. Ordered to start from
# the requested state (charging ON + placed) and minimize handling: placed -> remove
# -> disconnect pad -> place on the disconnected pad. Stages needing the pad detached
# are skippable. This separates "pad disconnected while placed" from "...while empty".
run_stage() { # $1=prompt $2=PAD $3=PLACED $4=skippable(1)
    if [ "${4:-0}" = "1" ]; then
        echo; echo ">>> $1"
        read -r -p "    Enter when done, or 's'+Enter to skip: " a
        if [ "$a" = "s" ] || [ "$a" = "S" ]; then p "  (skipped: pad=$2 placed=$3)"; return; fi
    else
        pause_for "$1"
    fi
    p "  --- pad=$2 placed=$3 ---"
    for ch in 1 0; do
        write127 "$ch"
        $ADB shell "sleep 0.$(printf '%03d' "$SETTLE_MS")" 2>/dev/null || sleep 0.5
        [ "$ch" = "1" ] && emit ON "$2" "$3" || emit OFF "$2" "$3"
    done
}

# 4 physical stages x 2 charging states = 8 cells: every combination of
# {charging on/off} x {device placed/removed} x {pad connected/disconnected}.
run_stage "PAD CONNECTED, device PLACED on the pad (charging is ON)."          CONN YES 0
run_stage "REMOVE the device from the pad (pad empty, still connected)."       CONN NO  0
run_stage "DISCONNECT the pad (nothing on it)."                                DISC NO  1
run_stage "PLACE the device on the DISCONNECTED pad."                          DISC YES 1

# ── extensive report ─────────────────────────────────────────────────────────
lu() { echo "${V[$1]:- —}"; }              # label lookup, dash if the cell was skipped
det() { echo "${EDG[$1]:--}/${HI[$1]:--}%"; }

p ""
p "=============================================================="
p " MATRIX REPORT   ($(date '+%Y-%m-%d %H:%M' 2>/dev/null || echo run))"
p "   STABLE-1 = line high, STABLE-0 = line low, TOGGLING = free-running"
p "=============================================================="
pf ' %-34s %-10s %s' "condition" "gpio27" "edges/high%"
for row in "${RESULTS[@]}"; do
    IFS='|' read -r tag label edges high <<<"$row"
    pf ' %-34s %-10s %s/%s%%' "$tag" "$label" "$edges" "$high"
done

p ""
p " GROUPED BY CHARGING STATE"
for chg in ON OFF; do
    p "   charging $chg (gpio127=$([ $chg = ON ] && echo 1 || echo 0)):"
    pf '     %-24s %-10s [%s]' "pad connected, placed" "$(lu "$chg|CONN|YES")" "$(det "$chg|CONN|YES")"
    pf '     %-24s %-10s [%s]' "pad connected, empty"  "$(lu "$chg|CONN|NO")"  "$(det "$chg|CONN|NO")"
    pf '     %-24s %-10s [%s]' "pad DISCONNECTED, placed" "$(lu "$chg|DISC|YES")" "$(det "$chg|DISC|YES")"
    pf '     %-24s %-10s [%s]' "pad DISCONNECTED, empty"  "$(lu "$chg|DISC|NO")"  "$(det "$chg|DISC|NO")"
done

p ""
p " DERIVED CONCLUSIONS (from this run)"
# 1. Is gpio27 a clean guest signal while charging is ON?
onP="$(lu 'ON|CONN|YES')"; onE="$(lu 'ON|CONN|NO')"
if [ "$onP" = "STABLE-1" ] && [ "$onE" = "STABLE-0" ]; then
    p "  [OK]   Charging ON: gpio27 cleanly separates guest (STABLE-1) from vacant (STABLE-0)."
else
    p "  [??]   Charging ON: placed=$onP empty=$onE (expected STABLE-1 / STABLE-0)."
fi
# 2. Does gpio27 carry any info while charging is OFF?
off_all_toggle="yes"; off_seen="no"
for k in 'OFF|CONN|YES' 'OFF|CONN|NO' 'OFF|DISC|YES' 'OFF|DISC|NO'; do
    l="${V[$k]:-}"; [ -n "$l" ] && off_seen="yes"
    [ -n "$l" ] && [ "$l" != "TOGGLING" ] && off_all_toggle="no"
done
if [ "$off_seen" = "yes" ] && [ "$off_all_toggle" = "yes" ]; then
    p "  [BUG]  Charging OFF: every measured cell is TOGGLING -> gpio27 carries NO"
    p "         placement info. The current classifyWlc mislabels this as '2=no pad'."
elif [ "$off_seen" = "yes" ]; then
    p "  [??]   Charging OFF: not all cells toggled — inspect the table above."
fi
# 3. Is a disconnected pad distinguishable from a connected one (charging ON)?
if [ "$(lu 'ON|DISC|YES')" != " —" ] || [ "$(lu 'ON|DISC|NO')" != " —" ]; then
    if [ "$(lu 'ON|DISC|NO')" = "$onE" ] && [ "$(lu 'ON|DISC|YES')" = "$onP" ]; then
        p "  [INFO] 'No pad' is NOT detectable: a DISCONNECTED pad reads the same as a"
        p "         connected one (charging ON) -> drop the '2=no pad' status entirely."
    else
        p "  [INFO] DISCONNECTED pad differs from connected (charging ON): CONN placed=$onP"
        p "         DISC placed=$(lu 'ON|DISC|YES') / CONN empty=$onE DISC empty=$(lu 'ON|DISC|NO')."
    fi
else
    p "  [--]   Pad-disconnect cells skipped; 'no pad' detectability not evaluated."
fi

p ""
p " RECOMMENDED classifyWlc (validate the pulse with wlc-pulse-test.sh):"
p "   read gpio127 (charging enable)"
p "   if charging ON : return settled gpio27   (1=guest, 0=vacant)"
p "   if charging OFF: pulse gpio127=1 ~350ms, read settled gpio27, restore gpio127"
p "                    (rate-limited; hold last value between pulses)"
p "   never treat a moving/toggling line as 'no pad'."
p ""
p " (report saved to $REPORT)"
