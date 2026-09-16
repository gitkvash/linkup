#!/bin/sh
# Contract check against the Flutter client (sibling repo linkup_app).
#
# The other verify-api*.sh scripts assert security/visibility invariants of the API as
# designed. This one asserts the API matches what the shipped client actually calls:
# every path in lib/core/network/*_api.dart, with the bodies its freezed models emit
# (UTC ISO-8601 timestamps, repeated `ids` query params, enum names in SCREAMING_CASE).
# A mismatch here is a feature that is dead in the app while every backend test is green.
BASE=${BASE:-http://localhost:8080}
API=$BASE/api/v1
pass=0; fail=0
ok()  { pass=$((pass+1)); echo "  PASS  $1"; }
bad() { fail=$((fail+1)); echo "  FAIL  $1"; }
check() { if [ "$2" = "$3" ]; then ok "$1 ($3)"; else bad "$1 (expected $2, got $3)"; fi; }
has()  { case "$2" in *"$3"*) ok "$1" ;; *) bad "$1 (got $2)" ;; esac; }
jsonf() { printf '%s' "$1" | sed -n "s/.*\"$2\":\"\([^\"]*\)\".*/\1/p"; }
code() { curl -s -o /dev/null -w '%{http_code}' "$@"; }
JSON='Content-Type: application/json'

STAMP="$$_$(date +%s)"
A="cc_a_$STAMP"; B="cc_b_$STAMP"
reg() { curl -s -X POST "$API/auth/register" -H "$JSON" -d "{\"username\":\"$1\",\"password\":\"password123\"}"; }

echo "== identity: what auth_api.dart / user_api.dart call =="
RA=$(reg "$A"); RB=$(reg "$B")
TA=$(jsonf "$RA" token); TB=$(jsonf "$RB" token)
IDA=$(jsonf "$RA" userId); IDB=$(jsonf "$RB" userId)
if [ -n "$TA" ] && [ -n "$TB" ]; then ok "register returns token/userId/username"; else bad "register ($RA)"; exit 1; fi
AUTHA="Authorization: Bearer $TA"; AUTHB="Authorization: Bearer $TB"

LOGIN=$(curl -s -X POST "$API/auth/login" -H "$JSON" -d "{\"username\":\"$A\",\"password\":\"password123\"}")
has "POST /auth/login returns a token" "$LOGIN" '"token"'
ME=$(curl -s -H "$AUTHA" "$API/users/me")
has "GET /users/me carries userId" "$ME" '"userId"'
has "GET /users/me carries username" "$ME" "$A"
SEARCH=$(curl -s -H "$AUTHA" "$API/users/search?q=cc_b_$STAMP")
has "GET /users/search?q= finds the other user" "$SEARCH" "$B"
# Dio serialises List<String> query params as repeated keys (ListFormat.multi).
BYIDS=$(curl -s -H "$AUTHA" "$API/users?ids=$IDA&ids=$IDB")
has "GET /users?ids=&ids= accepts repeated keys" "$BYIDS" "$IDB"
BYID=$(curl -s -H "$AUTHA" "$API/users/$IDB")
has "GET /users/{id}" "$BYID" "$B"

echo "== identity: usernames ignore case =="
# "alice" and "Alice" were two accounts, and logging in with the wrong shift key was
# answered "Incorrect username or password". V20 makes the uniqueness case-insensitive;
# the spelling the user typed is still what is stored and displayed.
check "the same name in another case is taken" 409 "$(code -X POST "$API/auth/register" -H "$JSON" -d "{\"username\":\"$(printf '%s' "$A" | tr 'a-z' 'A-Z')\",\"password\":\"password123\"}")"
UPPER_LOGIN=$(curl -s -X POST "$API/auth/login" -H "$JSON" -d "{\"username\":\"$(printf '%s' "$A" | tr 'a-z' 'A-Z')\",\"password\":\"password123\"}")
has "login works whatever case is typed" "$UPPER_LOGIN" '"token"'
has "and the stored spelling is unchanged" "$UPPER_LOGIN" "\"username\":\"$A\""
check "a wrong password is still refused" 401 "$(code -X POST "$API/auth/login" -H "$JSON" -d "{\"username\":\"$(printf '%s' "$A" | tr 'a-z' 'A-Z')\",\"password\":\"wrongpassword\"}")"

echo "== social: what social_api.dart calls =="
check "POST /friends/request" 204 "$(code -X POST "$API/friends/request" -H "$JSON" -H "$AUTHA" -d "{\"targetUserId\":\"$IDB\"}")"
REQS=$(curl -s -H "$AUTHB" "$API/friends/requests")
has "GET /friends/requests carries incoming flag" "$REQS" '"incoming"'
check "POST /friends/accept" 204 "$(code -X POST "$API/friends/accept" -H "$JSON" -H "$AUTHB" -d "{\"targetUserId\":\"$IDA\"}")"
FRIENDS=$(curl -s -H "$AUTHA" "$API/friends")
has "GET /friends carries userId+username" "$FRIENDS" '"username"'

# Blocking used to be a one-way door: the blocker themselves got 403 trying to re-add,
# DELETE /friends/{id} wouldn't clear it, and the pair appeared in no list at all - so a
# mis-tap on Block ended a friendship permanently, for both people.
echo "== social: a block can be undone by the person who applied it =="
check "A blocks B" 204 "$(code -X POST "$API/friends/block" -H "$JSON" -H "$AUTHA" -d "{\"targetUserId\":\"$IDB\"}")"
has "GET /friends/blocked lists them for A" "$(curl -s -H "$AUTHA" "$API/friends/blocked")" "$B"
has "and tells B nothing" "$(curl -s -H "$AUTHB" "$API/friends/blocked")" '[]'
check "B cannot lift A's block" 404 "$(code -X POST "$API/friends/unblock" -H "$JSON" -H "$AUTHB" -d "{\"targetUserId\":\"$IDA\"}")"
check "A can" 204 "$(code -X POST "$API/friends/unblock" -H "$JSON" -H "$AUTHA" -d "{\"targetUserId\":\"$IDB\"}")"
check "and B may ask again afterwards" 204 "$(code -X POST "$API/friends/request" -H "$JSON" -H "$AUTHB" -d "{\"targetUserId\":\"$IDA\"}")"
check "unblocking someone who isn't blocked is 404" 404 "$(code -X POST "$API/friends/unblock" -H "$JSON" -H "$AUTHA" -d "{\"targetUserId\":\"$IDB\"}")"
curl -s -o /dev/null -X POST "$API/friends/accept" -H "$JSON" -H "$AUTHA" -d "{\"targetUserId\":\"$IDB\"}"

GRP=$(curl -s -X POST "$API/groups" -H "$JSON" -H "$AUTHA" -d '{"groupName":"Contract"}')
GID=$(jsonf "$GRP" id)
has "POST /groups returns id/ownerId/groupName" "$GRP" '"groupName"'
has "GET /groups/mine" "$(curl -s -H "$AUTHA" "$API/groups/mine")" "$GID"
has "GET /groups/{id}" "$(curl -s -H "$AUTHA" "$API/groups/$GID")" "$GID"
check "POST /groups/{id}/members" 204 "$(code -X POST "$API/groups/$GID/members" -H "$JSON" -H "$AUTHA" -d "{\"userId\":\"$IDB\"}")"
has "GET /groups/{id}/members carries owner flag" "$(curl -s -H "$AUTHA" "$API/groups/$GID/members")" '"owner"'
# The client hides "remove" on the owner's own row; the server must not depend on that.
# An owner without a membership row keeps write access and loses every read: the group
# drops out of /groups/mine and /groups/{id} answers 403, with no way back to it.
check "the owner cannot remove themselves" 400 "$(code -X DELETE -H "$AUTHA" "$API/groups/$GID/members/$IDA")"
has "the group is still readable by its owner" "$(curl -s -H "$AUTHA" "$API/groups/mine")" "$GID"

# UserApi.updateMe - the edit-profile screen, and the username step a brand-new Google
# account gets on its way in.
ME=$(curl -s -X PATCH "$API/users/me" -H "$JSON" -H "$AUTHA" -d "{
  \"username\":\"$A\",\"displayName\":\"Contract Tester\",\"bio\":\"Testing the contract.\"}")
has "PATCH /users/me sets a display name" "$ME" '"displayName":"Contract Tester"'
has "and a bio" "$ME" '"bio"'
has "and GET /users/me reads them back" "$(curl -s -H "$AUTHA" "$API/users/me")" '"displayName":"Contract Tester"'
# Null means "leave it", empty means "clear it" - the form sends the whole shape every
# time, so a profile with no bio must not become one that cannot keep a bio.
CLEARED=$(curl -s -X PATCH "$API/users/me" -H "$JSON" -H "$AUTHA" -d '{"displayName":"","bio":""}')
has "an empty display name clears it" "$CLEARED" '"displayName":null'
check "a username already taken is a 409" 409 "$(code -X PATCH "$API/users/me" -H "$JSON" -H "$AUTHA" -d "{\"username\":\"$B\"}")"
check "a username with spaces is a 400" 400 "$(code -X PATCH "$API/users/me" -H "$JSON" -H "$AUTHA" -d '{"username":"not a name"}')"
# AuthResponse.newAccount is what sends a first-time Google user to the username step;
# signing in again must not put that form in front of them.
has "register says the account is new" "$RA" '"newAccount":true'
has "login says it is not" "$(curl -s -X POST "$API/auth/login" -H "$JSON" -d "{\"username\":\"$A\",\"password\":\"password123\"}")" '"newAccount":false'

echo "== activity: what activity_api.dart calls =="
# The client sends startTime as DateTime.toUtc().toIso8601String() - milliseconds, Z suffix.
CREATE=$(curl -s -X POST "$API/activities" -H "$JSON" -H "$AUTHA" -d '{
  "title":"Contract plan","startTime":"2031-03-04T15:00:00.000Z","endTime":null,"hasTime":true,
  "lat":41.7151,"lng":44.8271,"addressText":"Rustaveli","visibility":"FRIENDS","inviteeUserIds":[]}')
AID=$(jsonf "$CREATE" id)
if [ -n "$AID" ]; then ok "POST /activities accepts the client's body"; else bad "create ($CREATE)"; exit 1; fi
has "create response carries hasTime (Activity.hasTime)" "$CREATE" '"hasTime"'
has "create response carries activityType" "$CREATE" '"activityType"'

# RepeatSelector's "Recurring" column. The three fields are only ever written
# together (chk_activities_repeat), so a server that accepts the body but drops one
# of them is a rule the app renders and the database refuses on the next edit.
REPEAT=$(curl -s -X POST "$API/activities" -H "$JSON" -H "$AUTHA" -d '{
  "title":"Every other Tuesday","startTime":"2031-03-04T15:00:00.000Z","endTime":null,"hasTime":true,
  "lat":41.7151,"lng":44.8271,"addressText":"Rustaveli","visibility":"FRIENDS","inviteeUserIds":[],
  "repeatFrequency":"WEEKLY","repeatInterval":2,"repeatUntil":"2031-09-04T15:00:00.000Z"}')
RID=$(jsonf "$REPEAT" id)
if [ -n "$RID" ]; then ok "POST /activities accepts a repeat rule"; else bad "repeat create ($REPEAT)"; fi
RDETAIL=$(curl -s -H "$AUTHA" "$API/activities/$RID")
has "GET /activities/{id} carries repeatFrequency" "$RDETAIL" '"repeatFrequency":"WEEKLY"'
has "and the interval survives the round trip" "$RDETAIL" '"repeatInterval":2'
has "and the end date does too" "$RDETAIL" '"repeatUntil"'
# The stepper stops at 52 and "Flexible" sends no rule at all; both bounds are the
# server's, so a client that drifts past them gets a 400 rather than a 409 from Postgres.
check "an interval past the column's range is a 400" 400 "$(code -X POST "$API/activities" -H "$JSON" -H "$AUTHA" -d '{
  "title":"Too often","startTime":"2031-03-04T15:00:00.000Z","lat":41.7,"lng":44.8,
  "addressText":"x","visibility":"FRIENDS","repeatFrequency":"WEEKLY","repeatInterval":99}')"
check "a rule ending before it starts is a 400" 400 "$(code -X POST "$API/activities" -H "$JSON" -H "$AUTHA" -d '{
  "title":"Backwards","startTime":"2031-03-04T15:00:00.000Z","lat":41.7,"lng":44.8,
  "addressText":"x","visibility":"FRIENDS","repeatFrequency":"WEEKLY","repeatUntil":"2031-03-01T15:00:00.000Z"}')"
check "cleaning up the repeating plan" 204 "$(code -X DELETE -H "$AUTHA" "$API/activities/$RID")"

FROMTEXT=$(curl -s -X POST "$API/activities/from-text" -H "$JSON" -H "$AUTHA" -d '{
  "rawText":"coffee tomorrow at 5pm at Fabrika","visibility":"FRIENDS","groupId":null,
  "inviteeUserIds":[],"timeZone":"Asia/Tbilisi"}')
has "POST /activities/from-text accepts timeZone" "$FROMTEXT" '"id"'

DETAIL=$(curl -s -H "$AUTHA" "$API/activities/$AID")
has "GET /activities/{id} carries activityId" "$DETAIL" '"activityId"'
# Without this the detail screen's "Organiser" row can only render the raw id.
has "GET /activities/{id} names the creator" "$DETAIL" "\"creatorUsername\":\"$A\""
has "GET /activities/{id} carries participantCount" "$DETAIL" '"participantCount"'
# The status is derived on every read, never stored, so a client that shows "Happening
# now" is reading this field and nothing else.
has "GET /activities/{id} carries status" "$DETAIL" '"status":"UPCOMING"'
has "GET /activities/{id} carries viewerStatus" "$DETAIL" '"viewerStatus"'
has "GET /activities/mine" "$(curl -s -H "$AUTHA" "$API/activities/mine")" "$AID"
check "GET /activities/invited" 200 "$(code -H "$AUTHB" "$API/activities/invited")"
check "GET /activities/joined" 200 "$(code -H "$AUTHB" "$API/activities/joined")"
has "GET /activities/{id}/participants" "$(curl -s -H "$AUTHA" "$API/activities/$AID/participants")" '"status"'

has "POST /activities/{id}/join returns viewerStatus" "$(curl -s -X POST -H "$AUTHB" "$API/activities/$AID/join")" '"viewerStatus"'
has "POST /activities/{id}/respond?going=false" "$(curl -s -X POST -H "$AUTHB" "$API/activities/$AID/respond?going=false")" 'DECLINED'
# Joining is documented as idempotent, and the client can genuinely fire two at once (the
# same account on a second device, or a retry). Read-then-write made both callers see no
# row, both insert, and one lose on the primary key - a 409 for an operation whose whole
# contract is that repeating it is fine.
JOINCODES=$(for i in 1 2 3 4 5 6; do curl -s -o /dev/null -w '%{http_code} ' -X POST -H "$AUTHB" "$API/activities/$AID/join" & done; wait)
case "$JOINCODES" in
  *409*|*500*) bad "six simultaneous joins are all accepted (got $JOINCODES)" ;;
  *) ok "six simultaneous joins are all accepted" ;;
esac
has "and the headcount counts the joiner once" "$(curl -s -H "$AUTHA" "$API/activities/$AID")" '"participantCount":2'
check "DELETE /activities/{id}/join" 204 "$(code -X DELETE -H "$AUTHB" "$API/activities/$AID/join")"

# ActivityApi.startActivity / endActivity - the host's two lifecycle controls. Both
# answer with the read model, so the detail screen re-renders without a second GET.
STARTED=$(curl -s -X POST -H "$AUTHA" "$API/activities/$AID/start")
has "POST /activities/{id}/start makes it live" "$STARTED" '"status":"LIVE"'
ENDED=$(curl -s -X POST -H "$AUTHA" "$API/activities/$AID/end")
has "POST /activities/{id}/end ends it" "$ENDED" '"status":"ENDED"'
# Same 404 rule as editing: a non-creator must not learn the plan exists.
check "someone else cannot start your plan" 404 "$(code -X POST -H "$AUTHB" "$API/activities/$AID/start")"
# Put it back, so the map assertions below still have something live to find.
curl -s -o /dev/null -X POST -H "$AUTHA" "$API/activities/$AID/start"

MAP=$(curl -s -H "$AUTHA" "$API/activities/map?minLat=41.6&minLng=44.7&maxLat=41.8&maxLng=44.9&zoom=14")
has "GET /activities/map carries type/lat/lng/count" "$MAP" '"count"'
# Every pin draws its plan's category glyph. Without this field the map is a screen of
# identical dots again, and MarkerIcon has nothing to tell a run from a dinner.
has "GET /activities/map carries the category the pin draws" "$MAP" '"category"'
# The map filters out anything that is over, so the only statuses it can answer with are
# the two the pin has a treatment for.
has "GET /activities/map carries the status the pin draws" "$MAP" '"status"'

# ActivityApi.searchMapActivities - the map's search box, which is the only reason the
# placeholder can say "activities or places". Ordered by distance from the map centre.
MAPQ=$(curl -s -H "$AUTHA" "$API/activities/map/search?q=Contract&lat=41.7151&lng=44.8271&limit=8")
has "GET /activities/map/search finds a plan by title" "$MAPQ" '"title":"Contract plan"'
has "and carries the distance the result row shows" "$MAPQ" '"distanceMeters"'
has "and the category its glyph comes from" "$MAPQ" '"category"'
has "and the time, which is half of what the row says" "$MAPQ" '"startTime"'
# The client never sends a shorter one (MapPlanSearchNotifier stops at two characters);
# a bare wildcard would otherwise return every future plan the caller can see.
check "a one-character query is rejected" 400 "$(code -H "$AUTHA" "$API/activities/map/search?q=x&lat=41.7151&lng=44.8271")"

# ActivityApi.updateActivity / deleteActivity - the edit screen's only two calls.
UPD=$(code -X PATCH "$API/activities/$AID" -H "$JSON" -H "$AUTHA" -d '{
  "title":"Edited","startTime":"2031-03-04T16:00:00.000Z","endTime":null,"hasTime":false,
  "lat":41.7151,"lng":44.8271,"addressText":"Rustaveli","visibility":"FRIENDS","groupId":null,
  "repeatFrequency":"MONTHLY","repeatInterval":1,"repeatUntil":null}')
check "PATCH /activities/{id} (edit screen)" 200 "$UPD"
EDITED=$(curl -s -H "$AUTHA" "$API/activities/$AID")
has "the edit actually persisted" "$EDITED" '"title":"Edited"'
# PATCH replaces the whole editable surface, so the edit form has to send hasTime and
# the repeat rule on every save - omitting hasTime is what handed an all-day plan back
# a time nobody chose.
has "PATCH turns a one-off into a repeating plan" "$EDITED" '"repeatFrequency":"MONTHLY"'
has "and hasTime false survives the edit" "$EDITED" '"hasTime":false'
# The other direction: RepeatSelector back on "One-time" sends no rule, and the plan has
# to stop repeating rather than keeping the one it had.
check "PATCH with no rule makes it one-off again" 200 "$(code -X PATCH "$API/activities/$AID" -H "$JSON" -H "$AUTHA" -d '{
  "title":"Edited","startTime":"2031-03-04T16:00:00.000Z","endTime":null,"hasTime":true,
  "lat":41.7151,"lng":44.8271,"addressText":"Rustaveli","visibility":"FRIENDS","groupId":null}')"
has "and the old rule is gone" "$(curl -s -H "$AUTHA" "$API/activities/$AID")" '"repeatFrequency":null'
check "PATCH by a non-creator is 404" 404 "$(code -X PATCH "$API/activities/$AID" -H "$JSON" -H "$AUTHB" -d '{
  "title":"Hijacked","startTime":"2031-03-04T16:00:00.000Z","lat":41.7,"lng":44.8,
  "addressText":"x","visibility":"FRIENDS"}')"
check "DELETE by a non-creator is 404" 404 "$(code -X DELETE -H "$AUTHB" "$API/activities/$AID")"
check "DELETE /activities/{id} (edit screen)" 204 "$(code -X DELETE -H "$AUTHA" "$API/activities/$AID")"
check "the deleted activity is gone" 404 "$(code -H "$AUTHA" "$API/activities/$AID")"

echo "== field lengths: what the form fields allow =="
# The columns are VARCHAR(255)/VARCHAR(100). These used to reach Postgres and come back
# as a 409 "that conflicts with something that already exists" - about a title.
LONG_TITLE=$(printf 'y%.0s' $(seq 1 300))
printf '{"title":"%s","startTime":"2031-01-01T10:00:00Z","visibility":"PUBLIC"}' "$LONG_TITLE" > /tmp/cc_long.json
LONGRES=$(curl -s -X POST "$API/activities" -H "$JSON" -H "$AUTHA" --data-binary @/tmp/cc_long.json)
has "an over-long title is a 400 naming the field" "$LONGRES" '"title"'
has "and is not reported as a conflict" "$LONGRES" '"status":400'
# Free text is the user's own sentence (the client allows 280 chars), so the title
# derived from it is truncated rather than refused.
LONG_TEXT=$(printf 'lets go for a walk somewhere quite far away and back again %.0s' 1 2 3 4 | cut -c1-270)
printf '{"rawText":"%s","visibility":"PUBLIC","timeZone":"Asia/Tbilisi"}' "$LONG_TEXT" > /tmp/cc_long2.json
check "a 270-char free-text plan is accepted" 201 "$(code -X POST "$API/activities/from-text" -H "$JSON" -H "$AUTHA" --data-binary @/tmp/cc_long2.json)"

echo "== feed: what feed_api.dart calls =="
FEED=$(curl -s -H "$AUTHB" "$API/feed?limit=20")
has "GET /feed returns items/nextCursor" "$FEED" '"items"'
has "feed items carry creatorUsername" "$FEED" '"creatorUsername"'

echo "== notifications: what notification_api.dart calls =="
NOTIFS=$(curl -s -H "$AUTHB" "$API/notifications")
check "GET /notifications" 200 "$(code -H "$AUTHB" "$API/notifications")"
UNREAD=$(curl -s -H "$AUTHB" "$API/notifications/unread-count")
has "GET /notifications/unread-count uses the 'unread' key" "$UNREAD" '"unread"'
check "POST /notifications/read-all" 200 "$(code -X POST -H "$AUTHB" "$API/notifications/read-all")"
check "POST /notifications/device-token" 204 "$(code -X POST "$API/notifications/device-token" -H "$JSON" -H "$AUTHA" -d "{\"token\":\"cc-$STAMP\",\"platform\":\"ANDROID\"}")"
check "DELETE /notifications/device-token?token=" 204 "$(code -X DELETE -H "$AUTHA" "$API/notifications/device-token?token=cc-$STAMP")"

echo "== sse: what sse_client.dart connects to =="
# The client must learn it is connected without waiting for a notification: Dio resolves
# the request when the response HEADERS arrive and applies a receive timeout to that, so
# a stream that sends nothing until its first event reads as a hung request, gets
# aborted, and reconnects on a loop. The registry writes a comment on connect for exactly
# this reason - so headers here, on an idle stream, are the assertion.
SSE=$(curl -s -m 4 -D - -o /dev/null -H "$AUTHA" -H 'Accept: text/event-stream' "$API/feed/stream")
has "GET /feed/stream sends headers on connect, before any event" "$SSE" 'text/event-stream'

echo "== error mapping: statuses the client branches on =="
# mapDioException turns >=500 into "Server error. Please try again later." and 404 into
# "Not found.", so a client mistake answered with 500 tells the user the server is broken.
check "an unknown path is 404, not 500" 404 "$(code -H "$AUTHA" "$API/nope")"
check "a wrong method is 405, not 500" 405 "$(code -X PUT -H "$AUTHA" "$API/friends")"
check "a wrong content type is 415, not 500" 415 "$(code -X POST -H 'Content-Type: text/plain' -H "$AUTHA" --data 'x' "$API/groups")"
has "the 405 body still carries a message" "$(curl -s -X PUT -H "$AUTHA" "$API/friends")" '"message"'

echo "== unfriend (social screen) =="
check "DELETE /friends/{userId}" 204 "$(code -X DELETE -H "$AUTHA" "$API/friends/$IDB")"

echo
echo "==== $pass passed, $fail failed ===="
[ "$fail" -eq 0 ]
