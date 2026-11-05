#!/usr/bin/env bash
# 页面快照：把一批页面抓成 HTML 存下来，用于「改完代码页面应该一模一样」的回归比对。
#   bash deploy/snap.sh <base-url> <输出目录>
#   例：bash deploy/snap.sh http://127.0.0.1:8082/app /root/snap/base
#
# 抓两种身份：owner 登录态 + 游客态。输出目录里每个页面一个 .html。
# 比对用 deploy/snap-diff.sh（会忽略 jsessionid 和标签属性顺序这类无意义差异）。
set -u
BASE=${1:?用法: snap.sh <base-url> <输出目录>}
OUT=${2:?用法: snap.sh <base-url> <输出目录>}
ENVF=${ENVF:-/opt/apps/notes-app-test/app.env}
CJ=$(mktemp)
mkdir -p "$OUT"

U=$(grep -m1 '^OWNER_USERNAME=' "$ENVF" | cut -d= -f2-)
P=$(grep -m1 '^OWNER_PASSWORD=' "$ENVF" | cut -d= -f2-)
TOK=$(curl -s -c "$CJ" "$BASE/login" | grep -o 'name="_csrf" value="[^"]*"' | head -1 | sed 's/.*value="//;s/"$//')
curl -s -b "$CJ" -c "$CJ" -o /dev/null -d "username=$U&password=$P&_csrf=$TOK" "$BASE/login"

# 有正文、有补充笔记、有锁的几条，尽量覆盖到不同渲染分支
pick() { docker exec notes-pg psql -U notes -d notes_test -tAc "$1" | tr -d ' '; }
BAGU=$(pick "select slug from content_item where kind='BAGU' and deleted_at is null order by octet_length(body) desc limit 1")
NOTE=$(pick "select slug from content_item where kind='NOTE' and deleted_at is null order by octet_length(body) desc limit 1")
ALGO=$(pick "select slug from content_item where kind='ALGO' and deleted_at is null limit 1")
PROJ=$(pick "select slug from content_item where kind='PROJECT' and deleted_at is null limit 1")
ADMINID=$(pick "select id from content_item where kind='BAGU' and deleted_at is null order by id limit 1")

grab() { # grab <名字> <路径> [anon]
  local name=$1 path=$2 mode=${3:-auth}
  if [ "$mode" = anon ]; then curl -s "$BASE$path"; else curl -s -b "$CJ" "$BASE$path"; fi > "$OUT/$name.html"
  printf '  %-28s %8sB\n' "$name" "$(wc -c < "$OUT/$name.html")"
}

grab bagu /bagu
grab notes /notes
grab algo /algo
grab projects /projects
grab notes-rate "/notes?rate=star"
grab bagu-tag-filter "/bagu?rate=3&tag=%E5%B9%B6%E5%8F%91"
grab explore /explore
grab explore-group /explore/bagu/bagu-java
grab explore-group2 /explore/bagu/bagu-project
grab detail-bagu "/bagu/$BAGU"
grab detail-note "/notes/$NOTE"
grab detail-algo "/algo/$ALGO"
grab detail-project "/projects/$PROJ"
grab me /me
grab bookmarks /bookmarks
grab memo /memo
grab trash /trash
grab changelog /changelog
grab guide /guide
grab admin /admin
grab admin-kind "/admin?kind=BAGU"
grab admin-edit "/admin/$ADMINID/edit"
grab admin-new /admin/new
grab api-tree "/api/tree/bagu?parent=bagu-java"
grab anon-bagu /bagu anon
grab anon-explore /explore anon
grab anon-group /explore/bagu/bagu-project anon
grab anon-detail "/bagu/$BAGU" anon
echo "→ $OUT"
rm -f "$CJ"
