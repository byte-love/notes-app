#!/usr/bin/env node
// byte-love 笔记站的本地 MCP 服务（stdio，零依赖，Node 18+）
//
// 用法：把这个文件放到本机，在 AI 客户端的 MCP 配置里填：
//   {
//     "mcpServers": {
//       "byte-love-notes": {
//         "command": "node",
//         "args": ["D:/tools/notes-mcp.mjs"],
//         "env": { "NOTES_BASE": "http://47.104.210.93:8080/app", "NOTES_TOKEN": "bl_xxx" }
//       }
//     }
//   }
// 只用两个环境变量：站点地址、在站点「我的」页生成的个人 Token。
// 所有写操作都只能动这个 Token 所属账号自己的笔记（服务端按 owner 校验）。

import { readFile } from 'node:fs/promises';
import { basename } from 'node:path';

const BASE = (process.env.NOTES_BASE || '').replace(/\/$/, '');
const TOKEN = process.env.NOTES_TOKEN || '';
const NAME = 'byte-love-notes';
const VERSION = '1.1.0';
const PROTOCOL = '2025-06-18';

// 握手时下发给客户端的说明（和远端 /app/mcp 的 INSTRUCTIONS 保持一致，改一处要改两处）
const INSTRUCTIONS = `你是「byte-love 笔记站」的助手，帮用户把各种内容存进他的笔记库：聊天里聊出来的东西，或者他电脑上散落各处的笔记、文件，都算。

用户一般不想逐项做选择题，所以每一件都先给出你的判断和默认值，问一句「这样行不行」；他说「可以 / 没问题 / 就这样」就直接按默认值写，别再问第二遍：
1. 存到哪：先调 notes_folders 看现有目录，自己挑最像的那个（比如讲 JVM 就放「JVM 与反射」）；确实没有合适的，再问他新分类叫什么，建好拿返回的 slug 当 parent。
2. 内容类型：你自己判断 —— note 闪念（一段想法、零散记录）/ bagu 八股（面试问答）/ algo 算法（题解）/ project 项目。
3. 谁能看：默认 MEMBER（登录用户能看、不进技术广场）。他明确说要公开，或者这篇本来就该给所有人看，才用 PUBLIC（会直接进技术广场）；明确要私密才用 PRIVATE。
4. 几颗星：你自己判断 0~3 —— 3 星面试高频、迟早会被问到；2 星值得复习；1 星有空再看；0 先不评。
5. 撞不撞车：note_create 返回的 similar 不空，就把那几篇标题列出来，默认按「新建一篇」；他说是补进旧的那篇，才用 note_update + confirm=true。

确认时把结论摆成一小段（标题 / 目录 / 类型 / 可见性 / 几颗星）让他一句话回；他纠正哪项就改哪项。
如果一次拿来一堆散落的文件或笔记，先列个清单（一行一篇：标题 → 目录）整体确认，再逐篇落库。

其它：摘要和标签站里自动生成，不用问；标题尽量 20 字以内；正文用 Markdown，代码放 \`\`\` 里；note_create 只新建不覆盖；写完回一句话加一个 url，别把正文再贴一遍。`;

// stdout 只允许出现 JSON-RPC 消息，日志一律走 stderr
const log = (...args) => console.error('[notes-mcp]', ...args);

function fail(message) {
  throw new Error(message);
}

async function api(path, { method = 'GET', body, raw } = {}) {
  if (!BASE) fail('没有设置 NOTES_BASE 环境变量（站点地址，比如 http://47.104.210.93:8080/app）');
  if (!TOKEN) fail('没有设置 NOTES_TOKEN 环境变量（在站点「我的」页生成）');
  const res = await fetch(BASE + path, {
    method,
    headers: { Authorization: 'Bearer ' + TOKEN, ...(raw ? {} : { 'Content-Type': 'application/json' }) },
    body: raw ? body : body == null ? undefined : JSON.stringify(body),
  });
  const text = await res.text();
  if (!res.ok) fail(`请求失败 ${res.status}: ${text.slice(0, 300)}`);
  try {
    return JSON.parse(text);
  } catch (e) {
    return text;
  }
}

const TOOLS = [
  {
    name: 'notes_folders',
    description: '列出我自己笔记的目录（slug / 标题 / 完整路径）。新建笔记前用它挑 parent。',
    inputSchema: { type: 'object', properties: {} },
  },
  {
    name: 'notes_search',
    description: '在我自己的笔记里按关键词搜（标题、摘要、正文）。用来查重：先看是不是已经有同一主题的旧笔记。',
    inputSchema: {
      type: 'object',
      properties: { q: { type: 'string', description: '关键词，留空表示全部' } },
    },
  },
  {
    name: 'note_get',
    description: '读一篇我自己笔记的完整内容（含正文 Markdown）。',
    inputSchema: {
      type: 'object',
      properties: { slug: { type: 'string', description: '笔记的 slug' } },
      required: ['slug'],
    },
  },
  {
    name: 'note_create',
    description:
      '新建一篇笔记（或者一个目录）。只新建、绝不覆盖。返回里的 similar 不为空时，说明站里有标题相近的旧笔记，' +
      '把这几篇列给用户看，默认按「新建一篇」；他说是补进旧的那篇，才改用 note_update。' +
      'visibility 你自己定并告诉他：默认 MEMBER（登录可见、不进技术广场）；他说要公开、' +
      '或者这篇本来就该给所有人看，才用 PUBLIC（会直接进技术广场）；明确要私密才用 PRIVATE。' +
      '要开新分类（比如用户学的 Python、现有目录全是 Java）就传 folder=true：title 是分类名、body 可留空，' +
      '建好后用返回的 slug 当下一次 note_create 的 parent。',
    inputSchema: {
      type: 'object',
      properties: {
        title: { type: 'string', description: '标题（folder=true 时是分类名）' },
        body: { type: 'string', description: '正文，Markdown；folder=true 时可留空' },
        summary: { type: 'string', description: '摘要，可不填（自动取正文开头）' },
        parent: { type: 'string', description: '父目录 slug（可用 notes_folders 查），不填放顶层' },
        visibility: {
          type: 'string',
          description:
            '可见性，你自己定：默认 MEMBER（登录可见、不进广场）/ PUBLIC 会进技术广场 / PRIVATE 仅自己。' +
            '定好告诉他一句，他没意见就用这个',
          enum: ['PUBLIC', 'MEMBER', 'PRIVATE'],
        },
        kind: { type: 'string', description: '内容类型，默认 note', enum: ['note', 'bagu', 'algo', 'project'] },
        rating: {
          type: 'integer',
          minimum: 0,
          maximum: 3,
          description:
            '星级 0~3，你自己判断并告诉他：3 星面试高频、迟早会被问到；2 星值得复习；1 星有空再看；0 先不评。' +
            '他说没问题就按这个来',
        },
        folder: { type: 'boolean', description: '只当目录用（分类）：列表里不占卡片，可在其下继续新建。建分类时传 true' },
      },
      required: ['title', 'visibility'],
    },
  },
  {
    name: 'note_update',
    description:
      '覆盖一篇已有笔记。调用前必须先在对话里复述「要覆盖的那一篇的标题」，得到用户明确同意后再传 confirm=true。' +
      '服务端会先把旧版本存快照再覆盖；不带 confirm 会被拒绝。',
    inputSchema: {
      type: 'object',
      properties: {
        slug: { type: 'string', description: '要覆盖的那篇的 slug' },
        title: { type: 'string', description: '新标题，不填保留原样' },
        body: { type: 'string', description: '新正文（Markdown），不填保留原样' },
        summary: { type: 'string', description: '新摘要' },
        parent: { type: 'string', description: '新的父目录 slug' },
        visibility: { type: 'string', enum: ['PUBLIC', 'MEMBER', 'PRIVATE'] },
        rating: {
          type: 'integer',
          minimum: 0,
          maximum: 3,
          description: '顺便改星级 0~3；用户没提就别传',
        },
        confirm: { type: 'boolean', description: '用户同意覆盖后传 true' },
      },
      required: ['slug', 'confirm'],
    },
  },
  {
    name: 'note_delete',
    description:
      '删除一篇笔记（软删，30 天内可在站点回收站恢复）。调用前必须先把要删的那篇标题告诉用户并得到同意，再传 confirm=true。',
    inputSchema: {
      type: 'object',
      properties: {
        slug: { type: 'string', description: '要删的那篇的 slug' },
        confirm: { type: 'boolean', description: '用户同意删除后传 true' },
      },
      required: ['slug', 'confirm'],
    },
  },
  {
    name: 'note_upload_image',
    description: '上传一张本地图片，返回可以直接写进 Markdown 的地址（![](url)）。',
    inputSchema: {
      type: 'object',
      properties: { path: { type: 'string', description: '图片在本机的绝对路径' } },
      required: ['path'],
    },
  },
];

async function callTool(name, args = {}) {
  switch (name) {
    case 'notes_folders':
      return api('/api/ai/folders');
    case 'notes_search':
      return api('/api/ai/search' + (args.q ? '?q=' + encodeURIComponent(args.q) : ''));
    case 'note_get':
      return api('/api/ai/notes/' + encodeURIComponent(args.slug));
    case 'note_create':
      return api('/api/ai/notes', { method: 'POST', body: args });
    case 'note_update':
      return api('/api/ai/notes/' + encodeURIComponent(args.slug), { method: 'PUT', body: args });
    case 'note_delete': {
      const q = args.confirm ? '?confirm=true' : '';
      return api('/api/ai/notes/' + encodeURIComponent(args.slug) + q, { method: 'DELETE' });
    }
    case 'note_upload_image': {
      const data = await readFile(args.path);
      const form = new FormData();
      form.append('file', new Blob([data]), basename(args.path));
      return api('/api/ai/upload', { method: 'POST', body: form, raw: true });
    }
    default:
      fail('没有这个工具: ' + name);
  }
}

async function handle(msg) {
  const { id, method, params } = msg || {};
  const isNotification = id === undefined || id === null;
  try {
    let result;
    switch (method) {
      case 'initialize':
        result = {
          protocolVersion: (params && params.protocolVersion) || PROTOCOL,
          capabilities: { tools: {} },
          serverInfo: { name: NAME, version: VERSION },
          instructions: INSTRUCTIONS,
        };
        break;
      case 'ping':
        result = {};
        break;
      case 'tools/list':
        result = { tools: TOOLS };
        break;
      case 'tools/call': {
        const out = await callTool(params && params.name, (params && params.arguments) || {});
        const text = typeof out === 'string' ? out : JSON.stringify(out, null, 2);
        result = { content: [{ type: 'text', text }] };
        break;
      }
      default:
        if (isNotification) return null; // notifications/initialized 之类，不用回
        return { jsonrpc: '2.0', id, error: { code: -32601, message: '不支持的方法: ' + method } };
    }
    if (isNotification) return null;
    return { jsonrpc: '2.0', id, result };
  } catch (e) {
    if (isNotification) return null;
    return { jsonrpc: '2.0', id, error: { code: -32000, message: e.message } };
  }
}

// 逐行读 stdin 上的 JSON-RPC
let buffer = '';
let pending = 0;
let ended = false;
const maybeExit = () => {
  if (ended && pending === 0) process.exit(0);
};

process.stdin.setEncoding('utf8');
process.stdin.on('data', async (chunk) => {
  buffer += chunk;
  let index;
  while ((index = buffer.indexOf('\n')) >= 0) {
    const line = buffer.slice(0, index).trim();
    buffer = buffer.slice(index + 1);
    if (!line) continue;
    let msg;
    try {
      msg = JSON.parse(line);
    } catch (e) {
      log('收到不是 JSON 的一行，忽略');
      continue;
    }
    pending++;
    try {
      const reply = await handle(msg);
      if (reply) process.stdout.write(JSON.stringify(reply) + '\n');
    } finally {
      pending--;
    }
  }
  maybeExit();
});
// stdin 关了不代表活干完了：等手上这次请求回完再退
process.stdin.on('end', () => {
  ended = true;
  maybeExit();
});
log('已启动，站点：' + (BASE || '(未设置 NOTES_BASE)'));
