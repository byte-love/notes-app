#!/usr/bin/env bash
# 部署静态站：site.sh <dev|prod> [tar路径]
#   prod -> /srv/sites/lab      (nginx :8080)
#   dev  -> /srv/sites/lab-dev  (nginx :8180)
set -uo pipefail

ENV=${1:-}
case "$ENV" in
  prod) STATIC=/srv/sites/lab;     TAR=${2:-/root/dist.tar.gz};     PORT=8080 ;;
  dev)  STATIC=/srv/sites/lab-dev; TAR=${2:-/root/dist-dev.tar.gz}; PORT=8180 ;;
  *) echo "usage: $0 <dev|prod> [tar]"; exit 1 ;;
esac

if [ -z "${2:-}" ]; then
  SHARED=/srv/deploy/dist-$ENV.tar.gz
  if [ -f "$SHARED" ]; then
    TAR=$SHARED
  fi
fi

[ -f "$TAR" ] || { echo "找不到产物包: $TAR"; exit 1; }
echo "  用的包: $TAR  (修改时间 $(stat -c %y "$TAR" | cut -d. -f1), 属主 $(stat -c %U "$TAR"))"

rm -rf "$STATIC.new"
mkdir -p "$STATIC.new"
tar -xzf "$TAR" -C "$STATIC.new"
[ -f "$STATIC.new/index.html" ] || { echo "BAD_ARCHIVE"; rm -rf "$STATIC.new"; exit 1; }

rm -rf "$STATIC.old"
[ -d "$STATIC" ] && mv "$STATIC" "$STATIC.old"
mv "$STATIC.new" "$STATIC"

echo "-- files: $(find "$STATIC" -type f | wc -l)"
echo "-- index mtime: $(date -r "$STATIC/index.html" '+%F %T')"
curl -s -o /dev/null -w "-- :$PORT / -> %{http_code}\n" "http://127.0.0.1:$PORT/"
echo "DONE ($ENV)"
