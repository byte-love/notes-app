// 详情页的交互脚本：本页目录高亮、树折叠、书签、三栏拖拽
// 从 detail.html 的内联脚本抽出来，好让浏览器缓存（内联的话每次翻页都要重传）
    (() => {
        const links = [...document.querySelectorAll('.toc a')];
        if (!links.length) return;
        const byId = new Map(links.map((a) => [a.getAttribute('href').slice(1), a]));
        const targets = [...byId.keys()].map((id) => document.getElementById(id)).filter(Boolean);
        if (!targets.length) return;

        const OFFSET = 96;
        let active = null;

        const setActive = (a) => {
            if (!a || a === active) return;
            if (active) active.classList.remove('on');
            active = a;
            active.classList.add('on');
        };

        const update = () => {
            if (window.innerHeight + window.scrollY >= document.documentElement.scrollHeight - 4) {
                setActive(byId.get(targets[targets.length - 1].id));
                return;
            }
            let current = targets[0];
            for (const t of targets) {
                if (t.getBoundingClientRect().top <= OFFSET) current = t;
                else break;
            }
            setActive(byId.get(current.id));
        };

        let ticking = false;
        const onScroll = () => {
            if (ticking) return;
            ticking = true;
            requestAnimationFrame(() => {
                ticking = false;
                update();
            });
        };

        window.addEventListener('scroll', onScroll, { passive: true });
        window.addEventListener('resize', onScroll);
        update();
    })();

    (() => {
        const marks = document.querySelectorAll('.markdown [data-tip]');
        if (!marks.length) return;

        const tip = document.createElement('div');
        tip.className = 'gl-tip';
        document.body.appendChild(tip);

        let current = null;

        const show = (el) => {
            current = el;
            tip.textContent = el.getAttribute('data-tip');
            tip.style.left = '0px';
            tip.style.top = '0px';
            tip.classList.add('on');

            const r = el.getBoundingClientRect();
            const tw = tip.offsetWidth;
            const th = tip.offsetHeight;
            let left = r.left + r.width / 2 - tw / 2;
            left = Math.max(12, Math.min(left, window.innerWidth - tw - 12));
            let top = r.bottom + 8;
            if (top + th > window.innerHeight - 12) top = r.top - th - 8;
            tip.style.left = Math.round(left) + 'px';
            tip.style.top = Math.round(Math.max(12, top)) + 'px';
        };

        const hide = () => {
            current = null;
            tip.classList.remove('on');
        };

        const pick = (target) => (target.closest ? target.closest('.markdown [data-tip]') : null);

        document.addEventListener('mouseover', (e) => {
            const el = pick(e.target);
            if (el) {
                if (el !== current) show(el);
            } else if (current) {
                hide();
            }
        });

        document.addEventListener('mouseout', (e) => {
            const el = pick(e.target);
            if (el && el === current && !el.contains(e.relatedTarget)) hide();
        });

        document.addEventListener('click', (e) => {
            const el = pick(e.target);
            if (el) show(el);
            else hide();
        });

        window.addEventListener('scroll', hide, { passive: true });
        window.addEventListener('resize', hide);
    })();

    // 每个正文标题右边挂一个书签按钮，点一下把位置记下来；下次从「书签」页直接跳回来
    (() => {
        const box = document.querySelector('.markdown[data-slug]');
        if (!box) return;
        const token = document.querySelector('meta[name="_csrf"]')?.content;
        if (!token) return;

        const slug = box.dataset.slug;
        const mine = new Set((box.dataset.anchors || '').split(',').filter(Boolean));
        const heads = [...box.querySelectorAll('h2[id], h3[id]')];
        if (!heads.length) return;

        heads.forEach((h) => {
            const btn = document.createElement('button');
            btn.type = 'button';
            btn.className = 'head-bookmark' + (mine.has(h.id) ? ' on' : '');
            btn.title = mine.has(h.id) ? '已在此加书签' : '在这一块加书签';
            btn.setAttribute('aria-label', btn.title);
            btn.addEventListener('click', async (e) => {
                e.preventDefault();
                btn.disabled = true;
                try {
                    await fetch(`/app/bookmark/${encodeURIComponent(slug)}?anchor=${encodeURIComponent(h.id)}`, {
                        method: 'POST',
                        headers: { 'X-CSRF-TOKEN': token },
                        credentials: 'same-origin',
                    });
                    btn.classList.toggle('on');
                    btn.title = btn.classList.contains('on') ? '已在此加书签' : '在这一块加书签';
                } catch (err) {
                    /* 失败就不改状态 */
                } finally {
                    btn.disabled = false;
                }
            });
            h.appendChild(btn);
        });
    })();

    // 三栏联动：左栏用像素，中间和右边按比例分剩余宽度。
    // 拖左栏 -> 左栏变宽，中间和右边一起变窄，两者比例不变；
    // 拖右栏 -> 只改中间和右边的比例。宽度都记在 localStorage。
    (() => {
        const wrap = document.querySelector('.with-tree.has-tree');
        if (!wrap) return;
        const tree = wrap.querySelector('.tree-pane');
        const docWrap = wrap.querySelector('.doc-wrap');
        const toc = docWrap ? docWrap.querySelector('.toc') : null;
        if (!tree || !docWrap) return;

        const MIN_TREE = 140, MAX_TREE = 520;
        const MIN_TOC = 110, MAX_TOC = 380;

        const clamp = (v, lo, hi) => Math.min(hi, Math.max(lo, v));
        const read = (k, dflt) => {
            try {
                const v = parseFloat(localStorage.getItem(k));
                return isNaN(v) ? dflt : v;
            } catch (e) { return dflt; }
        };
        const save = (k, v) => {
            try { localStorage.setItem(k, String(v)); } catch (e) {}
        };

        // 默认宽度按视口比例：树 19%、右栏 12%（按用户给的截图量的比例，
        // 换算成 1300px 窗口就是 约 247px / 156px）。窄屏/超宽屏用括号里上下限兜住。
        // key 跟着换，老用户存在 localStorage 里的旧宽度不再盖掉新默认值
        const byViewport = (ratio, lo, hi) =>
            Math.round(Math.min(hi, Math.max(lo, window.innerWidth * ratio)));
        let treeW = read('layout:tree4', byViewport(0.19, 160, 380));
        // 右栏固定像素：拖左栏时它只是被推着走，宽度不变
        let tocW = read('layout:toc5', byViewport(0.12, 120, 300));

        // <=820 是单列堆叠、<=1100 没有右侧目录，这两种宽度下列宽交给 CSS 媒体查询；
        // 内联样式一旦盖上去，手机上正文会被挤成 0 宽
        const mqStack = window.matchMedia('(max-width: 820px)');
        const mqNoToc = window.matchMedia('(max-width: 1100px)');
        const stacked = () => mqStack.matches;
        const tocHidden = () => stacked() || mqNoToc.matches;

        const applyTree = () => {
            const w = clamp(treeW, MIN_TREE, MAX_TREE);
            wrap.style.gridTemplateColumns = stacked() ? '' : w + 'px minmax(0, 1fr)';
        };
        const applyToc = () => {
            if (!toc) return;
            const w = clamp(tocW, MIN_TOC, MAX_TOC);
            // 右栏固定像素宽度，正文列吃剩下的
            docWrap.style.gridTemplateColumns = tocHidden() ? '' : 'minmax(0, 1fr) ' + w + 'px';
        };

        applyTree();
        applyToc();

        const makeSplitter = (host, onMove, onDone) => {
            const bar = document.createElement('div');
            bar.className = 'splitter';
            host.appendChild(bar);

            let lastX = 0;
            const down = (e) => {
                lastX = e.touches ? e.touches[0].clientX : e.clientX;
                bar.classList.add('dragging');
                const move = (ev) => {
                    const x = ev.touches ? ev.touches[0].clientX : ev.clientX;
                    onMove(x - lastX);
                    lastX = x;
                    if (ev.cancelable) ev.preventDefault();
                };
                const up = () => {
                    bar.classList.remove('dragging');
                    document.removeEventListener('mousemove', move);
                    document.removeEventListener('mouseup', up);
                    document.removeEventListener('touchmove', move);
                    document.removeEventListener('touchend', up);
                    onDone();
                };
                document.addEventListener('mousemove', move);
                document.addEventListener('mouseup', up);
                document.addEventListener('touchmove', move, { passive: false });
                document.addEventListener('touchend', up);
                e.preventDefault();
            };
            bar.addEventListener('mousedown', down);
            bar.addEventListener('touchstart', down, { passive: false });
            return bar;
        };

        // 左：树目录的右边缘
        const leftBar = makeSplitter(wrap, (dx) => {
            treeW = clamp(treeW + dx, MIN_TREE, MAX_TREE);
            applyTree();
        }, () => save('layout:tree4', Math.round(treeW)));

        // 右：本页目录的左边缘（只改右栏自己的宽度）
        let rightBar = null;
        if (toc) {
            rightBar = makeSplitter(docWrap, (dx) => {
                tocW = clamp(tocW - dx, MIN_TOC, MAX_TOC);
                applyToc();
            }, () => save('layout:toc5', Math.round(tocW)));
        }

        const layout = () => {
            const wr = wrap.getBoundingClientRect();
            const tr = tree.getBoundingClientRect();
            // 分隔条是 .with-tree（position: relative）的绝对定位子元素，
            // left 一律按 wrap 的坐标算；原先右栏用 docWrap 的坐标，整体偏了一个 padding
            leftBar.style.left = (tr.right - wr.left) + 'px';
            // 单列堆叠时段落中间没有可拖的分界，藏掉
            leftBar.style.display = stacked() ? 'none' : '';

            if (rightBar && toc) {
                const rr = toc.getBoundingClientRect();
                rightBar.style.left = (rr.left - wr.left) + 'px';
                rightBar.style.display = tocHidden() ? 'none' : '';
            }
        };

        // 跨过断点时才需要重算列宽，普通 resize 只挪分隔条
        const relayout = () => { applyTree(); applyToc(); layout(); };
        mqStack.addEventListener('change', relayout);
        mqNoToc.addEventListener('change', relayout);

        layout();
        window.addEventListener('resize', layout);
        // 拖动时也要让分隔条跟着走
        const tick = () => { layout(); requestAnimationFrame(tick); };
        wrap.addEventListener('mousemove', layout);
        wrap.addEventListener('touchmove', layout, { passive: true });
        document.addEventListener('mouseup', layout);
        document.addEventListener('touchend', layout);
    })();

// 左侧目录：把当前这条滚进可见区；挡住它的分组顺手展开（和点按钮一样记住状态）
(() => {
    const pane = document.querySelector('.tree-pane');
    if (!pane) return;
    const active = pane.querySelector('a.on');
    if (!active) return;

    for (let li = active.closest('li'); li; li = li.parentElement ? li.parentElement.closest('li') : null) {
        if (!li.classList.contains('folded')) continue;
        li.classList.remove('folded');
        const btn = li.querySelector(':scope > .tree-group');
        if (btn && btn.dataset.key) {
            try { localStorage.setItem('tree-fold:' + btn.dataset.key, '0'); } catch (e) {}
        }
    }

    // 展开之后布局要重算，等一帧再滚
    requestAnimationFrame(() => {
        const pr = pane.getBoundingClientRect();
        const ar = active.getBoundingClientRect();
        if (!ar.height) return;
        if (ar.top < pr.top || ar.bottom > pr.bottom) {
            pane.scrollTop += ar.top - pr.top - (pr.height - ar.height) / 2;
        }
    });
})();

// 阅读进度：离开页面时把「滚到全文的百分之几」记到服务端，
// 下次打开同一条就滚回去（短于一屏的文章比例恒为 0，滚不滚都一样）
(() => {
    const box = document.querySelector('.markdown[data-slug]');
    if (!box) return;
    const slug = box.dataset.slug;
    const token = document.querySelector('meta[name="_csrf"]')?.content;
    if (!slug || !token) return;

    const scrollable = () => document.documentElement.scrollHeight - window.innerHeight;
    const ratio = () => {
        const max = scrollable();
        // 一屏能显示完就没有「进度」可言
        return max > 40 ? Math.min(1, Math.max(0, window.scrollY / max)) : 0;
    };

    // 带 #锚点 进来时听锚点的，别抢
    const saved = parseFloat(box.dataset.resume || '0');
    if (saved > 0.02 && !location.hash) {
        const back = () => window.scrollTo(0, Math.round(saved * scrollable()));
        requestAnimationFrame(back);
        // 图片/字体加载完高度会变，load 之后再校正一次
        window.addEventListener('load', back);
    }

    let last = saved;
    const save = () => {
        const now = ratio();
        if (Math.abs(now - last) < 0.01) return;
        last = now;
        const body = new URLSearchParams({ progress: now.toFixed(4), _csrf: token });
        const url = `/app/position/${encodeURIComponent(slug)}`;
        if (navigator.sendBeacon) {
            navigator.sendBeacon(url, body);
        } else {
            fetch(url, { method: 'POST', body, keepalive: true, credentials: 'same-origin' });
        }
    };

    document.addEventListener('visibilitychange', () => {
        if (document.hidden) save();
    });
    window.addEventListener('pagehide', save);
})();
