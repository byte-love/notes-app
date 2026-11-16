/*
 * 编辑器：粘贴富文本时把层级还原成 Markdown + 一个「整理排版」按钮。
 *
 * 为什么需要：从网页 / 在线文档里复制内容时，剪贴板里同时有 text/plain 和 text/html。
 * 只取纯文本的话，行内换行会变成空格、列表符号丢失、编号全变成 1，
 * 粘进编辑器就只剩一大段（2026-11-16 用户在 bagu-mysql-025-detail 上踩到）。
 */
(() => {
    'use strict';

    // ---------- 富文本 → Markdown ----------

    const langOf = (pre) => {
        const code = pre.querySelector('code');
        const cls = (code && code.className) || pre.className || '';
        const m = /language-([\w-]+)/.exec(cls);
        return m ? m[1] : '';
    };

    const inlineMd = (el) => {
        let out = '';
        el.childNodes.forEach((n) => {
            if (n.nodeType === Node.TEXT_NODE) {
                out += n.nodeValue.replace(/\s+/g, ' ');
            } else if (n.nodeType === Node.ELEMENT_NODE) {
                const tag = n.tagName;
                if (tag === 'BR') {
                    out += '  \n';
                } else if (tag === 'STRONG' || tag === 'B') {
                    const t = inlineMd(n).trim();
                    out += t ? '**' + t + '**' : '';
                } else if (tag === 'EM' || tag === 'I') {
                    const t = inlineMd(n).trim();
                    out += t ? '*' + t + '*' : '';
                } else if (tag === 'CODE') {
                    out += '`' + n.textContent.replace(/`/g, '') + '`';
                } else if (tag === 'A') {
                    const href = n.getAttribute('href') || '';
                    const t = inlineMd(n).trim();
                    out += href && t ? '[' + t + '](' + href + ')' : t;
                } else if (tag === 'IMG') {
                    const src = n.getAttribute('src') || '';
                    if (src && !/^(data|blob):/.test(src)) {
                        out += '![](' + src + ')';
                    }
                } else {
                    out += inlineMd(n);
                }
            }
        });
        return out;
    };

    const tableToMd = (table) => {
        const rows = [];
        table.querySelectorAll('tr').forEach((tr) => {
            const cells = [];
            tr.querySelectorAll('th,td').forEach((cell) => {
                cells.push(cell.textContent.replace(/\s+/g, ' ').trim().replace(/\|/g, '\\|'));
            });
            if (cells.length) {
                rows.push('| ' + cells.join(' | ') + ' |');
            }
        });
        if (rows.length > 1) {
            const head = rows[0];
            const cols = head.split('|').length - 2;
            rows.splice(1, 0, '| ' + Array.from({ length: cols }, () => '---').join(' | ') + ' |');
        }
        return rows.join('\n');
    };

    const walkList = (list, depth, lines) => {
        const ordered = list.tagName === 'OL';
        let index = Number(list.getAttribute('start') || 1) || 1;
        const indent = '  '.repeat(depth);
        for (const li of list.children) {
            if (li.tagName !== 'LI') continue;
            const marker = ordered ? (index++) + '. ' : '- ';
            const own = li.cloneNode(true);
            own.querySelectorAll('ul, ol').forEach((x) => x.remove());
            const text = inlineMd(own).replace(/\s*\n\s*/g, ' ').trim();
            lines.push(indent + marker + text);
            li.querySelectorAll(':scope > ul, :scope > ol').forEach((sub) => walkList(sub, depth + 1, lines));
        }
        lines.push('');
    };

    const walkBlocks = (el, lines) => {
        for (const n of el.children) {
            const tag = n.tagName;
            if (/^H[1-6]$/.test(tag)) {
                const level = Math.min(6, Number(tag[1]));
                lines.push('', '#'.repeat(level) + ' ' + inlineMd(n).trim(), '');
            } else if (tag === 'UL' || tag === 'OL') {
                lines.push('');
                walkList(n, 0, lines);
            } else if (tag === 'PRE') {
                const lang = langOf(n);
                lines.push('', '```' + lang, n.textContent.replace(/\n+$/, ''), '```', '');
            } else if (tag === 'BLOCKQUOTE') {
                lines.push('');
                inlineMd(n).trim().split(/\s*\n\s*/).forEach((l) => lines.push('> ' + l));
                lines.push('');
            } else if (tag === 'TABLE') {
                lines.push('', tableToMd(n), '');
            } else if (n.querySelector('h1,h2,h3,h4,h5,h6,ul,ol,pre,blockquote,table')) {
                walkBlocks(n, lines);
            } else {
                const text = inlineMd(n).trim();
                if (text) {
                    lines.push('', text);
                }
            }
        }
    };

    const htmlToMarkdown = (html) => {
        const doc = new DOMParser().parseFromString(html, 'text/html');
        const lines = [];
        walkBlocks(doc.body, lines);
        return lines.join('\n')
            .replace(/\n{3,}/g, '\n\n')
            .replace(/^\n+/, '')
            .replace(/\n+$/, '') + '\n';
    };

    // ---------- 整理排版（针对已经粘坏的纯文本） ----------

    // ---------- 绑定 ----------

    const insert = (ta, text) => {
        const start = ta.selectionStart || 0;
        const end = ta.selectionEnd || 0;
        ta.setRangeText(text, start, end, 'end');
        ta.dispatchEvent(new Event('input', { bubbles: true }));
    };

    const bindPaste = (ta) => {
        ta.addEventListener('paste', (e) => {
            const data = e.clipboardData;
            if (!data) return;
            const html = data.getData('text/html');
            if (!html || !/<(p|div|li|ul|ol|h[1-6]|pre|br)\b/i.test(html)) return;
            const md = htmlToMarkdown(html);
            if (!md.trim()) return;
            e.preventDefault();
            insert(ta, md);
        });
    };

    document.querySelectorAll('#md-source, textarea[name="body"]').forEach(bindPaste);

    // 整理排版：只认 #md-source（正文那个大编辑器）
    const source = document.getElementById('md-source');
    const tidyBtn = document.getElementById('md-tidy');
    const undoBtn = document.getElementById('md-undo');
    if (source && tidyBtn) {
        let snapshot = null;
        tidyBtn.addEventListener('click', () => {
            const before = source.value;
            const after = window.MdTidy.tidy(before);
            if (after === before) return;
            snapshot = before;
            source.value = after;
            source.dispatchEvent(new Event('input', { bubbles: true }));
            if (undoBtn) undoBtn.hidden = false;
        });
        if (undoBtn) {
            undoBtn.addEventListener('click', () => {
                if (snapshot === null) return;
                source.value = snapshot;
                snapshot = null;
                undoBtn.hidden = true;
                source.dispatchEvent(new Event('input', { bubbles: true }));
            });
        }
    }
})();
