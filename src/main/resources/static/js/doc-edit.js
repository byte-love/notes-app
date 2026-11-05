// 正文直接编辑：contenteditable 里就地改，停手后把 DOM 折回 Markdown 静默保存（不刷新页面）
// 只覆盖 MarkdownRenderer 会产出的标签；文本节点空白要折叠，否则 HTML 源码里的换行会变成硬换行
(() => {
    // 标题就地改：h1 也是 contenteditable，回车或失焦时存一次
    const titleEl = document.querySelector('h1[data-item-id][contenteditable="true"]');
    if (titleEl) {
        const itemId = titleEl.dataset.itemId;
        let savedTitle = titleEl.textContent.replace(/\s+/g, ' ').trim();
        const saveTitle = async () => {
            const text = titleEl.textContent.replace(/\s+/g, ' ').trim();
            if (!text || text === savedTitle) return;
            try {
                await App.post('/app/admin/' + itemId + '/rename', { title: text, back: location.pathname });
                savedTitle = text;
                // 改完刷新一次：卡片标题、面包屑、左树都是服务端渲染的，光改 document.title 不够
                location.reload();
            } catch (e) {}
        };
        titleEl.addEventListener('keydown', (e) => {
            if (e.key === 'Enter') {
                e.preventDefault();
                titleEl.blur();
            }
        });
        titleEl.addEventListener('blur', saveTitle);
    }

    // 从浏览器「后退」缓存回来时页面是旧的（改名后卡片标题不同步就是这个）
    window.addEventListener('pageshow', (e) => {
        if (e.persisted) location.reload();
    });

    const body = document.querySelector('.doc .markdown');
    if (!body || body.getAttribute('contenteditable') !== 'true' || !body.dataset.save) return;

    const saveUrl = body.dataset.save;

    const inline = (node) => {
        if (node.nodeType === 3) return node.textContent.replace(/\s+/g, ' ');
        if (node.nodeType !== 1) return '';
        const tag = node.tagName.toLowerCase();
        // 评论那一套会往每个块里插「＋」按钮，它不是正文
        if (tag === 'button' || node.classList.contains('cmt-block-btn')) return '';
        if (tag === 'br') return '  \n';
        if (tag === 'code') return '`' + node.textContent + '`';
        if (tag === 'img') return '![' + (node.getAttribute('alt') || '') + '](' + (node.getAttribute('src') || '') + ')';
        const kids = [...node.childNodes].map(inline).join('');
        switch (tag) {
            case 'strong':
            case 'b':
                return kids.trim() ? '**' + kids.trim() + '**' : kids;
            case 'em':
            case 'i':
                return kids.trim() ? '*' + kids.trim() + '*' : kids;
            case 'del':
            case 's':
                return kids.trim() ? '~~' + kids.trim() + '~~' : kids;
            case 'a':
                return '[' + kids.trim() + '](' + (node.getAttribute('href') || '') + ')';
            default:
                return kids;
        }
    };

    const tidy = (text) =>
        text
            .replace(/\u00a0/g, ' ')
            .replace(/[ \t]+/g, ' ')
            .replace(/[ \t]*\n[ \t]*/g, '\n')
            .trim();

    // 代码块取文本：优先用 code.js 高亮时挂上来的 dataset.raw（那份是原文，
    // 跟显示结构无关）；没有就用 .line 逐行拼；再没有就按换行元素兜底。
    // ⚠ 别退回 el.textContent——高亮后行是 <span>，换行只在 CSS 里，直接取会把整段粘成一行
    const codeText = (el) => {
        if (el.dataset && el.dataset.raw !== undefined) {
            return el.dataset.raw;
        }
        const lines = el.querySelectorAll('.line');
        if (lines.length) {
            return [...lines].map((line) => line.textContent).join('\n');
        }
        const clone = el.cloneNode(true);
        clone.querySelectorAll('br').forEach((br) => br.replaceWith('\n'));
        clone.querySelectorAll('div, p, li').forEach((d) => d.append('\n'));
        return clone.textContent;
    };

    const block = (el, out) => {
        const tag = el.tagName.toLowerCase();
        // 代码块顶部的语言条 / 复制按钮是 code.js 加的界面，不是正文
        if (el.classList && el.classList.contains('code-head')) return;
        if (/^h[1-6]$/.test(tag)) {
            const text = tidy(inline(el));
            if (text) out.push('#'.repeat(Number(tag.slice(1))) + ' ' + text);
            return;
        }
        if (tag === 'p' || tag === 'div' || tag === 'section' || tag === 'article') {
            const nested = [...el.children].some((c) => ['div', 'p'].includes(c.tagName.toLowerCase()));
            if (nested) {
                for (const child of el.childNodes) {
                    if (child.nodeType === 1) block(child, out);
                    else if (child.nodeType === 3) {
                        const text = tidy(inline(child));
                        if (text) out.push(text);
                    }
                }
                return;
            }
            const text = tidy(inline(el));
            if (text) out.push(text);
            return;
        }
        if (tag === 'ul' || tag === 'ol') {
            const ordered = tag === 'ol';
            let n = 1;
            for (const li of el.children) {
                if (li.tagName.toLowerCase() !== 'li') continue;
                const nested = [...li.children].filter((c) => ['ul', 'ol'].includes(c.tagName.toLowerCase()));
                const clone = li.cloneNode(true);
                [...clone.children].forEach((c) => {
                    if (['ul', 'ol'].includes(c.tagName.toLowerCase())) c.remove();
                });
                out.push((ordered ? n++ + '. ' : '- ') + tidy(inline(clone)));
                for (const sub of nested) block(sub, out);
            }
            return;
        }
        if (tag === 'pre') {
            const code = el.querySelector('code');
            const lang = el.dataset.lang !== undefined
                ? el.dataset.lang
                : ((code ? code.className : '').match(/language-([\w-]+)/) || [])[1] || '';
            out.push('```' + lang);
            out.push(codeText(el).replace(/\n+$/, ''));
            out.push('```');
            return;
        }
        if (tag === 'blockquote') {
            const inner = [];
            for (const child of el.childNodes) {
                if (child.nodeType === 1) block(child, inner);
                else if (child.nodeType === 3) {
                    const text = tidy(inline(child));
                    if (text) inner.push(text);
                }
            }
            inner.forEach((line) => out.push('> ' + line));
            return;
        }
        if (tag === 'table') {
            const rows = [...el.querySelectorAll('tr')];
            if (!rows.length) return;
            const cells = (row) => [...row.children].map((c) => tidy(inline(c)));
            const head = cells(rows[0]);
            out.push('| ' + head.join(' | ') + ' |');
            out.push('| ' + head.map(() => '---').join(' | ') + ' |');
            rows.slice(1).forEach((row) => out.push('| ' + cells(row).join(' | ') + ' |'));
            return;
        }
        if (tag === 'hr') {
            out.push('---');
            return;
        }
        if (tag === 'br') return;
        for (const child of el.childNodes) {
            if (child.nodeType === 1) block(child, out);
        }
    };

    const toMarkdown = () => {
        const out = [];
        for (const node of body.childNodes) {
            if (node.nodeType === 1) {
                block(node, out);
                out.push('');
            } else if (node.nodeType === 3) {
                const text = tidy(inline(node));
                if (text) out.push(text, '');
            }
        }
        return out.join('\n').replace(/\n{3,}/g, '\n\n').trim() + '\n';
    };

    let timer = 0;
    let saving = false;
    let dirty = false;
    let lastSaved = toMarkdown();

    const save = async () => {
        clearTimeout(timer);
        if (saving || !dirty) return;
        const md = toMarkdown();
        if (md === lastSaved) {
            dirty = false;
            return;
        }
        saving = true;
        try {
            await App.post(saveUrl, { body: md, back: location.pathname });
            lastSaved = md;
            dirty = false;
        } catch (e) {
            // 断网就留着 dirty，下次输入或离开页面再试
        } finally {
            saving = false;
        }
    };

    const schedule = () => {
        dirty = true;
        clearTimeout(timer);
        timer = setTimeout(save, 1200);
    };

    body.addEventListener('input', schedule);
    body.addEventListener('blur', save);
    window.addEventListener('pagehide', save);
    document.addEventListener('visibilitychange', () => {
        if (document.hidden) save();
    });

    // 从网页 / AI 对话里粘贴。以前一律只留纯文本，结果表格变成一整行、代码块变成普通段落。
    // 现在：剪贴板里带了结构（表格 / 代码块 / 列表 / 标题 / 图片）就先把 HTML 洗一遍再插进去，
    // 页面上当场就是表格和代码块的样子；保存时 toMarkdown 再序列化成 Markdown。
    // 没有结构就还是走纯文本，避免把来源页的底色、宽度、div 包裹带进来。
    const ALLOWED_TAGS = new Set(['P', 'BR', 'DIV', 'SECTION', 'ARTICLE', 'STRONG', 'B', 'EM', 'I',
        'DEL', 'S', 'CODE', 'PRE', 'A', 'IMG', 'UL', 'OL', 'LI', 'BLOCKQUOTE',
        'TABLE', 'THEAD', 'TBODY', 'TR', 'TH', 'TD', 'H1', 'H2', 'H3', 'H4', 'H5', 'H6', 'HR']);
    const DROP_TAGS = /^(SCRIPT|STYLE|META|LINK|NOSCRIPT|IFRAME|SVG|BUTTON|INPUT|TEXTAREA|SELECT|FORM)$/;

    const scrub = (root) => {
        for (const child of [...root.childNodes]) {
            if (child.nodeType === 3) continue;
            if (child.nodeType !== 1) {
                child.remove();
                continue;
            }
            if (DROP_TAGS.test(child.tagName)) {
                child.remove();
                continue;
            }
            scrub(child);
            if (!ALLOWED_TAGS.has(child.tagName)) {
                // 不认识的标签拆掉，只留里面的内容
                child.replaceWith(...child.childNodes);
                continue;
            }
            for (const attr of [...child.attributes]) {
                const name = attr.name.toLowerCase();
                const keep = (child.tagName === 'A' && name === 'href')
                    || (child.tagName === 'IMG' && (name === 'src' || name === 'alt'));
                if (!keep) child.removeAttribute(attr.name);
            }
            if (child.tagName === 'CODE') {
                const lang = (child.getAttribute('class') || '').match(/language-[\w-]+/);
                child.removeAttribute('class');
                if (lang) child.className = lang[0];
            }
            if (child.tagName === 'TD' || child.tagName === 'TH') {
                // 空单元格给个空格，Markdown 表格的列才对得上
                if (!child.textContent.trim()) child.textContent = ' ';
            }
        }
    };

    const pasteHtml = (html) => {
        const doc = new DOMParser().parseFromString(html, 'text/html');
        const box = document.createElement('div');
        while (doc.body.firstChild) box.appendChild(doc.body.firstChild);
        scrub(box);
        return box.textContent.trim() || box.querySelector('img') ? box.innerHTML : '';
    };

    // 剪贴板里直接是图片文件时（截图工具那种）：先当场插成 base64 保证立刻能看到，
    // 同时后台上传一份；传成了就把那段 base64 换成 /app/media/notes/xxx.png 的短地址，
    // 正文不至于被几十 K 的 base64 撑爆（过了 65536 这页就不能直接编辑了）。
    // 传不成或没登录就留着 base64，图不会丢。
    const uploadPasted = async (file, dataUrl) => {
        const url = body.dataset.upload;
        if (!url || !file) return;
        try {
            const form = new FormData();
            form.append('file', file, file.name || 'paste.png');
            const res = await fetch(url, {
                method: 'POST',
                credentials: 'same-origin',
                headers: { 'X-CSRF-TOKEN': App.csrf() },
                body: form,
            });
            if (!res.ok) return;
            const out = await res.json();
            if (!out || !out.url) return;
            const img = [...body.querySelectorAll('img')].find((i) => i.getAttribute('src') === dataUrl);
            if (!img) return;
            img.setAttribute('src', out.url);
            schedule();
        } catch (err) {
            // 上传失败就保持 base64
        }
    };

    body.addEventListener('paste', (e) => {
        const dt = e.clipboardData;
        if (!dt) return;
        const files = [...(dt.files || [])].filter((f) => /^image\//.test(f.type || ''));
        if (files.length) {
            e.preventDefault();
            for (const file of files) {
                const reader = new FileReader();
                reader.onload = () => {
                    const dataUrl = String(reader.result || '');
                    if (!dataUrl) return;
                    document.execCommand('insertHTML', false, '<img src="' + dataUrl + '" alt="">');
                    schedule();
                    uploadPasted(file, dataUrl);
                };
                reader.readAsDataURL(file);
            }
            return;
        }
        const html = dt.getData('text/html');
        const text = dt.getData('text/plain');
        let marked = '';
        if (html && /<(table|pre|ul|ol|h[1-6]|blockquote|img)\b/i.test(html)) {
            try {
                marked = pasteHtml(html);
            } catch (err) {
                marked = '';
            }
        }
        if (!marked && !text) return;
        e.preventDefault();
        if (marked) {
            document.execCommand('insertHTML', false, marked);
        } else {
            document.execCommand('insertText', false, text);
        }
        schedule();
    });

    // 回车另起一段（不带上一段样式）；Shift+Enter 走浏览器默认的软换行
    body.addEventListener('keydown', (e) => {
        if (e.key !== 'Enter' || e.shiftKey || e.isComposing) return;
        const sel = window.getSelection();
        if (!sel || !sel.rangeCount) return;
        const anchor = sel.anchorNode;
        const base = anchor && (anchor.nodeType === 1 ? anchor : anchor.parentElement);
        const host = base ? base.closest('p, div, li, h1, h2, h3, h4, blockquote') : null;
        if (!host || !body.contains(host)) return;
        e.preventDefault();
        const range = sel.getRangeAt(0);
        const tail = range.cloneRange();
        tail.setEndAfter(host);
        const rest = tail.extractContents();
        const p = document.createElement('p');
        p.appendChild(rest);
        if (!p.textContent.trim() && !p.querySelector('img')) p.innerHTML = '<br>';
        host.after(p);
        const r = document.createRange();
        r.setStart(p, 0);
        r.collapse(true);
        sel.removeAllRanges();
        sel.addRange(r);
        schedule();
    });

    // 插入笔记卡片：单独一个按钮 + 小表单，跟正文编辑无关
    const suppBtn = document.querySelector('[data-act="supplement"]');
    const suppForm = document.getElementById('supp-form');
    if (suppBtn && suppForm) {
        suppBtn.addEventListener('click', () => {
            suppForm.hidden = !suppForm.hidden;
            if (!suppForm.hidden) suppForm.querySelector('input[name="supplementTitle"]').focus();
        });
        const cancel = suppForm.querySelector('[data-act="supplement-cancel"]');
        if (cancel) {
            cancel.addEventListener('click', () => {
                suppForm.hidden = true;
            });
        }
    }
})();
