// 站内助手：右下角机器人球 + 面板。key 存在本机（按账号分开），请求只带当前这次对话。
(() => {
    const meta = (name) => (document.querySelector('meta[name="' + name + '"]') || {}).content || '';
    const user = meta('user-name');
    if (!user) return;

    const KEY = 'agent-key:' + user;
    const readKey = () => {
        try {
            return localStorage.getItem(KEY) || '';
        } catch (e) {
            return '';
        }
    };

    const holder = document.querySelector('[data-note-slug]');
    const context = holder ? {
        slug: holder.dataset.noteSlug,
        kind: holder.dataset.noteKind,
        title: holder.dataset.noteTitle || holder.dataset.noteSlug,
    } : null;

    const fab = document.createElement('button');
    fab.type = 'button';
    fab.className = 'ai-fab';
    fab.setAttribute('aria-label', 'AI 助手');
    fab.innerHTML = `
        <svg viewBox="0 0 48 48" aria-hidden="true">
            <path d="M24 8v5"></path>
            <circle cx="24" cy="6" r="2.5"></circle>
            <rect x="11" y="13" width="26" height="22" rx="8"></rect>
            <circle cx="18" cy="24" r="2.6" class="ai-eye"></circle>
            <circle cx="30" cy="24" r="2.6" class="ai-eye"></circle>
            <path d="M19 30h10"></path>
            <path d="M7 22v6M41 22v6"></path>
        </svg>`;
    document.body.appendChild(fab);

    const panel = document.createElement('section');
    panel.className = 'ai-panel';
    panel.hidden = true;
    panel.innerHTML = `
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
        <div class="ai-modes">
            <button type="button" data-mode="ask">答疑</button>
            <button type="button" data-mode="create">记一条</button>
            <button type="button" data-mode="edit">改这篇</button>
            <button type="button" data-mode="search">搜笔记</button>
        </div>
        <div class="ai-kinds" hidden>
            <button type="button" data-kind="note">闪念</button>
            <button type="button" data-kind="project">项目</button>
            <button type="button" data-kind="bagu">八股</button>
            <button type="button" data-kind="algo">算法</button>
            <button type="button" data-kind="interview">面试</button>
        </div>
        <div class="ai-compose">
            <textarea rows="3"></textarea>
            <button type="button" class="btn-quiet ai-send">发送</button>
        </div>`;
    document.body.appendChild(panel);

    const keyBox = panel.querySelector('.ai-keybox');
    const keyInput = keyBox.querySelector('input');
    const ctxEl = panel.querySelector('.ai-ctx');
    const msgs = panel.querySelector('.ai-msgs');
    const modes = panel.querySelector('.ai-modes');
    const kinds = panel.querySelector('.ai-kinds');
    const input = panel.querySelector('textarea');
    const sendBtn = panel.querySelector('.ai-send');
    const closeBtn = panel.querySelector('.ai-x');

    let mode = 'ask';
    let kind = '';
    let locked = context;
    let sending = false;
    const history = [];

    const key = () => readKey();
    const hasKey = () => !!readKey();

    const updateSend = () => {
        sendBtn.disabled = sending || (mode === 'create' && !kind);
    };

    const setMode = (next) => {
        if (next === 'edit' && !locked) next = 'ask';
        mode = next;
        modes.querySelectorAll('button').forEach((b) => {
            b.classList.toggle('on', b.dataset.mode === next);
        });
        kinds.hidden = next !== 'create';
        if (next !== 'create') {
            kind = '';
            kinds.querySelectorAll('button').forEach((b) => b.classList.remove('on'));
        }
        renderCtx();
        updateSend();
    };

    const renderKey = () => {
        const ok = hasKey();
        keyBox.hidden = ok;
        panel.classList.toggle('need-key', !ok);
    };

    const renderCtx = () => {
        if (locked) {
            ctxEl.hidden = false;
            ctxEl.textContent = locked.title;
            const x = document.createElement('button');
            x.type = 'button';
            x.className = 'ai-ctx-x';
            x.setAttribute('aria-label', '取消上下文');
            x.textContent = '×';
            x.addEventListener('click', () => {
                locked = null;
                renderCtx();
                setMode('ask');
            });
            ctxEl.appendChild(x);
        } else {
            ctxEl.hidden = true;
        }
        const edit = modes.querySelector('[data-mode="edit"]');
        edit.hidden = !locked;
    };

    const append = (cls, text) => {
        const el = document.createElement('div');
        el.className = 'ai-msg ' + cls;
        el.textContent = text;
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
            } catch (e) {
                append('error', '写入失败');
                write.disabled = false;
            }
        });
        ignore.addEventListener('click', () => card.remove());

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
        if (mode === 'create' && !kind) return;
        input.value = '';
        const userMsg = { role: 'user', content: text };
        history.push(userMsg);
        append('user', text);
        sending = true;
        updateSend();
        try {
            const res = await post('/app/api/agent/chat', {
                messages: history.slice(-20),
                mode,
                kind,
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
            ((data && data.proposals) || []).forEach(renderProposal);
        } catch (e) {
            append('error', '请求失败');
        } finally {
            sending = false;
            updateSend();
        }
    };

    const open = () => {
        panel.hidden = false;
        renderKey();
        if (!hasKey()) {
            keyInput.focus();
        } else {
            input.focus();
        }
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

    modes.addEventListener('click', (e) => {
        const btn = e.target.closest('button[data-mode]');
        if (btn) setMode(btn.dataset.mode);
    });

    kinds.addEventListener('click', (e) => {
        const btn = e.target.closest('button[data-kind]');
        if (!btn) return;
        kind = btn.dataset.kind;
        kinds.querySelectorAll('button').forEach((b) => b.classList.toggle('on', b === btn));
        updateSend();
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
    setMode('ask');

    if (window.Float) {
        window.Float.attach(fab, 'assistant', open);
    }
    fab.addEventListener('click', () => {
        if (panel.hidden) open();
        else close();
    });
})();
