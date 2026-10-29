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

## 多用户与权限

从 V5 开始，每个用户有自己的项目/笔记/八股/算法空间。

- `content_item.owner_id` 指向创建者；`slug` 只在本人范围内唯一（`uq_content_owner_slug`）
- `recommended = true` 的内容才会出现在「推荐」里、对别人可见；管理员新建的内容自动是 `true`
- `visibility` 只在「别人看推荐内容」时起作用：`PUBLIC` 谁都能看、`MEMBER` 要登录、`PRIVATE` 只有本人
- 备忘录是 `content_star(user_id, item_id)`，不是内容上的一个布尔值——标记属于「谁标的」
- `/admin` 现在登录就能进，默认只看到自己的；管理员额外能看到全部
- 注册开关：`/opt/apps/notes-app-test/app.env` 里的 `REGISTER_ENABLED=false` 关掉（关掉后 `/register` 直接 404），同 IP 每小时最多注册 3 个

## 约定

- **不要直接往 `main` 推**，`main` 只接 `dev` 的合并
- 改表结构（加字段/加表）前先 `sync-db.sh` 拿一份新的测试数据，在自己环境上验证 migration 能跑通
- 部署前确认工作区干净（`git status`），别把临时文件带上服务器
- 前台改动（CSS/模板）部署完记得**硬刷新**再看效果，浏览器会缓存

## 踩过的坑

**恢复脚本必须解压**：`restore-db.sh` 早期版本把 `.sql.gz` 直接喂给 `psql`，日志里只有一堆
`invalid byte sequence for encoding "UTF8"`，脚本却照样跑完并打印 `DONE`——`drop schema` 执行了、
数据一条没进去，库被清空。现在脚本会先数备份里有几个 `CREATE TABLE`，一个都没有就直接拒绝恢复。
**任何恢复/导入脚本改完，必须在测试库上真跑一次并核对条数，不能只看退出码和 `DONE`。**

**`set -o pipefail` 配 `grep -q` 会误判**：`nginx -t 2>&1 | tee log | grep -q "syntax is ok"`，
grep 命中就退出，`tee` 收到 SIGPIPE，整个管道退出码变成 141，`if` 认为「配置有问题」——
实际上配置是好的。正确写法是先 `nginx -t > log 2>&1`，再用 `grep -q ... log` 判断。

**CSS 里 `padding` 简写会覆盖同一条规则的 `padding-left`**：给树的分组按钮加
`padding: 0.34rem 0.5rem` 之后，之前单独写的 `padding-left: 0.95rem` 被冲掉，折叠箭头直接压在文字上。
改的时候要么写全四个值，要么把 `padding-left` 放在简写之后。

**PowerShell 写文件会带 BOM，javac 编译不过**：`Set-Content -Encoding UTF8` 在 Windows PowerShell 下
会写 `EF BB BF` 开头，编译报 `class, interface, enum, or record expected`（位置指向第一个 import，
很容易误以为是括号问题）。改 Java 源码要么用编辑器，要么写完后去掉 BOM。

**`grant all` 不等于能改表**：测试库的表是从正式库 dump 恢复的，属主是 `notes`，而 `notes-app-dev`
用 `notes_dev` 连。`grant all` 只给 DML 权限，`ALTER TABLE`（Flyway 迁移）要的是**属主**，
报错 `must be owner of table content_item`。`restore-db.sh` / `sync-db.sh` 现在恢复完会调用
`/opt/deploy/test-owner.sql` 把表和序列的属主转给 `notes_dev`。
不能用 `REASSIGN OWNED`：`notes` 同时是数据库属主，会报「被数据库系统要求的对象不能转」。

**健康检查别用 nginx 的状态码**：应用没起来时 nginx 返回 502，而 502 不等于「连接失败」，
脚本里 `[ "$code" != "000" ]` 会立刻误判为已就绪。要么直连 `127.0.0.1:8082` 等 200，
要么显式排除 502/504。重启一次大概要 30 秒。


