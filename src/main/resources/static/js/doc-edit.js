// 正文直接编辑：contenteditable 里就地改，停手后把 DOM 折回 Markdown 静默保存（不刷新页面）
// 只覆盖 MarkdownRenderer 会产出的标签；文本节点空白要折叠，否则 HTML 源码里的换行会变成硬换行
(() => {
    const body = document.querySelector('.doc .markdown');
    if (!body || body.getAttribute('contenteditable') !== 'true' || !body.dataset.save) return;

    const meta = document.querySelector('meta[name="_csrf"]');
    const csrf = meta ? meta.getAttribute('content') : '';
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

    const block = (el, out) => {
        const tag = el.tagName.toLowerCase();
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
            const lang = ((code ? code.className : '').match(/language-([\w-]+)/) || [])[1] || '';
            out.push('```' + lang);
            out.push((code || el).textContent.replace(/\n+$/, ''));
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
            await fetch(saveUrl, {
                method: 'POST',
                credentials: 'same-origin',
                headers: { 'content-type': 'application/x-www-form-urlencoded' },
                body: new URLSearchParams({ _csrf: csrf, body: md, back: location.pathname }).toString(),
            });
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
