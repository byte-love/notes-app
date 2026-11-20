// 站内助手：机器人球 + 可拖动/缩放的悬浮窗。会话、key、模型选择都存在本机（按账号分开）。
(() => {
    const meta = (name) => (document.querySelector('meta[name="' + name + '"]') || {}).content || '';
    const user = meta('user-name');
    if (!user) return;

    const KEY = 'agent-key:' + user;
    const CHATS = 'agent-chats:' + user;
    const ACTIVE = 'agent-active:' + user;
    const GEO = 'agent-geo:' + user;
    const MODEL_KEY = 'agent-model:' + user;

    const MODELS = {
        'deepseek-flash': {
            hint: '快、便宜，支持图片 · 输入 $0.15–0.30 / 输出 $0.60–1.20（每百万 tokens）',
            inPrice: 0.30,
            outPrice: 1.20,
        },
        'deepseek-v4-pro': {
            hint: '推理更强，不支持图片 · 输入 $0.66–1.32 / 输出 $1.98–3.96（每百万 tokens）',
            inPrice: 1.32,
            outPrice: 3.96,
        },
    };

    const loadJson = (key) => {
        try {
            return JSON.parse(localStorage.getItem(key) || 'null');
        } catch (e) {
            return null;
        }
    };
    const saveJson = (key, value) => {
        try {
            localStorage.setItem(key, JSON.stringify(value));
            return true;
        } catch (e) {
            return false;
        }
    };
    const readRaw = (key) => {
        try {
            return localStorage.getItem(key);
        } catch (e) {
            return null;
        }
    };
    const writeRaw = (key, value) => {
        try {
            localStorage.setItem(key, value);
        } catch (e) {}
    };

    const uid = () => Date.now().toString(36) + Math.random().toString(36).slice(2, 6);

    /* ---------- 图片仓（IndexedDB，存不下就退回 data URL） ---------- */
    const imgCache = {};
    const idb = (() => {
        let db = null;
        const open = () => new Promise((resolve, reject) => {
            const req = indexedDB.open('agent-img', 1);
            req.onupgradeneeded = () => req.result.createObjectStore('img');
            req.onsuccess = () => {
                db = req.result;
                resolve(db);
            };
            req.onerror = () => reject(req.error);
        });
        return {
            async put(dataUrl) {
                if (!('indexedDB' in window)) throw new Error('no idb');
                const store = await (db ? Promise.resolve(db) : open());
                return new Promise((resolve, reject) => {
                    const tx = store.transaction('img', 'readwrite');
                    const id = uid();
                    tx.objectStore('img').put(dataUrl, id);
                    tx.oncomplete = () => resolve(id);
                    tx.onerror = () => reject(tx.error);
                });
            },
            async get(id) {
                if (!('indexedDB' in window)) return null;
                const store = await (db ? Promise.resolve(db) : open());
                return new Promise((resolve) => {
                    const tx = store.transaction('img', 'readonly');
                    const req = tx.objectStore('img').get(id);
                    req.onsuccess = () => resolve(req.result || null);
                    req.onerror = () => resolve(null);
                });
            },
        };
    })();

    const putImage = async (dataUrl) => {
        try {
            const id = await idb.put(dataUrl);
            imgCache[id] = dataUrl;
            return { type: 'image', id };
        } catch (e) {
            return { type: 'image', url: dataUrl };
        }
    };

    const imageUrl = (block) => block.url || imgCache[block.id] || '';

    /* ---------- 会话数据 ---------- */
    const textOf = (content) => {
        if (!content) return '';
        if (typeof content === 'string') return content;
        if (Array.isArray(content)) {
            return content.filter((b) => b.type === 'text').map((b) => b.text).join('\n');
        }
        return '';
    };

    const newChat = () => ({ id: uid(), title: '', messages: [], pending: [], usage: null, pinned: false });

    let chats = loadJson(CHATS);
    if (!Array.isArray(chats) || !chats.length) {
        chats = [];
        const old = loadJson('agent-chat:' + user);
        if (Array.isArray(old) && old.length) {
            const first = old.find((m) => m.role === 'user');
            chats.push({
                id: uid(),
                title: first ? textOf(first.content).slice(0, 24) : '',
                messages: old,
                pending: loadJson('agent-pending:' + user) || [],
                usage: null,
                pinned: false,
            });
            try {
                localStorage.removeItem('agent-chat:' + user);
                localStorage.removeItem('agent-pending:' + user);
            } catch (e) {}
        }
    }
    let activeId = readRaw(ACTIVE) || '';
    let chat = chats.find((c) => c.id === activeId);
    if (!chat) {
        chat = chats[0] || newChat();
        if (!chats.length) chats.push(chat);
        activeId = chat.id;
    }

    const hydrate = async (c) => {
        for (const m of c.messages) {
            if (!Array.isArray(m.content)) continue;
            for (const b of m.content) {
                if (b.type === 'image' && b.id && !imgCache[b.id]) {
                    const url = await idb.get(b.id);
                    if (url) imgCache[b.id] = url;
                }
            }
        }
    };

    const saveChats = () => {
        chat.messages = chat.messages.slice(-40);
        chat.pending = chat.pending.slice(-12);
        chat.at = Date.now();
        saveJson(CHATS, chats);
        writeRaw(ACTIVE, chat.id);
    };

    const apiContent = (content) => {
        if (!Array.isArray(content)) return content;
        const blocks = [];
        content.forEach((b) => {
            if (b.type === 'text') {
                blocks.push({ type: 'text', text: b.text });
            } else if (b.type === 'file') {
                blocks.push({ type: 'text', text: `文件 ${b.name}：\n${b.text}` });
            } else if (b.type === 'image') {
                const url = imageUrl(b);
                if (url) blocks.push({ type: 'image_url', image_url: { url } });
            }
        });
        if (blocks.every((b) => b.type === 'text')) {
            return blocks.map((b) => b.text).join('\n\n');
        }
        if (blocks.length === 1) return blocks[0];
        return blocks;
    };

    /* ---------- 机器人球 ---------- */
    const fab = document.createElement('button');
    fab.type = 'button';
    fab.className = 'ai-fab';
    fab.setAttribute('aria-label', 'AI 助手');
    fab.innerHTML = `
        <svg viewBox="0 0 64 64" aria-hidden="true">
            <circle cx="32" cy="7" r="3.2" class="ai-antenna"></circle>
            <path d="M32 10.5v4.5" class="ai-line"></path>
            <rect x="10" y="15" width="44" height="34" rx="12" class="ai-head"></rect>
            <rect x="17" y="23" width="30" height="18" rx="8" class="ai-face"></rect>
            <circle cx="25" cy="31" r="2.7" class="ai-eye"></circle>
            <circle cx="39" cy="31" r="2.7" class="ai-eye"></circle>
            <path d="M28 39.5h8" class="ai-mouth"></path>
            <rect x="5.5" y="26" width="3.5" height="11" rx="1.75" class="ai-ear"></rect>
            <rect x="55" y="26" width="3.5" height="11" rx="1.75" class="ai-ear"></rect>
        </svg>`;
    document.body.appendChild(fab);

    const svg = (d) => `<svg viewBox="0 0 24 24" aria-hidden="true"><path d="${d}"/></svg>`;
    const iconMenu = svg('M4 6h16M4 12h16M4 18h16');
    const iconPlus = svg('M12 5v14M5 12h14');
    const iconPin = svg('M9 4h6l-1 6 4 4H6l4-4zM12 14v6');

    const panel = document.createElement('section');
    panel.className = 'ai-panel';
    panel.hidden = true;
    panel.innerHTML = `
        <div class="ai-handle" data-dir="nw"></div>
        <div class="ai-handle" data-dir="n"></div>
        <div class="ai-handle" data-dir="ne"></div>
        <div class="ai-handle" data-dir="e"></div>
        <div class="ai-handle" data-dir="se"></div>
        <div class="ai-handle" data-dir="s"></div>
        <div class="ai-handle" data-dir="sw"></div>
        <div class="ai-handle" data-dir="w"></div>
        <div class="ai-bar">
            <button type="button" class="ai-icon ai-side-toggle" aria-label="会话列表">${iconMenu}</button>
            <span class="ai-title">AI 助手</span>
            <span class="ai-ctx" hidden></span>
            <button type="button" class="ai-x" aria-label="关闭">×</button>
        </div>
        <div class="ai-shell">
            <aside class="ai-side">
                <button type="button" class="ai-new">${iconPlus}<span>新对话</span></button>
                <input class="ai-search" type="search" placeholder="搜索会话">
                <div class="ai-chatlist"></div>
            </aside>
            <div class="ai-main">
                <div class="ai-msgs"></div>
                <button type="button" class="ai-down" aria-label="回到底部">↓</button>
                <div class="ai-keybox">
                    <label>API Key</label>
                    <input type="password" autocomplete="off" spellcheck="false">
                    <button type="button" class="btn-quiet ai-keysave">保存</button>
                </div>
                <div class="ai-attach" hidden></div>
                <div class="ai-foot">
                    <div class="ai-foot-row">
                        <select class="ai-model"></select>
                        <span class="ai-usage"></span>
                    </div>
                    <span class="ai-hint"></span>
                </div>
                <div class="ai-compose">
                    <textarea rows="3"></textarea>
                    <button type="button" class="btn-quiet ai-file" aria-label="上传文件">${svg('M21 12.5 12 21a5 5 0 0 1-7-7l8.5-8.5a3.5 3.5 0 0 1 5 5L10 19a2 2 0 0 1-3-3l8-8')}</button>
                    <button type="button" class="btn-quiet ai-send">发送</button>
                </div>
                <input class="ai-file-input" type="file" accept=".md,.txt,.pdf" hidden>
            </div>
        </div>`;
    document.body.appendChild(panel);

    const side = panel.querySelector('.ai-side');
    const chatList = panel.querySelector('.ai-chatlist');
    const search = panel.querySelector('.ai-search');
    const msgs = panel.querySelector('.ai-msgs');
    const input = panel.querySelector('textarea');
    const sendBtn = panel.querySelector('.ai-send');
    const closeBtn = panel.querySelector('.ai-x');
    const bar = panel.querySelector('.ai-bar');
    const keyBox = panel.querySelector('.ai-keybox');
    const keyInput = keyBox.querySelector('input');
    const ctxEl = panel.querySelector('.ai-ctx');
    const usageEl = panel.querySelector('.ai-usage');
    const modelSel = panel.querySelector('.ai-model');
    const hintEl = panel.querySelector('.ai-hint');
    const attach = panel.querySelector('.ai-attach');
    const fileBtn = panel.querySelector('.ai-file');
    const fileInput = panel.querySelector('.ai-file-input');
    const downBtn = panel.querySelector('.ai-down');

    let locked = null;
    let sending = false;
    let sideOpen = !window.matchMedia('(max-width: 560px)').matches;
    let files = [];
    let pendingImages = [];
    let ctl = null;
    let reqId = 0;
    let lastPick = '';
    let lastPickAt = 0;

    // 点按钮会把选区清掉，所以先在 selectionchange 里记一份
    document.addEventListener('selectionchange', () => {
        const sel = window.getSelection();
        if (!sel || sel.isCollapsed) return;
        const text = sel.toString().trim();
        if (!text || text.length > 800) return;
        const node = sel.anchorNode;
        const el = node && (node.nodeType === 1 ? node : node.parentElement);
        if (!el || !el.closest('.doc-wrap')) return;
        lastPick = text;
        lastPickAt = Date.now();
    });

    const cancelRequest = () => {
        if (ctl) ctl.abort();
        reqId++;
        setSending(false);
    };

    // 正文页锁定当前笔记
    const holder = document.querySelector('[data-note-slug]');
    const fromPath = () => {
        const m = location.pathname.match(/^\/app\/(notes|bagu|algo|interview|projects)\/([a-z0-9-]+)$/);
        if (!m) return null;
        return { slug: m[2], kind: m[1], title: document.title.replace(/\s*·\s*byte-love$/, '') };
    };
    locked = holder ? {
        slug: holder.dataset.noteSlug,
        kind: holder.dataset.noteKind,
        title: holder.dataset.noteTitle || holder.dataset.noteSlug,
    } : fromPath();

    /* ---------- 窗口拖动 / 缩放 ---------- */
    const narrow = window.matchMedia('(max-width: 560px)');

    const clampGeo = (g) => {
        const w = Math.min(g.w, window.innerWidth - 16);
        const h = Math.min(g.h, window.innerHeight - 16);
        const x = Math.min(Math.max(8, g.x), Math.max(8, window.innerWidth - w - 8));
        const y = Math.min(Math.max(8, g.y), Math.max(8, window.innerHeight - h - 8));
        return { x, y, w, h };
    };

    const geo = () => {
        const r = panel.getBoundingClientRect();
        return { x: r.left, y: r.top, w: r.width, h: r.height };
    };

    const applyGeo = () => {
        if (narrow.matches) {
            panel.style.left = '';
            panel.style.top = '';
            panel.style.width = '';
            panel.style.height = '';
            panel.style.right = '';
            panel.style.bottom = '';
            return;
        }
        const g = loadJson(GEO);
        if (!g) {
            panel.style.left = '';
            panel.style.top = '';
            panel.style.width = '';
            panel.style.height = '';
            panel.style.right = '';
            panel.style.bottom = '';
            return;
        }
        const c = clampGeo(g);
        panel.style.left = c.x + 'px';
        panel.style.top = c.y + 'px';
        panel.style.width = c.w + 'px';
        panel.style.height = c.h + 'px';
        panel.style.right = 'auto';
        panel.style.bottom = 'auto';
    };

    const detach = () => {
        const r = panel.getBoundingClientRect();
        panel.style.left = r.left + 'px';
        panel.style.top = r.top + 'px';
        panel.style.width = r.width + 'px';
        panel.style.height = r.height + 'px';
        panel.style.right = 'auto';
        panel.style.bottom = 'auto';
        return r;
    };

    const startDrag = (e) => {
        if (narrow.matches || e.button !== 0 || e.target.closest('button, select, input')) return;
        e.preventDefault();
        const r = detach();
        const start = { x: e.clientX, y: e.clientY, left: r.left, top: r.top };
        bar.setPointerCapture(e.pointerId);
        const move = (ev) => {
            const x = Math.min(Math.max(0, start.left + ev.clientX - start.x), window.innerWidth - 80);
            const y = Math.min(Math.max(0, start.top + ev.clientY - start.y), window.innerHeight - 40);
            panel.style.left = x + 'px';
            panel.style.top = y + 'px';
        };
        const up = () => {
            bar.removeEventListener('pointermove', move);
            bar.removeEventListener('pointerup', up);
            const g = geo();
            saveJson(GEO, { x: g.x, y: g.y, w: g.w, h: g.h });
        };
        bar.addEventListener('pointermove', move);
        bar.addEventListener('pointerup', up);
    };

    const startResize = (e, dir) => {
        if (narrow.matches || e.button !== 0) return;
        e.preventDefault();
        e.stopPropagation();
        const r = detach();
        const start = { x: e.clientX, y: e.clientY, left: r.left, top: r.top, w: r.width, h: r.height };
        const minW = 480;
        const minH = 320;
        e.target.setPointerCapture(e.pointerId);
        const move = (ev) => {
            const dx = ev.clientX - start.x;
            const dy = ev.clientY - start.y;
            let left = start.left;
            let top = start.top;
            let w = start.w;
            let h = start.h;
            if (dir.includes('e')) w = Math.max(minW, start.w + dx);
            if (dir.includes('s')) h = Math.max(minH, start.h + dy);
            if (dir.includes('w')) {
                w = Math.max(minW, start.w - dx);
                left = start.left + (start.w - w);
            }
            if (dir.includes('n')) {
                h = Math.max(minH, start.h - dy);
                top = start.top + (start.h - h);
            }
            left = Math.max(0, left);
            top = Math.max(0, top);
            w = Math.min(w, window.innerWidth - left);
            h = Math.min(h, window.innerHeight - top);
            panel.style.left = left + 'px';
            panel.style.top = top + 'px';
            panel.style.width = w + 'px';
            panel.style.height = h + 'px';
        };
        const up = () => {
            e.target.removeEventListener('pointermove', move);
            e.target.removeEventListener('pointerup', up);
            const g = geo();
            saveJson(GEO, { x: g.x, y: g.y, w: g.w, h: g.h });
        };
        e.target.addEventListener('pointermove', move);
        e.target.addEventListener('pointerup', up);
    };

    bar.addEventListener('pointerdown', startDrag);
    panel.querySelectorAll('.ai-handle').forEach((h) => {
        h.addEventListener('pointerdown', (e) => startResize(e, h.dataset.dir));
    });

    /* ---------- 侧栏 / 会话列表 ---------- */
    const applySide = () => {
        if (narrow.matches) {
            panel.classList.toggle('side-open', sideOpen);
            panel.classList.remove('side-collapsed');
        } else {
            panel.classList.remove('side-open');
            panel.classList.toggle('side-collapsed', !sideOpen);
        }
    };

    const fmtTokens = (n) => {
        if (n >= 1_000_000) return (n / 1_000_000).toFixed(1).replace('.0', '') + 'M';
        if (n >= 1000) return (n / 1000).toFixed(1).replace('.0', '') + 'k';
        return String(n);
    };

    const fmtCost = (model, usage) => {
        const m = MODELS[model] || MODELS['deepseek-flash'];
        const cost = ((usage.prompt || 0) * m.inPrice + (usage.completion || 0) * m.outPrice) / 1_000_000;
        return '$' + cost.toFixed(4);
    };

    const renderUsage = () => {
        const u = chat.usage;
        if (!u) {
            usageEl.textContent = '';
            return;
        }
        const limit = u.limit || 1_000_000;
        const ratio = u.prompt / limit;
        const model = u.model || modelSel.value;
        usageEl.textContent = `上下文 ${fmtTokens(u.prompt)} / ${fmtTokens(limit)} · ≈${fmtCost(model, u)}`;
        usageEl.classList.toggle('warn', ratio >= 0.7);
        usageEl.classList.toggle('danger', ratio >= 0.9);
    };

    const sortedChats = () => chats.slice().sort((a, b) => {
        if (!!b.pinned !== !!a.pinned) return b.pinned ? 1 : -1;
        return (b.at || 0) - (a.at || 0);
    });

    const renderChatList = () => {
        const q = search.value.trim().toLowerCase();
        chatList.replaceChildren();
        sortedChats().forEach((c) => {
            const title = c.title || '新对话';
            if (q && !title.toLowerCase().includes(q)) return;
            const row = document.createElement('div');
            row.className = 'ai-chat-row' + (c.id === chat.id ? ' on' : '');
            const pick = document.createElement('button');
            pick.type = 'button';
            pick.className = 'ai-chat-pick';
            pick.textContent = title;
            pick.title = title;
            pick.addEventListener('click', async () => {
                if (c.id === chat.id) {
                    if (narrow.matches) {
                        sideOpen = false;
                        applySide();
                    }
                    return;
                }
                cancelRequest();
                chat = c;
                activeId = c.id;
                saveChats();
                await hydrate(chat);
                if (narrow.matches) {
                    sideOpen = false;
                    applySide();
                }
                renderAll();
            });
            pick.addEventListener('dblclick', (e) => {
                e.preventDefault();
                e.stopPropagation();
                startRename(row, c);
            });

            const pin = document.createElement('button');
            pin.type = 'button';
            pin.className = 'ai-chat-pin' + (c.pinned ? ' on' : '');
            pin.setAttribute('aria-label', '置顶');
            pin.innerHTML = iconPin;
            pin.addEventListener('click', (e) => {
                e.stopPropagation();
                c.pinned = !c.pinned;
                saveChats();
                renderChatList();
            });

            const del = document.createElement('button');
            del.type = 'button';
            del.className = 'ai-chat-del';
            del.setAttribute('aria-label', '删除对话');
            del.textContent = '×';
            del.addEventListener('click', (e) => {
                e.stopPropagation();
                chats = chats.filter((x) => x.id !== c.id);
                if (!chats.length) chats.push(newChat());
                if (chat.id === c.id) {
                    cancelRequest();
                    chat = chats.find((x) => x.id !== c.id) || chats[0];
                    activeId = chat.id;
                }
                saveChats();
                renderChatList();
                renderAll();
            });

            row.append(pick, pin, del);
            chatList.appendChild(row);
        });
    };

    const startRename = (row, c) => {
        const pick = row.querySelector('.ai-chat-pick');
        const edit = document.createElement('input');
        edit.className = 'ai-chat-rename';
        edit.value = c.title;
        row.replaceChild(edit, pick);
        edit.focus();
        edit.select();
        const commit = () => {
            const v = edit.value.trim();
            if (v) c.title = v.slice(0, 40);
            saveChats();
            renderChatList();
        };
        edit.addEventListener('keydown', (e) => {
            if (e.key === 'Enter') commit();
            if (e.key === 'Escape') renderChatList();
        });
        edit.addEventListener('blur', commit);
    };

    /* ---------- 消息渲染 ---------- */
    const greetText = () => (locked
        ? '我是站内助手：可以解释这篇里的句子、把内容整理成新笔记、改这篇，或者搜以前的笔记。直接说就行。'
        : '我是站内助手：可以解释笔记里的句子、整理成新笔记、搜以前的笔记。直接说就行。');

    const append = (cls, text) => {
        const el = document.createElement('div');
        el.className = 'ai-msg ' + cls;
        el.textContent = text;
        msgs.appendChild(el);
        msgs.scrollTop = msgs.scrollHeight;
        return el;
    };

    const greeting = () => {
        if (chat.messages.length) return;
        const el = document.createElement('div');
        el.className = 'ai-msg ai-greeting';
        el.textContent = greetText();
        msgs.appendChild(el);
    };

    const renderUserContent = (content) => {
        const text = textOf(content);
        if (text) append('user', text);
        if (!Array.isArray(content)) return;
        const fs = content.filter((b) => b.type === 'file');
        if (fs.length) {
            const box = document.createElement('div');
            box.className = 'ai-msg user ai-files';
            fs.forEach((b) => {
                const chip = document.createElement('span');
                chip.className = 'ai-file-chip';
                const name = document.createElement('b');
                name.textContent = b.name;
                chip.appendChild(name);
                box.appendChild(chip);
            });
            msgs.appendChild(box);
        }
        const pics = content.filter((b) => b.type === 'image' && imageUrl(b));
        if (!pics.length) return;
        const box = document.createElement('div');
        box.className = 'ai-msg user ai-pics';
        pics.forEach((b) => {
            const a = document.createElement('a');
            a.href = imageUrl(b);
            a.target = '_blank';
            a.rel = 'noopener';
            const img = document.createElement('img');
            img.src = imageUrl(b);
            img.alt = '';
            a.appendChild(img);
            box.appendChild(a);
        });
        msgs.appendChild(box);
    };

    const renderProposal = (p) => {
        const card = document.createElement('div');
        card.className = 'ai-proposal';
        const title = document.createElement('div');
        title.className = 'ai-proposal-title';
        title.textContent = p.title || p.slug || '';
        const body = document.createElement('div');
        body.className = 'ai-proposal-body';
        const text = p.body || p.oldBody || '';
        body.textContent = text.length > 600 ? text.slice(0, 600) : text;
        const actions = document.createElement('div');
        actions.className = 'ai-proposal-actions';
        const write = document.createElement('button');
        write.type = 'button';
        write.className = 'btn-quiet';
        write.textContent = '写入';
        const ignore = document.createElement('button');
        ignore.type = 'button';
        ignore.className = 'btn-quiet';
        ignore.textContent = '忽略';

        write.addEventListener('click', async () => {
            write.disabled = true;
            try {
                const res = await post('/app/api/agent/apply', { id: p.id });
                const data = await res.json().catch(() => null);
                if (!res.ok || !data || !data.ok) {
                    append('error', (data && data.error) || '写入失败');
                    write.disabled = false;
                    return;
                }
                card.replaceChildren();
                const link = document.createElement('a');
                link.href = data.url;
                link.textContent = p.title || data.slug;
                card.appendChild(link);
                chat.pending = chat.pending.filter((x) => x.id !== p.id);
                saveChats();
            } catch (e) {
                append('error', '写入失败');
                write.disabled = false;
            }
        });
        ignore.addEventListener('click', () => {
            card.remove();
            chat.pending = chat.pending.filter((x) => x.id !== p.id);
            saveChats();
        });

        actions.append(write, ignore);
        card.append(title, body, actions);
        msgs.appendChild(card);
    };

    const renderRegen = () => {
        const last = chat.messages[chat.messages.length - 1];
        if (!last || sending) return;
        if (last.role !== 'assistant' && last.role !== 'user') return;
        const btn = document.createElement('button');
        btn.type = 'button';
        btn.className = 'ai-regen';
        btn.textContent = '重新生成';
        btn.addEventListener('click', regen);
        msgs.appendChild(btn);
    };

    const renderAll = () => {
        msgs.replaceChildren();
        chat.messages.forEach((m) => {
            if (m.role === 'user') {
                renderUserContent(m.content);
            } else if (m.role === 'assistant') {
                append('assistant', m.content || '');
            }
        });
        chat.pending.forEach(renderProposal);
        greeting();
        renderRegen();
        renderUsage();
        renderChatList();
        renderAttach();
        msgs.scrollTop = msgs.scrollHeight;
        updateDown();
    };

    /* ---------- 附件 ---------- */
    const renderAttach = () => {
        attach.replaceChildren();
        const items = [];
        pendingImages.forEach((b) => items.push({ kind: 'image', block: b }));
        files.forEach((f) => items.push({ kind: 'file', file: f }));
        attach.hidden = !items.length;
        items.forEach((item) => {
            const box = document.createElement('div');
            box.className = 'ai-thumb' + (item.kind === 'file' ? ' ai-thumb-file' : '');
            if (item.kind === 'image') {
                const img = document.createElement('img');
                img.src = imageUrl(item.block);
                img.alt = '';
                box.appendChild(img);
            } else {
                const name = document.createElement('span');
                name.className = 'ai-file-name';
                name.textContent = item.file.name;
                box.appendChild(name);
            }
            const del = document.createElement('button');
            del.type = 'button';
            del.textContent = '×';
            del.setAttribute('aria-label', '移除');
            del.addEventListener('click', async () => {
                if (item.kind === 'image') {
                    pendingImages = pendingImages.filter((x) => x !== item.block);
                } else {
                    files = files.filter((x) => x !== item.file);
                }
                renderAttach();
            });
            box.appendChild(del);
            attach.appendChild(box);
        });
    };

    const shrinkImage = (file) => new Promise((resolve) => {
        const img = new Image();
        const src = URL.createObjectURL(file);
        img.onload = () => {
            const max = 1280;
            const scale = Math.min(1, max / Math.max(img.width, img.height));
            const canvas = document.createElement('canvas');
            canvas.width = Math.max(1, Math.round(img.width * scale));
            canvas.height = Math.max(1, Math.round(img.height * scale));
            const g = canvas.getContext('2d');
            g.fillStyle = '#fff';
            g.fillRect(0, 0, canvas.width, canvas.height);
            g.drawImage(img, 0, 0, canvas.width, canvas.height);
            URL.revokeObjectURL(src);
            resolve(canvas.toDataURL('image/jpeg', 0.82));
        };
        img.onerror = () => {
            URL.revokeObjectURL(src);
            resolve(null);
        };
        img.src = src;
    });

    const pickImages = async (list) => {
        for (const file of list.slice(0, 6 - pendingImages.length)) {
            if (!file.type.startsWith('image/')) continue;
            const url = await shrinkImage(file);
            if (url) pendingImages.push(await putImage(url));
        }
        renderAttach();
    };

    input.addEventListener('paste', (e) => {
        const items = e.clipboardData ? Array.from(e.clipboardData.items) : [];
        const list = items
            .filter((i) => i.kind === 'file' && i.type.startsWith('image/'))
            .map((i) => i.getAsFile())
            .filter(Boolean);
        if (!list.length) return;
        e.preventDefault();
        pickImages(list);
    });

    const uploadFile = async (file) => {
        const body = new FormData();
        body.append('file', file);
        const send = () => fetch('/app/api/agent/extract', {
            method: 'POST',
            credentials: 'same-origin',
            headers: { 'X-CSRF-TOKEN': App.csrf() },
            body,
        });
        let res = await send();
        if (res.status === 403) {
            await App.refreshCsrf();
            res = await send();
        }
        if (!res.ok) {
            const data = await res.json().catch(() => null);
            append('error', (data && data.error) || '读取文件失败');
            return;
        }
        const data = await res.json();
        files.push({ name: file.name, text: data.text || '' });
        renderAttach();
    };

    fileBtn.addEventListener('click', () => fileInput.click());
    fileInput.addEventListener('change', () => {
        const f = fileInput.files && fileInput.files[0];
        if (f) uploadFile(f);
        fileInput.value = '';
    });

    /* ---------- 发送 / 停止 / 重试 ---------- */
    const post = async (url, body, headers) => {
        const call = () => fetch(url, {
            method: 'POST',
            credentials: 'same-origin',
            headers: Object.assign(
                { 'content-type': 'application/json', 'X-CSRF-TOKEN': App.csrf() },
                headers || {},
            ),
            body: JSON.stringify(body),
        });
        let res = await call();
        if (res.status === 403) {
            await App.refreshCsrf();
            res = await call();
        }
        return res;
    };

    const thinking = () => {
        const el = document.createElement('div');
        el.className = 'ai-msg ai-thinking';
        el.innerHTML = '<span></span><span></span><span></span>';
        msgs.appendChild(el);
        msgs.scrollTop = msgs.scrollHeight;
        return el;
    };

    const setSending = (v) => {
        sending = v;
        sendBtn.textContent = v ? '停止' : '发送';
        sendBtn.classList.toggle('stopping', v);
        if (v) downBtn.hidden = true;
    };

    const runRequest = async () => {
        const target = chat;
        const my = ++reqId;
        await hydrate(target);
        msgs.querySelectorAll('.ai-regen').forEach((el) => el.remove());
        setSending(true);
        const think = thinking();
        ctl = new AbortController();
        try {
            const messages = target.messages.slice(-20).map((m) => ({ role: m.role, content: apiContent(m.content) }));
            const res = await fetch('/app/api/agent/chat', {
                method: 'POST',
                credentials: 'same-origin',
                headers: {
                    'content-type': 'application/json',
                    'X-CSRF-TOKEN': App.csrf(),
                    'X-Agent-Key': readRaw(KEY) || '',
                },
                body: JSON.stringify({
                    messages,
                    contextSlug: locked ? locked.slug : null,
                    model: modelSel.value,
                }),
                signal: ctl.signal,
            });
            const data = await res.json().catch(() => null);
            if (my !== reqId || chat !== target) return;
            if (!res.ok) {
                append('error', (data && data.error) || '请求失败');
                return;
            }
            const reply = (data && data.reply) || '';
            target.messages.push({ role: 'assistant', content: reply });
            if (reply) append('assistant', reply);
            ((data && data.proposals) || []).forEach((p) => {
                if (!target.pending.some((x) => x.id === p.id)) target.pending.push(p);
                renderProposal(p);
            });
            if (data && data.usage && data.usage.prompt_tokens) {
                target.usage = {
                    prompt: data.usage.prompt_tokens,
                    completion: data.usage.completion_tokens || 0,
                    total: data.usage.total_tokens,
                    limit: data.contextLimit || 1_000_000,
                    model: data.model,
                };
            }
            renderUsage();
            saveChats();
        } catch (e) {
            if (e.name !== 'AbortError' && my === reqId) append('error', '请求失败');
        } finally {
            if (my === reqId) {
                think.remove();
                ctl = null;
                setSending(false);
                renderRegen();
                updateDown();
                msgs.scrollTop = msgs.scrollHeight;
            }
        }
    };

    const send = async () => {
        if (sending) return;
        if (!hasKey()) {
            renderKey();
            keyInput.focus();
            return;
        }
        const text = input.value.trim();
        if (!text && !pendingImages.length && !files.length) return;
        const blocks = [];
        files.forEach((f) => blocks.push({ type: 'file', name: f.name, text: f.text }));
        if (text) blocks.push({ type: 'text', text });
        pendingImages.forEach((b) => blocks.push(b));
        const content = blocks.length === 1 && blocks[0].type === 'text' ? blocks[0].text : blocks;

        input.value = '';
        files = [];
        pendingImages = [];
        renderAttach();
        const greet = msgs.querySelector('.ai-greeting');
        if (greet) greet.remove();
        msgs.querySelectorAll('.ai-regen').forEach((el) => el.remove());
        chat.messages.push({ role: 'user', content });
        if (!chat.title) {
            chat.title = text ? text.slice(0, 24) : (blocks.find((b) => b.type === 'file') || {}).name || '图片';
        }
        renderUserContent(content);
        saveChats();
        await runRequest();
    };

    const regen = async () => {
        if (sending) return;
        while (chat.messages.length && chat.messages[chat.messages.length - 1].role !== 'user') {
            chat.messages.pop();
        }
        if (!chat.messages.length) return;
        renderAll();
        await runRequest();
    };

    sendBtn.addEventListener('click', () => {
        if (sending && ctl) {
            ctl.abort();
            return;
        }
        send();
    });

    input.addEventListener('keydown', (e) => {
        if (e.key === 'Enter' && !e.shiftKey && !e.isComposing) {
            e.preventDefault();
            if (!sending) send();
        }
    });

    /* ---------- 模型 ---------- */
    Object.keys(MODELS).forEach((m) => {
        const opt = document.createElement('option');
        opt.value = m;
        opt.textContent = m;
        modelSel.appendChild(opt);
    });

    const applyModel = (m) => {
        modelSel.value = MODELS[m] ? m : 'deepseek-flash';
        hintEl.textContent = MODELS[modelSel.value].hint;
        writeRaw(MODEL_KEY, modelSel.value);
    };

    modelSel.addEventListener('change', () => applyModel(modelSel.value));

    /* ---------- key / 上下文 / 开关 ---------- */
    const hasKey = () => !!readRaw(KEY);

    const renderKey = () => {
        const ok = hasKey();
        keyBox.hidden = ok;
        panel.classList.toggle('need-key', !ok);
        if (!ok) {
            panel.style.height = 'auto';
        } else {
            panel.style.height = '';
            applyGeo();
        }
    };

    const renderCtx = () => {
        ctxEl.hidden = !locked;
        if (!locked) return;
        ctxEl.textContent = locked.title;
        const x = document.createElement('button');
        x.type = 'button';
        x.className = 'ai-ctx-x';
        x.setAttribute('aria-label', '取消上下文');
        x.textContent = '×';
        x.addEventListener('click', (e) => {
            e.stopPropagation();
            locked = null;
            renderCtx();
        });
        ctxEl.appendChild(x);
    };

    const updateDown = () => {
        const gap = msgs.scrollHeight - msgs.scrollTop - msgs.clientHeight;
        downBtn.hidden = gap < 120 || sending;
    };

    msgs.addEventListener('scroll', updateDown);
    downBtn.addEventListener('click', () => {
        msgs.scrollTo({ top: msgs.scrollHeight, behavior: 'smooth' });
    });

    const open = async () => {
        panel.hidden = false;
        applyGeo();
        applySide();
        renderKey();
        await hydrate(chat);
        renderAll();
        if (!hasKey()) {
            keyInput.focus();
        } else if (!input.value) {
            if (lastPick && Date.now() - lastPickAt < 15000) {
                input.value = lastPick.split('\n').map((l) => '> ' + l).join('\n') + '\n\n';
                lastPick = '';
            }
            input.focus();
        }
        msgs.scrollTop = msgs.scrollHeight;
    };

    const close = () => {
        panel.hidden = true;
    };

    keyBox.querySelector('.ai-keysave').addEventListener('click', () => {
        const value = keyInput.value.trim();
        if (!value) return;
        writeRaw(KEY, value);
        keyInput.value = '';
        renderKey();
        input.focus();
    });

    panel.querySelector('.ai-side-toggle').addEventListener('click', (e) => {
        e.stopPropagation();
        sideOpen = !sideOpen;
        applySide();
    });

    panel.querySelector('.ai-new').addEventListener('click', () => {
        if (!chat.messages.length) return;
        cancelRequest();
        chat = newChat();
        chats.push(chat);
        activeId = chat.id;
        saveChats();
        if (narrow.matches) {
            sideOpen = false;
            applySide();
        }
        renderAll();
        input.focus();
    });

    search.addEventListener('input', renderChatList);
    closeBtn.addEventListener('click', close);

    applyModel(readRaw(MODEL_KEY) || 'deepseek-flash');
    renderCtx();
    applySide();
    hydrate(chat).then(renderAll);
    narrow.addEventListener('change', () => {
        sideOpen = !narrow.matches;
        applySide();
        applyGeo();
    });
    window.addEventListener('resize', () => {
        if (!panel.hidden && !narrow.matches) {
            const c = clampGeo(geo());
            panel.style.left = c.x + 'px';
            panel.style.top = c.y + 'px';
            panel.style.width = c.w + 'px';
            panel.style.height = c.h + 'px';
        }
    });

    if (window.Float) {
        window.Float.attach(fab, 'assistant', open);
    }
    fab.addEventListener('click', () => {
        if (panel.hidden) open();
        else close();
    });
})();
