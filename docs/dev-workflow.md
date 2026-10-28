# 开发与部署手册

这个站有两个环境，**所有改动先上测试环境，验收通过再上正式**。

## 环境一览

| | 正式 | 测试 |
|---|---|---|
| 静态站（作品集） | http://47.104.210.93:8080/ | http://47.104.210.93:8180/ |
| 笔记服务 | 同上 `/app/` 路径 | 同上 `/app/` 路径 |
| 应用进程 | `notes-app` :8081 | `notes-app-dev` :8082 |
| 数据库 | `notes` | `notes_test` |
| 代码目录 | `/opt/apps/notes-app` | `/opt/apps/notes-app-test` |
| 分支 | `main` | `dev` |

测试库是正式库的副本，随便改、随便删，搞坏了用下面的「恢复数据库」重来。

## 账号

SSH：`ssh dev@47.104.210.93`（密钥登录，私钥单独给）

权限边界：

- **能**：拉代码、改代码、部署测试/正式、重启服务、查库、备份、恢复
- **不能**：改 nginx 配置、动 systemd 单元、读别人的私钥（这些需要 root）

查库（不用装客户端，也不给 docker 权限）：

```bash
sudo /opt/deploy/psql.sh "select slug, title from content_item where kind='BAGU' limit 5" test
sudo /opt/deploy/psql.sh "select count(*) from content_item" prod   # 第二个参数省略默认 test
```

## 日常开发

```bash
git clone https://github.com/byte-love/notes-app.git
cd notes-app && git checkout dev

# 改代码
git add -A && git commit -m "feat: 描述你改了什么"
git push origin dev

# 上测试环境
ssh dev@47.104.210.93
sudo /opt/deploy/app.sh dev      # 改后端/模板/CSS
sudo /opt/deploy/site.sh dev     # 改作品集（Astro 静态站）时用
```

部署脚本会自动：解压源码 → Maven 编译 → 备份旧 jar（`.bak`）→ 停服 → 换包 → 启动 → 等健康检查 → 报告状态码。编译失败会直接退出，**不会把服务搞停**。

验收 OK 之后再上正式：

```bash
# 合并到 main
git checkout main && git merge dev && git push origin main

# 部署正式
sudo /opt/deploy/app.sh prod
sudo /opt/deploy/site.sh prod
```

## 改数据库

先备份，再动手：

```bash
sudo /srv/backup/backup-pg.sh          # 手动打一份（每小时也会自动跑）
sudo /opt/deploy/psql.sh "update ..." prod
```

自动备份策略：每小时一份留 24 小时，每天 3 点那份留 14 天，位置 `/srv/backup/pg/`，日志 `/var/log/backup-pg.log`。

## 出事怎么办

**应用起不来**：部署脚本换包前会把旧 jar 备份成 `notes-app.jar.bak`，直接换回去：

```bash
# 测试环境
sudo systemctl stop notes-app-dev
sudo cp /opt/apps/notes-app-test/notes-app.jar.bak /opt/apps/notes-app-test/notes-app.jar
sudo systemctl start notes-app-dev
```

**数据被改坏了**：

```bash
# 看有哪些备份
ls -lt /srv/backup/pg/*.sql.gz | head

# 恢复（会自动把当前状态再备份一份，防手滑）
sudo /opt/deploy/restore-db.sh /srv/backup/pg/notes-20261028-1705-hourly.sql.gz prod
```

**测试库想拿最新数据**：

```bash
sudo /opt/deploy/sync-db.sh    # 正式库整库覆盖到测试库
```

`restore-db.sh` 和 `sync-db.sh` 都会先停服、导入、再启动，中间大约 40 秒服务不可用，别在别人正在看的时候跑。

## 约定

- **不要直接往 `main` 推**，`main` 只接 `dev` 的合并
- 改表结构（加字段/加表）前先 `sync-db.sh` 拿一份新的测试数据，在自己环境上验证 migration 能跑通
- 部署前确认工作区干净（`git status`），别把临时文件带上服务器
- 前台改动（CSS/模板）部署完记得**硬刷新**再看效果，浏览器会缓存
