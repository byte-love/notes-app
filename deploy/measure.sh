#!/usr/bin/env bash
# 页面耗时打点（在服务器上跑，排除网络因素）
#   bash /opt/deploy/measure.sh [dev|prod] [轮数] [路径…]
#
# 登录态下打点：/app/bagu 这种分区登录后才是全量（几百条），匿名只看到推荐的那几条，
# 所以这里先拿 app.env 里的 owner 账号登录，再逐页采样。指定路径时只打这些路径。
#
# 每页先热身 4 次再采 N 次（JVM 热起来前后能差 3-5 倍，冷启动的数字没有意义），
# 报 p50/p95、TTFB、HTML 字节数和同一份 HTML 里的卡片数、标签数（等价于 DOM 规模）。
set -u
ENV=${1:-dev}
N=${2:-15}
shift 2 2>/dev/null || shift $#
case "$ENV" in
  prod) H=http://127.0.0.1:8081; ENVF=/opt/apps/notes-app/app.env ;;
  dev)  H=http://127.0.0.1:8082; ENVF=/opt/apps/notes-app-test/app.env ;;
  *) echo "usage: $0 [dev|prod] [loops] [paths...]"; exit 1 ;;
esac

U=$(grep -m1 '^OWNER_USERNAME=' "$ENVF" | cut -d= -f2-)
P=$(grep -m1 '^OWNER_PASSWORD=' "$ENVF" | cut -d= -f2-)
CJ=$(mktemp)
TOK=$(curl -s -c "$CJ" "$H/app/login" | grep -o 'name="_csrf" value="[^"]*"' | head -1 | sed 's/.*value="//;s/"$//')
curl -s -b "$CJ" -c "$CJ" -o /dev/null -d "username=$U&password=$P&_csrf=$TOK" "$H/app/login"

if [ $# -gt 0 ]; then
  URLS=("$@")
else
  BAGU=$(docker exec notes-pg psql -U notes -d notes_test -tAc \
    "select slug from content_item where kind='BAGU' and deleted_at is null order by octet_length(body) desc limit 1" | tr -d ' ')
  NOTE=$(docker exec notes-pg psql -U notes -d notes_test -tAc \
    "select slug from content_item where kind='NOTE' and deleted_at is null order by octet_length(body) desc limit 1" | tr -d ' ')
  URLS=(/app/bagu /app/notes /app/algo /app/projects /app/explore /app/explore/bagu/bagu-java
        "/app/bagu/$BAGU" "/app/notes/$NOTE" /app/me /app/bookmarks /app/memo /app/trash
        /app/changelog /app/guide /app/admin)
fi

pct() { sort -n | awk -v p="$1" '{a[NR]=$1} END{i=int(NR*p/100); if(i<1)i=1; if(i>NR)i=NR; printf "%.0f", a[i]}'; }
echo "环境 $ENV ($H)  登录=$([ -s "$CJ" ] && echo ok || echo 失败)"
printf '%-40s %6s %6s %6s %8s %6s %6s\n' 页面 p50ms p95ms ttfb html卡片 标签数
for u in "${URLS[@]}"; do
  for i in 1 2 3 4; do curl -s -b "$CJ" -o /dev/null --compressed "$H$u"; done
  : > /tmp/m.t
  for i in $(seq 1 "$N"); do
    curl -s -b "$CJ" -o /dev/null --compressed \
      -w '%{http_code} %{time_total} %{time_starttransfer}\n' "$H$u" >> /tmp/m.t
  done
  HTML=$(curl -s -b "$CJ" "$H$u")
  printf '%-40s %6s %6s %6s %8s %6s %6s\n' "${u:0:40}" \
    "$(awk '{print $2*1000}' /tmp/m.t | pct 50)" \
    "$(awk '{print $2*1000}' /tmp/m.t | pct 95)" \
    "$(awk '{print $3*1000}' /tmp/m.t | pct 50)" \
    "${#HTML}" \
    "$(printf '%s' "$HTML" | grep -c 'class="item-title')" \
    "$(printf '%s' "$HTML" | grep -c 'class="item-tag"')"
done
rm -f /tmp/m.t "$CJ"
