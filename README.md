# notes-app

个人笔记站。Spring Boot 单体应用，带账号体系、树状目录和三级可见性，部署在一台 2C2G 的阿里云 ECS 上，作为作品集站的「笔记」页签在用。

## 技术栈

- Java 21 + Spring Boot 3.3.5（Web / Security / Data JPA / Thymeleaf）
- PostgreSQL 16（Docker 运行），Flyway 管表结构
- 服务端渲染 Thymeleaf 模板，无前端框架、无 Node 构建
- commonmark-java 渲染 Markdown

## 功能

- **登录与会话**：Spring Security 表单登录，服务端 Session（HttpOnly + SameSite=Lax），全局 CSRF 防护，密码 BCrypt 存储，登录失败有次数限制与临时锁定。
- **三级可见性**：`PUBLIC` 匿名可读 / `MEMBER` 需登录 / `PRIVATE` 仅 OWNER。权限判断集中在 `VisibilityService`，列表、详情、接口共用同一套规则，不存在「列表藏了但直连 URL 能看」的漏洞。
- **看不到的条目不消失**：游客仍会在目录树和列表里看到 `MEMBER` 条目，但只是「压暗 + 锁」的占位（点进去走登录页），并提示「登录后可查看其余 N 篇」；`PRIVATE`（公司内容）连标题都不出现。`VisibilityService.lockedTo()` 只返回 `MEMBER`，就是这个边界。
- **树状目录**：`content_item` 表用 `parent_id` 自引用 + `sort_order` 排序，一次查询装配整棵树，不递归查库。
- **Markdown 渲染**：在 AST 阶段把匹配到的术语文本节点替换成自定义节点，输出成带 `data-tip` 的 `<span>`，术语注解由服务端生成，不依赖前端脚本；跳过标题和代码块，同一个词每篇只标第一次出现。术语解释存在 `glossary_term` 表（`resources/glossary.json` 只在表为空时当种子），管理员点一下词就能改。
- **评论与注释**：整篇或选中一句都能评，一句话下可楼中楼回复；长解释走「注释」那条线（正文里画短虚下划线、点开弹窗），两者共表不同 `entry_kind`。
- **重点高亮**：选中文字标五色底纹，只给标的人自己看；底纹用 CSS Custom Highlight API 画，不往 DOM 塞 span。
- **星级与标签**：每条内容可以标 1–3 星（点星/键盘 1·2·3），列表按 `★★★ / ★★以上 / 收藏 / 未评` 筛；标签由 `resources/tags.json` 的固定词表自动打（标题 3 分、正文 1 分，取前 5），列表可按标签筛、并能和星级叠着用。标签气泡按词固定配色（同一个词永远同一个色调，`TagTone` 哈希到 8 个低调色相，见 `app.css` 的 `t0~t7`）。
- **正文就地编辑**：自己的文章，正文和标题**点进去就能改**（`contenteditable`），停手约 1.2 秒自动存、不刷新页面；回车另起一段。前端把 DOM 折回 Markdown 再提交（`static/js/doc-edit.js`，覆盖标题/段落/列表/引用/代码块/表格/行内标记）。正文超过 64 KB 自动降级成「编辑」按钮 + Markdown 源码框。
- **笔记卡片**：正文页右上角「插入笔记卡片」，写一段挂到这篇末尾（`supplement_of_id`），卡片右侧「×」可删（进回收站）。
- **AI 接入**：「我的」页生成个人 token，本机 AI（Claude Code / Cursor / Claude Desktop）通过 MCP 直接往里存笔记。两种接法：远程 `POST /app/mcp`（填地址 + token），或本地 `mcp/notes-mcp.mjs`（stdio，零依赖）。只能操作自己的条目；覆盖 / 删除必须带 `confirm`，覆盖前留旧版本快照，新建永不覆盖；可见性由 AI 先问用户（服务端强制）；`note_create` 带 `folder=true` 可以建分类。
- **导航与分区**：顶栏四项 —— 我的笔记 / 技术广场 / 用户指南 / 更新日志。「我的笔记」里用一排分区切换四类内容：闪念（`/notes`）、项目（`/projects`）、八股（`/bagu`）、算法（`/algo`）；技术广场（`/explore`）只摆 `PUBLIC` 内容；用户指南（`/guide`）是静态说明页，截图在 `static/img/guide/`。
- **目录与就地新建**：左树顶上「我的目录」右侧「+」加到最外层、每行末尾「+」加到那一行下面，填名字回车即建好（`POST /admin/quick`，slug 自动生成）；行末「⋯」里有重命名、编辑、复制链接、删除（连子节点一起软删，`POST /admin/{id}/delete`）。编辑页勾「当目录用」= 纯分类，不占列表卡片。
- **收藏 / 书签 / 回收站 / 技术广场 / 更新日志**：收藏和个人书签落在用户自己名下；删除先进回收站（30 天）；选 `PUBLIC` 的内容自动进技术广场（「加入我的空间」的副本除外），管理员在后台那一行可以「下架 / 推荐」；广场分组页会显示「你的 / 已加入」并给「移除」（只删自己那份副本）。正文末尾有「下一篇」，走的是同一分类内的顺序，到尾显示终点。
- **管理后台**：可新建 / 编辑内容、改可见性、调整父子关系；列表行有「推荐 / 下架」开关（只对管理员显示）。批量重打标签只留接口 `POST /admin/retag`（页面上不再放按钮，换词表时内部调用）。
- **`/app/api/me`**：给同源静态作品集页读登录态和 CSRF token，作品集页据此渲染「登录 / 后台 / 退出」。

## 本地跑

需要一个 PostgreSQL，最快是 Docker：

```bash
docker run -d --name notes-pg \
  -e POSTGRES_DB=notes -e POSTGRES_USER=notes -e POSTGRES_PASSWORD=dev \
  -p 5432:5432 postgres:16-alpine
```

然后带环境变量启动（不设密码就不会建账号）：

```bash
export PG_PASSWORD=dev
export OWNER_PASSWORD=dev-owner
export VIEWER_PASSWORD=dev-viewer
mvn spring-boot:run
```

访问 http://localhost:8081/ 。Flyway 首启自动建表，`AccountSeeder` 用环境变量里的密码建 `owner` / `viewer` 两个账号。

## 目录

```text
src/main/java/cn/bytelove/notes/
  config/     SecurityConfig（路由权限、登录成功/失败处理）、AppProperties
  domain/     AppUser、ContentItem、Role、Visibility、ContentKind
  repo/       Spring Data JPA 仓储
  security/   UserDetailsService
  service/    AccountSeeder、LoginAttemptService、VisibilityService、NoteTreeService、
              MarkdownRenderer、GlossaryService、CommentService、HighlightService、
              RatingService、TagService、ApiTokenService、ChangelogService、TrashService、
              RecommendService…
  web/        PublicController、AdminController、AuthController、ApiController、
              AiController（/api/ai/*）、McpController（/mcp）、
              GlossaryEditController、ChangelogController…
src/main/resources/
  application.yml        全部配置；密码只从环境变量读；上传上限也在这里
  db/migration/          Flyway 脚本（V1 建表 … V28 AI token，共 28 个）
  glossary.json          术语表种子（表为空时导入，之后以库里为准）
  tags.json              标签词表：{标签: [关键词…]}
  subgroups.json         大分类的二级分档规则
  templates/             Thymeleaf 模板
  static/css/app.css     全部样式，手写，无框架
mcp/
  notes-mcp.mjs          本地 stdio MCP（零依赖 Node），给 AI 客户端调
  README.md              两种接法（远程 / 本地）的配置写法
```

## 部署

线上是单机双环境：nginx 在 **443/80** 上服务 `https://byte-love.cn/`（`/app/` → `127.0.0.1:8081`、`/` →
`/srv/sites/lab`），测试站是 `http://47.104.210.93:8180/`（`/app/` → `127.0.0.1:8082`）。
老的 IP 入口 `http://47.104.210.93:8080/` 已于 2026-11-04 关闭。
Spring Boot 只监听回环，PostgreSQL 跑在 Docker 里也只绑回环，不对外暴露。应用由 systemd 托管，
JVM 参数按 2G 内存调（正式 `-Xms256m -Xmx448m`、测试 `-Xms192m -Xmx320m`，都是
`-XX:+UseG1GC -XX:MaxGCPauseMillis=50`）。测试实例连独立的 `notes_test` 库，改坏了可以用
`sync-db.sh` 从正式库重灌。正式站的会话 cookie 带 `Secure`（`COOKIE_SECURE=true`），只在 HTTPS 下发。

发布流程：源码打包上传服务器 → 服务器上 `mvn package` → 停服务、换 jar、起服务、探活。
正式和测试共用同一套参数化脚本 `/opt/deploy/app.sh <dev|prod>`，完整流程与权限边界见
[`docs/dev-workflow.md`](docs/dev-workflow.md)。
