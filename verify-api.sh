#!/bin/sh
# Asserts every P0 fix against the live local stack. Run with the backend up.
BASE=${BASE:-http://localhost:8080}
API=$BASE/api/v1
pass=0; fail=0
ok()   { pass=$((pass+1)); echo "  PASS  $1"; }
bad()  { fail=$((fail+1)); echo "  FAIL  $1"; }
check() { # check <label> <expected> <actual>
  if [ "$2" = "$3" ]; then ok "$1 ($3)"; else bad "$1 (expected $2, got $3)"; fi
}
jsonf() { printf '%s' "$1" | sed -n "s/.*\"$2\":\"\([^\"]*\)\".*/\1/p"; }

# Unique per run. /proc/sys/kernel/random/uuid doesn't exist on Git Bash, and piping a
# failed cat into cut yields success with empty output - which silently reused the
# previous run's usernames and made every request 401 after the 409.
STAMP="$$_$(date +%s)"
A="alice_$STAMP"; B="bob_$STAMP"

reg() { curl -s -X POST "$API/auth/register" -H 'Content-Type: application/json' \
          -d "{\"username\":\"$1\",\"password\":\"password123\"}"; }

echo "== setup =="
RA=$(reg "$A"); RB=$(reg "$B")
TA=$(jsonf "$RA" token); TB=$(jsonf "$RB" token)
IDA=$(jsonf "$RA" userId); IDB=$(jsonf "$RB" userId)
if [ -n "$TA" ] && [ -n "$TB" ]; then
  ok "registered two users"
else
  bad "registration ($RA / $RB)"
  echo "  setup failed; the rest of the checks would report false results"
  exit 1
fi

AUTHA="Authorization: Bearer $TA"; AUTHB="Authorization: Bearer $TB"
JSON='Content-Type: application/json'

# alice creates a PRIVATE activity with coordinates in Tbilisi
CREATED=$(curl -s -X POST "$API/activities" -H "$JSON" -H "$AUTHA" -d '{
  "title":"Secret plan","startTime":"2030-01-01T18:00:00Z",
  "lat":41.7151,"lng":44.8271,"addressText":"Vake Park",
  "visibility":"PRIVATE","inviteeUserIds":[]}')
AID=$(jsonf "$CREATED" id)
[ -n "$AID" ] && ok "alice created a private activity" || bad "create ($CREATED)"

echo "== S2: private activity is not readable by another user =="
check "alice reads her own" 200 "$(curl -s -o /dev/null -w '%{http_code}' -H "$AUTHA" "$API/activities/$AID")"
check "bob gets 404, not 200" 404 "$(curl -s -o /dev/null -w '%{http_code}' -H "$AUTHB" "$API/activities/$AID")"

echo "== S3: private activity is not on another user's map =="
BBOX="minLat=41.6&minLng=44.7&maxLat=41.8&maxLng=44.9&zoom=12"
MA=$(curl -s -H "$AUTHA" "$API/activities/map?$BBOX")
MB=$(curl -s -H "$AUTHB" "$API/activities/map?$BBOX")
case "$MA" in *"$AID"*) ok "alice sees her own pin" ;; *) bad "alice's own pin missing ($MA)" ;; esac
case "$MB" in *"$AID"*) bad "bob CAN see alice's private pin ($MB)" ;; *) ok "bob cannot see alice's private pin" ;; esac

echo "== B4: bad input is a 400 with a message, not a 500 =="
WIDE=$(curl -s -w '\n%{http_code}' -H "$AUTHA" "$API/activities/map?minLat=0&minLng=0&maxLat=80&maxLng=80&zoom=12")
check "too-wide bbox status" 400 "$(printf '%s' "$WIDE" | tail -n1)"
case "$WIDE" in *'"message"'*) ok "too-wide bbox has a message" ;; *) bad "no message ($WIDE)" ;; esac

BADENUM=$(curl -s -w '\n%{http_code}' -X POST "$API/activities" -H "$JSON" -H "$AUTHA" \
  -d '{"title":"x","startTime":"2030-01-01T18:00:00Z","visibility":"NOPE"}')
check "bad enum status" 400 "$(printf '%s' "$BADENUM" | tail -n1)"
case "$BADENUM" in *'"message"'*) ok "bad enum has a message" ;; *) bad "no message ($BADENUM)" ;; esac

BLANK=$(curl -s -w '\n%{http_code}' -X POST "$API/activities" -H "$JSON" -H "$AUTHA" \
  -d '{"title":"","startTime":"2030-01-01T18:00:00Z","visibility":"PUBLIC"}')
check "blank title status" 400 "$(printf '%s' "$BLANK" | tail -n1)"
case "$BLANK" in *'"fields"'*) ok "blank title names the field" ;; *) bad "no fields ($BLANK)" ;; esac

echo "== B5: wrong password =="
WRONG=$(curl -s -w '\n%{http_code}' -X POST "$API/auth/login" -H "$JSON" \
  -d "{\"username\":\"$A\",\"password\":\"wrong-password\"}")
check "wrong password status" 401 "$(printf '%s' "$WRONG" | tail -n1)"
case "$WRONG" in *INVALID_CREDENTIALS*) ok "wrong password is flagged INVALID_CREDENTIALS" ;; *) bad "no code ($WRONG)" ;; esac

echo "== duplicate username is a 409, not a 500 =="
DUP=$(curl -s -o /dev/null -w '%{http_code}' -X POST "$API/auth/register" -H "$JSON" \
  -d "{\"username\":\"$A\",\"password\":\"password123\"}")
check "duplicate username status" 409 "$DUP"

echo "== unauthenticated is 401, not 403 =="
check "no token" 401 "$(curl -s -o /dev/null -w '%{http_code}' "$API/activities/mine")"

echo "== S5: actuator is closed except health =="
check "/actuator/health" 200 "$(curl -s -o /dev/null -w '%{http_code}' $BASE/actuator/health)"
PROM=$(curl -s -o /dev/null -w '%{http_code}' $BASE/actuator/prometheus)
if [ "$PROM" = "200" ]; then bad "/actuator/prometheus is open ($PROM)"; else ok "/actuator/prometheus is closed ($PROM)"; fi

echo "== B1/B2: accepting a friend request persists a notification WITH a body =="
curl -s -o /dev/null -X POST "$API/friends/request" -H "$JSON" -H "$AUTHA" -d "{\"targetUserId\":\"$IDB\"}"
curl -s -o /dev/null -X POST "$API/friends/accept" -H "$JSON" -H "$AUTHB" -d "{\"targetUserId\":\"$IDA\"}"
sleep 2
NB=$(curl -s -H "$AUTHB" "$API/notifications")
case "$NB" in *FRIEND_ACCEPTED*) ok "notification row was committed" ;; *) bad "no notification persisted ($NB)" ;; esac
case "$NB" in *'"body":null'*) bad "notification body is null (breaks the client)" ;; *) ok "notification body is non-null" ;; esac

echo "== S7: inviting a non-friend is a 400 with a message, not a 500 =="
STRANGER=00000000-0000-0000-0000-0000000000ff
INV=$(curl -s -w '\n%{http_code}' -X POST "$API/activities" -H "$JSON" -H "$AUTHA" -d "{
  \"title\":\"Party\",\"startTime\":\"2030-01-01T18:00:00Z\",\"visibility\":\"FRIENDS\",
  \"inviteeUserIds\":[\"$STRANGER\"]}")
check "unknown invitee status" 400 "$(printf '%s' "$INV" | tail -n1)"

echo "== friends-only visibility now that they are friends =="
FR=$(curl -s -X POST "$API/activities" -H "$JSON" -H "$AUTHA" -d '{
  "title":"Friends plan","startTime":"2030-01-02T18:00:00Z","visibility":"FRIENDS","inviteeUserIds":[]}')
FID=$(jsonf "$FR" id)
check "bob reads alice's FRIENDS activity" 200 "$(curl -s -o /dev/null -w '%{http_code}' -H "$AUTHB" "$API/activities/$FID")"

echo "== B3: feed pagination terminates and does not repeat =="
P1=$(curl -s -H "$AUTHB" "$API/feed?limit=1")
C1=$(printf '%s' "$P1" | sed -n 's/.*"nextCursor":\([0-9]*\).*/\1/p')
if [ -n "$C1" ]; then
  P2=$(curl -s -H "$AUTHB" "$API/feed?limit=1&cursor=$C1")
  ID1=$(printf '%s' "$P1" | sed -n 's/.*"activityId":"\([^"]*\)".*/\1/p' | head -1)
  ID2=$(printf '%s' "$P2" | sed -n 's/.*"activityId":"\([^"]*\)".*/\1/p' | head -1)
  if [ "$ID1" = "$ID2" ] && [ -n "$ID2" ]; then bad "page 2 repeats page 1 ($ID1)"; else ok "page 2 differs from page 1"; fi
  C2=$(printf '%s' "$P2" | sed -n 's/.*"nextCursor":\([0-9]*\).*/\1/p')
  if [ -n "$C2" ] && [ "$C2" = "$C1" ]; then bad "cursor did not advance"; else ok "cursor advanced or ended"; fi
else
  ok "feed ended after one page (no cursor)"
fi

echo "== B7: free text is read in the caller's time zone =="
TZC=$(curl -s -X POST "$API/activities/from-text" -H "$JSON" -H "$AUTHA" \
  -d '{"rawText":"Coffee at Vake park tomorrow at 7pm","visibility":"FRIENDS","timeZone":"Asia/Tbilisi"}')
case "$TZC" in *T19:00:00+04:00*) ok "7pm Tbilisi stays 19:00+04:00" ;; *) bad "wrong instant ($TZC)" ;; esac
case "$TZC" in *'"title":"Coffee"'*) ok "title excludes the time and place" ;; *) bad "title not cleaned ($TZC)" ;; esac

MINE=$(curl -s -H "$AUTHA" "$API/activities/mine")
case "$MINE" in *'"addressText":"Vake park"'*) ok "text-created plan kept its address" ;; *) bad "address dropped" ;; esac

echo "== B8: Georgian free text finds a time and a place =="
# Written to a file: passing UTF-8 through a Windows command line turns Georgian
# into "?" before curl ever sees it, which looks exactly like a parser failure.
GE_BODY=$(mktemp)
printf '%s' '{"rawText":"ყავა ვაკის პარკში ხვალ 19 საათზე","visibility":"FRIENDS","timeZone":"Asia/Tbilisi"}' > "$GE_BODY"
GEC=$(curl -s -X POST "$API/activities/from-text" -H 'Content-Type: application/json; charset=utf-8' \
  -H "$AUTHA" --data-binary "@$GE_BODY")
rm -f "$GE_BODY"
case "$GEC" in *T19:00:00+04:00*) ok "Georgian '19 საათზე' resolves to 19:00+04:00" ;; *) bad "no Georgian time found ($GEC)" ;; esac
case "$GEC" in *'"title":"ყავა"'*) ok "Georgian title has the place and time stripped" ;; *) bad "Georgian title not cleaned ($GEC)" ;; esac

echo
echo "==== $pass passed, $fail failed ===="
[ "$fail" -eq 0 ]
