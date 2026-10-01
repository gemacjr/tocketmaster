#!/usr/bin/env bash
# End-to-end smoke test against the local stack (nginx on :18080).
# Exercises: search -> view -> reserve -> confirm (webhook) -> double-booking rejection.
set -euo pipefail

BASE="${1:-http://localhost:18080}"
EVENT_ID="eeeeeee1-0000-0000-0000-000000000002"   # Marina Voss (no waiting room)
USER="smoke-$(date +%s)"
PASS=0

say()  { printf '\n\033[1;34m== %s ==\033[0m\n' "$*"; }
ok()   { printf '  \033[1;32m✔ %s\033[0m\n' "$*"; PASS=$((PASS+1)); }
die()  { printf '  \033[1;31m✘ %s\033[0m\n' "$*"; exit 1; }

command -v jq >/dev/null || die "jq is required"

say "1. Search (fuzzy typo: 'marina vos')"
RES=$(curl -sf "$BASE/api/search?q=marina+vos&size=5")
echo "$RES" | jq -e '.hits | length > 0' >/dev/null || die "expected hits"
SRC=$(echo "$RES" | jq -r '.source')
ok "search ok via $SRC ($(echo "$RES" | jq -r '.tookMs')ms)"

say "2. View event detail (cached static payload)"
curl -sf "$BASE/api/events/$EVENT_ID" | jq -e '.venue.seatMap.sections | length > 0' >/dev/null \
  || die "event detail missing seat map"
ok "detail + venue seat map present"

say "3. Fetch live seat map and pick two available seats"
SEATS=$(curl -sf "$BASE/api/events/$EVENT_ID/seats")
TICKETS=$(echo "$SEATS" | jq -r '
  [.sections[] | .rows[] | .seats[]
   | select(.status=="AVAILABLE")][:2] | map(.ticketId)')
COUNT=$(echo "$TICKETS" | jq 'length')
[ "$COUNT" = "2" ] || die "wanted 2 available seats, got $COUNT"
TOTAL=$(echo "$SEATS" | jq -r --argjson t "$(echo "$TICKETS")" '
  [ .sections[] | .rows[] | .seats[] | select(.ticketId as $id | $t | index($id))
  | .priceCents ] | add')
ok "picked tickets $(echo "$TICKETS" | jq -c .) total=$TOTAL"

say "4. Reserve (Redis hold, default 10-minute TTL)"
BOOK=$(curl -sf -X POST "$BASE/api/bookings/reservations" \
  -H "Content-Type: application/json" -H "X-User-Id: $USER" -H "X-Session-Id: sess-$USER" \
  -d "{\"eventId\":\"$EVENT_ID\",\"ticketIds\":$TICKETS}")
BOOKING_ID=$(echo "$BOOK" | jq -r '.bookingId')
[ -n "$BOOKING_ID" ] && [ "$BOOKING_ID" != "null" ] || die "reservation failed"
ok "booking $BOOKING_ID PENDING, expires $(echo "$BOOK" | jq -r '.expiresAt')"

say "5. Confirm with a good card (mock gateway -> idempotent webhook)"
CONFIRM=$(curl -sf -X POST "$BASE/api/bookings/$BOOKING_ID/confirm" \
  -H "Content-Type: application/json" -H "X-User-Id: $USER" \
  -d '{"cardToken":"tok_visa"}')
STATUS=$(echo "$CONFIRM" | jq -r '.status')
[ "$STATUS" = "CONFIRMED" ] || die "booking not CONFIRMED: $STATUS"
ok "booking CONFIRMED"

say "6. Verify seats are now SOLD in the live map"
SOLD=0
for T in $(echo "$TICKETS" | jq -r '.[]'); do
  ST=$(curl -sf "$BASE/api/events/$EVENT_ID/seats" | jq -r --arg id "$T" \
       '.sections[].rows[].seats[] | select(.ticketId==$id) | .status')
  [ "$ST" = "SOLD" ] && SOLD=$((SOLD+1))
done
[ "$SOLD" = "2" ] || die "expected both seats SOLD, got $SOLD"
ok "both seats show SOLD"

say "7. Double-booking rejected: second user reserves the same seats"
CODE=$(curl -s -o /dev/null -w '%{http_code}' -X POST "$BASE/api/bookings/reservations" \
  -H "Content-Type: application/json" -H "X-User-Id: other-$USER" \
  -H "X-Session-Id: sess-other-$USER" \
  -d "{\"eventId\":\"$EVENT_ID\",\"ticketIds\":$TICKETS}")
[ "$CODE" = "409" ] || die "expected 409 for sold seats, got $CODE"
ok "409 conflict as designed"

printf '\n\033[1;32mSMOKE PASS — %d checks\033[0m\n' "$PASS"
