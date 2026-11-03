#!/usr/bin/env bash
# ─────────────────────────────────────────────────────────────────────────────
# Solidus WS-0 / Task A — live boot driver + RCON economy scenario.
#
# Phases (invoke one per CI step / shell call):
#   reset   : wipe disposable run-live state (world/config/logs) — binaries stay.
#   boot1   : boot server, wait for Done, run the FULL RCON economy scenario
#             (admin harness per TASK_A_LIVE_BOOT.md §أ-4), stop.
#   boot2   : boot AGAIN on the same state, verify the funded escrow and supply
#             SURVIVED the restart (ESC-01 regression, §أ-5), stop.
#   familyboot : C-3أ family lockstep drill — boot with ALL FOUR family jars in
#             mods/, assert the four mods load together on 26.3 (the check that
#             fails before C-1/C-2), Core compat green (palette EXACT / drop
#             THREE_ARG_PREDICTION), Governance hook registered + a real tax
#             ledger trace through Core's pay-as path, then the base economy
#             scenario + audit, stop.
#   familyboot2: boot AGAIN on the same state — family reloads and the funded
#             escrow survives (family-flavored ESC-01), stop.
#   logcheck: fail if boot.log contains ERROR / CRITICAL / degradation banner.
#
# Verification model: RCON dispatches the REAL production commands; the
# authoritative assertions run against the SQLite state files (economy.db /
# auctions.db) — the admin command feedback is async (futures marshaled onto
# the server thread) so RCON response capture is inherently racy; when an
# output IS captured it is additionally asserted (rcon_until).
#
# Requirements: java 25 (JAVA_HOME), python3, run-live prepared per §أ-2.
# ─────────────────────────────────────────────────────────────────────────────
set -uo pipefail

RUN_DIR="$(readlink -f "${1:-run-live}")"
PHASE="${2:-boot1}"
RCON_HOST="127.0.0.1"
RCON_PORT="25575"
RCON_PASS="solidus-test"
BOOT_WAIT_SECONDS="${BOOT_WAIT_SECONDS:-300}"
HERE="$(cd "$(dirname "$0")" && pwd)"
RCON="python3 $HERE/rcon.py $RCON_HOST $RCON_PORT $RCON_PASS"

FAILED=0
fail() { echo "✗ FAIL: $*"; FAILED=1; }
pass() { echo "✓ PASS: $*"; }

rcon() {
  local out="" i
  for i in $(seq 1 60); do
    out="$($RCON "$@" 2>&1)" && { printf '%s' "$out"; return 0; }
    sleep 1
  done
  echo "RCON unreachable: $out" >&2
  return 3
}

rcon_until() { # re-issue a read-only command until its output is captured
  local out="" i
  for i in 1 2 3 4 5 6; do
    out="$(rcon "$@")"
    [ -n "$out" ] && { printf '%s' "$out"; return 0; }
    sleep 2
  done
  printf '%s' "$out"
  return 1
}

expect_contains() { # haystack_file needle label
  if grep -qF -- "$2" "$1"; then pass "$3"; else fail "$3 (missing: $2)"; fi
}

# ── SQLite state assertions (authoritative) ────────────────────────────────
db_json() {
  python3 - "$RUN_DIR" <<'PY'
import sqlite3, glob, json, sys, pathlib
run = pathlib.Path(sys.argv[1])
eco = glob.glob(str(run / "config/*/economy.db")) + [str(run / "economy.db")]
aud = glob.glob(str(run / "config/*/auctions.db")) + [str(run / "auctions.db")]
out = {"balances": {}, "escrow": None, "listing": None, "bid": None}
if eco and pathlib.Path(eco[0]).exists():
    con = sqlite3.connect(eco[0])
    for name, bal in con.execute(
            "SELECT player_name, balance FROM player_balances WHERE player_name IN ('Alice','Bob')"):
        out["balances"][name] = bal
    row = con.execute(
        "SELECT balance FROM player_balances WHERE uuid = '00000000-0000-0000-0000-000000000000'"
    ).fetchone()
    out["escrow"] = row[0] if row else None
if aud and pathlib.Path(aud[0]).exists():
    con = sqlite3.connect(aud[0])
    lst = con.execute(
        "SELECT listing_id, material_name, quantity, price FROM auction_listings "
        "WHERE status=0 ORDER BY listed_timestamp DESC LIMIT 1").fetchone()
    if lst:
        out["listing"] = {"listing_id": lst[0], "material": lst[1],
                          "quantity": lst[2], "price": lst[3]}
        bid = con.execute(
            "SELECT current_bid, current_bidder_name, bid_count FROM auction_bid_state "
            "WHERE listing_id = ?", (lst[0],)).fetchone()
        if bid:
            out["bid"] = {"current_bid": bid[0], "bidder": bid[1], "bid_count": bid[2]}
print(json.dumps(out))
PY
}

assert_balances() { # alice bob label
  local want_a="$1" want_b="$2" got; got="$(db_json)"
  local a b
  a="$(python3 -c "import json,sys; print(json.loads(sys.argv[1])['balances'].get('Alice'))" "$got")"
  b="$(python3 -c "import json,sys; print(json.loads(sys.argv[1])['balances'].get('Bob'))" "$got")"
  python3 -c "import sys; sys.exit(0 if abs(float(sys.argv[1])-float(sys.argv[2]))<0.005 else 1)" \
    "${a:-nan}" "$want_a" && pass "$3: Alice = $want_a" || fail "$3: Alice = ${a:-none}, expected $want_a"
  python3 -c "import sys; sys.exit(0 if abs(float(sys.argv[1])-float(sys.argv[2]))<0.005 else 1)" \
    "${b:-nan}" "$want_b" && pass "$3: Bob = $want_b" || fail "$3: Bob = ${b:-none}, expected $want_b"
}

assert_escrow() { # amount label
  local want="$1" got; got="$(db_json)"
  python3 - "$got" "$want" <<'PY'
import json, sys
esc = json.loads(sys.argv[1]).get("escrow")
want = float(sys.argv[2])
if esc is None:
    print("FAIL escrow row missing"); sys.exit(1)
sys.exit(0 if abs(float(esc) - want) < 0.005 else 1)
PY
  local rc=$?
  [ $rc -eq 0 ] && pass "$2: escrow = $want" || fail "$2: escrow mismatch (got: $(echo "$got" | head -c 200))"
}

assert_open_listing() { # label → echoes ONLY the listing_id on stdout
  local got; got="$(db_json)"
  local lid
  lid="$(python3 -c "import json,sys; l=json.loads(sys.argv[1])['listing']; print(l['listing_id'] if l else '')" "$got")"
  if [ ${#lid} -eq 36 ]; then
    local qty price
    qty="$(python3 -c "import json,sys; print(json.loads(sys.argv[1])['listing']['quantity'])" "$got")"
    price="$(python3 -c "import json,sys; print(json.loads(sys.argv[1])['listing']['price'])" "$got")"
    pass "$1: ACTIVE listing $lid (5x diamond, price $price)" >&2
    [ "$qty" = "5" ] || fail "listing quantity = $qty, expected 5" >&2
    [ "$price" = "500.0" ] || fail "listing price = $price, expected 500.0" >&2
    printf '%s' "$lid"
  else
    fail "$1: no ACTIVE listing in auctions.db" >&2
    printf ''
  fi
}

assert_bid_state() { # amount bidder label
  local want="$1" wantbidder="$2" got; got="$(db_json)"
  python3 - "$got" "$want" "$wantbidder" <<'PY'
import json, sys
d = json.loads(sys.argv[1]); want = float(sys.argv[2]); who = sys.argv[3]
bid = d.get("bid")
if not bid:
    print("FAIL bid_state missing"); sys.exit(1)
if abs(float(bid["current_bid"]) - want) > 0.005:
    print(f"FAIL current_bid={bid['current_bid']} want {want}"); sys.exit(1)
if bid["bidder"] != who:
    print(f"FAIL bidder={bid['bidder']} want {who}"); sys.exit(1)
print("OK"); sys.exit(0)
PY
  local rc=$?
  [ $rc -eq 0 ] && pass "$3: bid_state current_bid=$want by $wantbidder" \
    || fail "$3: bid_state wrong (see above)"
}

boot_server() {
  echo "── booting server in $RUN_DIR (wait ≤ ${BOOT_WAIT_SECONDS}s for 'Done') ──"
  cd "$RUN_DIR" || return 1
  : > boot.log
  JAVA_HOME="${JAVA_HOME:-/usr}" nohup java -Xmx2G -jar fabric-server-launch.jar nogui \
    >> boot.log 2>&1 &
  SERVER_PID=$!
  local waited=0
  while [ $waited -lt $BOOT_WAIT_SECONDS ]; do
    if grep -q "Done (" boot.log; then echo "server is up after ~${waited}s"; return 0; fi
    kill -0 "$SERVER_PID" 2>/dev/null || { echo "server process died:"; tail -40 boot.log; return 1; }
    sleep 2; waited=$((waited + 2))
  done
  echo "timeout waiting for Done"; tail -40 boot.log; return 1
}

stop_server() {
  echo "── stopping server ──"
  $RCON stop > /dev/null 2>&1 || true
  for _ in $(seq 1 60); do
    kill -0 "$SERVER_PID" 2>/dev/null || { echo "server stopped"; return 0; }
    sleep 1
  done
  echo "server did not exit after stop — killing"; kill -9 "$SERVER_PID" 2>/dev/null || true
  return 1
}

reset_data() {
  echo "── resetting run-live state (world/config/logs) ──"
  ( cd "$RUN_DIR" && rm -rf world config logs usercache.json ops.json \
      banned-ips.json banned-players.json whitelist.json )
}

check_audit_capture() { # $1=file  asserts a CAPTURED audit output
  grep -qF "Money supply: 1,490 S$" "$1" || fail "audit: supply must be exactly 1,490 S$ (1,500 minus the burned 10 S$ listing fee)"
  grep -qF "Audit complete." "$1" || fail "audit: incomplete output"
  grep -q "!!" "$1" && fail "audit: reported invariant problems (!!) lines"
  grep -qF "Escrow holds 120 S$" "$1" || fail "audit: escrow-holds-120 line missing"
}

# ── Family lockstep (Task C-3أ) ────────────────────────────────────────────
# The 2.4.0 family must INSTALL TOGETHER on one Minecraft version. On the
# day of the C work package no MC version could host the family: Core said
# ~26.3 while every companion said ~26.1.2, so the loader rejected one side
# or the other — and because the companions' `solidus` floors accepted 2.4.0
# numerically, the failure looked like an MC-version problem, not a
# lockstep problem. These assertions pin the fix.
assert_family_loaded() { # boot.log only — the four mods must be IN the mod list
  echo "── family lockstep: all four 2.4.0 mods loaded on 26.3 ──"
  for mod in solidus solidus-analytics solidus-governance solidus-enforcer; do
    if grep -Eq "${mod} 2\.4\.0" boot.log; then
      pass "loader mod list contains ${mod} 2.4.0"
    else
      fail "loader mod list does not contain ${mod} 2.4.0 (lockstep break — the exact C-1/C-2 regression)"
    fi
  done
  # The exact failure modes the package describes — must stay absent.
  if grep -q "Incompatible mod set" boot.log; then
    fail "loader reported an incompatible mod set (fabric.mod.json floors disagree)"
  else
    pass "no 'Incompatible mod set' rejection"
  fi
  if grep -Eq "Mod '[^']+' requires" boot.log; then
    fail "loader rejected a mod over unmet requirements (the exact 'Mod ... requires ...' break)"
  else
    pass "no mod-requirement rejection lines"
  fi
  if grep -q "Missing or unsupported mandatory dependencies" boot.log; then
    fail "loader reported missing mandatory dependencies"
  else
    pass "no missing-mandatory-dependency rejection"
  fi
}

assert_family_entrypoints() { # init banners + cross-mod wiring from boot.log
  echo "── family lockstep: companion entrypoints + wiring ──"
  grep -qF "Solidus Enforcer initialized" boot.log \
    && pass "Enforcer entrypoint initialized" || fail "Enforcer init banner missing"
  grep -qF "Solidus Enforcer fully activated! Solidus Core available: true" boot.log \
    && pass "Enforcer fully activated with Core detected" \
    || fail "Enforcer did not fully activate against Core (integration seam broken)"
  grep -qF "Solidus Analytics initialized successfully." boot.log \
    && pass "Analytics entrypoint initialized" || fail "Analytics init banner missing"
  grep -Eq "Solidus Governance v.* initializing" boot.log \
    && pass "Governance entrypoint initialized" || fail "Governance init banner missing"
  grep -qF "Economy transaction hook registered: solidus-governance (1 active)" boot.log \
    && pass "Governance enforcement hook registered with Core" \
    || fail "Governance hook NOT registered — vetoes/taxes would silently no-op"
  grep -qF "CoreHookBridge: enforcement hook registered with Solidus Core" boot.log \
    && pass "CoreHookBridge confirms registration" \
    || fail "CoreHookBridge registration banner missing"
  grep -q "injection skipped" boot.log && fail "mixin injection skipped" \
    || pass "no skipped mixin injections (Enforcer mixins are require=1 — Done proves they applied)"
}

assert_core_compat_green() { # /solidus-admin compat output file
  echo "── family lockstep: Core compat report on 26.3 ──"
  grep -qF "Virtual GUI routing: HEALTHY" "$1" || fail "compat: GUI routing not HEALTHY"
  grep -qF "Container-click mixin applied: true" "$1" || fail "compat: container-click mixin not applied"
  grep -qF "GUI palette: EXACT (all 17 registry ids resolved)" "$1" \
    || fail "compat: palette not EXACT (Family A regression)"
  grep -qF "World drop: THREE_ARG_PREDICTION" "$1" \
    || fail "compat: drop strategy is not THREE_ARG_PREDICTION (Family B regression)"
  grep -q "!!" "$1" && fail "compat report contains !! failure lines"
}

assert_governance_tax_ledger() { # amount label — pending_taxes row must exist
  local want="$1" label="$2"
  python3 - "$RUN_DIR" "$want" "$label" <<'PY'
import sqlite3, glob, json, sys, pathlib
run, want, label = pathlib.Path(sys.argv[1]), float(sys.argv[2]), sys.argv[3]
dbs = glob.glob(str(run / "config/solidus-governance/tax_ledger*")) + \
      glob.glob(str(run / "config/*/tax_ledger*"))
rows = []
for p in dbs:
    if not p.endswith((".db", ".sqlite", ".sqlite3")):
        continue
    con = sqlite3.connect(p)
    rows += list(con.execute(
        "SELECT player_name, tax_type, amount FROM pending_taxes WHERE amount > 0"))
hit = [r for r in rows if r[0] == "Alice" and abs(float(r[2]) - want) < 0.02]
if hit:
    print(f"PASS {label}: pending_taxes row for Alice, amount={hit[0][2]} (type {hit[0][1]})")
    sys.exit(0)
print(f"FAIL {label}: no pending_taxes row for Alice ~{want} "
      f"(rows found: {rows}; tax_ledger dbs: {dbs}) — the Core->Governance "
      "money-path hook (afterTransfer) did not produce a ledger trace")
sys.exit(1)
PY
  local rc=$?
  [ $rc -eq 0 ] && pass "$2: transfer tax recorded in Governance ledger" || fail "$2: tax ledger trace missing"
}

case "$PHASE" in
reset)
  reset_data
  echo "PHASE reset: done"
  ;;

boot1)
  boot_server || { echo "PHASE boot1: BOOT FAILED"; exit 1; }
  echo "── أ-3: boot-log assertions ──"
  sleep 2
  expect_contains boot.log "Done (" "server reached Done"
  grep -q "GUI layer DISABLED" boot.log && fail "compat degradation banner present" \
    || pass "no GUI-degradation banner"
  grep -q "injection skipped" boot.log && fail "mixin injection skipped" \
    || pass "no skipped mixin injections"
  grep -qE "\[(main/ERROR|main/FATAL)\]" boot.log && fail "boot.log contains ERROR/FATAL lines" \
    || pass "no ERROR/FATAL log lines"

  echo "── أ-4: RCON economy scenario ──"
  rcon "solidus-admin compat" > /tmp/r-compat.txt 2>&1 \
    || fail "RCON: solidus-admin compat unreachable"
  cat /tmp/r-compat.txt; echo
  expect_contains /tmp/r-compat.txt "Virtual GUI routing: HEALTHY" "compat: GUI routing HEALTHY"
  expect_contains /tmp/r-compat.txt "Container-click mixin applied: true" "compat: mixin applied"

  if rcon_until "solidus-admin diag" > /tmp/r-diag.txt 2>&1; then
    cat /tmp/r-diag.txt; echo
    expect_contains /tmp/r-diag.txt "Backend: SQLite" "diag: backend=SQLite"
    expect_contains /tmp/r-diag.txt "local SQLite (auctions.db)" "diag: auction store=SQLITE"
    expect_contains /tmp/r-diag.txt "Redis layer: disabled" "diag: Redis=disabled"
  else
    echo "(diag output not captured via RCON this round — asserting via boot.log/DB)"
    grep -q "SQLite database initialized successfully" boot.log \
      || fail "diag fallback: SQLite init line missing from boot.log"
  fi

  # NOTE: the documented 'minecraft:diamond' item id does not parse — the
  # item_id argument is brigadier word() which rejects ':'. The bare material
  # name resolves to the same item (Identifier.tryParse defaults namespace).
  # Reported to the owner; the scenario uses the accepted form.
  rcon "solidus-admin account create Alice 1000" > /dev/null 2>&1 \
    || fail "RCON: create Alice unreachable"
  rcon "solidus-admin account create Bob 500" > /dev/null 2>&1 \
    || fail "RCON: create Bob unreachable"
  sleep 3
  assert_balances 1000 500 "post-create"

  rcon "solidus-admin pay-as Alice Bob 99.99" > /dev/null 2>&1 \
    || fail "RCON: pay-as unreachable"
  sleep 3
  assert_balances 900.01 599.99 "post-pay-as"
  if rcon_until "solidus-admin account balance Bob" > /tmp/r-bb.txt 2>&1; then
    cat /tmp/r-bb.txt; echo
    expect_contains /tmp/r-bb.txt "599.99 S$" "RCON capture: Bob balance = 599.99"
  else
    echo "(balance output not captured via RCON — DB assertion above is authoritative)"
  fi

  rcon "solidus-admin auction create Alice diamond 5 500 100" > /dev/null 2>&1 \
    || fail "RCON: auction create unreachable"
  sleep 3
  LISTING_ID="$(assert_open_listing "post-create-listing")"

  rcon "solidus-admin bid-as Bob $LISTING_ID 120" > /dev/null 2>&1 \
    || fail "RCON: bid-as unreachable"
  echo "waiting for the async escrow pipeline to settle..."
  sleep 8
  assert_bid_state 120 Bob "post-bid"
  assert_escrow 120 "post-bid"
  assert_balances 890.01 479.99 "post-bid (Alice minus 10 S$ listing fee)"

  if rcon_until "solidus-admin audit" > /tmp/r-audit1.txt 2>&1; then
    cat /tmp/r-audit1.txt; echo
    check_audit_capture /tmp/r-audit1.txt
  else
    echo "(audit output not captured via RCON — DB assertions above are authoritative)"
  fi

  stop_server || FAILED=1
  [ $FAILED -eq 0 ] && echo "PHASE boot1: ALL GREEN" || echo "PHASE boot1: FAILURES PRESENT"
  exit $FAILED
  ;;

boot2)
  boot_server || { echo "PHASE boot2: BOOT FAILED"; exit 1; }
  sleep 2
  echo "── أ-5: ESC-01 — funded escrow must survive the restart ──"
  sleep 4
  assert_escrow 120 "after-restart"
  assert_balances 890.01 479.99 "after-restart"
  assert_bid_state 120 Bob "after-restart"

  if rcon_until "solidus-admin audit" > /tmp/r-audit2.txt 2>&1; then
    cat /tmp/r-audit2.txt; echo
    check_audit_capture /tmp/r-audit2.txt
  else
    echo "(audit output not captured via RCON — DB assertions above are authoritative)"
  fi

  stop_server || FAILED=1
  [ $FAILED -eq 0 ] && echo "PHASE boot2: ALL GREEN (ESC-01 holds)" \
    || echo "PHASE boot2: FAILURES PRESENT"
  exit $FAILED
  ;;

logcheck)
  echo "── log hygiene check on $RUN_DIR/boot.log ──"
  cd "$RUN_DIR" || exit 1
  STATUS=0
  for pattern in "ERROR" "CRITICAL" "GUI layer DISABLED" "ESCROW CONSISTENCY" "SUPPLY INTEGRITY"; do
    if grep -qF "$pattern" boot.log; then
      echo "✗ FAIL: forbidden pattern in boot.log: $pattern"
      grep -nF "$pattern" boot.log | head -5
      STATUS=1
    fi
  done
  [ $STATUS -eq 0 ] && echo "✓ boot.log is clean (no ERROR/CRITICAL/degradation markers)"
  exit $STATUS
  ;;

familyboot)
  boot_server || { echo "PHASE familyboot: BOOT FAILED"; exit 1; }
  sleep 2
  echo "── C-3أ: family lockstep assertions ──"
  expect_contains boot.log "Done (" "server reached Done"
  assert_family_loaded
  assert_family_entrypoints

  rcon "solidus-admin compat" > /tmp/r-compat-fam.txt 2>&1 \
    || fail "RCON: solidus-admin compat unreachable"
  cat /tmp/r-compat-fam.txt; echo
  assert_core_compat_green /tmp/r-compat-fam.txt

  echo "── base economy scenario (identical expectations to boot1) ──"
  rcon "solidus-admin account create Alice 1000" > /dev/null 2>&1 \
    || fail "RCON: create Alice unreachable"
  rcon "solidus-admin account create Bob 500" > /dev/null 2>&1 \
    || fail "RCON: create Bob unreachable"
  sleep 3
  assert_balances 1000 500 "post-create"

  rcon "solidus-admin pay-as Alice Bob 99.99" > /dev/null 2>&1 \
    || fail "RCON: pay-as unreachable"
  sleep 3
  assert_balances 900.01 599.99 "post-pay-as"

  rcon "solidus-admin auction create Alice diamond 5 500 100" > /dev/null 2>&1 \
    || fail "RCON: auction create unreachable"
  sleep 3
  LISTING_ID="$(assert_open_listing "post-create-listing")"

  rcon "solidus-admin bid-as Bob $LISTING_ID 120" > /dev/null 2>&1 \
    || fail "RCON: bid-as unreachable"
  echo "waiting for the async escrow pipeline to settle..."
  sleep 8
  assert_bid_state 120 Bob "post-bid"
  assert_escrow 120 "post-bid"
  assert_balances 890.01 479.99 "post-bid (Alice minus 10 S$ listing fee)"

  echo "── C-3أ: Governance hook inside Core's money path (transfer tax) ──"
  # taxation.enabled=true is pre-written into governance.properties by the
  # workflow BEFORE this phase; the rate starts at 0.0 so the base scenario
  # above stays exactly as deterministic as boot1.
  rcon "governance tax set transfer 0.10" > /tmp/r-taxset.txt 2>&1 \
    || fail "RCON: governance tax set unreachable"
  cat /tmp/r-taxset.txt; echo
  rcon "solidus-admin pay-as Alice Bob 20" > /dev/null 2>&1 \
    || fail "RCON: tax-scenario pay-as unreachable"
  sleep 6
  # 0.10 x 20 = 2.00 S$ transfer tax on Alice, recorded in pending_taxes.
  # NOTE: the sender balance is deliberately NOT asserted here — tax
  # collection is an async retry queue, so the balance at read time depends
  # on whether a collection attempt already ran.
  assert_governance_tax_ledger 2.00 "governance-tax"

  echo "── Enforcer compass guard (informational probe) ──"
  # /hunter requires a ServerPlayer; from RCON (console) it must be refused.
  # This pins the guard only — the SERVER_ONLY drop-semantics pin needs an
  # online player client and is staged for the WS-8 two-server environment.
  rcon "hunter track Alice" > /tmp/r-hunter.txt 2>&1 || true
  cat /tmp/r-hunter.txt; echo

  echo "── final audit (supply conservation across the whole family scenario) ──"
  if rcon_until "solidus-admin audit" > /tmp/r-audit-fam.txt 2>&1; then
    cat /tmp/r-audit-fam.txt; echo
    check_audit_capture /tmp/r-audit-fam.txt
  else
    echo "(audit output not captured via RCON — DB assertions above are authoritative)"
  fi

  stop_server || FAILED=1
  [ $FAILED -eq 0 ] && echo "PHASE familyboot: ALL GREEN (family 2.4.0 installs together on 26.3)" \
    || echo "PHASE familyboot: FAILURES PRESENT"
  exit $FAILED
  ;;

familyboot2)
  boot_server || { echo "PHASE familyboot2: BOOT FAILED"; exit 1; }
  sleep 2
  echo "── C-3أ: second boot — family reloads, funded escrow survives ──"
  assert_family_loaded
  assert_family_entrypoints
  sleep 4
  assert_escrow 120 "after-restart"
  assert_bid_state 120 Bob "after-restart"

  if rcon_until "solidus-admin audit" > /tmp/r-audit-fam2.txt 2>&1; then
    cat /tmp/r-audit-fam2.txt; echo
    check_audit_capture /tmp/r-audit-fam2.txt
  else
    echo "(audit output not captured via RCON — DB assertions above are authoritative)"
  fi

  stop_server || FAILED=1
  [ $FAILED -eq 0 ] && echo "PHASE familyboot2: ALL GREEN (family state survives restart)" \
    || echo "PHASE familyboot2: FAILURES PRESENT"
  exit $FAILED
  ;;

*)
  echo "usage: $0 <run-dir> <reset|boot1|boot2|familyboot|familyboot2|logcheck>"; exit 2 ;;
esac
