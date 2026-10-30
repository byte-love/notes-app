#!/usr/bin/env bash
# 页面响应时间打点（在服务器上跑，排除网络因素）
#   bash /opt/deploy/measure.sh [dev|prod]
# 前 6 次热身（JVM 冷起来时头十几次请求会慢 5-10 倍），再采 40 次，报 p50/p95 和 TTFB。
set -u
ENV=${1:-dev}
case "$ENV" in
  prod) H=http://127.0.0.1:8081 ;;
  dev)  H=http://127.0.0.1:8082 ;;
  *) echo "usage: $0 [dev|prod]"; exit 1 ;;
esac
SLUG=$(curl -s "$H/app/notes" | grep -o '/app/notes/[a-z0-9-]*' | head -1)
URLS=("/app/bagu" "/app/notes" "/app/algo" "/app/explore" "${SLUG:-/app/notes}")
pct() { sort -n | awk -v p="$1" '{a[NR]=$1} END{i=int(NR*p/100); if(i<1)i=1; if(i>NR)i=NR; printf "%.0f", a[i]}'; }
echo "环境 $ENV  ($H)"
for u in "${URLS[@]}"; do
  for i in $(seq 1 6); do curl -s -o /dev/null --compressed "$H$u"; done
  : > /tmp/m.t
  for i in $(seq 1 40); do
    curl -s -o /dev/null --compressed -w '%{http_code} %{time_total} %{time_starttransfer} %{size_download}\n' "$H$u" >> /tmp/m.t
  done
  printf '  %-26s %s %7sB  p50=%3sms p95=%3sms  ttfb_p50=%3sms\n' "$u" \
    "$(awk '{print $1}' /tmp/m.t | sort -u | tr '\n' ' ')" \
    "$(awk '{print $4}' /tmp/m.t | sort -n | tail -1)" \
    "$(awk '{print $2*1000}' /tmp/m.t | pct 50)" \
    "$(awk '{print $2*1000}' /tmp/m.t | pct 95)" \
    "$(awk '{print $3*1000}' /tmp/m.t | pct 50)"
done
rm -f /tmp/m.t
