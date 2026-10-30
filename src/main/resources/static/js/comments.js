// 注释：正文里有注释的地方画一条短虚下划线，点开是弹窗——弹窗里读这一处的全部注释，也能接着写。
// 选中文字 / 点段落左边的 ＋ 也是打开同一个弹窗，只是把引文和块序号先填好。
// 定位参考 Hypothesis 的 TextQuoteSelector（原文 + 前后文）与 PositionSelector（块序号）
(() => {
    const data = document.getElementById('comments');
    const article = document.querySelector('.markdown');
    if (!data || !article) return;

    const form = data.querySelector('.cmt-form');
    const loginHint = data.querySelector('.cmt-login');
    const textarea = form && form.querySelector('textarea[name="body"]');
    const field = (name) => (form ? form.querySelector(`[name="${name}"]`) : null);
    const blocks = [...article.children];
    const canComment = !!form;

    // 弹窗当前对着哪段引文：那段原文要一直高亮着，所以不能用「清掉选区」来收起工具条，
    // 改成记住这段引文，对同一段不再弹工具条
    let openWith = '';

    const isNarrow = () => window.matchMedia('(max-width: 820px)').matches;

    // 正在注释的那段原文要高亮着。原生选区一旦把光标点进输入框就没了，
    // 所以再用 CSS Custom Highlight 画一层底（Edge/Chrome 105+、Safari 17+），不支持就算了
    const paintAnchor = (range) => {
        if (!range || !window.CSS || !CSS.highlights || typeof Highlight === 'undefined') return;
        try {
            CSS.highlights.set('cmt-anchor', new Highlight(range));
        } catch (e) {
            // 老浏览器：只剩原生选区那一层高亮
        }
    };
    const unpaintAnchor = () => {
        if (window.CSS && CSS.highlights) {
            try {
                CSS.highlights.delete('cmt-anchor');
            } catch (e) {}
        }
    };

    // ---------- 弹窗 ----------
    const pop = document.createElement('div');
    pop.className = 'cmt-pop';
    pop.hidden = true;
    document.body.appendChild(pop);

    const list = document.createElement('div');
    list.className = 'cmt-pop-list';
    pop.appendChild(list);
    if (form) {
        form.hidden = false;
        pop.appendChild(form);
    }
    if (loginHint) {
        loginHint.hidden = canComment;
        pop.appendChild(loginHint);
    }

    const place = (rect) => {
        const r = rect || { left: 40, top: 120, width: 0, bottom: 140, height: 0 };
        pop.hidden = false;
        const p = pop.getBoundingClientRect();
        let left = r.left + r.width / 2 - p.width / 2;
        left = Math.max(10, Math.min(left, window.innerWidth - p.width - 10));
        let top = (r.bottom || 0) + 8;
        if (top + p.height > window.innerHeight - 10) {
            top = Math.max(10, (r.top || 0) - p.height - 8);
        }
        pop.style.left = `${Math.round(left)}px`;
        pop.style.top = `${Math.round(top)}px`;
    };

    const setFields = (a) => {
        if (!form) return;
        const put = (name, value) => {
            const el = field(name);
            if (el) el.value = value == null ? '' : String(value);
        };
        put('quote', a.quote);
        put('anchorKind', a.kind);
        put('blockIndex', a.block);
        put('prefix', a.prefix);
        put('suffix', a.suffix);
    };

    /** anchor: {quote, kind, block, prefix, suffix, range}；rect: 弹窗贴着的位置；thread: 这一处已有的注释 */
    const openPop = (anchor, rect, thread) => {
        list.textContent = '';
        if (thread) {
            thread.querySelectorAll('.cmt-card').forEach((card) => {
                const clone = card.cloneNode(true);
                // 弹窗里不给「回复」入口，其余（自己写的删除）保留
                clone.querySelectorAll('.cmt-reply').forEach((el) => el.remove());
                list.appendChild(clone);
            });
        }
        setFields(anchor);
        if (textarea) textarea.value = '';
        openWith = anchor.quote || '';
        place(rect);
        if (anchor.range) {
            paintAnchor(anchor.range);
        } else {
            unpaintAnchor();
        }
        if (textarea) {
            setTimeout(() => textarea.focus(), isNarrow() ? 60 : 0);
        }
    };

    const closePop = () => {
        pop.hidden = true;
        openWith = '';
        setFields({});
        if (textarea) textarea.value = '';
        unpaintAnchor();
        const sel = window.getSelection();
        sel && sel.removeAllRanges();
    };

    const closeBtn = document.createElement('button');
    closeBtn.type = 'button';
    closeBtn.className = 'cmt-pop-close';
    closeBtn.setAttribute('aria-label', '关闭');
    closeBtn.textContent = '×';
    closeBtn.addEventListener('click', closePop);
    pop.appendChild(closeBtn);

    if (form) {
        const cancel = form.querySelector('.cmt-cancel');
        cancel && cancel.addEventListener('click', closePop);
        form.addEventListener('submit', () => {
            if (!field('anchorKind').value) field('anchorKind').value = 'text';
        });
    }

    document.addEventListener('mousedown', (e) => {
        if (pop.hidden || pop.contains(e.target)) return;
        // 点正文里的下划线不在这里关，交给它自己的 click 换内容
        if (e.target.closest && e.target.closest('.cmt-mark, .cmt-block-mark')) return;
        closePop();
    });
    document.addEventListener('keydown', (e) => {
        if (e.key === 'Escape') closePop();
    });
    window.addEventListener('scroll', closePop, { passive: true });

    // ---------- 发完一条后 10 秒内不让再发 ----------
    // 服务端同样按 10 秒拦一次；这里只是别让人把字打完再被丢掉
    const COOLDOWN = 10000;
    const LAST_KEY = 'cmt:last';
    const submitButtons = [...document.querySelectorAll('.cmt-submit')];
    const lastPost = () => {
        try {
            return Number(window.localStorage.getItem(LAST_KEY) || 0);
        } catch (e) {
            return 0;
        }
    };
    const lockSubmit = (ms) => {
        submitButtons.forEach((b) => {
            b.disabled = true;
        });
        setTimeout(() => submitButtons.forEach((b) => {
            b.disabled = false;
        }), ms);
    };
    const left = COOLDOWN - (Date.now() - lastPost());
    if (left > 0) lockSubmit(left);
    document.addEventListener('submit', () => {
        try {
            window.localStorage.setItem(LAST_KEY, String(Date.now()));
        } catch (e) {
            // 隐私模式下写不进去就算了，服务端还拦着一道
        }
    });

    // ---------- 选中文字后的浮动工具条 ----------
    let lastRange = null;
    let lastQuote = '';

    const tool = document.createElement('div');
    tool.className = 'cmt-tool';
    tool.hidden = true;
    const toolBtn = document.createElement('button');
    toolBtn.type = 'button';
    toolBtn.textContent = '注释';
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

    // 段落正文：要先把自己插进去的那些 ＋ 按钮摘掉，不然引文里会带一个「＋」
    const textOf = (block) => {
        if (!block) return '';
        const clone = block.cloneNode(true);
        clone.querySelectorAll('.cmt-block-btn').forEach((b) => b.remove());
        return clone.textContent || '';
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
        // 弹窗已经对着这一段了，就别再弹工具条（选区要留着当高亮）
        if (openWith && !pop.hidden && text === openWith) {
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
        const index = lastRange ? blockOf(lastRange.startContainer) : -1;
        const block = index >= 0 ? blocks[index] : null;
        const blockText = textOf(block);
        const at = blockText.indexOf(quote);
        const range = lastRange ? lastRange.cloneRange() : null;
        const rect = range ? range.getBoundingClientRect() : null;
        // 只收工具条，不动选区：那段字要一直高亮着，用户才知道自己在注释哪一段。
        // 工具条不会因为 focus 挪进输入框再弹出来——showTool 里认 openWith 这一段
        hideTool();
        lastQuote = '';
        lastRange = null;
        if (!quote) return;
        openPop({
            kind: 'text',
            quote,
            prefix: at > 0 ? blockText.slice(Math.max(0, at - 20), at) : '',
            suffix: at >= 0 ? blockText.slice(at + quote.length, at + quote.length + 20) : '',
            block: index,
            range,
        }, rect);
    });

    // 点空白处要把工具条和选区一起收掉。
    // 有些位置（按钮、块间距、容器边缘）浏览器不会自己清掉旧选区，mouseup 之后 showTool
    // 看到选区还在，又把工具条画回来——所以要自己判断「按下到松开之间有没有产生新选区」。
    let down = null;
    const selected = () => (window.getSelection() ? window.getSelection().toString() : '');

    const onDown = (e) => {
        if (tool.contains(e.target) || pop.contains(e.target)) return;
        down = { x: e.clientX || 0, y: e.clientY || 0, text: selected() };
        hideTool();
    };

    const onUp = (e) => {
        if (!down) return;
        const moved = Math.abs((e.clientX || 0) - down.x) + Math.abs((e.clientY || 0) - down.y) > 4;
        const same = selected() === down.text;
        down = null;
        if (moved) return; // 拖选，交给 showTool
        const sel = window.getSelection();
        if (!sel || sel.isCollapsed) {
            hideTool();
            return;
        }
        if (same) {
            // 只是点了一下空白，旧选区还原样挂着：手动清掉
            sel.removeAllRanges();
            hideTool();
        }
    };

    document.addEventListener('mousedown', onDown);
    document.addEventListener('mouseup', onUp);
    document.addEventListener('touchstart', onDown, { passive: true });
    document.addEventListener('touchend', onUp, { passive: true });
    // 滚动时工具条别跟着飘
    window.addEventListener('scroll', hideTool, { passive: true });

    // ---------- 整段 / 代码块左侧的 ＋ ----------
    if (canComment) {
        blocks.forEach((block, index) => {
            if (!block.textContent || !block.textContent.trim()) return;
            const btn = document.createElement('button');
            btn.type = 'button';
            btn.className = 'cmt-block-btn';
            btn.textContent = '＋';
            btn.title = '注释这一段';
            btn.addEventListener('click', (e) => {
                e.stopPropagation();
                hideTool();
                let range = null;
                try {
                    range = document.createRange();
                    range.selectNodeContents(block);
                } catch (err) {
                    range = null;
                }
                openPop({
                    kind: 'block',
                    quote: textOf(block).slice(0, 120),
                    block: index,
                    range,
                }, block.getBoundingClientRect());
            });
            block.classList.add('cmt-block-host');
            block.appendChild(btn);
        });
    }

    // ---------- 已有注释标回正文 ----------
    const threads = [...data.querySelectorAll('.cmt-thread')];

    const anchorOf = (thread) => ({
        kind: thread.dataset.kind || 'text',
        quote: thread.dataset.quote || '',
        block: thread.dataset.block,
        prefix: thread.dataset.prefix || '',
        suffix: thread.dataset.suffix || '',
    });

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
                span.title = '有注释';
                try {
                    range.surroundContents(span);
                } catch (e) {
                    return true;
                }
                span.addEventListener('click', (e) => {
                    e.stopPropagation();
                    const r = span.getBoundingClientRect();
                    const a = anchorOf(thread);
                    a.range = null;
                    openPop(a, r, thread);
                });
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
                if (e.target.closest('.cmt-block-btn') || e.target.closest('.cmt-pop')) return;
                const sel = window.getSelection();
                if (sel && !sel.isCollapsed) return; // 正在选字，别弹窗打断
                const a = anchorOf(thread);
                let range = null;
                try {
                    range = document.createRange();
                    range.selectNodeContents(block);
                } catch (err) {
                    range = null;
                }
                a.range = range;
                openPop(a, block.getBoundingClientRect(), thread);
            });
        } else if (!markText(thread, false)) {
            markText(thread, true);
        }
    });
})();
