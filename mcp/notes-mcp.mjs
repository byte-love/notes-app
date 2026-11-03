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
const VERSION = '1.0.0';
const PROTOCOL = '2025-06-18';

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
      '必须把这几篇念给用户听，问他是「新建」还是「去改那一篇」，不要自己决定。' +
      'visibility 必填：先问用户这篇给什么权限 —— PUBLIC 任何用户可见（会进技术广场）；' +
      'MEMBER 登录可见（只有用户把链接发给别人、对方注册登录后才能看，不进技术广场）；PRIVATE 仅自己。' +
      '用户已经说过了就直接用那个值，不要再问一遍。' +
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
            '必填。PUBLIC 任何用户可见（进技术广场）/ MEMBER 登录可见（不进广场，只有拿到链接的人登录后能看）/ PRIVATE 仅自己。' +
            '用户没说就先问，说了就别再问',
          enum: ['PUBLIC', 'MEMBER', 'PRIVATE'],
        },
        kind: { type: 'string', description: '内容类型，默认 note', enum: ['note', 'bagu', 'algo', 'project'] },
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
