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
- **树状目录**：`content_item` 表用 `parent_id` 自引用 + `sort_order` 排序，一次查询装配整棵树，不递归查库。
- **Markdown 渲染**：在 AST 阶段把匹配到的术语文本节点替换成自定义节点，输出成带 `data-tip` 的 `<span>`，术语注解由服务端生成，不依赖前端脚本；跳过标题和代码块，同一个词每篇只标第一次出现。
- **管理后台**：OWNER 可新建 / 编辑内容、改可见性、调整父子关系。
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
  service/    AccountSeeder、LoginAttemptService、VisibilityService、
              NoteTreeService、MarkdownRenderer、GlossaryService
  web/        PublicController、AdminController、AuthController、ApiController
src/main/resources/
  application.yml        全部配置；密码只从环境变量读
  db/migration/          Flyway 脚本（V1 建表、V2 加树结构）
  glossary.json          术语表，渲染时用来做注解
  templates/             Thymeleaf 模板
  static/css/app.css     全部样式，手写，无框架
```

## 部署

线上是单机：nginx 占 80 端口，`/app/` 反代到 `127.0.0.1:8081`，Spring Boot 只监听回环；PostgreSQL 跑在 Docker 里，同样只绑回环，不对外暴露。应用由 systemd 托管，JVM 参数按 2G 内存调（`-Xmx448m -XX:+UseSerialGC`）。

发布流程：源码打包上传服务器 → 服务器上 `mvn package` → 停服务、换 jar、起服务、探活。
