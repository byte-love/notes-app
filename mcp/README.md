# 接入 AI：把你的 AI 变成会往里存笔记的助手

两个方式，任选一个：

| | 本地 MCP（stdio） | 远程 MCP（HTTP） |
|---|---|---|
| 需要 | 本机装 Node 18+，放一个脚本文件 | 什么都不装 |
| 配置 | `command` + `args` + 两个环境变量 | 一个 URL + 一个 token |
| 插图 | 支持（读本地图片文件上传） | 暂不支持（只支持文字笔记） |

两边的能力一样：**只能操作你这个账号自己的笔记**，改和删都必须由用户在对话里点头。

## 第 1 步：拿 token

登录站点 → 右上角点头像 → 「我的」→ **AI 接入** → 「生成 Token」。
明文只显示这一次，丢了就「重新生成」（旧的立刻失效）。

## 第 2 步（方式 A）：本地 MCP

把仓库里的 `mcp/notes-mcp.mjs` 拷到本机任意位置，比如 `D:/tools/notes-mcp.mjs`，然后：

**Claude Desktop**（`claude_desktop_config.json`）

```json
{
  "mcpServers": {
    "byte-love-notes": {
      "command": "node",
      "args": ["D:/tools/notes-mcp.mjs"],
      "env": {
        "NOTES_BASE": "http://47.104.210.93:8080/app",
        "NOTES_TOKEN": "bl_你生成的token"
      }
    }
  }
}
```

**Claude Code**

```bash
claude mcp add byte-love-notes \
  -e NOTES_BASE=http://47.104.210.93:8080/app \
  -e NOTES_TOKEN=bl_你的token \
  -- node D:/tools/notes-mcp.mjs
```

**Cursor**（`.cursor/mcp.json`，格式同 Claude Desktop）

## 第 2 步（方式 B）：远程 MCP

同样一个 token，填地址就行（Claude Code / Cursor / 支持 HTTP MCP 的客户端）：

```bash
claude mcp add --transport http byte-love-notes \
  http://47.104.210.93:8080/app/mcp \
  --header "Authorization: Bearer bl_你的token"
```

## 第 3 步：直接跟 AI 说话

> 把咱们刚才聊的整理成一篇笔记，标题叫「线程池参数怎么调」，放到「多线程」目录下。
>
> 存之前先看看站里是不是已经有类似的笔记，有就拿给我看，别自己覆盖。

存之前 AI 会问你**这篇给什么权限**、**几颗星**（除非你已经说了）：

| 档 | 谁能看 | 会不会进技术广场 |
|---|---|---|
| `PUBLIC` | 任何人，包括没登录的游客 | ✅ |
| `MEMBER` | 只有你把链接发给对方、他注册登录之后能看 | ❌ |
| `PRIVATE` | 只有你自己 | ❌ |

星级 `rating` 是 `0~3`：3 星 = 面试高频、迟早会被问到；2 星 = 值得复习；1 星 = 有空再看；0 = 先不评（默认）。

完整的问题清单写在服务端握手时下发的 `instructions` 里（远端 `McpController.INSTRUCTIONS`、本地
`notes-mcp.mjs` 的 `INSTRUCTIONS`，改一处要改两处）：目录 / 类型 / 谁能看 / 几颗星 / 查重 / 落库前复述确认。

服务端也把这条钉死了：`note_create` 不带 `visibility` 会直接 400 并要求先问用户，不会有“默认值”悄悄替你决定。

## 工具一览

| 工具 | 干什么 |
|---|---|
| `notes_folders` | 列出我的目录，挑 `parent` 用 |
| `notes_search` | 在自己笔记里搜关键词（查重） |
| `note_get` | 读一篇的完整 Markdown |
| `note_create` | 新建（**永不覆盖**；`visibility` 必填，AI 会先问你；标题像已有笔记时会在 `similar` 里列出来，让 AI 问你） |
| `note_update` | 覆盖（必须 `confirm: true`；服务端先把旧版本存进 `content_revision`） |
| `note_delete` | 软删进回收站（必须 `confirm: true`，30 天内可恢复） |
| `note_upload_image` | 仅本地版：上传本地图片，返回可写进 Markdown 的地址 |

## 安全边界

- 每个请求按 token 找用户，**所有读写都限定在这个人自己的条目上**；别人的 slug 一律 404
- 覆盖/删除缺 `confirm` 时服务端直接拒绝，不靠 AI 自觉
- 覆盖前自动留快照（`content_revision` 表），删的是软删（回收站）
- 写操作限流：10 分钟最多 60 次
