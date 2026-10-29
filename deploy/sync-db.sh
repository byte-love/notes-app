#!/usr/bin/env bash
# 把正式库整库复制到测试库（只读正式库，测试库会被重建）
# 用途：同学改了内容/结构把测试库搞乱了，或者想拿最新的真实数据调试
set -uo pipefail

echo "[1/3] 清空测试库 schema"
docker exec notes-pg psql -U notes -d notes_test -q -c "drop schema public cascade; create schema public;"
docker exec notes-pg psql -U notes -d notes_test -q -c "grant all on schema public to notes_dev;"

echo "[2/3] 从正式库 dump 并导入"
docker exec notes-pg bash -c 'pg_dump -U notes -d notes --no-owner --no-acl | psql -q -U notes -d notes_test' 2>&1 | tail -2

echo "[3/3] 重新授权给测试账号"
docker exec notes-pg psql -U notes -d notes_test -q -c "grant all on all tables in schema public to notes_dev;"
docker exec notes-pg psql -U notes -d notes_test -q -c "grant all on all sequences in schema public to notes_dev;"
  # 只 grant 权限不够，属主要一起转，否则 notes_dev 改不了表
  docker exec -i notes-pg psql -U notes -d notes_test -q < /opt/deploy/test-owner.sql

echo "---- 结果对比 ----"
for db in notes notes_test; do
  echo -n "  $db: "
  docker exec notes-pg psql -U notes -d $db -Atc "select kind || '=' || count(*) from content_item group by kind order by kind" | tr '\n' ' '
  echo
done
echo "注意：如果 notes-app-dev 正在跑，建议重启它让连接池重连 -> systemctl restart notes-app-dev"
