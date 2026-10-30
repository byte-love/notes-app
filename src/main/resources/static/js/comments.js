// 评论：选中文字 -> 浮出工具条 -> 点「评论」-> 右栏才展开输入框
// 定位参考 Hypothesis 的 TextQuoteSelector（原文 + 前后文）与 PositionSelector（块序号）
(() => {
    const pane = document.getElementById('comments');
    const article = document.querySelector('.markdown');
    const form = pane && pane.querySelector('.cmt-form');
    if (!pane || !article) return;

    const textarea = form && form.querySelector('textarea[name="body"]');
    const field = (name) => (form ? form.querySelector(`[name="${name}"]`) : null);
    const chip = form && form.querySelector('.cmt-anchor-chip');
    const chipText = chip && chip.querySelector('.cmt-anchor-text');
    const blocks = [...article.children];
    const canComment = !!form;

    const isNarrow = () => window.matchMedia('(max-width: 820px)').matches;

    // ---------- 打开 / 关闭输入框 ----------
    const openForm = (anchor) => {
        if (!form) return;
        const set = (name, value) => {
            const el = field(name);
            if (el) el.value = value == null ? '' : String(value);
        };
        set('quote', anchor.quote);
        set('anchorKind', anchor.kind);
        set('blockIndex', anchor.block);
        set('prefix', anchor.prefix);
        set('suffix', anchor.suffix);
        if (chip && chipText) {
            chipText.textContent = anchor.quote ? anchor.quote.slice(0, 80) : '';
            chip.hidden = !anchor.quote;
        }
        form.hidden = false;
        if (isNarrow()) {
            // 窄屏右栏在正文下方，直接滚过去
            pane.scrollIntoView({ block: 'start', behavior: 'smooth' });
            setTimeout(() => textarea && textarea.focus(), 260);
        } else {
            pane.scrollTo({ top: 0, behavior: 'smooth' });
            textarea && textarea.focus();
        }
    };

    const closeForm = () => {
        if (!form) return;
        form.hidden = true;
        ['quote', 'anchorKind', 'blockIndex', 'prefix', 'suffix'].forEach((n) => {
            const el = field(n);
            if (el) el.value = '';
        });
        if (chip) chip.hidden = true;
        if (textarea) textarea.value = '';
    };

    if (form) {
        const cancel = form.querySelector('.cmt-cancel');
        cancel && cancel.addEventListener('click', closeForm);
        const clearBtn = form.querySelector('.cmt-anchor-clear');
        clearBtn && clearBtn.addEventListener('click', () => {
            ['quote', 'anchorKind', 'blockIndex', 'prefix', 'suffix'].forEach((n) => {
                const el = field(n);
                if (el) el.value = '';
            });
            if (chip) chip.hidden = true;
        });
        form.addEventListener('submit', () => {
            if (!field('anchorKind').value) field('anchorKind').value = 'text';
        });
        // 右栏头部的 ＋：评论整篇
        const newBtn = pane.querySelector('.cmt-new');
        newBtn && newBtn.addEventListener('click', () => openForm({ kind: 'text', quote: '' }));
    }

    // ---------- 选中文字后的浮动工具条 ----------
    let lastRange = null;
    let lastQuote = '';

    const tool = document.createElement('div');
    tool.className = 'cmt-tool';
    tool.hidden = true;
    const toolBtn = document.createElement('button');
    toolBtn.type = 'button';
    toolBtn.textContent = '评论';
    tool.appendChild(toolBtn);
    document.body.appendChild(tool);

    const hideTool = () => {
        tool.hidden = true;
    };

    // 点工具条不能把选区弄没
    tool.addEventListener('mousedown', (e) => {
        e.preventDefault();
        e.stopPropagation();
    });
    tool.addEventListener('touchstart', (e) => {
        e.stopPropagation();
    }, { passive: true });

    const blockOf = (node) => {
        for (let el = node.nodeType === 1 ? node : node.parentElement; el; el = el.parentElement) {
            if (el.parentElement === article) return blocks.indexOf(el);
        }
        return -1;
    };

    const showTool = () => {
        if (!canComment) return;
        const sel = window.getSelection();
        if (!sel || sel.isCollapsed || !sel.rangeCount) {
            hideTool();
            return;
        }
        const text = sel.toString().trim();
        if (!text || !article.contains(sel.anchorNode)) {
            hideTool();
            return;
        }
        const range = sel.getRangeAt(0);
        const rect = range.getBoundingClientRect();
        if (!rect || (!rect.width && !rect.height)) {
            hideTool();
            return;
        }
        lastRange = range.cloneRange();
        lastQuote = text;
        tool.hidden = false;
        const top = rect.top + window.scrollY - tool.offsetHeight - 8;
        tool.style.top = `${Math.max(8, top)}px`;
        tool.style.left = `${Math.max(10, rect.left + window.scrollX + rect.width / 2 - tool.offsetWidth / 2)}px`;
    };

    // 桌面：selectionchange + mouseup；手机：长按选中后 touchend 再兜一次
    document.addEventListener('selectionchange', showTool);
    article.addEventListener('mouseup', () => setTimeout(showTool, 0));
    article.addEventListener('touchend', () => setTimeout(showTool, 260));
    article.addEventListener('keyup', showTool);

    toolBtn.addEventListener('click', () => {
        const quote = lastQuote || (window.getSelection() ? window.getSelection().toString().trim() : '');
        if (!quote) return;
        const index = lastRange ? blockOf(lastRange.startContainer) : -1;
        const block = index >= 0 ? blocks[index] : null;
        const blockText = block ? block.textContent || '' : '';
        const at = blockText.indexOf(quote);
        openForm({
            kind: 'text',
            quote,
            prefix: at > 0 ? blockText.slice(Math.max(0, at - 20), at) : '',
            suffix: at >= 0 ? blockText.slice(at + quote.length, at + quote.length + 20) : '',
            block: index,
        });
        hideTool();
        const sel = window.getSelection();
        sel && sel.removeAllRanges();
    });

    document.addEventListener('mousedown', (e) => {
        if (!tool.contains(e.target)) hideTool();
    });
    document.addEventListener('touchstart', (e) => {
        if (!tool.contains(e.target)) hideTool();
    }, { passive: true });

    // ---------- 整段 / 代码块左侧的 ＋ ----------
    if (canComment) {
        blocks.forEach((block, index) => {
            if (!block.textContent || !block.textContent.trim()) return;
            const btn = document.createElement('button');
            btn.type = 'button';
            btn.className = 'cmt-block-btn';
            btn.textContent = '＋';
            btn.title = '评论这一段';
            btn.addEventListener('click', (e) => {
                e.stopPropagation();
                openForm({ kind: 'block', quote: (block.textContent || '').slice(0, 120), block: index });
            });
            block.classList.add('cmt-block-host');
            block.appendChild(btn);
        });
    }

    // ---------- 已有评论标回正文 ----------
    const threads = [...pane.querySelectorAll('.cmt-thread')];

    const focusThread = (thread) => {
        if (!thread) return;
        pane.querySelectorAll('.cmt-thread.on').forEach((el) => el.classList.remove('on'));
        thread.classList.add('on');
        thread.scrollIntoView({ block: 'nearest', behavior: 'smooth' });
        thread.animate?.([{ background: 'var(--accent-soft)' }, { background: 'transparent' }], 900);
    };

    const markText = (thread, loose) => {
        const quote = thread.dataset.quote || '';
        if (!quote) return true;
        const prefix = loose ? '' : (thread.dataset.prefix || '');
        const suffix = loose ? '' : (thread.dataset.suffix || '');

        const walker = document.createTreeWalker(article, NodeFilter.SHOW_TEXT, {
            acceptNode(node) {
                if (!node.nodeValue) return NodeFilter.FILTER_REJECT;
                for (let el = node.parentElement; el && el !== article; el = el.parentElement) {
                    if (['PRE', 'CODE', 'SCRIPT', 'STYLE'].includes(el.tagName)) return NodeFilter.FILTER_REJECT;
                    if (el.classList.contains('cmt-mark')) return NodeFilter.FILTER_REJECT;
                }
                return NodeFilter.FILTER_ACCEPT;
            },
        });

        const nodes = [];
        while (walker.nextNode()) nodes.push(walker.currentNode);

        for (const node of nodes) {
            const value = node.nodeValue;
            let from = 0;
            while (from <= value.length) {
                const at = value.indexOf(quote, from);
                if (at < 0) break;
                if (prefix && value.slice(Math.max(0, at - prefix.length), at) !== prefix) {
                    from = at + 1;
                    continue;
                }
                if (suffix && value.slice(at + quote.length, at + quote.length + suffix.length) !== suffix) {
                    from = at + 1;
                    continue;
                }
                const range = document.createRange();
                range.setStart(node, at);
                range.setEnd(node, at + quote.length);
                const span = document.createElement('span');
                span.className = 'cmt-mark';
                span.title = '有评论';
                try {
                    range.surroundContents(span);
                } catch (e) {
                    return true;
                }
                span.addEventListener('click', () => focusThread(thread));
                return true;
            }
        }
        return false;
    };

    threads.forEach((thread) => {
        const kind = thread.dataset.kind || 'text';
        const index = thread.dataset.block;
        if (kind === 'block' && index !== '' && blocks[Number(index)]) {
            const block = blocks[Number(index)];
            block.classList.add('cmt-block-mark');
            block.addEventListener('click', (e) => {
                if (e.target.closest('.cmt-block-btn')) return;
                focusThread(thread);
            });
        } else if (!markText(thread, false)) {
            markText(thread, true);
        }
    });
})();
