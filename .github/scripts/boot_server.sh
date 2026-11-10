#!/usr/bin/env bash
# Boot a real dedicated server with Palworks and check that it starts and stops cleanly.
#
# Usage: boot_server.sh <fabric|neoforge> <server-dir>
#   <server-dir>/mods must already hold Palworks, Cobblemon and Cobblemon's dependencies.
# Environment:
#   MINECRAFT_VERSION                                    always
#   FABRIC_LOADER_VERSION, FABRIC_INSTALLER_VERSION      for fabric
#   NEOFORGE_VERSION                                     for neoforge
#   BOOT_TIMEOUT    seconds to wait for startup (default 600)
#
# Fails if the server doesn't finish starting, Palworks doesn't initialise, a mixin fails to apply,
# mod loading reports an error, a crash report is written, or the server doesn't stop cleanly.
# After startup it spawns a Pokemon so Pokemon entity classes (and Palworks' mixin into them) load.
set -euo pipefail

loader=$1
dir=$2
boot_timeout=${BOOT_TIMEOUT:-600}
cd "$dir"

summary() { if [ -n "${GITHUB_STEP_SUMMARY:-}" ]; then echo "$*" >> "$GITHUB_STEP_SUMMARY"; fi; }
fail() {
  echo "::error title=Server boot ($loader)::$1"
  summary "❌ **$loader:** $1"
  if [ -f server.log ]; then
    echo "----- last 80 lines of server.log -----"
    tail -n 80 server.log
  fi
  exit 1
}

# --- Install the server (skipped when restored from cache) ---
case "$loader" in
  fabric)
    if [ ! -f fabric-server-launch.jar ]; then
      curl -fsSLo fabric-server-launch.jar \
        "https://meta.fabricmc.net/v2/versions/loader/$MINECRAFT_VERSION/$FABRIC_LOADER_VERSION/$FABRIC_INSTALLER_VERSION/server/jar"
    fi
    server_cmd=(java -Xmx3G -jar fabric-server-launch.jar nogui)
    init_marker="Palworks Fabric initialized!"
    ;;
  neoforge)
    if [ ! -f run.sh ]; then
      curl -fsSLo installer.jar \
        "https://maven.neoforged.net/releases/net/neoforged/neoforge/$NEOFORGE_VERSION/neoforge-$NEOFORGE_VERSION-installer.jar"
      if ! java -jar installer.jar --install-server . > installer.log 2>&1; then
        tail -n 40 installer.log
        fail "NeoForge $NEOFORGE_VERSION installer failed."
      fi
      rm -f installer.jar installer.jar.log
    fi
    echo "-Xmx3G" > user_jvm_args.txt
    server_cmd=(bash run.sh nogui)
    init_marker="Palworks NeoForge initialized!"
    ;;
  *)
    echo "Unknown loader: $loader" >&2
    exit 2
    ;;
esac

# --- Fresh world every run, kept small and fast ---
rm -rf world logs crash-reports server.log console
echo "eula=true" > eula.txt
cat > server.properties <<'EOF'
level-type=minecraft\:flat
generate-structures=false
online-mode=false
spawn-protection=0
max-tick-time=-1
sync-chunk-writes=false
EOF

# --- Start the server with a FIFO as its console so commands can be sent to it ---
mkfifo console
"${server_cmd[@]}" < console > server.log 2>&1 &
server_pid=$!
exec 3> console # keep the console open; opening it also unblocks the server's stdin

send() { echo "> $1"; echo "$1" >&3; }

# wait_for PATTERN SECONDS: 0 when PATTERN appears in the log, 1 on timeout, 2 if the server exits.
wait_for() {
  local i
  for ((i = 0; i < $2; i++)); do
    if grep -qE "$1" server.log; then return 0; fi
    if ! kill -0 "$server_pid" 2>/dev/null; then return 2; fi
    sleep 1
  done
  return 1
}

started=0
wait_for 'Done \([0-9.]+s\)!' "$boot_timeout" || started=$?
if [ "$started" = 2 ]; then
  fail "Server exited during startup."
elif [ "$started" = 1 ]; then
  kill "$server_pid" 2>/dev/null || true
  fail "Server didn't finish starting within ${boot_timeout}s."
fi
startup=$(grep -oE 'Done \([0-9.]+s\)' server.log | head -n1)
echo "Server started: $startup"

# Spawn a Pokemon at the (always loaded) world spawn, let it tick, then count Pokemon entities.
send "pokespawnat 0 -60 0 pikachu"
sleep 10
send "execute if entity @e[type=cobblemon:pokemon]"
spawned=yes
wait_for 'Test passed' 15 || spawned=no

send "stop"
stopped=no
for _ in $(seq 1 120); do
  if ! kill -0 "$server_pid" 2>/dev/null; then stopped=yes; break; fi
  sleep 1
done
exec 3>&-
if [ "$stopped" = no ]; then
  kill -9 "$server_pid" 2>/dev/null || true
  fail "Server didn't stop within 120s of the stop command."
fi
exit_code=0
wait "$server_pid" || exit_code=$?

# --- Check the log ---
grep -qF "$init_marker" server.log || fail "Palworks didn't initialise (no \"$init_marker\" in the log)."

problems=$(grep -nE \
  'Mixin (apply|transformation).*failed|MixinApplyError|InvalidInjectionException|InjectionError|MixinTransformerError|Mod loading has failed|Incompatible mods found|Missing or unsupported mandatory dependencies|---- Minecraft Crash Report ----|[Ee]xception.*palworks|palworks.*[Ee]xception' \
  server.log || true)
if [ -n "$problems" ]; then
  echo "$problems" | head -n 20
  fail "The log contains errors (first lines above)."
fi
if [ -n "$(ls -A crash-reports 2>/dev/null)" ]; then
  fail "A crash report was written: $(ls crash-reports)"
fi
if [ "$exit_code" != 0 ]; then
  fail "Server exited with code $exit_code after stop."
fi

if [ "$spawned" = no ]; then
  echo "::warning title=Server boot ($loader)::Couldn't confirm a Pokemon spawned, so the Pokemon entity mixin may not have been exercised."
fi
summary "✅ **$loader:** started ($startup), Palworks initialised, Pokémon spawned: $spawned, stopped cleanly."
echo "Boot test passed."
