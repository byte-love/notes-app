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

> 2026-10-30 起 `/` **不再展示静态作品集**，nginx 里加了 `location = /` 直接 302 到 `/app/`
> （`/index.html`、`/en/` 同理）。静态文件还留在 `/srv/sites/lab` 和 `lab-dev`，要恢复就把
> `sites-available/00-test`、`01-dev` 里那 4 行 `location = ...` 删掉再 reload。

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

# 上测试环境：先把包投到共享目录（不需要 root）
tar czf notes-src-dev.tar.gz --exclude=notes-app/target --exclude=notes-app/.git notes-app
scp notes-src-dev.tar.gz dev@47.104.210.93:/srv/deploy/

ssh dev@47.104.210.93
sudo /opt/deploy/app.sh dev      # 改后端/模板/CSS
# 改作品集：把 dist.tar.gz 传成 /srv/deploy/dist-dev.tar.gz，再 sudo /opt/deploy/site.sh dev
```

**包必须放在脚本自己会去找的位置。** 脚本按环境名读 `/srv/deploy/` 下的包：

| 命令 | 优先读 | 没有才回落到 |
|---|---|---|
| `app.sh dev` | `/srv/deploy/notes-src-dev.tar.gz` | `/root/notes-src.tar.gz` |
| `app.sh prod` | `/srv/deploy/notes-src-prod.tar.gz` | `/root/notes-src.tar.gz` |
| `site.sh dev` | `/srv/deploy/dist-dev.tar.gz` | `/root/dist-dev.tar.gz` |
| `site.sh prod` | `/srv/deploy/dist-prod.tar.gz` | `/root/dist.tar.gz` |

`/srv/deploy` 是 `root:dev`、组可写，两个人都能往里面放包。脚本开头会打印
`用的包: <路径> (修改时间 …, 属主 …)`，部署完看一眼就知道发的是谁传的那份。

`sudo` 白名单是**精确匹配**的：`sudo /opt/deploy/app.sh dev` 能过，
在后面多加一个参数（比如自己指定源码包路径 `... dev /tmp/x.tar.gz`）就不匹配了，
会提示 `sudo: a password is required`。

**两个人都能发正式。** 所以发之前先确认那份包是对的——覆盖上去就是线上，
出问题用 `/srv/backup/pg/` 的小时备份回滚数据库，jar 用 `notes-app.jar.bak` 换回来。

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

**正文里上传的图片不在备份里**：它们存在 `/srv/data/uploads/media/`（`backup-pg.sh` 只管 PostgreSQL），
删了就是真没了。要保就把这个目录一起备份（同步到对象存储或另一台机器都行）。
目前它是应用静态资源的一部分（见 `application.yml` 的 `static-locations`），部署不会覆盖。

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
- 收藏夹是 `content_star(user_id, item_id)`，不是内容上的一个布尔值——标记属于「谁标的」
- 书签是 `content_bookmark(user_id, item_id, anchor)`：`anchor` 为空是整篇，非空是正文里某个标题
  （标题 id 由标题文字生成，见 `MarkdownRenderer.slugOf`；以前用 h1/h2 序位号，插标题会错位）
- 「上次看到」是 `reading_position(user_id, kind, item_id, progress)`：每个分区只记一条，打开详情页自动写；
  `progress` 是 0~1 的滚动比例，离开页面时由 `sendBeacon` 上报，下次打开按比例滚回去（短于一屏时为 0，不跳）
- 列表页（笔记/八股/算法/项目）**分批加载**：服务端按分类整组切约 40 条，滚到底再用
  `?from=N&fragment=true` 取下一批；左侧树超过 60 个节点时只渲染顶层 + 当前路径，展开时调
  `/api/tree/{kind}?parent=<slug>` 补子节点
- 静态资源带 `?v=` 版本号 + `Cache-Control: max-age=31536000`，nginx 开了 `gzip_types`（css/js）；
  nginx 到应用是长连接（`upstream ... keepalive` + `Connection ""`）
  **改了 css/js 必须同时升 `?v=`**，否则强缓存让浏览器永远跑老代码（2026-10-30 踩过：评论改了 4 版用户一直看不到）
- 评论是 `content_comment(item_id, user_id, parent_id, quote, body, anchor_kind, block_index, prefix, suffix, entry_kind)`：
  锚点两种粒度——`text`（选中文字，存原文 + 前后各 20 字）和 `block`（整段 / 代码块，存正文顶层块序号）；
  楼中楼用 `parent_id`，渲染最多两层。**评论按「最初来源条目」归并**：复制出来的副本沿 `copied_from_id`
  往上找根节点，所有人看同一串评论（否则每份副本各聊各的）
- **评论和注释是同一张表的两条线，靠 `entry_kind` 区分**（V22 加的列）：
  - `COMMENT`：右栏评论区，整篇评 / 选中文字评 / 楼中楼，进 `#comments`
  - `ANNOTATION`：正文里**短虚下划线**（`.ann-mark`，`1px dotted`），点开是 `.ann-pop` 弹窗——列这一处全部注释，
    也能接着写。数据渲染在 `#annotations`（hidden）里当数据源，表单 `.ann-form` 带 `entryKind=ANNOTATION`
  - 选中文字后工具条给三个按钮「评论」「注释」「高亮」，走 `comments.js` 里各自的 handler；评论 / 注释两种标记互不嵌套
    （`markText` 的 walker 同时跳过 `.cmt-mark` 和 `.ann-mark`）
  - 服务端 `addComment` 收 `entryKind`：注释必须带 `quote`（没有落点画不出下划线，直接忽略）
  - 两条线共用 10 秒一条的限流、脏话挡板。**字数上限按 kind 分开**（2026-11-02 收口）：
    评论 500 字（`MAX_COMMENT`，前端输入框也是 `maxlength=500`）、注释 2000 字（`MAX_ANNOTATION`，
    前端也是 2000）；服务端按 `entryKind` 选上限后 `substring` 截断，所以绕过输入框直接 POST 也写不进长评论。
    实测：700 字的评论落库是 500，700 字的注释原样 700
  - **写什么进哪条线**：评论只写「对这一段有疑问 / 一点小见解」这种短的；长解释、补充、贴代码走注释。
    2026-10-30 已经把当时所有 `COMMENT` 行改成了 `ANNOTATION`
    （`update content_comment set entry_kind='ANNOTATION' where entry_kind='COMMENT'`），因为那些内容全是长解释
- **重点高亮（V24）**：`content_highlight(item_id, user_id, quote, anchor_kind, block_index, prefix, suffix, color)`，
  锚点和评论同一套（原文 + 前后各二十字 + 块序号），颜色五种（yellow/green/blue/pink/purple）。
  **只给标的本人看**：查询一律带 `user_id`，别人打开同一篇看不到。同一段再标一次是换色（服务端按
  锚点去重，不会叠两层）。正文里的底纹用 **CSS Custom Highlight API** 画（`CSS.highlights` + `::highlight(hl-*)`），
  不往 DOM 里塞 span——评论 / 注释已经在正文里套 span 了，再套一层容易把它们的选区结构弄坏。
  ⚠ **`::highlight()` 伪元素不认 `var()`**（自定义属性不会带进高亮伪元素，写了就是空值、页面上一点底纹都没有，
  2026-11-02 踩过）：颜色由 `comments.js` 从 `:root` 读出 `--hl-*` / `--accent-soft` 写成实体值的 `<style>`，
  切主题时重写。点击已标文字浮出换色 / 删除的小面板（点位置靠 `caretRangeFromPoint` + `Range.compareBoundaryPoints` 判定）
- **正文自动围代码块的判据**（`MarkdownRenderer.fenceBareCode`，2026-11-02 全站体检后收紧）：
  去掉列表符号后不含中文、长度 ≤200、含 `; { } ( ) =` 之一，或以 `//` `#` `--` `/*` `@` 开头；**连续 ≥3 行**才围。
  另外三条别再改回去：① Markdown 的图片 / 链接 / 表格行**不是代码**（以前 Redis、MySQL 两篇里成组的截图被围成
  代码块，页面上是 `![](...)` 字面量）；② JSON 的键值行、单独一行括号**算代码**，且要先于「含中文就不算」判断
  （`"id": 202,   // 活动ID` 这种行尾中文注释是正常写法）；③ 行尾注释（`code; // 说明`）先摘掉再判中文和长度。
  只围 2 行会把「1kb = 1024byte。」这种并列的两条换算误收进去，所以阈值是 3

- 术语浮窗是另一套（tooltip）：`.markdown .gl[data-tip]` + `detail.js` 的 `.gl-tip`，鼠标悬停显示、灰色长虚线，
  跟注释的松绿点线不是一回事。**解释存在 `glossary_term` 表**（V25，第一次启动用 `resources/glossary.json`
  播种，表非空就不动），所以能在页面上直接改：
  - 管理员点正文里带解释的词 → `.gl-edit` 浮出「术语 / 语境关键词 / 解释」+ 保存·删除·新增；接口在
    `GlossaryEditController`（`POST /glossary`、`POST /glossary/{id}`、`POST /glossary/{id}/delete`，非管理员 403），
    入口靠 `layout.html` 里的 `<meta name="glossary-endpoint">`（只有 `glossaryAdmin` 才渲染）
  - **语境关键词（`sense`）**：同一个词可以有多个义项，只有关键词出现在**这一段的正文**里才命中那一条；
    一个词只有语境义项、又一条都没命中时宁可不标。这是为了「标签」这种词 —— Redis 段落里是 hash tag，
    模型段落里才是 label，以前一套解释套全站，在 Redis 的 Hash 结构里弹出来的是模型的 label（2026-11-02 用户指出）
  - 改/删/新增完页面会 `location.reload()` 一次（术语可能改名、新增的词要重新包标记）

- **星级（V26）**：`content_item.rating`（0 = 没评，1–3）。改星级由 `RatingService` 的 SQL 直写，
  **不碰 `updated_at`** —— 列表是按 `updated_at` 倒序排的，标个星就把这条顶到最前，翻着翻着顺序就乱。
  列表卡片右侧和正文标题下面都能点：点第 n 颗 = n 星，再点当前那颗 = 清回 0；列表里鼠标停在哪张卡上按
  `1` `2` `3` 打星、`0` 清空（`rate.js`，左侧目录里同一篇的星跟着刷新）。`POST /rate/{slug}` 只允许改
  自己名下的条目，别人的内容 403。筛选：`?rate=star|3|2|1|0`（收藏 / 三星 / 两星以上 / 一星以上 / 没评），
  **只筛卡片，左侧目录整棵不动**。初始值来自《复习重点》原文档题目后面的 ☆☆☆，按标题回填
  （一次性 SQL 存在 `_publish/rating-backfill-2026-11-02.sql`）。

- **自动标签（V27）**：`content_tag(item_id, tag, score)`，词表在 `resources/tags.json`（20 个标签，
  写成 `{标签: [关键词…]}`）。打分规则：标题命中 3 分、正文命中 1 分（同一个词在正文里最多算 3 次），
  够 3 分才挂，最多 5 个；**英文缩写按左边界匹配**（`(?<![a-z0-9])`），否则 `ES` 会命中 `false`、
  `AI` 会命中 `wait`。触发点：后台新建/保存、导入脚本（走同一张表单）、`POST /admin/retag`
  （管理页「重新打标」，只重算自己名下）。**改完 `tags.json` 必须重新部署 + 点一次「重新打标」**，
  否则存量 `content_tag` 还是旧词表算出来的。筛选：`?tag=Redis`，可以跟 `rate` 叠着用；
  标签条数按「当前星级筛选后」的集合算。

- **正文编辑器**：正文页标题下面那排有「编辑」（只对自己内容显示），链接带 `back=<文章路径>`。
  编辑页左写右预览（`admin-editor.js`）：预览打 `POST /admin/preview`，用的是**服务端同一个
  `MarkdownRenderer`**，不是前端另写一套渲染；保存后 `redirect:` 到 `back`，所以改完直接回文章页看到效果。
  `back` 只收站内路径（`safeBack` 挡 `//` 和 `://`）。插图走 `POST /admin/upload`：文件落在
  `/srv/data/uploads/media/notes/<随机名>.<ext>`（`notes.media-dir` 可配），返回 `/app/media/notes/xxx.png`
  写进正文。这个地址能通，是因为 `application.yml` 的 `static-locations` 把整个 `/srv/data/uploads/`
  当静态目录挂了出来（所以文件在 `uploads/media/...` 下，URL 就是 `/media/...`），`/media/**` 在
  `SecurityConfig` 里放行。上传的 CSRF 走 **`X-CSRF-TOKEN` 请求头**：multipart 的 body 在过滤器那一层
  还读不到 `_csrf` 参数。上传上限在 `application.yml`（`spring.servlet.multipart.max-file-size: 8MB`）。

- 「加入我的空间」是**幂等**的：`copyTo` 先查 `(owner_id, copied_from_id)` 有没有复制过，有就返回 0；
  页面上已加入的卡片显示「已加入」。重复复制产生的脏数据用
  `partition by owner_id, copied_from_id` 保留最小 id 清掉
- 403 分两种原因：没登录（提示「需要登录」）和登录了但没权限（提示「只有作者本人能看」）。
  原因由 `PublicController.forbidden(reason)` 挂在 request 的 `errorReason` 上，`AppErrorController` 据此换文案
- 更新日志**一天一个版本**（不是一次改动一个版本），当天的新增功能写在「新增功能」小节；
  修复 / 优化 / 清理收进「修复与优化」小节，页面上默认折叠（`details.cl-fold`）
- 版本号按改动量加：很少 → 末位 +1（`1.0.1`），较多 → 中间位 +1（`1.1.0`），全新版本才 `2.0.0`；
  同一天只用同一个版本号
- `ChangelogService.releases()` 的分组键是 **`released_on + version + title`**：同一天同一个版本的行
  `title` 必须一致，后补条目时 `title` 留空会在页面上多出一个同名版本块（2026-10-30 出现过一天两个 v1.1.0）。
  补条目要么照抄标题，要么插完跑一次
  `update changelog_entry set title = '...' where version = '...' and coalesce(title,'') = ''`
- **`hidden` 属性要靠 `app.css` 开头那条 `[hidden] { display: none !important }` 才真生效**：
  组件自己写了 `display: flex` 的（`.cmt-tool`、`.cmt-anchor-chip`）会把浏览器默认的
  `[hidden] { display: none }` 盖掉，属性设上了、元素还在屏上（2026-10-30 就踩了：评论工具条、
  引文小框一直不消失，前端量 `el.hidden` 却是 true，误判了两轮）。以后新增带 `display` 的浮层
  要记得这条全局规则，别删
- **前端改动要量渲染结果，不能只量 DOM 属性**：判断「收起来了没有」用
  `getComputedStyle(el).display === 'none'` / `el.offsetParent === null` / `document.elementFromPoint`
  实际命中的元素，必要时截一张图；只读 `el.hidden`、只读 JS 返回值都会骗人
- **列表页是「两级」结构，改样式前先看这段**（2026-11-02 定的）：
  - 一级 = 分组标题条（`.item-group-toggle`：`bg-2` 底 + 描边 + 圆角 12 + 计数胶囊），整行可点；
    收起后退成一张两行的卡片（上行：三角 + 标题 + 右侧条数；下行：这组前两条标题的预览），
    跟同样收起的其他分类并排成多列（宽屏 3 列）
    （见下面「收起的一级排成卡片网格」）
  - 二级 = 该组卡片的**筛选标签**（`.subgroup-chip`：胶囊 + 计数），**永远有一档选中**（服务端先把第一档标 `on`），
    所以点开任何一组看到的都是卡片，不会再出现「点开是又一层分类列表」
  - 二级档位由 `SubGroupService` + `resources/subgroups.json` 算：按分组 slug 配 `{name, words}`，
    顺序匹配「标题 + 摘要」，命中第一个词就归那档，都没命中归「其他」；**只有该组条数 ≥ 13 且配了规则才拆**，
    只拆出一档就不拆。加新组只要往 json 里加一段，不用动数据库
  - 展开状态：一级记 `group-fold:<组标题>`，二级记 `sub-open:<组标题>`（存 chip 的 `data-chip`）
  - **收起的一级排成卡片网格**（2026-11-02 定的）：分组外层是 `.group-grid`
    （`repeat(auto-fill, minmax(min(300px, 100%), 1fr))`，窄屏一列，宽度上限和条目列一样是
    `min(1160px, 96%)`）；`.item-group` 默认 `grid-column: 1 / -1` 占满整行，
    挂了 `.folded` 才回到网格里跟别的分类并排。
    卡片本体是 3 列 × 2 行的 grid：`三角 / 标题 / 条数` 在上，`.item-group-preview` 在下（跨到右边缘）。
    所以：**新增页面渲染 `groupSections` 片段的，外面要包一层 `.group-grid`**，
    否则「加载更多」插进来的新分组不会进网格；`.list-more` 锚点已按 `grid-column: 1 / -1` 处理
  - **补充笔记（`supplement_of_id`）不单独占位置**：列表里并进「本体」那张卡片的内嵌附件行
    （`.card-attach`），左树里直接不渲染它，正文末尾给一张附件卡（`.attach-card`），
    补充笔记页标题下面给一条「本体」回链（`.doc-origin`）。层级上两者同级（`parent_id` 相同）
    - 再挂一条补充笔记的做法：新建一篇内容，`parent_id` 设成**本体的父节点**（同级而不是子节点），
      `supplement_of_id` 设成本体 id，`sort_order` 排在本体后面；页面上的附件卡 / 内嵌行会自动出来
    - 附件卡 / 内嵌行上那个体积（`9.1 KB`）是 `PublicController.sizeOf()` 按正文 UTF-8 字节折算的，
      只做量级参考；行号的来源见下面「左树每行带行号」那条
  - **左树样式**（2026-11-02 改）：不再画整列竖线 + 每行短横线（`.tree ul` 的 border-left 和
    `li::before` 都删了），层级靠缩进 + 字重；行是圆角 8 的块，hover 浅底、当前项浅底 + 强调色 +
    左侧 2px 强调色；二级条目长标题用悬挂缩进（`padding-left` + 负 `text-indent`）对齐；
    顶层分组之间用 `border-top` 出一条极淡分隔线
  - **左树每行带行号 + 分组带条数**：行号来自 `NoteTreeService.Node.ordinal()`（同一个父节点下
    只给叶子连续编号，分组不占号），模板里 `#numbers.formatInteger(n.ordinal(), 2)` 补成 01；
    分组标题右侧 `#lists.size(n.children)`（lazy 的那种节点子列表还没渲染，不显示）。
    叶子行的 padding-left 是 2rem、text-indent -1.4rem，正好让号在 0.6rem 起、标题和折行对齐到 2rem
  - **逐行分隔线是「要的效果」，别当 bug 修**（2026-11-02 来回折腾了两次）：`fragments/tree :: nodes`
    是递归片段，**每一层的 `<ul>` 都带 `.tree` 类**，所以 `.tree > li + li` 的 `border-top` 会套到每层，
    叶子之间也是一条线 —— 用户要的就是这个观感（他拿正式版截图对照过）。我把子层改成裸 `<ul>`（`sub`）
    去掉线之后被否了，已还原。要动这块先问
  - **左树字号/行距固定值**：叶子 0.83rem、行高 28px、上下内边距 0.22rem、悬挂缩进 `padding-left:2rem`
    + `text-indent:-1.4rem`；行号 0.66rem（宽 1.1rem）；一级分组 0.9rem、二级分组 0.85rem。
    2026-11-02 试过整体 ×1.15，被否（太挤、换行多），已还原
  - **单行加粗的引用块**（`> **回答流程**`）当标签用：`.markdown blockquote:has(> p:only-of-type > strong:only-child)`
    收窄内边距 + 纵向居中（`display:flex; align-items:center`）、**横向仍然左对齐**（试过居中，
    用户明确要左对齐）。必须用 `:only-of-type`——注释功能会往引用块里插一个「＋」按钮，
    用 `:only-child` 永远匹配不上（踩过一次，规则写了页面没生效）
  - **正文里的三种下划线别撞**：评论 = 1px 实线（强调色 35% 透明，`color-mix`）、注释 = 1px 点线、
    术语浮窗 = 1px 灰虚线；评论不再铺半透明底，避免干扰阅读
  - **页面底部留白**：`.with-tree` 的 `padding-bottom` 是 **3.6rem**（2026-11-02 加）。这站
    **没有可见页脚**（`foot` 片段里只有脚本），改回 0 的话滚到底时最后一个元素（正文末尾的附件卡、
    列表最后一行卡片）会贴着视口底边，看着像被切掉
  - **预览行来自 `ItemGroup.preview`**：`PublicController.previewOf()` 取该组前两条的标题拼成
    `A · B`，收起时显示、展开时整条藏掉（`.item-group:not(.folded) .item-group-preview`）；
    组里只有一条就是一行标题，标题为空串时这一行不占位
  - **坑一：Thymeleaf 的 `th:replace` 优先级高于 `th:each`**。写
    `<th:block th:each="i : ${items}" th:replace="~{... card(${i})}">` 时 fragment 里拿到的是 null
    （列表页 500，报 `Property or field 'id' cannot be found on null`）。必须拆成两层 th:block
  - **坑二：折叠外层要包一个容器**。`.item-group.folded .item-list { display: none }` 只盖得住卡片，
    二级标签行不在 `.item-list` 里，所以二级内容整体包在 `.item-group-body` 里，靠
    `.item-group.folded .item-group-body { display: none }` 一起收掉
  - **坑三：条目列在分组里会缩两次**。`.with-tree .item-list` 本来就限了 `min(1160px, 96%)`，
    分组外层再限一次就变成 96% 的 96%，卡片比上面的条带窄一圈，所以
    `.group-grid > .item-group .item-list { max-width: 100% }` 要留着（实测外层和内层都是 745px 才对齐）
- **改完要看响应时间**：`bash /opt/deploy/measure.sh dev`（服务器上跑，5 个主页面各热身 6 次再采 40 次，
  报 p50/p95 和 TTFB；源码 `deploy/measure.sh`）。2026-10-30 基线：p50 25~82ms，p95 ≤ 111ms
- `app.sh` 部署完会自己把 4 个列表页 + 一篇正文各打 5 遍**预热**——JVM 刚起来头十几次请求是解释执行的，
  慢 5~10 倍（实测 2.7s / 850ms / 500ms 一路降到 80ms），不预热的话「刚发布完打开就很慢」
- `/admin` 现在登录就能进，默认只看到自己的；管理员额外能看到全部
- 更新日志（`/changelog`）是手写的发版说明，存在 `changelog_entry`，只有管理员能写
- 缩略图和更新日志里没有「内容动态」那个页面了（2026-10-29 去掉）
- 注册开关：`/opt/apps/notes-app-test/app.env` 里的 `REGISTER_ENABLED=false` 关掉（关掉后 `/register` 直接 404），同 IP 每小时最多注册 3 个
- 会话超时 24h，另有 remember-me 30 天：**重启进程不会把用户踢下线**（会话本身在内存里，重启会清空，靠 cookie 自动恢复）

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

**别用 `padding-left` 反算「正文居中」**：早先详情页写的是
`padding-left: max(1.5rem, calc((100% - 68ch)/2 - var(--tree-w) - 2rem))`，看着能让正文居中，
实际上 padding 和树宽互相抵消：拖左分隔条正文不动，树宽过了阈值又突然开始挤右栏、分隔条停在屏幕中间。
现在正文用 `justify-self: center` 自己居中，三栏各管各的宽度（树 px、右栏 px、正文吃剩下）。

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

**PowerShell 改含中文的模板会整篇变乱码，全站 500**：2026-10-30 我用 `Get-Content -Raw | Set-Content`
改 `fragments/layout.html` 里的一个版本号，Windows PowerShell 5.1 把 UTF-8 当 GBK 读，写回去整篇
变成 `鍒囨崲娣辨祬鑹?`，Thymeleaf 解析报 `Incomplete structure`，`/app/login` 都变 500。
**改模板/Java/CSS 一律用编辑器或 `edit` 工具**；非要用脚本就得显式 `[System.IO.File]::ReadAllText(path, UTF8)`
读、`UTF8Encoding($false)` 写，改完 `grep` 一遍中文确认没坏。

**`scrollbar-color` 是继承属性，别把「滚动中」做成全局状态**：挂在 `html` 上的滚动条颜色会被所有
`overflow: auto` 的子容器继承，翻正文时左侧目录、右侧大纲的滚动条会一起被点亮（看着像它们自己在动）。
要分栏控制就在各栏上单独写 `scrollbar-color`。

**别在 scroll 事件里调 `getComputedStyle`**：每帧读一次会强制同步样式重算，滚动起来发涩。
颜色在启动时读一次、切主题时重读就够。

**CSRF 的 meta 标签要读 `.content`，读 `.value` 永远是 `undefined`**：`<meta name="_csrf" content="…">`
不是表单控件。2026-11-02 两次栽在这：`rate.js` 打星时 token 是空串 → 403（点了星页面没反应，看着像 JS 没跑），
浏览器里临时拼登录脚本时同理。抄 `comments.js` 里 `const csrf = () => meta.content` 那行就不会错。


