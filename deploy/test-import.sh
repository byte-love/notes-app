#!/usr/bin/env bash
# 验证 import-note.sh：新建 -> 同 slug 编辑 -> 删除，最后不留痕迹
set -uo pipefail
BASE=http://127.0.0.1:8080/app
JAR=/tmp/test.jar
PSQL() { docker exec notes-pg psql -U notes -d notes -Atc "$1"; }
SCRATCH=tmp-import-check

printf '# scratch\n\nfirst version body.\n' > /tmp/scratch.md

echo "[A] create"
bash /root/import-note.sh /tmp/scratch.md $SCRATCH "scratch check" "verify only" PRIVATE doc-mysql 3

echo "[B] edit with same slug"
printf '# scratch\n\nsecond version body, longer than the first one.\n' > /tmp/scratch.md
bash /root/import-note.sh /tmp/scratch.md $SCRATCH "scratch check" "verify only" PRIVATE doc-mysql 3
echo "  rows with slug: $(PSQL "select count(*) from content_item where slug='$SCRATCH'")"

echo "[C] delete"
rm -f $JAR
T=$(curl -s -c $JAR $BASE/login | grep -o 'name="_csrf" value="[^"]*"' | sed 's/.*value="//; s/"//' | head -1)
OP=$(grep '^OWNER_PASSWORD=' /opt/apps/notes-app/app.env | cut -d= -f2-)
curl -s -o /dev/null -b $JAR -c $JAR \
  --data-urlencode "username=owner" --data-urlencode "password=$OP" --data-urlencode "_csrf=$T" $BASE/login
ID=$(PSQL "select id from content_item where slug='$SCRATCH'")
T2=$(curl -s -b $JAR $BASE/admin | grep -o 'name="_csrf" value="[^"]*"' | sed 's/.*value="//; s/"//' | head -1)
curl -s -o /dev/null -w '  delete -> %{http_code}\n' -b $JAR -c $JAR --data-urlencode "_csrf=$T2" "$BASE/admin/$ID/delete"
echo "  remaining rows with slug: $(PSQL "select count(*) from content_item where slug='$SCRATCH'")"
echo "  total content_item rows: $(PSQL "select count(*) from content_item")"
echo DONE
