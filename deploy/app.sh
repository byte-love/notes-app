#!/usr/bin/env bash
# 部署 notes-app
#   app.sh <dev|prod> [tar路径]        在服务器上解压源码并编译，再部署
#   app.sh <dev|prod> --jar <jar路径>  直接部署本地编译好的 jar（服务器不装 Maven 也能发）
#   prod -> /opt/apps/notes-app      :8081 (systemd: notes-app)
#   dev  -> /opt/apps/notes-app-test :8082 (systemd: notes-app-dev, 独立库 notes_test)
set -uo pipefail

ENV=${1:-}
shift || true
MODE=src
TAR=
JAR=

while [ $# -gt 0 ]; do
  case "$1" in
    --jar) MODE=jar; JAR=${2:-}; shift 2 || shift ;;
    *.jar) MODE=jar; JAR=$1; shift ;;
    *) TAR=$1; shift ;;
  esac
done

case "$ENV" in
  prod) APP_DIR=/opt/apps/notes-app;      PORT=8081; SVC=notes-app;     BUILD=/opt/build/app-prod ;;
  dev)  APP_DIR=/opt/apps/notes-app-test; PORT=8082; SVC=notes-app-dev; BUILD=/opt/build/app-dev ;;
  *) echo "usage: $0 <dev|prod> [tar] | <dev|prod> --jar <jar>"; exit 1 ;;
esac

if [ "$MODE" = jar ]; then
  [ -n "$JAR" ] || { echo "缺少 jar 路径: $0 $ENV --jar <jar>"; exit 1; }
  [ -f "$JAR" ] || { echo "找不到 jar: $JAR"; exit 1; }
  PKG=$JAR
  echo "  用的包: $PKG  ($(du -h "$PKG" | cut -f1), 修改时间 $(stat -c %y "$PKG" | cut -d. -f1))"
else
  if [ -z "$TAR" ]; then
    SHARED=/srv/deploy/notes-src-$ENV.tar.gz
    if [ -f "$SHARED" ]; then
      TAR=$SHARED
    else
      TAR=/root/notes-src.tar.gz
    fi
  fi
  [ -f "$TAR" ] || { echo "找不到源码包: $TAR"; exit 1; }
  echo "  用的包: $TAR  (修改时间 $(stat -c %y "$TAR" | cut -d. -f1), 属主 $(stat -c %U "$TAR"))"

  export JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64
  export MAVEN_OPTS="-Xmx320m -XX:MaxMetaspaceSize=192m"

  echo "[1/4] 解压源码 -> $BUILD"
  rm -rf "$BUILD"
  mkdir -p "$BUILD"
  tar -xzf "$TAR" -C "$BUILD"
  [ -d "$BUILD/notes-app" ] || { echo "源码包结构不对（应该含 notes-app/）"; exit 1; }

  echo "[2/4] 编译"
  cd "$BUILD/notes-app"
  mvn -B -q -DskipTests package 2>&1 | tail -10
  [ -f target/notes-app.jar ] || { echo "BUILD FAILED"; exit 1; }
  ls -lh target/notes-app.jar
  PKG=target/notes-app.jar
fi

echo "[3/4] 备份旧包并替换"
[ -f "$APP_DIR/notes-app.jar" ] && cp -f "$APP_DIR/notes-app.jar" "$APP_DIR/notes-app.jar.bak"
systemctl stop "$SVC"
cp -f "$PKG" "$APP_DIR/notes-app.jar"
systemctl start "$SVC"

echo "[4/4] 等待就绪（:${PORT}）"
for i in $(seq 1 25); do
  if curl -s -o /dev/null -m 2 "http://127.0.0.1:$PORT/app/login"; then
    echo "  就绪（约 $((i * 3))s）"
    break
  fi
  sleep 3
done
echo -n "  服务状态: "
systemctl is-active "$SVC"

# 预热：刚起来的 JVM 是解释执行的，头十几次请求 150~800ms，正是「改完打开就慢」的原因。
# 先把主要页面各打几遍，用户点进来就是热的。
DETAIL=$(curl -s "http://127.0.0.1:$PORT/app/notes" | grep -o '/app/notes/[a-z0-9-]*' | head -1)
DETAIL=${DETAIL:-/app/notes}
echo "预热"
for round in 1 2 3 4 5; do
  for u in /app/bagu /app/notes /app/algo /app/explore "$DETAIL"; do
    curl -s -o /dev/null "http://127.0.0.1:$PORT$u"
  done
done
curl -s -o /dev/null -w "  预热后 /app/bagu -> %{time_starttransfer}s\n" "http://127.0.0.1:$PORT/app/bagu"

echo "验证"
curl -s -o /dev/null -w "  /app/login -> %{http_code}\n" "http://127.0.0.1:$PORT/app/login"
curl -s -o /dev/null -w "  /app/notes -> %{http_code}\n" "http://127.0.0.1:$PORT/app/notes"
echo "DONE ($ENV)"
