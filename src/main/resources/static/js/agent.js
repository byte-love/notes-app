// 站内助手前端：key 只放 sessionStorage，历史只放当前页面内存。
(() => {
    const keyInput = document.getElementById('agentKey');
    const saveBtn = document.getElementById('agentSave');
    const input = document.getElementById('agentInput');
    const sendBtn = document.getElementById('agentSend');
    const list = document.getElementById('agentMessages');
    const fileInput = document.getElementById('agentFile');
    if (!keyInput || !saveBtn || !input || !sendBtn || !list) return;

    const STORE = 'agent-key';
    const messages = [];
    keyInput.value = sessionStorage.getItem(STORE) || '';

    saveBtn.addEventListener('click', () => {
        sessionStorage.setItem(STORE, keyInput.value.trim());
    });

    const append = (cls, text) => {
        const el = document.createElement('div');
        el.className = 'agent-msg ' + cls;
        el.textContent = text;
        list.appendChild(el);
        list.scrollTop = list.scrollHeight;
        return el;
    };

    const post = async (url, body, key) => {
        const send = () => fetch(url, {
            method: 'POST',
            credentials: 'same-origin',
            headers: Object.assign(
                { 'content-type': 'application/json', 'X-CSRF-TOKEN': App.csrf() },
                key ? { 'X-Agent-Key': key } : {},
            ),
            body: JSON.stringify(body),
        });
        let res = await send();
        if (res.status === 403) {
            await App.refreshCsrf();
            res = await send();
        }
        return res;
    };

    const renderProposal = (p) => {
        const card = document.createElement('div');
        card.className = 'agent-proposal';

        const title = document.createElement('div');
        title.className = 'agent-proposal-title';
        title.textContent = p.title || p.slug || '';

        const body = document.createElement('div');
        body.className = 'agent-proposal-body';
        const preview = p.body || p.oldBody || '';
        body.textContent = preview.length > 600 ? preview.slice(0, 600) : preview;

        const actions = document.createElement('div');
        actions.className = 'agent-proposal-actions';
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
        list.appendChild(card);
        list.scrollTop = list.scrollHeight;
    };

    const send = async () => {
        const text = input.value.trim();
        if (!text) return;
        const key = (keyInput.value || sessionStorage.getItem(STORE) || '').trim();
        if (!key) {
            append('error', '未填写 API Key');
            return;
        }
        sessionStorage.setItem(STORE, key);
        input.value = '';
        messages.push({ role: 'user', content: text });
        append('user', text);
        sendBtn.disabled = true;
        try {
            const res = await post('/app/api/agent/chat', { messages }, key);
            const data = await res.json().catch(() => null);
            if (!res.ok) {
                append('error', (data && data.error) || '请求失败');
                return;
            }
            const reply = (data && data.reply) || '';
            messages.push({ role: 'assistant', content: reply });
            if (reply) append('assistant', reply);
            ((data && data.proposals) || []).forEach(renderProposal);
        } catch (e) {
            append('error', '请求失败');
        } finally {
            sendBtn.disabled = false;
        }
    };

    sendBtn.addEventListener('click', send);
    input.addEventListener('keydown', (e) => {
        if ((e.ctrlKey || e.metaKey) && e.key === 'Enter') {
            e.preventDefault();
            send();
        }
    });

    fileInput.addEventListener('change', () => {
        const f = fileInput.files && fileInput.files[0];
        if (!f) return;
        const name = f.name.toLowerCase();
        if (!name.endsWith('.md') && !name.endsWith('.txt')) {
            fileInput.value = '';
            return;
        }
        const reader = new FileReader();
        reader.onload = () => {
            const text = String(reader.result || '');
            input.value = (input.value.trim() ? input.value.replace(/\s+$/, '') + '\n\n' : '') + text;
        };
        reader.readAsText(f);
        fileInput.value = '';
    });
})();
