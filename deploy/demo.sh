#!/bin/sh
# PayFlow demo: token -> seed wallets -> payments with Idempotency-Key -> poll to a terminal state -> print states.
# Needs the stack up (docker compose -f deploy/compose.yaml up -d --build --wait) and curl. Loopback only.
set -eu

TOKEN_URL="http://localhost:8180/realms/payflow/protocol/openid-connect/token"
WALLET_API="http://localhost:8082/api/v1/ops/wallets"
PAYMENT_API="http://localhost:8081/api/v1/payments"
CLIENT_ID="payflow-test"
# LOCAL DEMO VALUE: realm users alice and ops; each password equals the user name. Never reuse anywhere real.
ALICE_PASSWORD="alice"
OPS_PASSWORD="ops"
# Fixed Keycloak user ids from the realm export (wallet owners are the token subject).
ALICE_SUB="e45bda66-092a-4922-b8e0-b14c7b03b479"
BOB_SUB="ffbff25f-7df1-4acc-a432-40ec070024f7"
TIMEOUT_SECONDS=120

RUN="$(date +%s)"
PAYER="demo-payer-$RUN"
PAYEE="demo-payee-$RUN"

get_token() {
  curl -fsS -d grant_type=password -d "client_id=$CLIENT_ID" -d "username=$1" -d "password=$2" "$TOKEN_URL" \
    | sed -n 's/.*"access_token":"\([^"]*\)".*/\1/p'
}

json_field() { sed -n "s/.*\"$1\":\"\\([^\"]*\\)\".*/\\1/p"; }

seed_wallet() {
  curl -fsS -o /dev/null -X POST "$WALLET_API" \
    -H "Authorization: Bearer $OPS_TOKEN" -H "Content-Type: application/json" \
    -d "{\"id\":\"$1\",\"ownerSub\":\"$2\",\"initialBalance\":$3}"
}

send_payment() {
  curl -fsS -X POST "$PAYMENT_API" \
    -H "Authorization: Bearer $ALICE_TOKEN" -H "Content-Type: application/json" \
    -H "Idempotency-Key: demo-$RUN-$1" \
    -d "{\"payer\":\"$PAYER\",\"payee\":\"$PAYEE\",\"amount\":$2,\"currency\":\"INR\"}" \
    | json_field id
}

# Polls until COMPLETED, REJECTED or REVERSED; gives up after TIMEOUT_SECONDS.
wait_terminal() {
  deadline=$(( $(date +%s) + TIMEOUT_SECONDS ))
  while :; do
    state="$(curl -fsS -H "Authorization: Bearer $ALICE_TOKEN" "$PAYMENT_API/$1" | json_field state)"
    case "$state" in
      COMPLETED|REJECTED|REVERSED) echo "$state"; return 0 ;;
    esac
    if [ "$(date +%s)" -ge "$deadline" ]; then
      echo "TIMEOUT (last state: $state)"; return 1
    fi
    sleep 1
  done
}

echo "Getting tokens"
ALICE_TOKEN="$(get_token alice "$ALICE_PASSWORD")"
OPS_TOKEN="$(get_token ops "$OPS_PASSWORD")"

echo "Seeding wallets $PAYER (100000) and $PAYEE (5000)"
seed_wallet "$PAYER" "$ALICE_SUB" 100000
seed_wallet "$PAYEE" "$BOB_SUB" 5000

status=0
for spec in "ok-1:2500" "ok-2:1000" "too-big:9000000"; do
  name="${spec%%:*}"
  amount="${spec##*:}"
  id="$(send_payment "$name" "$amount")"
  state="$(wait_terminal "$id")" || status=1
  echo "payment $name amount=$amount id=$id -> $state"
done
exit "$status"
