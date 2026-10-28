#!/usr/bin/env bash
# Builds every mod in the repository, in dependency order.
#
# Why the order matters: fifteen mods compile against a sibling mod's built jar,
# picked up from `../<Mod>/build/libs/`. CheeseDash has to exist before anything
# that depends on it, and CheeseItems before CheeseStore. Within a tier the mods
# are independent of each other.
#
# Usage:
#   tools/build-all.sh            # build everything
#   tools/build-all.sh --clean    # wipe build/libs first (recommended before a release)
#
# Mods needing jars in localdeps/ (CheeseFixQuests, CheeseCompatModsFix) fail with
# a readable message if those are missing — see localdeps/README.md.

set -u

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

CLEAN=0
[ "${1:-}" = "--clean" ] && CLEAN=1

# Tier 1: depended upon by others. Tier 2: depends on tier 1, depended upon by tier 3.
TIER1="CheeseDash"
TIER2="CheeseGuard CheeseItems"
TIER3="AetherialBridge CheeseCompatModsFix CheeseExtraCommands CheeseFixQuests
       CheeseKart CheeseMarket CheeseMusic CheesePatrol CheesePurse CheeseRaids
       CheeseStore CheeseVanity CobbleCap CobbleCheeseScore CobblemonDungeons
       CobbleShift EggAndCheese ScheduleRestart"

if [ "$CLEAN" = 1 ]; then
    echo "== wiping build/libs =="
    find . -maxdepth 3 -type d -path "*/build/libs" -exec rm -rf {} + 2>/dev/null
fi

ok=0; failed=""
build_one() {
    local mod="$1"
    printf '== %-22s ' "$mod"
    if [ ! -d "$mod" ]; then echo "SKIP (absent)"; return; fi
    if ( cd "$mod" && ./gradlew build --no-daemon -q ) > "/tmp/build-$mod.log" 2>&1; then
        echo "OK"
        ok=$((ok + 1))
    else
        echo "FAILED  -> /tmp/build-$mod.log"
        failed="$failed $mod"
    fi
}

for tier in "$TIER1" "$TIER2" "$TIER3"; do
    for mod in $tier; do build_one "$mod"; done
done

echo
echo "built: $ok"
if [ -n "$failed" ]; then
    echo "failed:$failed"
    exit 1
fi
echo "all green"
