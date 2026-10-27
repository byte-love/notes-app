#!/usr/bin/env bash
# usage: import-note.sh <file> <slug> <title> <summary> <PUBLIC|MEMBER|PRIVATE> [parent-slug] [sort-order]
# slug 已存在时自动改走「编辑」，不会重复建。写入立即生效，不需要重启或重新部署。
set -uo pipefail
FILE="$1"; SLUG="$2"; TITLE="$3"; SUMMARY="$4"; VIS="$5"
PARENT_SLUG="${6:-}"; SORT="${7:-0}"
BASE=http://127.0.0.1:8080/app
JAR=/tmp/import.jar
PSQL() { docker exec notes-pg psql -U notes -d notes -Atc "$1"; }

rm -f "$JAR"

PARENT_ID=""
if [ -n "$PARENT_SLUG" ]; then
  PARENT_ID=$(PSQL "select id from content_item where slug='$PARENT_SLUG'")
  if [ -z "$PARENT_ID" ]; then echo "  parent slug not found: $PARENT_SLUG"; exit 1; fi
  echo "  parent: $PARENT_SLUG (id=$PARENT_ID)"
fi

EXISTING_ID=$(PSQL "select id from content_item where slug='$SLUG'")

T=$(curl -s -c "$JAR" "$BASE/login" | grep -o 'name="_csrf" value="[^"]*"' | sed 's/.*value="//; s/"//' | head -1)
OP=$(grep '^OWNER_PASSWORD=' /opt/apps/notes-app/app.env | cut -d= -f2-)
curl -s -o /dev/null -b "$JAR" -c "$JAR" \
  --data-urlencode "username=owner" --data-urlencode "password=$OP" --data-urlencode "_csrf=$T" "$BASE/login"
if ! curl -s -o /dev/null -w '%{http_code}' -b "$JAR" "$BASE/admin" | grep -q 200; then
  echo "  owner 登录失败"; exit 1
fi

if [ -n "$EXISTING_ID" ]; then
  TARGET="$BASE/admin/$EXISTING_ID/edit"
  echo "  slug exists (id=$EXISTING_ID) -> edit"
else
  TARGET="$BASE/admin/new"
  echo "  new content"
fi

T2=$(curl -s -b "$JAR" "$TARGET" | grep -o 'name="_csrf" value="[^"]*"' | sed 's/.*value="//; s/"//' | head -1)

ARGS=(--data-urlencode "kind=NOTE" \
      --data-urlencode "slug=$SLUG" \
      --data-urlencode "title=$TITLE" \
      --data-urlencode "summary=$SUMMARY" \
      --data-urlencode "visibility=$VIS" \
      --data-urlencode "sortOrder=$SORT" \
      --data-urlencode "body@$FILE" \
      --data-urlencode "_csrf=$T2")
if [ -n "$PARENT_ID" ]; then ARGS+=(--data-urlencode "parentId=$PARENT_ID"); fi

code=$(curl -s -o /dev/null -w '%{http_code}' -b "$JAR" -c "$JAR" "${ARGS[@]}" "$TARGET")
echo "  POST $TARGET -> $code (期望 302)"
PSQL "select slug, visibility, coalesce(parent_id,0), sort_order, length(body) from content_item where slug='$SLUG';"
