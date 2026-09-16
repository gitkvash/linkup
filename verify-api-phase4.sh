#!/bin/sh
# Phase 4: the paths that would come back EMPTY rather than fail if the least-privilege
# role were wired up wrong. A policy that matches nothing is indistinguishable from data
# that does not exist, so each of these asserts on content, never on a status code alone.
BASE=${BASE:-http://localhost:8081}
API=$BASE/api/v1
pass=0; fail=0
ok()  { pass=$((pass+1)); echo "  PASS  $1"; }
bad() { fail=$((fail+1)); echo "  FAIL  $1"; }
check() { if [ "$2" = "$3" ]; then ok "$1 ($3)"; else bad "$1 (expected $2, got $3)"; fi; }
jsonf() { printf '%s' "$1" | sed -n "s/.*\"$2\":\"\([^\"]*\)\".*/\1/p"; }
JSON='Content-Type: application/json'

STAMP="$$_$(date +%s)"
A="p4a_$STAMP"; B="p4b_$STAMP"
reg() { curl -s -X POST "$API/auth/register" -H "$JSON" -d "{\"username\":\"$1\",\"password\":\"password123\"}"; }

echo "== setup: two friends =="
RA=$(reg "$A"); RB=$(reg "$B")
TA=$(jsonf "$RA" token); TB=$(jsonf "$RB" token)
IDA=$(jsonf "$RA" userId); IDB=$(jsonf "$RB" userId)
if [ -n "$TA" ] && [ -n "$TB" ]; then ok "registered"; else bad "registration"; exit 1; fi
AUTHA="Authorization: Bearer $TA"; AUTHB="Authorization: Bearer $TB"

curl -s -o /dev/null -X POST "$API/friends/request" -H "$JSON" -H "$AUTHA" -d "{\"targetUserId\":\"$IDB\"}"
curl -s -o /dev/null -X POST "$API/friends/accept" -H "$JSON" -H "$AUTHB" -d "{\"targetUserId\":\"$IDA\"}"
ok "friendship accepted"

echo "== fan-out runs on the system role =="
# FeedFanOutService reads the CREATOR's friend list from a background thread. There is no
# user context there, so under the app role the friendships policy would match no rows and
# the activity would simply never reach anyone's timeline - silently, with a 201 and a
# green log line.
PLAN=$(curl -s -X POST "$API/activities" -H "$JSON" -H "$AUTHA" -d '{
  "title":"Fanned out","startTime":"2031-01-05T09:00:00Z","lat":41.7,"lng":44.8,
  "addressText":"Rike Park","visibility":"FRIENDS"}')
PID=$(jsonf "$PLAN" id)
if [ -n "$PID" ]; then ok "activity created"; else bad "create ($PLAN)"; exit 1; fi
sleep 3
FEED=$(curl -s -H "$AUTHB" "$API/feed")
case "$FEED" in *"$PID"*) ok "it reached the friend's feed" ;; *) bad "fan-out produced nothing ($FEED)" ;; esac

echo "== notifications are written for someone other than the caller =="
# Same shape: the dispatcher inserts a row whose recipient is not the current user, which
# notifications_insert_policy forbids for the app role.
INV=$(curl -s -X POST "$API/activities" -H "$JSON" -H "$AUTHA" -d "{
  \"title\":\"Invite me\",\"startTime\":\"2031-01-06T18:00:00Z\",\"visibility\":\"FRIENDS\",
  \"inviteeUserIds\":[\"$IDB\"]}")
sleep 3
NB=$(curl -s -H "$AUTHB" "$API/notifications")
case "$NB" in *ACTIVITY_INVITE*) ok "the invitee got a notification" ;; *) bad "no notification ($NB)" ;; esac

echo "== the map query still reads locations =="
# locations is newly under RLS, and the query needs SELECT on spatial_ref_sys for
# ST_Transform. Missing either gives an empty map rather than an error.
MAP=$(curl -s -H "$AUTHA" "$API/activities/map?minLat=41.6&maxLat=41.8&minLng=44.7&maxLng=44.9&zoom=14")
case "$MAP" in *lat*) ok "the creator sees their own pin" ;; *) bad "empty map ($MAP)" ;; esac
MAPB=$(curl -s -H "$AUTHB" "$API/activities/map?minLat=41.6&maxLat=41.8&minLng=44.7&maxLng=44.9&zoom=14")
case "$MAPB" in *lat*) ok "the friend sees it too" ;; *) bad "empty map for friend ($MAPB)" ;; esac

echo "== ordinary reads and writes are unaffected =="
DETAIL=$(curl -s -H "$AUTHB" "$API/activities/$PID")
case "$DETAIL" in *'"title":"Fanned out"'*) ok "friend reads the detail" ;; *) bad "detail ($DETAIL)" ;; esac
JOIN=$(curl -s -X POST -H "$AUTHB" "$API/activities/$PID/join")
case "$JOIN" in *JOINED*) ok "friend joins" ;; *) bad "join ($JOIN)" ;; esac
GROUP=$(curl -s -X POST "$API/groups" -H "$JSON" -H "$AUTHA" -d '{"groupName":"P4"}')
GID=$(printf '%s' "$GROUP" | sed -n 's/.*"id":"\([^"]*\)".*/\1/p')
if [ -n "$GID" ]; then ok "group created (owner membership insert passed its policy)"; else bad "group ($GROUP)"; fi
check "add a member" 204 "$(curl -s -o /dev/null -w '%{http_code}' -X POST "$API/groups/$GID/members" -H "$JSON" -H "$AUTHA" -d "{\"userId\":\"$IDB\"}")"
MEMBERS=$(curl -s -H "$AUTHA" "$API/groups/$GID/members")
case "$MEMBERS" in *"$B"*) ok "member list reads back" ;; *) bad "members ($MEMBERS)" ;; esac

echo "== device token re-registration still steals the token =="
# device_tokens_delete_policy is deliberately unscoped so that signing in as someone else
# on the same phone keeps working. Scoping it to the owner would turn this into a 409.
TOK="p4-token-$STAMP"
check "user A claims the device" 204 "$(curl -s -o /dev/null -w '%{http_code}' -X POST "$API/notifications/device-token" -H "$JSON" -H "$AUTHA" -d "{\"token\":\"$TOK\",\"platform\":\"ANDROID\"}")"
check "user B claims the same device" 204 "$(curl -s -o /dev/null -w '%{http_code}' -X POST "$API/notifications/device-token" -H "$JSON" -H "$AUTHB" -d "{\"token\":\"$TOK\",\"platform\":\"ANDROID\"}")"

echo
echo "==== $pass passed, $fail failed ===="
[ "$fail" -eq 0 ]
