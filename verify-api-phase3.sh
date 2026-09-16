#!/bin/sh
# Phase 3: user directory, join/RSVP, read state, friend inbox, group members.
BASE=${BASE:-http://localhost:8081}
API=$BASE/api/v1
pass=0; fail=0
ok()  { pass=$((pass+1)); echo "  PASS  $1"; }
bad() { fail=$((fail+1)); echo "  FAIL  $1"; }
check() { if [ "$2" = "$3" ]; then ok "$1 ($3)"; else bad "$1 (expected $2, got $3)"; fi; }
jsonf() { printf '%s' "$1" | sed -n "s/.*\"$2\":\"\([^\"]*\)\".*/\1/p"; }
JSON='Content-Type: application/json'

STAMP="$$_$(date +%s)"
A="ana_$STAMP"; B="bidz_$STAMP"; C="cato_$STAMP"
reg() { curl -s -X POST "$API/auth/register" -H "$JSON" -d "{\"username\":\"$1\",\"password\":\"password123\"}"; }

echo "== setup: three users =="
RA=$(reg "$A"); RB=$(reg "$B"); RC=$(reg "$C")
TA=$(jsonf "$RA" token); TB=$(jsonf "$RB" token); TC=$(jsonf "$RC" token)
IDA=$(jsonf "$RA" userId); IDB=$(jsonf "$RB" userId); IDC=$(jsonf "$RC" userId)
if [ -n "$TA" ] && [ -n "$TB" ] && [ -n "$TC" ]; then ok "registered"; else bad "registration"; exit 1; fi
AUTHA="Authorization: Bearer $TA"; AUTHB="Authorization: Bearer $TB"; AUTHC="Authorization: Bearer $TC"

echo "== M3/M4: user directory =="
ME=$(curl -s -H "$AUTHA" "$API/users/me")
case "$ME" in *"\"username\":\"$A\""*) ok "/users/me returns the caller" ;; *) bad "/users/me ($ME)" ;; esac
case "$ME" in *passwordHash*) bad "SECURITY: /users/me leaks the password hash" ;; *) ok "no password hash in the response" ;; esac

SEARCH=$(curl -s -H "$AUTHA" "$API/users/search?q=bidz_$STAMP")
case "$SEARCH" in *"$IDB"*) ok "search finds bidz by username" ;; *) bad "search ($SEARCH)" ;; esac
SELF=$(curl -s -H "$AUTHA" "$API/users/search?q=ana_$STAMP")
case "$SELF" in *"$IDA"*) bad "search returns the caller themselves" ;; *) ok "search excludes the caller" ;; esac
SHORT=$(curl -s -H "$AUTHA" "$API/users/search?q=a")
check "one-character search returns nothing" "[]" "$SHORT"

echo "== M5: friend request inbox + notification =="
curl -s -o /dev/null -X POST "$API/friends/request" -H "$JSON" -H "$AUTHA" -d "{\"targetUserId\":\"$IDB\"}"
sleep 2
INBOX=$(curl -s -H "$AUTHB" "$API/friends/requests")
case "$INBOX" in *"\"incoming\":true"*) ok "bidz sees an incoming request" ;; *) bad "inbox ($INBOX)" ;; esac
case "$INBOX" in *"$A"*) ok "the request names the requester" ;; *) bad "no username in the inbox" ;; esac
OUT=$(curl -s -H "$AUTHA" "$API/friends/requests")
case "$OUT" in *"\"incoming\":false"*) ok "ana sees it as outgoing" ;; *) bad "outgoing ($OUT)" ;; esac
NB=$(curl -s -H "$AUTHB" "$API/notifications")
case "$NB" in *FRIEND_REQUEST*) ok "a FRIEND_REQUEST notification was delivered" ;; *) bad "no notification ($NB)" ;; esac

echo "== M7: read state =="
UNREAD=$(curl -s -H "$AUTHB" "$API/notifications/unread-count")
case "$UNREAD" in *'"unread":1'*) ok "unread count is 1" ;; *) bad "unread ($UNREAD)" ;; esac
NID=$(printf '%s' "$NB" | sed -n 's/.*"id":"\([^"]*\)".*/\1/p' | head -1)
check "mark read" 204 "$(curl -s -o /dev/null -w '%{http_code}' -X POST -H "$AUTHB" "$API/notifications/$NID/read")"
UNREAD2=$(curl -s -H "$AUTHB" "$API/notifications/unread-count")
case "$UNREAD2" in *'"unread":0'*) ok "unread count went to 0" ;; *) bad "still unread ($UNREAD2)" ;; esac
check "cannot mark someone else's notification" 404 \
  "$(curl -s -o /dev/null -w '%{http_code}' -X POST -H "$AUTHC" "$API/notifications/$NID/read")"

echo "== accept, then friends have names =="
curl -s -o /dev/null -X POST "$API/friends/accept" -H "$JSON" -H "$AUTHB" -d "{\"targetUserId\":\"$IDA\"}"
FRIENDS=$(curl -s -H "$AUTHA" "$API/friends")
case "$FRIENDS" in *"\"username\":\"$B\""*) ok "GET /friends returns usernames, not bare ids" ;; *) bad "friends ($FRIENDS)" ;; esac
EMPTY=$(curl -s -H "$AUTHB" "$API/friends/requests")
check "inbox is empty after accepting" "[]" "$EMPTY"

echo "== M1/M2: join, leave, respond =="
PLAN=$(curl -s -X POST "$API/activities" -H "$JSON" -H "$AUTHA" -d '{
  "title":"Group ride","startTime":"2030-03-01T09:00:00Z","lat":41.7,"lng":44.8,
  "addressText":"Rike Park","visibility":"FRIENDS"}')
PID=$(jsonf "$PLAN" id)
JOIN=$(curl -s -X POST -H "$AUTHB" "$API/activities/$PID/join")
case "$JOIN" in *JOINED*) ok "bidz joined" ;; *) bad "join ($JOIN)" ;; esac
DETAIL=$(curl -s -H "$AUTHB" "$API/activities/$PID")
case "$DETAIL" in *'"participantCount":2'*) ok "participant count is 2" ;; *) bad "count ($DETAIL)" ;; esac
case "$DETAIL" in *'"viewerStatus":"JOINED"'*) ok "viewerStatus reflects the join" ;; *) bad "viewerStatus ($DETAIL)" ;; esac

PARTS=$(curl -s -H "$AUTHB" "$API/activities/$PID/participants")
case "$PARTS" in *"\"username\":\"$A\""*) ok "participants carry usernames" ;; *) bad "participants ($PARTS)" ;; esac

check "joining twice is idempotent" 200 \
  "$(curl -s -o /dev/null -w '%{http_code}' -X POST -H "$AUTHB" "$API/activities/$PID/join")"
check "leave" 204 "$(curl -s -o /dev/null -w '%{http_code}' -X DELETE -H "$AUTHB" "$API/activities/$PID/join")"
AFTER=$(curl -s -H "$AUTHB" "$API/activities/$PID")
case "$AFTER" in *'"participantCount":1'*) ok "count dropped after leaving" ;; *) bad "count ($AFTER)" ;; esac
CREATOR_LEAVE=$(curl -s -w '\n%{http_code}' -X DELETE -H "$AUTHA" "$API/activities/$PID/join")
check "the creator can't leave their own plan" 400 "$(printf '%s' "$CREATOR_LEAVE" | tail -n1)"

# 404 rather than 403: a write must not confirm that an activity exists when the
# corresponding read wouldn't.
echo "== a stranger can't join what they can't see =="
check "cato joining a friends-only plan" 404 \
  "$(curl -s -o /dev/null -w '%{http_code}' -X POST -H "$AUTHC" "$API/activities/$PID/join")"

echo "== invitations =="
INVPLAN=$(curl -s -X POST "$API/activities" -H "$JSON" -H "$AUTHA" -d "{
  \"title\":\"Dinner\",\"startTime\":\"2030-03-02T18:00:00Z\",\"visibility\":\"FRIENDS\",
  \"inviteeUserIds\":[\"$IDB\"]}")
IID=$(jsonf "$INVPLAN" id)
INVITES=$(curl -s -H "$AUTHB" "$API/activities/invited")
case "$INVITES" in *"$IID"*) ok "bidz sees the invitation" ;; *) bad "invitations ($INVITES)" ;; esac
RESP=$(curl -s -X POST -H "$AUTHB" "$API/activities/$IID/respond?going=false")
case "$RESP" in *DECLINED*) ok "declining works" ;; *) bad "respond ($RESP)" ;; esac
GONE=$(curl -s -H "$AUTHB" "$API/activities/invited")
case "$GONE" in *"$IID"*) bad "declined invitation still listed" ;; *) ok "answered invitation leaves the list" ;; esac

echo "== M6: group members =="
GROUP=$(curl -s -X POST "$API/groups" -H "$JSON" -H "$AUTHA" -d '{"groupName":"Riders"}')
GID=$(printf '%s' "$GROUP" | sed -n 's/.*"id":"\([^"]*\)".*/\1/p')
curl -s -o /dev/null -X POST "$API/groups/$GID/members" -H "$JSON" -H "$AUTHA" -d "{\"userId\":\"$IDB\"}"
MEMBERS=$(curl -s -H "$AUTHA" "$API/groups/$GID/members")
case "$MEMBERS" in *'"owner":true'*) ok "the owner is flagged" ;; *) bad "members ($MEMBERS)" ;; esac
case "$MEMBERS" in *"\"username\":\"$B\""*) ok "members carry usernames" ;; *) bad "no member usernames" ;; esac
check "a non-member can't read the member list" 403 \
  "$(curl -s -o /dev/null -w '%{http_code}' -H "$AUTHC" "$API/groups/$GID/members")"

echo "== unfriend =="
check "unfriend" 204 "$(curl -s -o /dev/null -w '%{http_code}' -X DELETE -H "$AUTHA" "$API/friends/$IDB")"
LEFT=$(curl -s -H "$AUTHA" "$API/friends")
check "friends list is empty again" "[]" "$LEFT"

echo
echo "==== $pass passed, $fail failed ===="
[ "$fail" -eq 0 ]
