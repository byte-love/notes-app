#!/usr/bin/env bash
# A/B 对比：改动前、改动后两个实例同时跑，交替打同一批页面，避免机器负载漂移。
#   bash /opt/deploy/ab-bench.sh <改动前的 jar> [轮数] [端口]
#
# 为什么要这么麻烦：同一台机器上先后打点，两次之间 CPU、JIT、缓存都在变，
# 改动前 83ms、改动后 80ms 这种数字根本分不清是谁的效果。两个实例都在内存里热着、
# 一轮请求里交替打，差值才可信。
#
# 用法（在测试环境上做）：
#   1) 用改动前的代码打个包：
#        git archive --format=tar.gz --prefix=notes-app/ -o /root/base-src.tar.gz <改动前的提交>
#        scp /root/base-src.tar.gz root@…:/root/        # 或者直接在服务器上 git archive
#        rm -rf /opt/build/app-base && mkdir -p /opt/build/app-base
#        tar xzf /root/base-src.tar.gz -C /opt/build/app-base
#        cd /opt/build/app-base/notes-app
#        JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 mvn -B -q -DskipTests package
#   2) bash /opt/deploy/ab-bench.sh /opt/build/app-base/notes-app/target/notes-app.jar 40
#   3) 脚本结束会自己把临时实例停掉（也可以手动 pkill -f notes-app-ab）
set -uo pipefail
JAR=${1:?用法: ab-bench.sh <改动前的 jar> [轮数] [端口]}
N=${2:-30}
PORT=${3:-8099}
A="http://127.0.0.1:$PORT/app"        # 改动前
B="http://127.0.0.1:8082/app"         # 改动后（测试环境）
ENVF=${ENVF:-/opt/apps/notes-app-test/app.env}

U=$(grep -m1 '^OWNER_USERNAME=' "$ENVF" | cut -d= -f2-)
P=$(grep -m1 '^OWNER_PASSWORD=' "$ENVF" | cut -d= -f2-)
login() {
  local base=$1 cj=$2 tok
  rm -f "$cj"
  tok=$(curl -s -c "$cj" "$base/login" | grep -o 'name="_csrf" value="[^"]*"' | head -1 | sed 's/.*value="//;s/"$//')
  curl -s -b "$cj" -c "$cj" -o /dev/null -d "username=$U&password=$P&_csrf=$tok" "$base/login"
}

# 起「改动前」的实例（同库、同 context path，换端口）
cd /opt/apps/notes-app-test
set -a
# shellcheck disable=SC1090
. "$ENVF"
set +a
pkill -f 'notes-app-ab' >/dev/null 2>&1
nohup java -Xmx320m -jar "$JAR" --server.port="$PORT" --spring.application.name=notes-app-ab \
  > /tmp/ab-app.log 2>&1 &
for i in $(seq 1 40); do
  curl -s -o /dev/null -m 2 "$A/login" && break
  sleep 2
done
curl -s -o /dev/null -m 2 "$A/login" || { echo "改动前的实例没起来，看 /tmp/ab-app.log"; exit 1; }

A_CJ=/tmp/ab-a.txt
B_CJ=/tmp/ab-b.txt
login "$A" "$A_CJ"
login "$B" "$B_CJ"

BAGU=$(docker exec notes-pg psql -U notes -d notes_test -tAc \
  "select slug from content_item where kind='BAGU' and deleted_at is null order by octet_length(body) desc limit 1" | tr -d ' ')
NOTE=$(docker exec notes-pg psql -U notes -d notes_test -tAc \
  "select slug from content_item where kind='NOTE' and deleted_at is null order by octet_length(body) desc limit 1" | tr -d ' ')
PATHS=("/bagu" "/notes" "/explore/bagu/bagu-java" "/bagu/$BAGU" "/notes/$NOTE" "/admin")

# 预热：两边各打 60 轮，让 JIT 和内联缓存都热起来
for i in $(seq 1 60); do
  for p in "${PATHS[@]}"; do
    curl -s -o /dev/null -b "$A_CJ" "$A$p"
    curl -s -o /dev/null -b "$B_CJ" "$B$p"
  done
done

pct() { sort -n | awk -v p="$1" '{a[NR]=$1} END{i=int(NR*p/100); if(i<1)i=1; if(i>NR)i=NR; printf "%.1f", a[i]}'; }
printf '\n%-28s %9s %9s %8s\n' 页面 改动前ms 改动后ms 变化
for p in "${PATHS[@]}"; do
  : > /tmp/ab-a.t
  : > /tmp/ab-b.t
  for i in $(seq 1 "$N"); do
    curl -s -o /dev/null -b "$A_CJ" -w '%{time_total}\n' "$A$p" >> /tmp/ab-a.t
    curl -s -o /dev/null -b "$B_CJ" -w '%{time_total}\n' "$B$p" >> /tmp/ab-b.t
  done
  a=$(awk '{print $1*1000}' /tmp/ab-a.t | pct 50)
  b=$(awk '{print $1*1000}' /tmp/ab-b.t | pct 50)
  ap95=$(awk '{print $1*1000}' /tmp/ab-a.t | pct 95)
  bp95=$(awk '{print $1*1000}' /tmp/ab-b.t | pct 95)
  printf '%-28s %9s %9s %8s   p95 %s -> %s\n' "${p:0:28}" "$a" "$b" \
    "$(awk -v x="$a" -v y="$b" 'BEGIN{printf "%+.0f%%", (y-x)/x*100}')" "$ap95" "$bp95"
done

# 数据库读了多少：正文在 TOAST 里，读它必然多访问 buffer，块访问数最能说明「有没有白读正文」
blk() { docker exec notes-pg psql -U notes -d notes_test -tAc \
  "select blks_hit+blks_read from pg_stat_database where datname='notes_test'" | tr -d ' '; }
for pair in "改动前 $A $A_CJ" "改动后 $B $B_CJ"; do
  set -- $pair
  before=$(blk)
  for i in $(seq 1 20); do curl -s -o /dev/null -b "$3" "$2/bagu"; done
  after=$(blk)
  printf '%s /bagu 20 次：数据库块访问 %s\n' "$1" "$((after - before))"
done

pkill -f 'notes-app-ab' >/dev/null 2>&1
echo "改动前的临时实例已停"
rm -f /tmp/ab-a.t /tmp/ab-b.t
