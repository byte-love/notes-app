#!/usr/bin/env bash
# 用测试/正式库的只读账号执行 SQL：sudo /opt/deploy/psql.sh "select ..."
# 默认连 notes_test；要连正式库加第二个参数 prod
set -uo pipefail
SQL=${1:-}
DB=${2:-test}
[ -n "$SQL" ] || { echo "用法: psql.sh <sql> [test|prod]"; exit 1; }
PASS=$(cat /opt/apps/notes-app-test/.dbpass)
case "$DB" in
  test) DBNAME=notes_test ;;
  prod) DBNAME=notes ;;
  *) echo "第二个参数只能是 test 或 prod"; exit 1 ;;
esac
docker exec -e PGPASSWORD="$PASS" notes-pg psql -U notes_dev -d "$DBNAME" -c "$SQL"
