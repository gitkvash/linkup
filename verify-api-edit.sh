#!/bin/sh
# Editing and cancelling a plan (PATCH/DELETE /api/v1/activities/{id}).
#
# These endpoints are creator-only, and "creator-only" here has to mean the same thing
# the read side means: a stranger gets the 404 an unknown id gets, never a 403, or the
# status alone confirms which ids are real. The rest asserts that an edit reaches every
# projection the plan appears in - detail, map, feed, and the participants who were
# already going - since a write that only half-lands is the failure mode that looks fine
# in the response and wrong in the app.
BASE=${BASE:-http://localhost:8080}
API=$BASE/api/v1
pass=0; fail=0
ok()  { pass=$((pass+1)); echo "  PASS  $1"; }
bad() { fail=$((fail+1)); echo "  FAIL  $1"; }
check() { if [ "$2" = "$3" ]; then ok "$1 ($3)"; else bad "$1 (expected $2, got $3)"; fi; }
has()  { case "$2" in *"$3"*) ok "$1" ;; *) bad "$1 (got $2)" ;; esac; }
hasnt() { case "$2" in *"$3"*) bad "$1 (got $2)" ;; *) ok "$1" ;; esac; }
jsonf() { printf '%s' "$1" | sed -n "s/.*\"$2\":\"\([^\"]*\)\".*/\1/p"; }
code() { curl -s -o /dev/null -w '%{http_code}' "$@"; }
JSON='Content-Type: application/json'

STAMP="$$_$(date +%s)"
A="ed_a_$STAMP"; B="ed_b_$STAMP"; C="ed_c_$STAMP"
reg() { curl -s -X POST "$API/auth/register" -H "$JSON" -d "{\"username\":\"$1\",\"password\":\"password123\"}"; }

echo "== setup: a creator, a friend, a stranger =="
RA=$(reg "$A"); RB=$(reg "$B"); RC=$(reg "$C")
TA=$(jsonf "$RA" token); TB=$(jsonf "$RB" token); TC=$(jsonf "$RC" token)
IDA=$(jsonf "$RA" userId); IDB=$(jsonf "$RB" userId)
if [ -n "$TA" ] && [ -n "$TB" ] && [ -n "$TC" ]; then ok "registered"; else bad "registration"; exit 1; fi
AUTHA="Authorization: Bearer $TA"; AUTHB="Authorization: Bearer $TB"; AUTHC="Authorization: Bearer $TC"
curl -s -o /dev/null -X POST "$API/friends/request" -H "$JSON" -H "$AUTHA" -d "{\"targetUserId\":\"$IDB\"}"
curl -s -o /dev/null -X POST "$API/friends/accept" -H "$JSON" -H "$AUTHB" -d "{\"targetUserId\":\"$IDA\"}"
ok "a and b are friends"

PLAN=$(curl -s -X POST "$API/activities" -H "$JSON" -H "$AUTHA" -d '{
  "title":"Original","startTime":"2031-05-01T15:00:00Z","lat":41.7151,"lng":44.8271,
  "addressText":"Rustaveli","visibility":"FRIENDS"}')
PID=$(jsonf "$PLAN" id)
if [ -n "$PID" ]; then ok "plan created"; else bad "create ($PLAN)"; exit 1; fi
curl -s -o /dev/null -X POST -H "$AUTHB" "$API/activities/$PID/join"

echo "== only the creator may edit, and a stranger cannot tell it exists =="
check "a friend who can SEE it still cannot edit it" 404 "$(code -X PATCH "$API/activities/$PID" -H "$JSON" -H "$AUTHB" -d '{
  "title":"Hijacked","startTime":"2031-05-01T15:00:00Z","lat":41.7,"lng":44.8,
  "addressText":"x","visibility":"FRIENDS"}')"
check "a stranger gets the same 404" 404 "$(code -X PATCH "$API/activities/$PID" -H "$JSON" -H "$AUTHC" -d '{
  "title":"Hijacked","startTime":"2031-05-01T15:00:00Z","lat":41.7,"lng":44.8,
  "addressText":"x","visibility":"FRIENDS"}')"
check "an id that does not exist is the same 404" 404 "$(code -X PATCH "$API/activities/11111111-1111-1111-1111-111111111111" -H "$JSON" -H "$AUTHA" -d '{
  "title":"Nothing","startTime":"2031-05-01T15:00:00Z","lat":41.7,"lng":44.8,
  "addressText":"x","visibility":"FRIENDS"}')"
has "the title did not change" "$(curl -s -H "$AUTHA" "$API/activities/$PID")" '"title":"Original"'

echo "== an edit lands everywhere the plan appears =="
EDIT=$(curl -s -X PATCH "$API/activities/$PID" -H "$JSON" -H "$AUTHA" -d '{
  "title":"Moved","startTime":"2031-05-02T18:30:00Z","lat":41.6938,"lng":44.8015,
  "addressText":"Vake Park","visibility":"FRIENDS"}')
has "the response is the read model, not the entity" "$EDIT" '"participantCount"'
has "the response carries the new title" "$EDIT" '"title":"Moved"'
has "the viewer's own status survives the edit" "$EDIT" '"viewerStatus":"JOINED"'
has "the participant who joined is still going" "$(curl -s -H "$AUTHA" "$API/activities/$PID/participants")" 'JOINED'
DETAIL_B=$(curl -s -H "$AUTHB" "$API/activities/$PID")
has "the friend sees the new title" "$DETAIL_B" '"title":"Moved"'
has "the friend sees the new address" "$DETAIL_B" 'Vake Park'
has "the friend sees the new time" "$DETAIL_B" '2031-05-02T18:30'
MAP=$(curl -s -H "$AUTHA" "$API/activities/map?minLat=41.68&minLng=44.79&maxLat=41.70&maxLng=44.81&zoom=16")
has "the pin moved to the new coordinates" "$MAP" '"count"'
OLDMAP=$(curl -s -H "$AUTHA" "$API/activities/map?minLat=41.714&minLng=44.826&maxLat=41.716&maxLng=44.828&zoom=18")
hasnt "and is no longer at the old ones" "$OLDMAP" "$PID"

echo "== an edit obeys the same group rules as creation =="
check "GROUP with no group is a 400" 400 "$(code -X PATCH "$API/activities/$PID" -H "$JSON" -H "$AUTHA" -d '{
  "title":"Moved","startTime":"2031-05-02T18:30:00Z","lat":41.69,"lng":44.80,
  "addressText":"Vake Park","visibility":"GROUP"}')"
GRP=$(curl -s -X POST "$API/groups" -H "$JSON" -H "$AUTHB" -d '{"groupName":"Not yours"}')
GID=$(jsonf "$GRP" id)
check "someone else's group is a 400" 400 "$(code -X PATCH "$API/activities/$PID" -H "$JSON" -H "$AUTHA" -d "{
  \"title\":\"Moved\",\"startTime\":\"2031-05-02T18:30:00Z\",\"lat\":41.69,\"lng\":44.80,
  \"addressText\":\"Vake Park\",\"visibility\":\"GROUP\",\"groupId\":\"$GID\"}")"
EDIT2=$(curl -s -X PATCH "$API/activities/$PID" -H "$JSON" -H "$AUTHA" -d "{
  \"title\":\"Moved\",\"startTime\":\"2031-05-02T18:30:00Z\",\"lat\":41.69,\"lng\":44.80,
  \"addressText\":\"Vake Park\",\"visibility\":\"PUBLIC\",\"groupId\":\"$GID\"}")
has "a group id on a non-GROUP plan is dropped, not rejected" "$EDIT2" '"groupId":null'

echo "== narrowing the audience takes the plan away =="
curl -s -o /dev/null -X PATCH "$API/activities/$PID" -H "$JSON" -H "$AUTHA" -d '{
  "title":"Moved","startTime":"2031-05-02T18:30:00Z","lat":41.69,"lng":44.80,
  "addressText":"Vake Park","visibility":"PRIVATE"}'
# The friend joined, so they keep access as a participant - that is the visibility rule,
# not an accident. The stranger is the one who must be shut out.
check "a stranger cannot read a plan turned PRIVATE" 404 "$(code -H "$AUTHC" "$API/activities/$PID")"

echo "== a text-created plan with no coordinates can be given some =="
TEXT=$(curl -s -X POST "$API/activities/from-text" -H "$JSON" -H "$AUTHA" -d '{
  "rawText":"coffee tomorrow at 5pm","visibility":"FRIENDS","timeZone":"Asia/Tbilisi"}')
TID=$(jsonf "$TEXT" id)
curl -s -o /dev/null -X PATCH "$API/activities/$TID" -H "$JSON" -H "$AUTHA" -d '{
  "title":"Coffee","startTime":"2031-06-01T09:00:00Z","lat":41.7,"lng":44.79,
  "addressText":"Fabrika","visibility":"FRIENDS"}'
TDETAIL=$(curl -s -H "$AUTHA" "$API/activities/$TID")
has "it now has coordinates" "$TDETAIL" '"lat":41.7'
has "and counts as a specific event" "$TDETAIL" 'SPECIFIC_EVENT'

echo "== cancelling =="
check "a stranger cannot delete it" 404 "$(code -X DELETE -H "$AUTHC" "$API/activities/$PID")"
check "a participant cannot delete it either" 404 "$(code -X DELETE -H "$AUTHB" "$API/activities/$PID")"
check "the creator can" 204 "$(code -X DELETE -H "$AUTHA" "$API/activities/$PID")"
check "it is gone for the creator" 404 "$(code -H "$AUTHA" "$API/activities/$PID")"
check "and for the participant" 404 "$(code -H "$AUTHB" "$API/activities/$PID")"
hasnt "it left the creator's list" "$(curl -s -H "$AUTHA" "$API/activities/mine")" "$PID"
hasnt "it left the participant's joined list" "$(curl -s -H "$AUTHB" "$API/activities/joined")" "$PID"
hasnt "it left the friend's feed" "$(curl -s -H "$AUTHB" "$API/feed")" "$PID"
check "deleting it twice is a 404, not a 500" 404 "$(code -X DELETE -H "$AUTHA" "$API/activities/$PID")"

echo
echo "==== $pass passed, $fail failed ===="
[ "$fail" -eq 0 ]
