#!/usr/bin/env bash
# 部署 notes-app：app.sh <dev|prod> [tar路径]
#   prod -> /opt/apps/notes-app      :8081 (systemd: notes-app)
#   dev  -> /opt/apps/notes-app-test :8082 (systemd: notes-app-dev, 独立库 notes_test)
set -uo pipefail

ENV=${1:-}
TAR=${2:-}

case "$ENV" in
  prod) APP_DIR=/opt/apps/notes-app;      PORT=8081; SVC=notes-app;     BUILD=/opt/build/app-prod ;;
  dev)  APP_DIR=/opt/apps/notes-app-test; PORT=8082; SVC=notes-app-dev; BUILD=/opt/build/app-dev ;;
  *) echo "usage: $0 <dev|prod> [tar]"; exit 1 ;;
esac

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

echo "[1/5] 解压源码 -> $BUILD"
rm -rf "$BUILD"
mkdir -p "$BUILD"
tar -xzf "$TAR" -C "$BUILD"
[ -d "$BUILD/notes-app" ] || { echo "源码包结构不对（应该含 notes-app/）"; exit 1; }

echo "[2/5] 编译"
cd "$BUILD/notes-app"
mvn -B -q -DskipTests package 2>&1 | tail -10
[ -f target/notes-app.jar ] || { echo "BUILD FAILED"; exit 1; }
ls -lh target/notes-app.jar

echo "[3/5] 备份旧包并替换"
[ -f "$APP_DIR/notes-app.jar" ] && cp -f "$APP_DIR/notes-app.jar" "$APP_DIR/notes-app.jar.bak"
systemctl stop "$SVC"
cp -f target/notes-app.jar "$APP_DIR/notes-app.jar"
systemctl start "$SVC"

echo "[4/5] 等待就绪（:${PORT}）"
for i in $(seq 1 25); do
  if curl -s -o /dev/null -m 2 "http://127.0.0.1:$PORT/app/login"; then
    echo "  就绪（约 $((i * 3))s）"
    break
  fi
  sleep 3
done
echo -n "  服务状态: "
systemctl is-active "$SVC"

echo "[5/5] 验证"
curl -s -o /dev/null -w "  /app/login -> %{http_code}\n" "http://127.0.0.1:$PORT/app/login"
curl -s -o /dev/null -w "  /app/notes -> %{http_code}\n" "http://127.0.0.1:$PORT/app/notes"
echo "DONE ($ENV)"
