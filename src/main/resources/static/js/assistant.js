// 站内助手：右下角机器人球 + 可拖动、可缩放的悬浮窗。
// key、会话列表、聊天记录都存在本机（按账号分开）。
(() => {
    const meta = (name) => (document.querySelector('meta[name="' + name + '"]') || {}).content || '';
    const user = meta('user-name');
    if (!user) return;

    const KEY = 'agent-key:' + user;
    const CHATS = 'agent-chats:' + user;
    const ACTIVE = 'agent-active:' + user;
    const GEO = 'agent-geo:' + user;

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
    const readKey = () => {
        try {
            return localStorage.getItem(KEY) || '';
        } catch (e) {
            return '';
        }
    };

    const holder = document.querySelector('[data-note-slug]');
    const fromPath = () => {
        const m = location.pathname.match(/^\/app\/(notes|bagu|algo|interview|projects)\/([a-z0-9-]+)$/);
        if (!m) return null;
        return {
            slug: m[2],
            kind: m[1],
            title: document.title.replace(/\s*·\s*byte-love$/, ''),
        };
    };
    const context = holder ? {
        slug: holder.dataset.noteSlug,
        kind: holder.dataset.noteKind,
        title: holder.dataset.noteTitle || holder.dataset.noteSlug,
    } : fromPath();

    const uid = () => Date.now().toString(36) + Math.random().toString(36).slice(2, 6);

    const newChat = () => ({ id: uid(), title: '', messages: [], pending: [], usage: null });

    let chats = loadJson(CHATS);
    if (!Array.isArray(chats) || !chats.length) {
        chats = [];
        const oldMessages = loadJson('agent-chat:' + user);
        if (Array.isArray(oldMessages) && oldMessages.length) {
            const firstText = oldMessages.find((m) => m.role === 'user');
            chats.push({
                id: uid(),
                title: firstText ? textOf(firstText.content).slice(0, 24) : '',
                messages: oldMessages,
                pending: loadJson('agent-pending:' + user) || [],
                usage: null,
            });
            try {
                localStorage.removeItem('agent-chat:' + user);
                localStorage.removeItem('agent-pending:' + user);
            } catch (e) {}
        }
    }
    let activeId = '';
    try {
        activeId = localStorage.getItem(ACTIVE) || '';
    } catch (e) {}
    let chat = chats.find((c) => c.id === activeId);
    if (!chat) {
        chat = chats[0] || newChat();
        if (!chats.length) chats.push(chat);
        activeId = chat.id;
    }

    function textOf(content) {
        if (!content) return '';
        if (typeof content === 'string') return content;
        if (Array.isArray(content)) {
            return content.filter((b) => b.type === 'text').map((b) => b.text).join('\n');
        }
        return '';
    }

    const imagesOf = (content) => (Array.isArray(content)
        ? content.filter((b) => b.type === 'image_url').map((b) => b.image_url.url)
        : []);

    const saveChats = () => {
        chat.messages = chat.messages.slice(-40);
        chat.pending = chat.pending.slice(-12);
        chat.at = Date.now();
        const write = () => saveJson(CHATS, chats);
        if (!write()) {
            // 图多到存不下就丢掉旧消息里的图片，文字保留
            chats.forEach((c) => c.messages.forEach((m) => {
                if (Array.isArray(m.content)) {
                    m.content = m.content.filter((b) => b.type !== 'image_url');
                    if (!m.content.some((b) => b.type === 'text' && b.text)) {
                        m.content = textOf(m.content) || '（图片已清理）';
                    }
                }
            }));
            write();
        }
        try {
            localStorage.setItem(ACTIVE, chat.id);
        } catch (e) {}
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

    const iconHistory = '<svg viewBox="0 0 24 24" aria-hidden="true"><path d="M4 6h16M4 12h16M4 18h10"/></svg>';
    const iconPlus = '<svg viewBox="0 0 24 24" aria-hidden="true"><path d="M12 5v14M5 12h14"/></svg>';

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
            <span class="ai-title">AI 助手</span>
            <span class="ai-ctx" hidden></span>
            <span class="ai-usage" hidden></span>
            <button type="button" class="ai-icon ai-history" aria-label="会话">${iconHistory}</button>
            <button type="button" class="ai-icon ai-new" aria-label="新建对话">${iconPlus}</button>
            <button type="button" class="ai-x" aria-label="关闭">×</button>
        </div>
        <div class="ai-chatlist" hidden></div>
        <div class="ai-keybox">
            <label>API Key</label>
            <input type="password" autocomplete="off" spellcheck="false">
            <button type="button" class="btn-quiet ai-keysave">保存</button>
        </div>
        <div class="ai-msgs"></div>
        <div class="ai-attach" hidden></div>
        <div class="ai-compose">
            <textarea rows="3"></textarea>
            <button type="button" class="btn-quiet ai-send">发送</button>
        </div>`;
    document.body.appendChild(panel);

    const keyBox = panel.querySelector('.ai-keybox');
    const keyInput = keyBox.querySelector('input');
    const ctxEl = panel.querySelector('.ai-ctx');
    const usageEl = panel.querySelector('.ai-usage');
    const msgs = panel.querySelector('.ai-msgs');
    const input = panel.querySelector('textarea');
    const sendBtn = panel.querySelector('.ai-send');
    const closeBtn = panel.querySelector('.ai-x');
    const bar = panel.querySelector('.ai-bar');
    const chatList = panel.querySelector('.ai-chatlist');
    const attach = panel.querySelector('.ai-attach');

    let locked = context;
    let sending = false;
    let pendingImages = [];

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
        if (narrow.matches || e.button !== 0 || e.target.closest('button')) return;
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
        const minW = 340;
        const minH = 300;
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

    /* ---------- 会话 ---------- */
    const fmtTokens = (n) => {
        if (n >= 1_000_000) return (n / 1_000_000).toFixed(1).replace('.0', '') + 'M';
        if (n >= 1000) return (n / 1000).toFixed(1).replace('.0', '') + 'k';
        return String(n);
    };

    const renderUsage = () => {
        const used = chat.usage;
        if (!used) {
            usageEl.hidden = true;
            return;
        }
        const limit = used.limit || 1_000_000;
        const ratio = used.prompt / limit;
        usageEl.hidden = false;
        usageEl.textContent = `上下文 ${fmtTokens(used.prompt)} / ${fmtTokens(limit)}`;
        usageEl.classList.toggle('warn', ratio >= 0.7);
        usageEl.classList.toggle('danger', ratio >= 0.9);
    };

    const renderChatList = () => {
        chatList.replaceChildren();
        chats.slice().sort((a, b) => (b.at || 0) - (a.at || 0)).forEach((c) => {
            const row = document.createElement('div');
            row.className = 'ai-chat-row' + (c.id === chat.id ? ' on' : '');
            const pick = document.createElement('button');
            pick.type = 'button';
            pick.className = 'ai-chat-pick';
            pick.textContent = c.title || '新对话';
            pick.addEventListener('click', () => {
                chat = c;
                activeId = c.id;
                saveChats();
                chatList.hidden = true;
                renderAll();
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
                    chat = chats[0];
                    activeId = chat.id;
                }
                saveChats();
                renderChatList();
                renderAll();
            });
            row.append(pick, del);
            chatList.appendChild(row);
        });
    };

    const greetText = () => (locked
        ? '我是站内助手：可以解释这篇里的句子、把内容整理成新笔记、改这篇，或者搜以前的笔记。直接说就行。'
        : '我是站内助手：可以解释笔记里的句子、整理成新笔记、搜以前的笔记。直接说就行。');

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
        const pics = imagesOf(content);
        if (!pics.length) return;
        const box = document.createElement('div');
        box.className = 'ai-msg user ai-pics';
        pics.forEach((url) => {
            const a = document.createElement('a');
            a.href = url;
            a.target = '_blank';
            a.rel = 'noopener';
            const img = document.createElement('img');
            img.src = url;
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
        renderUsage();
        renderChatList();
        msgs.scrollTop = msgs.scrollHeight;
    };

    /* ---------- 附件 ---------- */
    const renderAttach = () => {
        attach.replaceChildren();
        attach.hidden = !pendingImages.length;
        pendingImages.forEach((url, i) => {
            const box = document.createElement('div');
            box.className = 'ai-thumb';
            const img = document.createElement('img');
            img.src = url;
            img.alt = '';
            const del = document.createElement('button');
            del.type = 'button';
            del.textContent = '×';
            del.setAttribute('aria-label', '移除图片');
            del.addEventListener('click', () => {
                pendingImages.splice(i, 1);
                renderAttach();
            });
            box.append(img, del);
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

    const pickImages = async (files) => {
        for (const file of files.slice(0, 6 - pendingImages.length)) {
            if (!file.type.startsWith('image/')) continue;
            const url = await shrinkImage(file);
            if (url) pendingImages.push(url);
        }
        renderAttach();
    };

    input.addEventListener('paste', (e) => {
        const items = e.clipboardData ? Array.from(e.clipboardData.items) : [];
        const files = items
            .filter((i) => i.kind === 'file' && i.type.startsWith('image/'))
            .map((i) => i.getAsFile())
            .filter(Boolean);
        if (!files.length) return;
        e.preventDefault();
        pickImages(files);
    });

    /* ---------- 发送 ---------- */
    const key = () => readKey();
    const hasKey = () => !!readKey();

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

    const append = (cls, text) => {
        const el = document.createElement('div');
        el.className = 'ai-msg ' + cls;
        el.textContent = text;
        msgs.appendChild(el);
        msgs.scrollTop = msgs.scrollHeight;
        return el;
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
        sendBtn.disabled = v;
    };

    const send = async () => {
        if (!hasKey()) {
            renderKey();
            keyInput.focus();
            return;
        }
        const text = input.value.trim();
        const pics = pendingImages.slice();
        if (!text && !pics.length) return;
        const blocks = [];
        if (text) blocks.push({ type: 'text', text });
        pics.forEach((url) => blocks.push({ type: 'image_url', image_url: { url } }));
        const content = blocks.length === 1 && blocks[0].type === 'text' ? text : blocks;

        input.value = '';
        pendingImages = [];
        renderAttach();
        const greet = msgs.querySelector('.ai-greeting');
        if (greet) greet.remove();

        chat.messages.push({ role: 'user', content });
        if (!chat.title) chat.title = text ? text.slice(0, 24) : '图片';
        renderUserContent(content);
        saveChats();
        setSending(true);
        const think = thinking();
        try {
            const res = await post('/app/api/agent/chat', {
                messages: chat.messages.slice(-20),
                contextSlug: locked ? locked.slug : null,
            }, { 'X-Agent-Key': key() });
            const data = await res.json().catch(() => null);
            if (!res.ok) {
                append('error', (data && data.error) || '请求失败');
                return;
            }
            const reply = (data && data.reply) || '';
            chat.messages.push({ role: 'assistant', content: reply });
            if (reply) append('assistant', reply);
            ((data && data.proposals) || []).forEach((p) => {
                if (!chat.pending.some((x) => x.id === p.id)) chat.pending.push(p);
                renderProposal(p);
            });
            if (data && data.usage && data.usage.prompt_tokens) {
                chat.usage = {
                    prompt: data.usage.prompt_tokens,
                    total: data.usage.total_tokens,
                    limit: data.contextLimit || 1_000_000,
                };
            }
            renderUsage();
            saveChats();
        } catch (e) {
            append('error', '请求失败');
        } finally {
            think.remove();
            setSending(false);
        }
    };

    /* ---------- 开关 ---------- */
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
        if (!locked) {
            const g = msgs.querySelector('.ai-greeting');
            if (g) g.textContent = greetText();
            return;
        }
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

    const open = () => {
        panel.hidden = false;
        applyGeo();
        renderKey();
        if (!hasKey()) {
            keyInput.focus();
        } else {
            input.focus();
        }
        msgs.scrollTop = msgs.scrollHeight;
    };

    const close = () => {
        panel.hidden = true;
        chatList.hidden = true;
    };

    keyBox.querySelector('.ai-keysave').addEventListener('click', () => {
        const value = keyInput.value.trim();
        if (!value) return;
        try {
            localStorage.setItem(KEY, value);
        } catch (e) {}
        keyInput.value = '';
        renderKey();
        input.focus();
    });

    panel.querySelector('.ai-history').addEventListener('click', (e) => {
        e.stopPropagation();
        chatList.hidden = !chatList.hidden;
        renderChatList();
    });

    panel.querySelector('.ai-new').addEventListener('click', (e) => {
        e.stopPropagation();
        if (!chat.messages.length) {
            chatList.hidden = true;
            return;
        }
        chat = newChat();
        chats.push(chat);
        activeId = chat.id;
        chatList.hidden = true;
        saveChats();
        renderAll();
        input.focus();
    });

    sendBtn.addEventListener('click', send);
    input.addEventListener('keydown', (e) => {
        if (e.key === 'Enter' && !e.shiftKey && !e.isComposing) {
            e.preventDefault();
            send();
        }
    });
    closeBtn.addEventListener('click', close);
    document.addEventListener('click', (e) => {
        if (!chatList.hidden && !chatList.contains(e.target) && !e.target.closest('.ai-history')) {
            chatList.hidden = true;
        }
    });

    renderCtx();
    renderAll();
    applyGeo();
    narrow.addEventListener('change', applyGeo);
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
