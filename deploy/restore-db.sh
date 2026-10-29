#!/usr/bin/env bash
# 恢复数据库：restore-db.sh <备份文件> [notes|notes_test]
set -uo pipefail

FILE=${1:-}
TARGET=${2:-notes}
DIR=/srv/backup/pg

[ -n "$FILE" ] || { echo "用法: $0 <备份文件.sql.gz> [notes|notes_test]"; exit 1; }
[ -f "$FILE" ] || FILE="$DIR/$FILE"
[ -f "$FILE" ] || { echo "找不到备份文件: $1"; echo "可用的最近几份："; ls -t "$DIR"/*.sql.gz 2>/dev/null | head -5; exit 1; }

case "$TARGET" in
  notes)      SVC=notes-app;     PORT=8081 ;;
  notes_test) SVC=notes-app-dev; PORT=8082 ;;
  *) echo "target 只能是 notes 或 notes_test"; exit 1 ;;
esac

gzip -t "$FILE" 2>/dev/null || { echo "备份文件损坏: $FILE"; exit 1; }
TABLES=$(zcat "$FILE" | grep -c '^CREATE TABLE' || true)
[ "${TABLES:-0}" -ge 1 ] || { echo "备份里没有建表语句，拒绝恢复: $FILE"; exit 1; }

echo "来源: $FILE ($(du -h "$FILE" | cut -f1))"
echo "目标: $TARGET  服务: $SVC"
echo "其中含 $TABLES 个建表语句"

echo
echo "[1/5] 先把当前状态备份一份（before-restore）"
SAFE="$DIR/before-restore-$(date +%Y%m%d-%H%M%S)-$TARGET.sql.gz"
docker exec notes-pg pg_dump -U notes -d "$TARGET" --no-owner --no-acl | gzip > "$SAFE"
echo "  $SAFE ($(du -h "$SAFE" | cut -f1))"

echo
echo "[2/5] 停服务 $SVC"
systemctl stop "$SVC"

echo
echo "[3/5] 清空 schema 并导入（用 zcat 解压后喂给 psql）"
docker exec notes-pg psql -U notes -d "$TARGET" -q -c "drop schema public cascade; create schema public;"
if ! zcat "$FILE" | docker exec -i notes-pg psql -q -U notes -d "$TARGET" > /tmp/restore.log 2>&1; then
  echo "  导入失败，详情："
  tail -5 /tmp/restore.log
  echo "  !! 服务保持停止，人工处理后启动：systemctl start $SVC"
  exit 1
fi
if grep -q '^ERROR' /tmp/restore.log; then
  echo "  导入过程有错误（前 5 行）："
  grep '^ERROR' /tmp/restore.log | head -5
fi
echo "  导入完成"

echo
echo "[4/5] 重新授权（测试库要还给 notes_dev）"
if [ "$TARGET" = "notes_test" ]; then
  docker exec notes-pg psql -U notes -d notes_test -q -c "grant all on schema public to notes_dev;"
  docker exec notes-pg psql -U notes -d notes_test -q -c "grant all on all tables in schema public to notes_dev;"
  docker exec notes-pg psql -U notes -d notes_test -q -c "grant all on all sequences in schema public to notes_dev;"
  # 只 grant 权限不够，属主要一起转，否则 notes_dev 改不了表
  docker exec -i notes-pg psql -U notes -d notes_test -q < /opt/deploy/test-owner.sql
fi

echo
echo "[5/5] 启动服务并验证"
systemctl start "$SVC"
for i in $(seq 1 20); do
  curl -s -o /dev/null -m 2 "http://127.0.0.1:$PORT/app/login" && { echo "  就绪（约 $((i * 3))s）"; break; }
  sleep 3
done
echo -n "  服务: "; systemctl is-active "$SVC"
echo -n "  数据: "
docker exec notes-pg psql -U notes -d "$TARGET" -Atc "select kind || '=' || count(*) from content_item group by kind order by kind" | tr '\n' ' '
echo
echo "DONE"
