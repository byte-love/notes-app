#!/usr/bin/env bash
# 每小时备份正式库：3 点那份标成 daily 留 14 天，其余 hourly 留 24 小时
# 加了完整性校验，失败会打日志并以非零退出（cron 会记 mail，日志也留痕）
set -uo pipefail

DIR=/srv/backup/pg
LOG=/var/log/backup-pg.log
mkdir -p "$DIR"

STAMP=$(date +%Y%m%d-%H%M)
HOUR=$(date +%H)
if [ "$HOUR" = "03" ]; then TAG=daily; else TAG=hourly; fi
OUT="$DIR/notes-$STAMP-$TAG.sql.gz"

# 1) dump + 压缩
if ! docker exec notes-pg pg_dump -U notes -d notes --no-owner --no-acl 2>/tmp/pgdump.err | gzip > "$OUT"; then
  echo "$(date '+%F %T') FAILED $OUT $(head -1 /tmp/pgdump.err)" >> "$LOG"
  rm -f "$OUT"
  exit 1
fi

# 2) 校验：gzip 完整 + 至少有一个建表语句
if ! gzip -t "$OUT" 2>/dev/null; then
  echo "$(date '+%F %T') CORRUPT $OUT" >> "$LOG"
  rm -f "$OUT"
  exit 1
fi
TABLES=$(zcat "$OUT" | grep -c '^CREATE TABLE' || true)
if [ "${TABLES:-0}" -lt 1 ]; then
  echo "$(date '+%F %T') EMPTY $OUT tables=$TABLES" >> "$LOG"
  rm -f "$OUT"
  exit 1
fi

# 3) 保留策略
find "$DIR" -name 'notes-*-hourly.sql.gz' -mmin +1440 -delete 2>/dev/null
find "$DIR" -name 'notes-*-daily.sql.gz' -mtime +14 -delete 2>/dev/null
find "$DIR" -name 'notes-*.sql.gz' ! -name '*-hourly.sql.gz' ! -name '*-daily.sql.gz' -mtime +2 -delete 2>/dev/null

echo "$(date '+%F %T') ok $OUT $(du -h "$OUT" | cut -f1) tables=$TABLES" >> "$LOG"
