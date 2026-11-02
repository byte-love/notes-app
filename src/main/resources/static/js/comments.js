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

    // 输入框里正对着的那段引文：选中的原文要高亮留着，用户才知道自己在评论哪一段，
    // 所以不能用「清掉选区」来收起工具条，改成记住这段引文、对同一段不再弹工具条
    let openWith = '';

    const isNarrow = () => window.matchMedia('(max-width: 820px)').matches;

    // 正在评论的那段原文要高亮着。原生选区一旦把光标点进输入框就没了，
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

    // ---------- 注释：正文短虚下划线 + 弹窗（和右栏评论并行的另一条线）----------
    const annData = document.getElementById('annotations');
    const annForm = annData && annData.querySelector('.ann-form');
    const annTextarea = annForm && annForm.querySelector('textarea[name="body"]');
    const annList = document.createElement('div');
    annList.className = 'ann-pop-list';

    const pop = document.createElement('div');
    pop.className = 'ann-pop';
    pop.hidden = true;
    if (annData) {
        pop.appendChild(annList);
        if (annForm) {
            annForm.hidden = false;
            pop.appendChild(annForm);
        }
        const closeBtn = document.createElement('button');
        closeBtn.type = 'button';
        closeBtn.className = 'ann-pop-close';
        closeBtn.setAttribute('aria-label', '关闭');
        closeBtn.textContent = '×';
        closeBtn.addEventListener('click', () => closePop());
        pop.appendChild(closeBtn);
        document.body.appendChild(pop);
    }

    // 弹窗正对着哪段引文：这一段不再弹工具条，选区留着当高亮
    let popOpenWith = '';

    const placePop = (rect) => {
        const r = rect || { left: 40, top: 120, width: 0, bottom: 140 };
        pop.hidden = false;
        const p = pop.getBoundingClientRect();
        let l = r.left + r.width / 2 - p.width / 2;
        l = Math.max(10, Math.min(l, window.innerWidth - p.width - 10));
        let t = (r.bottom || 0) + 8;
        if (t + p.height > window.innerHeight - 10) {
            t = Math.max(10, (r.top || 0) - p.height - 8);
        }
        pop.style.left = `${Math.round(l)}px`;
        pop.style.top = `${Math.round(t)}px`;
    };

    const setAnn = (name, value) => {
        if (!annForm) return;
        const el = annForm.querySelector(`[name="${name}"]`);
        if (el) el.value = value == null ? '' : String(value);
    };

    const closePop = () => {
        if (!pop) return;
        pop.hidden = true;
        popOpenWith = '';
        ['quote', 'anchorKind', 'blockIndex', 'prefix', 'suffix'].forEach((n) => setAnn(n, ''));
        if (annTextarea) annTextarea.value = '';
        unpaintAnchor();
    };

    /** 打开注释弹窗：列这一处已有的注释，并给出写新注释的输入框 */
    const openPop = (anchor, rect, thread) => {
        if (!pop) return;
        annList.textContent = '';
        if (thread) {
            thread.querySelectorAll('.cmt-card').forEach((card) => {
                annList.appendChild(card.cloneNode(true));
            });
        }
        setAnn('quote', anchor.quote);
        setAnn('anchorKind', anchor.kind);
        setAnn('blockIndex', anchor.block);
        setAnn('prefix', anchor.prefix);
        setAnn('suffix', anchor.suffix);
        if (annTextarea) annTextarea.value = '';
        popOpenWith = anchor.quote || '';
        placePop(rect);
        if (anchor.range) {
            paintAnchor(anchor.range);
        } else {
            unpaintAnchor();
        }
        if (annTextarea) setTimeout(() => annTextarea.focus(), 0);
    };

    if (annForm) {
        const cancel = annForm.querySelector('.ann-cancel');
        cancel && cancel.addEventListener('click', () => closePop());
    }
    document.addEventListener('mousedown', (e) => {
        if (!pop || pop.hidden || pop.contains(e.target)) return;
        if (e.target.closest && e.target.closest('.ann-mark, .cmt-mark')) return;
        closePop();
    });
    document.addEventListener('keydown', (e) => {
        if (e.key === 'Escape') closePop();
    });
    window.addEventListener('scroll', () => closePop(), { passive: true });

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
        if (anchor.range) {
            paintAnchor(anchor.range);
        } else {
            unpaintAnchor();
        }
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
        // 取消就把选中高亮也一起收掉
        openWith = '';
        unpaintAnchor();
        const sel = window.getSelection();
        sel && sel.removeAllRanges();
    };

    if (form) {
        const cancel = form.querySelector('.cmt-cancel');
        cancel && cancel.addEventListener('click', closeForm);
        // 引文框右上角的 × 等于取消：连输入框一起收起来，不只是把引文清掉
        const clearBtn = form.querySelector('.cmt-anchor-clear');
        clearBtn && clearBtn.addEventListener('click', closeForm);
        form.addEventListener('submit', () => {
            if (!field('anchorKind').value) field('anchorKind').value = 'text';
        });
        // 右栏头部的 ＋：评论整篇
        const newBtn = pane.querySelector('.cmt-new');
        newBtn && newBtn.addEventListener('click', () => openForm({ kind: 'text', quote: '' }));
    }

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
    toolBtn.textContent = '评论';
    tool.appendChild(toolBtn);
    // 第二个选项：注释（正文里短虚下划线那种，走弹窗，不进右栏）
    const annBtn = document.createElement('button');
    annBtn.type = 'button';
    annBtn.textContent = '注释';
    if (annData) tool.appendChild(annBtn);
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
        // 输入框 / 注释弹窗已经对着这一段了，就别再弹工具条（选区要留着当高亮）
        if (openWith && form && !form.hidden && text === openWith) {
            hideTool();
            return;
        }
        if (popOpenWith && pop && !pop.hidden && text === popOpenWith) {
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

    // 同一个选区，两个去处：评论进右栏、注释进弹窗。先把选区和引文算好再分叉
    const currentAnchor = () => {
        const quote = lastQuote || (window.getSelection() ? window.getSelection().toString().trim() : '');
        const index = lastRange ? blockOf(lastRange.startContainer) : -1;
        const block = index >= 0 ? blocks[index] : null;
        const blockText = textOf(block);
        const at = blockText.indexOf(quote);
        return {
            kind: 'text',
            quote,
            prefix: at > 0 ? blockText.slice(Math.max(0, at - 20), at) : '',
            suffix: at >= 0 ? blockText.slice(at + quote.length, at + quote.length + 20) : '',
            block: index,
            range: lastRange ? lastRange.cloneRange() : null,
        };
    };

    toolBtn.addEventListener('click', () => {
        const anchor = currentAnchor();
        const { quote } = anchor;
        // 只收工具条，不动选区：那段字要一直高亮着，用户才知道自己在评论哪一段。
        // 工具条不会因为 focus 挪进 textarea 再弹出来——showTool 里认 openWith 这一段
        hideTool();
        lastQuote = '';
        lastRange = null;
        if (!quote) {
            openWith = '';
            unpaintAnchor();
            return;
        }
        openWith = quote;
        openForm(anchor);
    });

    annBtn.addEventListener('click', () => {
        if (!pop) return;
        const anchor = currentAnchor();
        const { quote } = anchor;
        hideTool();
        lastQuote = '';
        lastRange = null;
        if (!quote) {
            unpaintAnchor();
            return;
        }
        const rect = anchor.range ? anchor.range.getBoundingClientRect() : null;
        // 右栏的评论框开着就先收起来，别两个输入框同时开着
        if (form && !form.hidden) closeForm();
        closePop();
        openPop(anchor, rect);
    });

    // 点空白处要把工具条和选区一起收掉。
    // 有些位置（按钮、块间距、容器边缘）浏览器不会自己清掉旧选区，mouseup 之后 showTool
    // 看到选区还在，又把工具条画回来——所以要自己判断「按下到松开之间有没有产生新选区」。
    let down = null;
    const selected = () => (window.getSelection() ? window.getSelection().toString() : '');

    const onDown = (e) => {
        if (tool.contains(e.target)) return;
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
            btn.title = '评论这一段';
            btn.addEventListener('click', (e) => {
                e.stopPropagation();
                hideTool();
                // 整段评论也把这一段的底铺上，跟选中文字时一样能看出评论的是哪一段
                let range = null;
                try {
                    range = document.createRange();
                    range.selectNodeContents(block);
                } catch (err) {
                    range = null;
                }
                openForm({ kind: 'block', quote: textOf(block).slice(0, 120), block: index, range });
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

    const markText = (thread, cls, tip, onHit, loose) => {
        const quote = thread.dataset.quote || '';
        if (!quote) return true;
        const prefix = loose ? '' : (thread.dataset.prefix || '');
        const suffix = loose ? '' : (thread.dataset.suffix || '');

        const walker = document.createTreeWalker(article, NodeFilter.SHOW_TEXT, {
            acceptNode(node) {
                if (!node.nodeValue) return NodeFilter.FILTER_REJECT;
                for (let el = node.parentElement; el && el !== article; el = el.parentElement) {
                    if (['PRE', 'CODE', 'SCRIPT', 'STYLE'].includes(el.tagName)) return NodeFilter.FILTER_REJECT;
                    // 已经标过（评论 / 注释）的就不再套一层
                    if (el.classList.contains('cmt-mark') || el.classList.contains('ann-mark')) {
                        return NodeFilter.FILTER_REJECT;
                    }
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
                span.className = cls;
                span.title = tip;
                try {
                    range.surroundContents(span);
                } catch (e) {
                    return true;
                }
                span.addEventListener('click', onHit);
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
        } else {
            const hit = () => focusThread(thread);
            if (!markText(thread, 'cmt-mark', '有评论', hit, false)) {
                markText(thread, 'cmt-mark', '有评论', hit, true);
            }
        }
    });

    // ---------- 已有注释标回正文（短虚下划线，点开弹窗）----------
    const annThreads = annData ? [...annData.querySelectorAll('.ann-thread')] : [];
    annThreads.forEach((thread) => {
        const anchor = {
            kind: thread.dataset.kind || 'text',
            quote: thread.dataset.quote || '',
            block: thread.dataset.block || '',
            prefix: thread.dataset.prefix || '',
            suffix: thread.dataset.suffix || '',
            range: null,
        };
        const hit = (e) => {
            e.stopPropagation();
            // 同一处再点：把这一处的全部注释列出来，也能接着写
            const rect = e.currentTarget.getBoundingClientRect();
            if (form && !form.hidden) closeForm();
            openPop(anchor, rect, thread);
        };
        const blockIndex = anchor.block === '' ? -1 : Number(anchor.block);
        if (anchor.kind === 'block' && blockIndex >= 0 && blocks[blockIndex]) {
            // 整段 / 代码块上的注释：左边一条点线
            const block = blocks[blockIndex];
            block.classList.add('ann-block-mark');
            block.addEventListener('click', (e) => {
                if (e.target.closest('.cmt-block-btn') || e.target.closest('.ann-pop')) return;
                const sel = window.getSelection();
                if (sel && !sel.isCollapsed) return; // 正在选字，别弹窗打断
                hit(e);
            });
            return;
        }
        if (!markText(thread, 'ann-mark', '有注释', hit, false)) {
            markText(thread, 'ann-mark', '有注释', hit, true);
        }
    });
})();
