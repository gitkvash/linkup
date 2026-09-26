#!/bin/sh
# GROUP visibility (V18). Every check is about who can and cannot see a plan shared with
# a group, so each one asserts on content rather than on a status code alone - a policy
# that matches nothing looks exactly like data that does not exist.
BASE=${BASE:-http://localhost:8081}
API=$BASE/api/v1
pass=0; fail=0
ok()  { pass=$((pass+1)); echo "  PASS  $1"; }
bad() { fail=$((fail+1)); echo "  FAIL  $1"; }
check() { if [ "$2" = "$3" ]; then ok "$1 ($3)"; else bad "$1 (expected $2, got $3)"; fi; }
jsonf() { printf '%s' "$1" | sed -n "s/.*\"$2\":\"\([^\"]*\)\".*/\1/p"; }
JSON='Content-Type: application/json'

STAMP="$$_$(date +%s)"
A="ga_$STAMP"; B="gb_$STAMP"; C="gc_$STAMP"
reg() { curl -s -X POST "$API/auth/register" -H "$JSON" -d "{\"username\":\"$1\",\"password\":\"password123\"}"; }

echo "== setup: owner, member, outsider =="
RA=$(reg "$A"); RB=$(reg "$B"); RC=$(reg "$C")
TA=$(jsonf "$RA" token); TB=$(jsonf "$RB" token); TC=$(jsonf "$RC" token)
IDA=$(jsonf "$RA" userId); IDB=$(jsonf "$RB" userId); IDC=$(jsonf "$RC" userId)
if [ -n "$TA" ] && [ -n "$TB" ] && [ -n "$TC" ]; then ok "registered"; else bad "registration"; exit 1; fi
AUTHA="Authorization: Bearer $TA"; AUTHB="Authorization: Bearer $TB"; AUTHC="Authorization: Bearer $TC"

GROUP=$(curl -s -X POST "$API/groups" -H "$JSON" -H "$AUTHA" -d "{\"groupName\":\"Riders $STAMP\"}")
GID=$(printf '%s' "$GROUP" | sed -n 's/.*"id":"\([^"]*\)".*/\1/p')
curl -s -o /dev/null -X POST "$API/groups/$GID/members" -H "$JSON" -H "$AUTHA" -d "{\"userId\":\"$IDB\"}"
if [ -n "$GID" ]; then ok "group created with a second member"; else bad "group ($GROUP)"; exit 1; fi

# Deliberately NOT friends: proves visibility comes from the group, not from friendship.
echo "== a GROUP plan =="
PLAN=$(curl -s -X POST "$API/activities" -H "$JSON" -H "$AUTHA" -d "{
  \"title\":\"Group ride\",\"startTime\":\"2031-05-01T09:00:00Z\",\"lat\":41.7,\"lng\":44.8,
  \"addressText\":\"Rike Park\",\"visibility\":\"GROUP\",\"groupId\":\"$GID\"}")
PID=$(jsonf "$PLAN" id)
if [ -n "$PID" ]; then ok "created"; else bad "create ($PLAN)"; exit 1; fi
case "$PLAN" in *"\"groupId\":\"$GID\""*) ok "the response carries the group id" ;; *) bad "no groupId ($PLAN)" ;; esac

DETAIL=$(curl -s -H "$AUTHB" "$API/activities/$PID")
case "$DETAIL" in *'"title":"Group ride"'*) ok "a fellow member can read it" ;; *) bad "member cannot see it ($DETAIL)" ;; esac
case "$DETAIL" in *"\"groupName\":\"Riders $STAMP\""*) ok "it names the group, not just \"Group\"" ;; *) bad "no groupName ($DETAIL)" ;; esac
check "an outsider gets 404, not 403" 404 \
  "$(curl -s -o /dev/null -w '%{http_code}' -H "$AUTHC" "$API/activities/$PID")"

echo "== the group is the audience, and the map agrees =="
MAPB=$(curl -s -H "$AUTHB" "$API/activities/map?minLat=41.6&maxLat=41.8&minLng=44.7&maxLng=44.9&zoom=14")
case "$MAPB" in *lat*) ok "the member sees the pin" ;; *) bad "empty map for member ($MAPB)" ;; esac
MAPC=$(curl -s -H "$AUTHC" "$API/activities/map?minLat=41.6&maxLat=41.8&minLng=44.7&maxLng=44.9&zoom=14")
case "$MAPC" in *"$PID"*) bad "the outsider's map leaks the activity id" ;; *) ok "the outsider's map does not name it" ;; esac

echo "== it reaches the group's feed, not just the map =="
# A and B are not friends, so the ordinary friend fan-out would never have delivered this.
# The failure mode without the group branch is silent: a 201, a pin on the map, and an
# empty feed.
sleep 3
FEEDB=$(curl -s -H "$AUTHB" "$API/feed")
case "$FEEDB" in *"$PID"*) ok "a member who is not a friend gets it in their feed" ;; *) bad "fan-out missed the group ($FEEDB)" ;; esac
FEEDC=$(curl -s -H "$AUTHC" "$API/feed")
case "$FEEDC" in *"$PID"*) bad "the outsider's feed leaks it" ;; *) ok "the outsider's feed does not" ;; esac

echo "== joining follows the same rule =="
JOIN=$(curl -s -X POST -H "$AUTHB" "$API/activities/$PID/join")
case "$JOIN" in *JOINED*) ok "a member can join" ;; *) bad "member join ($JOIN)" ;; esac
check "an outsider cannot join" 404 \
  "$(curl -s -o /dev/null -w '%{http_code}' -X POST -H "$AUTHC" "$API/activities/$PID/join")"

echo "== leaving the group takes the plan with it =="
check "owner removes the member" 204 \
  "$(curl -s -o /dev/null -w '%{http_code}' -X DELETE -H "$AUTHA" "$API/groups/$GID/members/$IDB")"
# B stays a *participant* from the join above, and that is its own grant - so this
# asserts on the freshly created plan below instead, which B never joined.
PLAN2=$(curl -s -X POST "$API/activities" -H "$JSON" -H "$AUTHA" -d "{
  \"title\":\"After they left\",\"startTime\":\"2031-05-02T09:00:00Z\",
  \"visibility\":\"GROUP\",\"groupId\":\"$GID\"}")
PID2=$(jsonf "$PLAN2" id)
check "an ex-member cannot see a later group plan" 404 \
  "$(curl -s -o /dev/null -w '%{http_code}' -H "$AUTHB" "$API/activities/$PID2")"

echo "== bad input =="
NOGROUP=$(curl -s -w '\n%{http_code}' -X POST "$API/activities" -H "$JSON" -H "$AUTHA" -d '{
  "title":"No group","startTime":"2031-05-03T09:00:00Z","visibility":"GROUP"}')
check "GROUP with no group id is a 400" 400 "$(printf '%s' "$NOGROUP" | tail -n1)"
case "$NOGROUP" in *groupId*) ok "the error names the groupId field" ;; *) bad "unhelpful error ($NOGROUP)" ;; esac

NOTMINE=$(curl -s -w '\n%{http_code}' -X POST "$API/activities" -H "$JSON" -H "$AUTHC" -d "{
  \"title\":\"Not my group\",\"startTime\":\"2031-05-04T09:00:00Z\",
  \"visibility\":\"GROUP\",\"groupId\":\"$GID\"}")
check "sharing with someone else's group is a 400" 400 "$(printf '%s' "$NOTMINE" | tail -n1)"

echo "== a group id on a non-GROUP plan is ignored, not an error =="
# The visibility is what the user picked; a stale group id from a form they changed
# their mind on should not fail the request - and must not be stored either, or the
# CHECK constraint would reject the row.
STALE=$(curl -s -X POST "$API/activities" -H "$JSON" -H "$AUTHA" -d "{
  \"title\":\"Public anyway\",\"startTime\":\"2031-05-05T09:00:00Z\",
  \"visibility\":\"PUBLIC\",\"groupId\":\"$GID\"}")
SID=$(jsonf "$STALE" id)
if [ -n "$SID" ]; then ok "created without complaint"; else bad "stale groupId rejected ($STALE)"; fi
SEEN=$(curl -s -H "$AUTHC" "$API/activities/$SID")
case "$SEEN" in *'"title":"Public anyway"'*) ok "still public to everyone" ;; *) bad "not public ($SEEN)" ;; esac
case "$SEEN" in *'"groupId":null'*|*'"groupName":null'*) ok "the group id was dropped" ;; *) bad "group id was kept ($SEEN)" ;; esac

echo
echo "==== $pass passed, $fail failed ===="
[ "$fail" -eq 0 ]
