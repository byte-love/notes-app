# 服务器运维脚本

这些脚本跑在服务器上，仓库里是**源码副本**。改了之后要传回去才生效：

```bash
scp deploy/app.sh deploy/site.sh deploy/sync-db.sh deploy/restore-db.sh \
    deploy/psql.sh deploy/test-owner.sql root@47.104.210.93:/opt/deploy/
scp deploy/backup-pg.sh root@47.104.210.93:/srv/backup/
ssh root@47.104.210.93 "chmod +x /opt/deploy/*.sh /srv/backup/backup-pg.sh"
```

## 两个环境

| | 正式 | 测试 |
|---|---|---|
| 应用 | `/opt/apps/notes-app` `:8081` systemd `notes-app` | `/opt/apps/notes-app-test` `:8082` systemd `notes-app-dev` |
| 静态站 | `/srv/sites/lab` `:8080` | `/srv/sites/lab-dev` `:8180` |
| 数据库 | `notes` | `notes_test` |

## 每个脚本干什么

### `app.sh <dev|prod>`

后端发布。解压源码包 → Maven 编译 → 备份旧 jar 成 `notes-app.jar.bak` → 停服 → 换包 →
启动 → 轮询 `/app/login` 等就绪 → 打印状态码。

**编译失败会直接退出，不会把服务停掉**，所以编译不过最坏情况是没更新，不是站点挂了。

包从哪来：`/srv/deploy/notes-src-<env>.tar.gz`，没有才回落到 `/root/notes-src.tar.gz`。
脚本开头会打印实际用的包、修改时间和属主。

### `site.sh <dev|prod>`

静态站发布。解压到 `<目录>.new` → 检查有没有 `index.html` → 原子切换目录 →
上一版留成 `<目录>.old`。切换是秒级的，不会出现"删了旧的还没换新的"的空窗。

包从 `/srv/deploy/dist-<env>.tar.gz` 来，没有才回落到 `/root/dist[-dev].tar.gz`。

### `sync-db.sh`

把**正式库整库覆盖到测试库**：清空测试库 schema → `pg_dump` 正式库直接灌进测试库 →
把表和序列的属主转给 `notes_dev`。

注意它会停 `notes-app-dev` 约 40 秒。

### `restore-db.sh <备份文件> [notes|notes_test]`

从备份恢复。三步保命措施：

1. 先把当前状态再备份一份 `before-restore-<时间>-<库>.sql.gz`
2. 数一遍备份里有几个 `CREATE TABLE`，一个都没有就**拒绝恢复**（防的是文件损坏或没解压）
3. 导入后扫日志里的 `ERROR` 行

恢复完会把属主转回 `notes_dev`（测试库）并启动对应服务。

### `psql.sh "<sql>" [test|prod]`

用 `notes_dev` 账号执行一条 SQL，默认连测试库，加第二个参数 `prod` 连正式库。
密码不在脚本里，读的是服务器上 `/opt/apps/notes-app-test/.dbpass`。

给协作者用，省得他有数据库密码或者 docker 权限。

### `backup-pg.sh`

每小时 `pg_dump` 一次正式库，写到 `/srv/backup/pg/`：

- 3 点那份标 `daily`，留 14 天
- 其余标 `hourly`，留 24 小时
- 每次备份都做 `gzip -t` 完整性校验，并且要求至少有一个 `CREATE TABLE`，不合格就删掉并记失败日志

由 cron 每小时跑一次，日志 `/var/log/backup-pg.log`。

### `test-owner.sql`

把 `notes_test` 里的表和序列属主转给 `notes_dev`。

**为什么需要**：测试库是从正式库 dump 恢复的，恢复出来的对象属主是 `notes`，而应用用
`notes_dev` 连。`grant all` 只给 DML 权限，`ALTER TABLE`（Flyway 迁移）要的是属主，
缺了它测试环境启动时会报 `must be owner of table content_item`。

`sync-db.sh` 和 `restore-db.sh` 恢复完都会自动调用它。

### `import-note.sh` / `test-import.sh`

往站里导内容（见 `docs/` 和根 README），不是部署脚本。

## 权限边界

`dev` 账号的 sudo 白名单在 `/etc/sudoers.d/90-notes-dev`，只能跑：

- `app.sh dev|prod`、`site.sh dev|prod`
- `sync-db.sh`、`restore-db.sh *`、`psql.sh *`
- `backup-pg.sh`
- `systemctl restart|stop|start|status notes-app-dev`，`systemctl restart notes-app`

**sudo 是精确匹配的**：`sudo /opt/deploy/app.sh dev` 能过，后面多带一个参数就不匹配，
会提示 `sudo: a password is required`。所以包必须放在脚本自己会去找的 `/srv/deploy/` 里。
