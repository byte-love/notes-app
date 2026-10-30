// 评论锚点：可以对「选中一段文字」或「整段 / 整个代码块」发评论
// 定位参考 Hypothesis 的 TextQuoteSelector（原文 + 前后文）与 PositionSelector（块序号）
(() => {
    const pane = document.getElementById('comments');
    const article = document.querySelector('.markdown');
    const form = pane && pane.querySelector('.cmt-form');
    const canComment = !!form;

    const textarea = form && form.querySelector('textarea[name="body"]');
    const field = (name) => (form ? form.querySelector(`[name="${name}"]`) : null);
    const chip = form && form.querySelector('.cmt-anchor-chip');
    const chipText = chip && chip.querySelector('.cmt-anchor-text');
    const blocks = article ? [...article.children] : [];

    // ---------- 锚点状态 ----------
    const setAnchor = (anchor) => {
        if (!form) return;
        field('quote').value = anchor.quote || '';
        field('anchorKind').value = anchor.kind || '';
        field('blockIndex').value = anchor.block == null ? '' : String(anchor.block);
        field('prefix').value = anchor.prefix || '';
        field('suffix').value = anchor.suffix || '';
        if (chip && chipText) {
            chipText.textContent = anchor.quote ? anchor.quote.slice(0, 80) : '';
            chip.hidden = !anchor.quote;
        }
        pane.scrollTo({ top: 0, behavior: 'smooth' });
        textarea && textarea.focus();
    };

    const clearAnchor = () => {
        if (!form) return;
        ['quote', 'anchorKind', 'blockIndex', 'prefix', 'suffix'].forEach((n) => {
            const el = field(n);
            if (el) el.value = '';
        });
        if (chip) chip.hidden = true;
    };

    if (form) {
        const clearBtn = form.querySelector('.cmt-anchor-clear');
        clearBtn && clearBtn.addEventListener('click', clearAnchor);
        form.addEventListener('submit', () => {
            // 没选锚点就是评论整篇
            if (!field('anchorKind').value) field('anchorKind').value = 'text';
        });
    }

    // ---------- 选中文字 -> 浮出「评论」按钮 ----------
    if (canComment && article) {
        const btn = document.createElement('button');
        btn.type = 'button';
        btn.className = 'cmt-select-btn';
        btn.textContent = '评论';
        btn.hidden = true;
        document.body.appendChild(btn);

        let lastRange = null;

        const blockOf = (node) => {
            for (let el = node.nodeType === 1 ? node : node.parentElement; el; el = el.parentElement) {
                if (el.parentElement === article) return blocks.indexOf(el);
            }
            return -1;
        };

        const hideBtn = () => {
            btn.hidden = true;
        };

        document.addEventListener('selectionchange', () => {
            const sel = window.getSelection();
            if (!sel || sel.isCollapsed) {
                hideBtn();
                return;
            }
            const text = sel.toString().trim();
            if (!text || !article.contains(sel.anchorNode)) {
                hideBtn();
                return;
            }
            const range = sel.getRangeAt(0);
            const rect = range.getBoundingClientRect();
            if (!rect || (!rect.width && !rect.height)) {
                hideBtn();
                return;
            }
            lastRange = range.cloneRange();
            btn.hidden = false;
            btn.style.top = `${Math.min(window.innerHeight - 44, rect.bottom + window.scrollY + 6)}px`;
            btn.style.left = `${Math.max(12, rect.left + window.scrollX - 6)}px`;
        });

        btn.addEventListener('click', () => {
            if (!lastRange) return;
            const sel = window.getSelection();
            const quote = sel ? sel.toString().trim() : '';
            if (!quote) return;
            const index = blockOf(lastRange.startContainer);
            const block = index >= 0 ? blocks[index] : null;
            const blockText = block ? block.textContent || '' : '';
            const at = blockText.indexOf(quote);
            const prefix = at > 0 ? blockText.slice(Math.max(0, at - 20), at) : '';
            const suffix = at >= 0 ? blockText.slice(at + quote.length, at + quote.length + 20) : '';
            setAnchor({ kind: 'text', quote, prefix, suffix, block: index });
            hideBtn();
            sel && sel.removeAllRanges();
        });

        // 点到别处就收起按钮
        document.addEventListener('mousedown', (e) => {
            if (e.target !== btn) hideBtn();
        });
    }

    // ---------- 整块评论：每个顶层块左侧一个 ＋ ----------
    if (canComment && article) {
        const commentsOnBlock = (index, text) => {
            setAnchor({ kind: 'block', quote: text.slice(0, 120), prefix: '', suffix: '', block: index });
        };
        blocks.forEach((block, index) => {
            if (!block.textContent || !block.textContent.trim()) return;
            const btn = document.createElement('button');
            btn.type = 'button';
            btn.className = 'cmt-block-btn';
            btn.textContent = '＋';
            btn.title = '评论这一段';
            btn.addEventListener('click', (e) => {
                e.stopPropagation();
                commentsOnBlock(index, block.textContent || '');
            });
            block.classList.add('cmt-block-host');
            block.appendChild(btn);
        });
    }

    // ---------- 把已存在的评论标回正文 ----------
    const threads = pane ? [...pane.querySelectorAll('.cmt-thread')] : [];

    const focusThread = (thread) => {
        if (!thread) return;
        pane.querySelectorAll('.cmt-thread.on').forEach((el) => el.classList.remove('on'));
        thread.classList.add('on');
        thread.scrollIntoView({ block: 'nearest', behavior: 'smooth' });
        thread.animate?.([{ background: 'var(--accent-soft)' }, { background: 'transparent' }], 900);
    };

    const threadOf = (kind, index, quote) => threads.find((t) =>
        t.dataset.kind === kind
        && (t.dataset.block || '') === (index == null ? '' : String(index))
        && (t.dataset.quote || '') === (quote || ''))
        || threads.find((t) => (t.dataset.quote || '') === quote);

    /** 在正文里找到 quote（用前后文消歧），包成可点的下划线 */
    const markText = (thread, loose) => {
        const quote = thread.dataset.quote || '';
        if (!quote || !article) return;
        const prefix = loose ? '' : (thread.dataset.prefix || '');
        const suffix = loose ? '' : (thread.dataset.suffix || '');

        // 把候选块的文本节点串起来找
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
                    return;
                }
                span.addEventListener('click', () => focusThread(thread));
                return;
            }
        }
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
        } else {
            if (!markText(thread, false)) markText(thread, true);
        }
    });
})();
