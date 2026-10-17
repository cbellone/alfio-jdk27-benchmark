#!/usr/bin/env bash
# Creates the template database used by every benchmark run:
# a copy of SOURCE_DB with an API key and a published 50,000-seat event ("load-bench").
# Usage: prepare-template.sh <alf.io image> [SOURCE_DB] [TEMPLATE_DB]
set -euo pipefail
IMAGE=$1
SOURCE_DB=${2:-alfio}
TEMPLATE_DB=${3:-alfio_load_template}
HERE=$(cd "$(dirname "$0")" && pwd)
OUT="$HERE/../results"
PG="docker exec alfio-main-postgres"
TMP=$(mktemp -d)
mkdir -p "$OUT"

$PG dropdb -U postgres --if-exists "$TEMPLATE_DB"
$PG createdb -U postgres -T "$SOURCE_DB" "$TEMPLATE_DB"

docker run -d --rm --name alfio-prepare --network host \
  -e ALFIO_JAVA_OPTS="-Dspring.profiles.active=dev -Ddatasource.url=jdbc:postgresql://localhost:5432/$TEMPLATE_DB -Ddatasource.username=postgres -Ddatasource.password=password" \
  -v "$ALFIO_DEV_PROPERTIES:/home/alfio/app/application-dev.properties:ro" "$IMAGE" >/dev/null
# the event creation API downloads the event logo from a URL
jwebserver -b 127.0.0.1 -p 8099 -d "$HERE" >/dev/null 2>&1 &
WEBSERVER=$!
trap 'kill $WEBSERVER; docker stop alfio-prepare >/dev/null; rm -rf "$TMP"' EXIT
until curl -sf localhost:8080/healthz >/dev/null; do sleep 1; done

# admin login (form + CSRF)
curl -s -c "$TMP/cj" -b "$TMP/cj" localhost:8080/authentication -o "$TMP/login.html"
CSRF=$(grep -o 'name="_csrf" type="hidden" value="[^"]*"' "$TMP/login.html" | sed 's/.*value="//;s/"//')
curl -s -c "$TMP/cj" -b "$TMP/cj" -o /dev/null -d "username=admin&password=abcd&_csrf=$CSRF" localhost:8080/authenticate
curl -s -b "$TMP/cj" localhost:8080/admin -o "$TMP/admin.html"
XCSRF=$(grep -o 'name="_csrf" content="[^"]*"' "$TMP/admin.html" | sed 's/.*content="//;s/"//')

# API key, used by the load test for the admin API
curl -sf -b "$TMP/cj" -H "X-CSRF-TOKEN: $XCSRF" -H 'Content-Type: application/json' \
  -X POST "localhost:8080/admin/api/users/new?baseUrl=http://localhost:8080" \
  -d '{"organizationId":1,"role":"API_CONSUMER","username":"load-bench-api-key","firstName":"bench","lastName":"bench","emailAddress":"bench@example.org","type":"API_KEY","description":"load benchmark"}' \
  | grep -o '"username":"[^"]*"' | sed 's/.*:"//;s/"//' > "$OUT/api-key.txt"
KEY=$(cat "$OUT/api-key.txt")

curl -sf -H "Authorization: apikey $KEY" -H 'Content-Type: application/json' \
  -X POST localhost:8080/api/v1/admin/event/create -d @"$HERE/event.json" >/dev/null
EVENT_ID=$($PG psql -U postgres -d "$TEMPLATE_DB" -Atc "select id from event where short_name='load-bench'")
curl -sf -b "$TMP/cj" -H "X-CSRF-TOKEN: $XCSRF" -X PUT "localhost:8080/admin/api/events/$EVENT_ID/status?active=true" >/dev/null
echo "template $TEMPLATE_DB ready: event $EVENT_ID, $($PG psql -U postgres -d "$TEMPLATE_DB" -Atc "select count(*) from ticket where event_id=$EVENT_ID and status='FREE'") free tickets"
