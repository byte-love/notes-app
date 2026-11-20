// 站内助手：右下角机器人球 + 可拖动、可缩放的悬浮窗。key 和聊天记录都存在本机（按账号分开）。
(() => {
    const meta = (name) => (document.querySelector('meta[name="' + name + '"]') || {}).content || '';
    const user = meta('user-name');
    if (!user) return;

    const KEY = 'agent-key:' + user;
    const HIST = 'agent-chat:' + user;
    const PEND = 'agent-pending:' + user;
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
        } catch (e) {}
    };
    const readKey = () => {
        try {
            return localStorage.getItem(KEY) || '';
        } catch (e) {
            return '';
        }
    };

    // 正文页模板带 data-note-*；项目页没这个属性时退回按 URL 认
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
            <button type="button" class="ai-x" aria-label="关闭">×</button>
        </div>
        <div class="ai-keybox">
            <label>API Key</label>
            <input type="password" autocomplete="off" spellcheck="false">
            <button type="button" class="btn-quiet ai-keysave">保存</button>
        </div>
        <div class="ai-msgs"></div>
        <div class="ai-compose">
            <textarea rows="3"></textarea>
            <button type="button" class="btn-quiet ai-send">发送</button>
        </div>`;
    document.body.appendChild(panel);

    const keyBox = panel.querySelector('.ai-keybox');
    const keyInput = keyBox.querySelector('input');
    const ctxEl = panel.querySelector('.ai-ctx');
    const msgs = panel.querySelector('.ai-msgs');
    const input = panel.querySelector('textarea');
    const sendBtn = panel.querySelector('.ai-send');
    const closeBtn = panel.querySelector('.ai-x');
    const bar = panel.querySelector('.ai-bar');

    let locked = context;
    let sending = false;
    let history = loadJson(HIST) || [];
    let pending = loadJson(PEND) || [];

    const saveChat = () => {
        history = history.slice(-40);
        pending = pending.slice(-12);
        saveJson(HIST, history);
        saveJson(PEND, pending);
    };

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

    const key = () => readKey();
    const hasKey = () => !!readKey();

    const updateSend = () => {
        sendBtn.disabled = sending;
    };

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
            if (g) g.textContent = greetingText();
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

    const append = (cls, text) => {
        const el = document.createElement('div');
        el.className = 'ai-msg ' + cls;
        el.textContent = text;
        msgs.appendChild(el);
        msgs.scrollTop = msgs.scrollHeight;
        return el;
    };

    const greetingText = () => (locked
        ? '我是站内助手：可以解释这篇里的句子、把内容整理成新笔记、改这篇，或者搜以前的笔记。直接说就行。'
        : '我是站内助手：可以解释笔记里的句子、整理成新笔记、搜以前的笔记。直接说就行。');

    const greeting = () => {
        if (history.length) return;
        const el = document.createElement('div');
        el.className = 'ai-msg ai-greeting';
        el.textContent = greetingText();
        msgs.appendChild(el);
        msgs.scrollTop = msgs.scrollHeight;
    };

    const thinking = () => {
        const el = document.createElement('div');
        el.className = 'ai-msg ai-thinking';
        el.innerHTML = '<span></span><span></span><span></span>';
        msgs.appendChild(el);
        msgs.scrollTop = msgs.scrollHeight;
        return el;
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
                pending = pending.filter((x) => x.id !== p.id);
                saveChat();
            } catch (e) {
                append('error', '写入失败');
                write.disabled = false;
            }
        });
        ignore.addEventListener('click', () => {
            card.remove();
            pending = pending.filter((x) => x.id !== p.id);
            saveChat();
        });

        actions.append(write, ignore);
        card.append(title, body, actions);
        msgs.appendChild(card);
        msgs.scrollTop = msgs.scrollHeight;
    };

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

    const send = async () => {
        if (!hasKey()) {
            renderKey();
            keyInput.focus();
            return;
        }
        const text = input.value.trim();
        if (!text) return;
        input.value = '';
        const greet = msgs.querySelector('.ai-greeting');
        if (greet) greet.remove();
        history.push({ role: 'user', content: text });
        saveChat();
        append('user', text);
        sending = true;
        updateSend();
        const think = thinking();
        try {
            const res = await post('/app/api/agent/chat', {
                messages: history.slice(-20),
                contextSlug: locked ? locked.slug : null,
            }, { 'X-Agent-Key': key() });
            const data = await res.json().catch(() => null);
            if (!res.ok) {
                append('error', (data && data.error) || '请求失败');
                return;
            }
            const reply = (data && data.reply) || '';
            history.push({ role: 'assistant', content: reply });
            if (reply) append('assistant', reply);
            ((data && data.proposals) || []).forEach((p) => {
                if (!pending.some((x) => x.id === p.id)) pending.push(p);
                renderProposal(p);
            });
            saveChat();
        } catch (e) {
            append('error', '请求失败');
        } finally {
            think.remove();
            sending = false;
            updateSend();
        }
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

    sendBtn.addEventListener('click', send);
    input.addEventListener('keydown', (e) => {
        if ((e.ctrlKey || e.metaKey) && e.key === 'Enter') {
            e.preventDefault();
            send();
        }
    });
    closeBtn.addEventListener('click', close);

    renderCtx();
    history.forEach((m) => append(m.role === 'user' ? 'user' : 'assistant', m.content));
    pending.forEach(renderProposal);
    greeting();
    updateSend();
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
