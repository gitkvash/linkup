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

echo "== identity: password reset (auth_api.dart forgotPassword/resetPassword, user_api.dart myEmail/updateMyEmail) =="
# The code itself only ever reaches the user's inbox (or the dev log), so a black-box check
# cannot complete a reset; what it can pin is the wire shape and that nothing leaks.
EMAIL_A="cc_a_$STAMP@example.com"
has "a new account has no email yet" "$(curl -s -H "$AUTHA" "$API/users/me/email")" '"email":null'
has "PUT /users/me/email stores it lowercase" "$(curl -s -X PUT "$API/users/me/email" -H "$JSON" -H "$AUTHA" -d "{\"email\":\"CC_A_$STAMP@Example.com\"}")" "\"email\":\"$EMAIL_A\""
check "another account cannot claim it" 409 "$(code -X PUT "$API/users/me/email" -H "$JSON" -H "$AUTHB" -d "{\"email\":\"$EMAIL_A\"}")"
check "a malformed email is a 400" 400 "$(code -X PUT "$API/users/me/email" -H "$JSON" -H "$AUTHA" -d '{"email":"not-an-email"}')"
check "the email endpoint needs a token" 401 "$(code "$API/users/me/email")"
check "register with an email already taken is a 409" 409 "$(code -X POST "$API/auth/register" -H "$JSON" -d "{\"username\":\"cc_c_$STAMP\",\"password\":\"password123\",\"email\":\"$EMAIL_A\"}")"
check "POST /auth/forgot-password for a real address" 204 "$(code -X POST "$API/auth/forgot-password" -H "$JSON" -d "{\"email\":\"$EMAIL_A\"}")"
check "and for one nobody has - the same answer" 204 "$(code -X POST "$API/auth/forgot-password" -H "$JSON" -d '{"email":"nobody-here@example.com"}')"
check "forgot-password with a blank email is a 400" 400 "$(code -X POST "$API/auth/forgot-password" -H "$JSON" -d '{"email":""}')"
WRONG=$(curl -s -X POST "$API/auth/reset-password" -H "$JSON" -d "{\"email\":\"$EMAIL_A\",\"code\":\"000000\",\"newPassword\":\"newpassword123\"}")
has "a wrong code is INVALID_RESET_CODE" "$WRONG" '"code":"INVALID_RESET_CODE"'
check "a wrong code is a 400, not a 401 (which would sign the app out)" 400 "$(code -X POST "$API/auth/reset-password" -H "$JSON" -d "{\"email\":\"$EMAIL_A\",\"code\":\"000001\",\"newPassword\":\"newpassword123\"}")"
has "an unknown email fails the same way" "$(curl -s -X POST "$API/auth/reset-password" -H "$JSON" -d '{"email":"nobody-here@example.com","code":"000000","newPassword":"newpassword123"}')" '"code":"INVALID_RESET_CODE"'
check "a code that is not 6 digits is a 400" 400 "$(code -X POST "$API/auth/reset-password" -H "$JSON" -d "{\"email\":\"$EMAIL_A\",\"code\":\"12\",\"newPassword\":\"newpassword123\"}")"
check "the old password still works after failed resets" 200 "$(code -X POST "$API/auth/login" -H "$JSON" -d "{\"username\":\"$A\",\"password\":\"password123\"}")"

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

# AuthInterceptor trades refreshToken for a new pair on a 401 and only signs out if this
# refuses. The access token lives a day, so without these the app signs everyone out daily.
RT=$(jsonf "$LOGIN" refreshToken)
has "login returns a refreshToken" "$LOGIN" '"refreshToken"'
REFRESHED=$(curl -s -X POST "$API/auth/refresh" -H "$JSON" -d "{\"refreshToken\":\"$RT\"}")
has "POST /auth/refresh returns a new token" "$REFRESHED" '"token"'
has "and a new refresh token, so the window slides" "$REFRESHED" '"refreshToken"'
check "a refresh token is not a bearer token" 401 "$(code -H "Authorization: Bearer $RT" "$API/users/me")"
check "an access token cannot refresh" 401 "$(code -X POST "$API/auth/refresh" -H "$JSON" -d "{\"refreshToken\":\"$TA\"}")"

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

# Left in place on purpose: it is the plan that fills B's feed for the "feed items
# carry ..." checks below, once every other plan here has been deleted. Created this
# early so fan-out has written it to B's timeline by the time the feed is read.
FEEDPLAN=$(curl -s -X POST "$API/activities" -H "$JSON" -H "$AUTHA" -d '{
  "title":"Coffee at Fabrika","startTime":"2031-05-01T14:00:00.000Z","hasTime":true,
  "addressText":"Fabrika","visibility":"FRIENDS","groupId":null,"inviteeUserIds":[]}')
has "a friends-only plan for the feed checks is created" "$FEEDPLAN" '"id"'

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

# Create with no place at all (the form's place is optional), then invite B to it after
# the fact - the invite sheet on Activity Detail. B gets it as a pending invitation and a
# notification row that names the plan, which is what the Alerts row's Accept/Decline and
# its tap-through both key on.
NOPLACE=$(curl -s -X POST "$API/activities" -H "$JSON" -H "$AUTHA" -d '{
  "title":"No place yet","startTime":"2031-03-05T18:00:00.000Z","hasTime":true,
  "lat":null,"lng":null,"addressText":null,"visibility":"PRIVATE","inviteeUserIds":[]}')
NPID=$(jsonf "$NOPLACE" id)
if [ -n "$NPID" ]; then ok "POST /activities accepts a plan with no place"; else bad "create without place ($NOPLACE)"; fi
has "POST /activities/{id}/invites answers with participants" "$(curl -s -X POST "$API/activities/$NPID/invites" -H "$JSON" -H "$AUTHA" -d "{\"userIds\":[\"$IDB\"]}")" '"INVITED"'
has "the invitee sees it in /activities/invited" "$(curl -s -H "$AUTHB" "$API/activities/invited")" "$NPID"
check "someone else cannot invite to your plan" 404 "$(code -X POST "$API/activities/$NPID/invites" -H "$JSON" -H "$AUTHB" -d "{\"userIds\":[\"$IDA\"]}")"
check "an empty invite list is a 400" 400 "$(code -X POST "$API/activities/$NPID/invites" -H "$JSON" -H "$AUTHA" -d '{"userIds":[]}')"
sleep 1
has "the invitation notification carries activityId" "$(curl -s -H "$AUTHB" "$API/notifications")" "\"activityId\":\"$NPID\""
has "POST /activities/{id}/respond?going=true accepts it" "$(curl -s -X POST -H "$AUTHB" "$API/activities/$NPID/respond?going=true")" 'JOINED'
check "cleaning up the place-less plan" 204 "$(code -X DELETE -H "$AUTHA" "$API/activities/$NPID")"

# ActivityApi.cancelActivity - the host calls a plan off without deleting it. A plan of
# its own, not $AID, which has to stay live for the map and feed checks below. The plan
# stays readable to the people in it, reading CANCELLED, and they are told.
CANCELME=$(curl -s -X POST "$API/activities" -H "$JSON" -H "$AUTHA" -d '{
  "title":"Called off","startTime":"2031-03-06T18:00:00.000Z","hasTime":true,
  "lat":null,"lng":null,"addressText":null,"visibility":"PRIVATE","inviteeUserIds":[]}')
CID=$(jsonf "$CANCELME" id)
curl -s -o /dev/null -X POST "$API/activities/$CID/invites" -H "$JSON" -H "$AUTHA" -d "{\"userIds\":[\"$IDB\"]}"
curl -s -o /dev/null -X POST -H "$AUTHB" "$API/activities/$CID/respond?going=true"
check "someone else cannot cancel your plan" 404 "$(code -X POST -H "$AUTHB" "$API/activities/$CID/cancel")"
has "POST /activities/{id}/cancel cancels it" "$(curl -s -X POST -H "$AUTHA" "$API/activities/$CID/cancel")" '"status":"CANCELLED"'
check "cancelling it again is a no-op" 200 "$(code -X POST -H "$AUTHA" "$API/activities/$CID/cancel")"
has "a guest still sees the cancelled plan" "$(curl -s -H "$AUTHB" "$API/activities/$CID")" '"status":"CANCELLED"'
sleep 1
has "the guest is told it was cancelled" "$(curl -s -H "$AUTHB" "$API/notifications")" '"ACTIVITY_CANCELLED"'
# Ending it records that it happened; a plan that happened can't be called off after.
curl -s -o /dev/null -X POST -H "$AUTHA" "$API/activities/$CID/end"
check "a plan that already happened cannot be cancelled" 400 "$(code -X POST -H "$AUTHA" "$API/activities/$CID/cancel")"
check "cleaning up the cancelled plan" 204 "$(code -X DELETE -H "$AUTHA" "$API/activities/$CID")"

# ActivityApi.remindActivity - the host's "Remind everyone" - and the notification an edit
# sends. A plan of its own again. Only people who joined are reminded, a second press
# inside the cooldown is refused, and moving the plan tells the guest what changed.
NUDGE=$(curl -s -X POST "$API/activities" -H "$JSON" -H "$AUTHA" -d '{
  "title":"Nudge me","startTime":"2031-03-07T18:00:00.000Z","hasTime":true,
  "lat":null,"lng":null,"addressText":null,"visibility":"PRIVATE","inviteeUserIds":[]}')
NID=$(jsonf "$NUDGE" id)
check "reminding a plan nobody joined is a 400" 400 "$(code -X POST -H "$AUTHA" "$API/activities/$NID/remind")"
curl -s -o /dev/null -X POST "$API/activities/$NID/invites" -H "$JSON" -H "$AUTHA" -d "{\"userIds\":[\"$IDB\"]}"
curl -s -o /dev/null -X POST -H "$AUTHB" "$API/activities/$NID/respond?going=true"
check "someone else cannot remind for your plan" 404 "$(code -X POST -H "$AUTHB" "$API/activities/$NID/remind")"
has "POST /activities/{id}/remind answers with how many were told" "$(curl -s -X POST -H "$AUTHA" "$API/activities/$NID/remind")" '"reminded":1'
check "a second reminder inside the cooldown is a 400" 400 "$(code -X POST -H "$AUTHA" "$API/activities/$NID/remind")"
sleep 1
has "the guest is reminded" "$(curl -s -H "$AUTHB" "$API/notifications")" '"ACTIVITY_REMINDER"'
curl -s -o /dev/null -X PATCH "$API/activities/$NID" -H "$JSON" -H "$AUTHA" -d '{
  "title":"Nudge me","startTime":"2031-03-07T19:30:00.000Z","hasTime":true,
  "lat":null,"lng":null,"addressText":null,"visibility":"PRIVATE"}'
sleep 1
has "the guest is told the plan was edited" "$(curl -s -H "$AUTHB" "$API/notifications")" '"ACTIVITY_UPDATED"'

# ActivityApi.proposeTime / getTimeProposals / acceptTimeProposal / declineTimeProposal /
# withdrawTimeProposal - "suggest another time". A plan of its own. B is only invited, so
# accepting has to do three things at once: move the plan, put B in it, and tell B.
TP=$(curl -s -X POST "$API/activities" -H "$JSON" -H "$AUTHA" -d '{
  "title":"Suggest me","startTime":"2031-03-08T18:00:00.000Z","hasTime":true,
  "lat":null,"lng":null,"addressText":null,"visibility":"PRIVATE","inviteeUserIds":[]}')
TPID=$(jsonf "$TP" id)
curl -s -o /dev/null -X POST "$API/activities/$TPID/invites" -H "$JSON" -H "$AUTHA" -d "{\"userIds\":[\"$IDB\"]}"
check "the host cannot suggest a time for their own plan" 400 "$(code -X POST "$API/activities/$TPID/time-proposals" -H "$JSON" -H "$AUTHA" -d '{"startTime":"2031-03-08T19:00:00.000Z"}')"
check "a time in the past is a 400" 400 "$(code -X POST "$API/activities/$TPID/time-proposals" -H "$JSON" -H "$AUTHB" -d '{"startTime":"2001-03-08T19:00:00.000Z"}')"
check "the time it already has is a 400" 400 "$(code -X POST "$API/activities/$TPID/time-proposals" -H "$JSON" -H "$AUTHB" -d '{"startTime":"2031-03-08T18:00:00.000Z"}')"
check "a missing startTime is a 400" 400 "$(code -X POST "$API/activities/$TPID/time-proposals" -H "$JSON" -H "$AUTHB" -d '{}')"
FIRST=$(curl -s -X POST "$API/activities/$TPID/time-proposals" -H "$JSON" -H "$AUTHB" -d '{"startTime":"2031-03-08T19:00:00.000Z","message":"work until 7"}')
has "POST /activities/{id}/time-proposals records it as PENDING" "$FIRST" '"status":"PENDING"'
has "and keeps the message" "$FIRST" 'work until 7'
SECOND=$(curl -s -X POST "$API/activities/$TPID/time-proposals" -H "$JSON" -H "$AUTHB" -d '{"startTime":"2031-03-08T20:00:00.000Z"}')
PID=$(jsonf "$SECOND" id)
if [ -n "$PID" ]; then ok "suggesting again answers with the new one"; else bad "second suggestion ($SECOND)"; fi
HOSTLIST=$(curl -s -H "$AUTHA" "$API/activities/$TPID/time-proposals")
has "GET /activities/{id}/time-proposals: the host sees it" "$HOSTLIST" "$PID"
case "$HOSTLIST" in *"work until 7"*) bad "the replaced suggestion is still open" ;; *) ok "the replaced suggestion is no longer open" ;; esac
has "the proposer sees their own" "$(curl -s -H "$AUTHB" "$API/activities/$TPID/time-proposals")" "$PID"
check "someone else cannot accept it" 404 "$(code -X POST -H "$AUTHB" "$API/activities/$TPID/time-proposals/$PID/accept")"
check "someone else cannot decline it" 404 "$(code -X POST -H "$AUTHB" "$API/activities/$TPID/time-proposals/$PID/decline")"
sleep 1
has "the host is told about the suggestion" "$(curl -s -H "$AUTHA" "$API/notifications")" '"TIME_PROPOSAL"'
has "POST .../accept answers with the moved plan" "$(curl -s -X POST -H "$AUTHA" "$API/activities/$TPID/time-proposals/$PID/accept")" '2031-03-08T20:00'
has "the proposer is now going" "$(curl -s -H "$AUTHA" "$API/activities/$TPID/participants")" '"JOINED"'
check "an answered suggestion cannot be accepted again" 400 "$(code -X POST -H "$AUTHA" "$API/activities/$TPID/time-proposals/$PID/accept")"
sleep 1
has "the proposer is told it was accepted" "$(curl -s -H "$AUTHB" "$API/notifications")" '"TIME_PROPOSAL_ACCEPTED"'
case "$(curl -s -H "$AUTHA" "$API/activities/$TPID/time-proposals")" in "[]") ok "nothing is left open after accepting" ;; *) bad "something is still open after accepting" ;; esac
THIRD=$(curl -s -X POST "$API/activities/$TPID/time-proposals" -H "$JSON" -H "$AUTHB" -d '{"startTime":"2031-03-08T21:00:00.000Z"}')
P3=$(jsonf "$THIRD" id)
check "POST .../decline" 204 "$(code -X POST -H "$AUTHA" "$API/activities/$TPID/time-proposals/$P3/decline")"
sleep 1
has "the proposer is told it was declined" "$(curl -s -H "$AUTHB" "$API/notifications")" '"TIME_PROPOSAL_DECLINED"'
has "and the plan kept its time" "$(curl -s -H "$AUTHA" "$API/activities/$TPID")" '2031-03-08T20:00'
FOURTH=$(curl -s -X POST "$API/activities/$TPID/time-proposals" -H "$JSON" -H "$AUTHB" -d '{"startTime":"2031-03-08T22:00:00.000Z"}')
P4=$(jsonf "$FOURTH" id)
check "the host cannot withdraw someone else's suggestion" 404 "$(code -X DELETE -H "$AUTHA" "$API/activities/$TPID/time-proposals/$P4")"
check "DELETE /activities/{id}/time-proposals/{id} withdraws your own" 204 "$(code -X DELETE -H "$AUTHB" "$API/activities/$TPID/time-proposals/$P4")"
case "$(curl -s -H "$AUTHA" "$API/activities/$TPID/time-proposals")" in "[]") ok "a withdrawn suggestion is no longer open" ;; *) bad "a withdrawn suggestion is still open" ;; esac
check "cleaning up the suggestion plan" 204 "$(code -X DELETE -H "$AUTHA" "$API/activities/$TPID")"
check "cleaning up the reminded plan" 204 "$(code -X DELETE -H "$AUTHA" "$API/activities/$NID")"

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

echo "== places: what place_api.dart calls =="
# A plan links to a place by distance, on the server (V31) - the client sends only the
# point. This one is ~200m from Lisi Lake's centre and starts in two days, so it has to
# show up in the lake's "this week" count and list.
SOON=$(date -u -d '+2 days' +%Y-%m-%dT15:00:00.000Z)
LISI=$(curl -s -X POST "$API/activities" -H "$JSON" -H "$AUTHA" -d "{
  \"title\":\"Contract swim\",\"startTime\":\"$SOON\",\"endTime\":null,\"hasTime\":true,
  \"lat\":41.7456,\"lng\":44.7345,\"addressText\":\"Lisi\",\"visibility\":\"PUBLIC\",\"inviteeUserIds\":[]}")
LID=$(jsonf "$LISI" id)
PLACES=$(curl -s -H "$AUTHB" "$API/places?minLat=41.70&minLng=44.70&maxLat=41.76&maxLng=44.76")
has "GET /places carries the seeded places" "$PLACES" '"name":"Lisi Lake"'
has "GET /places carries the kind the marker draws" "$PLACES" '"kind":"LAKE"'
LISI_PLACE=$(printf '%s' "$PLACES" | grep -o '{[^{}]*"name":"Lisi Lake"[^{}]*}')
has "a plan by the lake is counted this week" "$LISI_PLACE" '"plansThisWeek":1'
PLACE_ID=$(printf '%s' "$LISI_PLACE" | sed -n 's/.*"placeId":"\([^"]*\)".*/\1/p')
has "GET /places/{id}/activities lists it" "$(curl -s -H "$AUTHB" "$API/places/$PLACE_ID/activities")" '"title":"Contract swim"'
check "a viewport past the span limit is a 400" 400 "$(code -H "$AUTHB" "$API/places?minLat=30&minLng=30&maxLat=45&maxLng=45")"
check "cleaning up the lake plan" 204 "$(code -X DELETE -H "$AUTHA" "$API/activities/$LID")"

echo "== feed: what feed_api.dart calls =="
FEED=$(curl -s -H "$AUTHB" "$API/feed?limit=20")
has "GET /feed returns items/nextCursor" "$FEED" '"items"'
has "feed items carry creatorUsername" "$FEED" '"creatorUsername"'
# The card's status chip renders whatever this says. Without the field the "All"
# tab has to guess from startTime, which calls a plan that is happening right now
# over -- and the guess is invisible in the client's own green tests.
has "feed items carry status" "$FEED" '"status"'
# The card's category band (colour, pattern, token) and its Fixed/Flexible line.
# An old server leaves these out and the "All" tab draws every plan as general.
has "feed items carry category" "$FEED" '"category":"'
has "and hasTime" "$FEED" '"hasTime":'
has "and activityType" "$FEED" '"activityType":"'
# Fan-out runs once, when a plan is created. A plan made by someone who only becomes a
# friend afterwards used to stay on the map and never reach the feed at all.
C="cc_c_$STAMP"; RC=$(reg "$C"); TC=$(jsonf "$RC" token); IDC=$(jsonf "$RC" userId)
AUTHC="Authorization: Bearer $TC"
LATE=$(curl -s -X POST "$API/activities" -H "$JSON" -H "$AUTHC" -d '{"title":"made before we were friends","startTime":"2031-06-01T10:00:00Z","visibility":"PUBLIC"}')
LATE_ID=$(jsonf "$LATE" id)
curl -s -o /dev/null -X POST "$API/friends/request" -H "$JSON" -H "$AUTHC" -d "{\"targetUserId\":\"$IDB\"}"
curl -s -o /dev/null -X POST "$API/friends/accept" -H "$JSON" -H "$AUTHB" -d "{\"targetUserId\":\"$IDC\"}"
sleep 2  # the backfill is an async module listener
has "a new friend's earlier plan reaches the feed" "$(curl -s -H "$AUTHB" "$API/feed?limit=50")" "${LATE_ID:-missing-id}"
# The client reloads All the moment a create returns, before the async fan-out has
# written the timeline; the creator's own plans are read from Postgres so it's there.
MINE=$(jsonf "$(curl -s -X POST "$API/activities" -H "$JSON" -H "$AUTHC" -d '{"title":"just made","startTime":"2031-07-01T10:00:00Z","visibility":"FRIENDS"}')" id)
has "a plan is in its creator's feed straight after the create" "$(curl -s -H "$AUTHC" "$API/feed?limit=50")" "${MINE:-missing-id}"
# A deleted plan still in a timeline used to count against the page, and a short page
# read as the end: two deletions among the newest three cut B's feed off right there.
PROBES=""
for d in 01 02 03 04 05 06; do
  PROBES="$PROBES $(jsonf "$(curl -s -X POST "$API/activities" -H "$JSON" -H "$AUTHC" -d "{\"title\":\"page probe $d\",\"startTime\":\"2032-01-${d}T10:00:00Z\",\"visibility\":\"FRIENDS\"}")" id)"
done
sleep 2  # fan-out is an async module listener
for GONE in $(echo $PROBES | cut -d' ' -f5-6); do
  curl -s -o /dev/null -X DELETE -H "$AUTHC" "$API/activities/$GONE"
done
PAGE=$(curl -s -H "$AUTHB" "$API/feed?limit=3")
case "$PAGE" in
  *'"nextCursor":null'*) bad "deleted plans among the newest don't end the feed (got $PAGE)" ;;
  *'"nextCursor":'*) ok "deleted plans among the newest don't end the feed" ;;
  *) bad "deleted plans among the newest don't end the feed (got $PAGE)" ;;
esac
has "and the page is still full" "$PAGE" 'page probe 02'

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

echo "== people: what people_api.dart calls (friend profile, your stats) =="
# PeopleApi.getProfile. A and B are friends, B and C are friends, A and C are not - so
# C's profile, seen by A, is a stranger's with B as the one mutual friend. The counts are
# the other person's, not the slice of them A's own connection can see (V35).
PROF=$(curl -s -H "$AUTHA" "$API/users/$IDB/profile")
has "GET /users/{id}/profile for a friend" "$PROF" '"relationship":"FRIENDS"'
has "carries the counts line" "$PROF" '"stats":{"friends":2'
has "and what's in common" "$PROF" '"groupsInCommon":['
PROFC=$(curl -s -H "$AUTHA" "$API/users/$IDC/profile")
has "a stranger's profile says so" "$PROFC" '"relationship":"NONE"'
has "and names the friend they share" "$PROFC" "\"mutualFriends\":[{\"userId\":\"$IDB\""
check "an account that doesn't exist is 404" 404 "$(code -H "$AUTHA" "$API/users/00000000-0000-0000-0000-000000000000/profile")"
# PeopleApi.getActivities, the Plans and Together tabs. PersonPlansScope.wire values.
has "GET /users/{id}/activities?scope=upcoming" "$(curl -s -H "$AUTHA" "$API/users/$IDB/activities?scope=upcoming")" '['
has "GET /users/{id}/activities?scope=together" "$(curl -s -H "$AUTHA" "$API/users/$IDB/activities?scope=together")" '['
check "an unknown scope is a 400" 400 "$(code -H "$AUTHA" "$API/users/$IDB/activities?scope=everything")"
# SocialApi.mute/unmute. B mutes C, whose plan is in B's feed from the backfill above; it
# leaves and comes back, and C is never told.
check "PUT /friends/{id}/mute" 204 "$(code -X PUT -H "$AUTHB" "$API/friends/$IDC/mute")"
has "the profile says muted" "$(curl -s -H "$AUTHB" "$API/users/$IDC/profile")" '"muted":true'
case "$(curl -s -H "$AUTHB" "$API/feed?limit=50")" in *"${LATE_ID:-missing-id}"*) bad "a muted friend's plan is still in the feed" ;; *) ok "a muted friend's plan leaves the feed" ;; esac
has "and the mute is B's alone" "$(curl -s -H "$AUTHC" "$API/users/$IDB/profile")" '"muted":false'
check "DELETE /friends/{id}/mute" 204 "$(code -X DELETE -H "$AUTHB" "$API/friends/$IDC/mute")"
has "and the plan comes back" "$(curl -s -H "$AUTHB" "$API/feed?limit=50")" "${LATE_ID:-missing-id}"
check "muting someone who isn't a friend is 404" 404 "$(code -X PUT -H "$AUTHA" "$API/friends/$IDC/mute")"
# PeopleApi.getStats. PeopleRepository sends the device's UTC offset as tz, which Dio
# encodes as %2B04:00; the server must read it as a zone, not fall back to UTC.
STATS=$(curl -s -H "$AUTHA" "$API/me/stats?year=$(date +%Y)&tz=%2B04:00")
has "GET /me/stats carries the year's months" "$STATS" '"byMonth":[{"hosted":'
has "and the year the account began" "$STATS" "\"firstYear\":$(date +%Y)"
check "a year out of range is a 400" 400 "$(code -H "$AUTHA" "$API/me/stats?year=1999&tz=%2B04:00")"

echo "== unfriend (social screen) =="
check "DELETE /friends/{userId}" 204 "$(code -X DELETE -H "$AUTHA" "$API/friends/$IDB")"

echo "== account deletion (profile screen) =="
# UserApi.deleteMe - the App Store's in-app deletion. A throwaway account, so nothing above
# loses the users it depends on; it leaves a friend request and a plan behind to prove the
# delete reaches rows that reference it (V33).
D="cc_d_$STAMP"; RD=$(reg "$D")
TD=$(jsonf "$RD" token); IDD=$(jsonf "$RD" userId); RTD=$(jsonf "$RD" refreshToken)
AUTHD="Authorization: Bearer $TD"
check "the doomed account can send a request" 204 "$(code -X POST "$API/friends/request" -H "$JSON" -H "$AUTHD" -d "{\"targetUserId\":\"$IDA\"}")"
DPLAN=$(jsonf "$(curl -s -X POST "$API/activities" -H "$JSON" -H "$AUTHD" -d '{
  "title":"Deleted with its host","startTime":"2031-03-04T15:00:00.000Z","endTime":null,"hasTime":true,
  "lat":null,"lng":null,"addressText":null,"visibility":"PUBLIC","inviteeUserIds":[]}')" id)
if [ -n "$DPLAN" ]; then ok "and host a plan"; else bad "the doomed account's plan was not created"; fi
check "DELETE /users/me" 204 "$(code -X DELETE -H "$AUTHD" "$API/users/me")"
# ProfileRepository.deleteAccount counts this as success: a retry after a lost response.
check "and again finds nothing to delete" 404 "$(code -X DELETE -H "$AUTHD" "$API/users/me")"
check "the account is gone for everyone else" 404 "$(code -H "$AUTHA" "$API/users/$IDD")"
check "its password no longer signs in" 401 "$(code -X POST "$API/auth/login" -H "$JSON" -d "{\"username\":\"$D\",\"password\":\"password123\"}")"
check "its refresh token no longer mints a session" 401 "$(code -X POST "$API/auth/refresh" -H "$JSON" -d "{\"refreshToken\":\"$RTD\"}")"
check "its plan went with it" 404 "$(code -H "$AUTHA" "$API/activities/$DPLAN")"
case "$(curl -s -H "$AUTHA" "$API/friends/requests")" in *"$IDD"*) bad "A still sees the deleted account's request" ;; *) ok "A no longer sees the deleted account's request" ;; esac

echo
echo "==== $pass passed, $fail failed ===="
[ "$fail" -eq 0 ]
