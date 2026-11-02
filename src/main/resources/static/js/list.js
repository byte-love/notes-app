// 列表页：① 滚到底取下一批 ② 分类标题可收起/展开，状态记在 localStorage
// ③ 大分类里的二级分档是这组卡片的筛选标签：永远有一档被选中，所以点开任何一组看到的都是卡片
(() => {
    // ---------- 分类折叠 ----------
    const KEY = 'group-fold:';
    const SUB_KEY = 'sub-open:';

    const applyFold = (group) => {
        const key = group.dataset.key;
        if (!key) return;
        try {
            if (localStorage.getItem(KEY + key) === '1') group.classList.add('folded');
        } catch (e) {}
    };

    // 二级分档：切到某一档（不提供「全关」，否则又变成「点开是空的分类列表」）
    const openSub = (group, chipKey) => {
        const chips = [...group.querySelectorAll('.subgroup-chip')];
        if (!chips.length) return;
        const exists = chips.some((c) => c.dataset.chip === chipKey);
        const target = exists ? chipKey : chips[0].dataset.chip;
        chips.forEach((c) => c.classList.toggle('on', c.dataset.chip === target));
        group.querySelectorAll('.subgroup-panel').forEach((p) => {
            p.classList.toggle('on', p.dataset.panel === target);
        });
    };

    const applySubOpen = (group) => {
        const chips = [...group.querySelectorAll('.subgroup-chip')];
        if (!chips.length) return;
        const key = group.dataset.key;
        let saved = null;
        if (key) {
            try {
                saved = localStorage.getItem(SUB_KEY + key);
            } catch (e) {}
        }
        // 没记过、或记的那一档已经不存在（规则改了）→ 默认选第一档
        openSub(group, saved);
    };

    const applyFolds = (root) => {
        (root || document).querySelectorAll('.item-group[data-key]').forEach((g) => {
            applyFold(g);
            applySubOpen(g);
        });
    };

    document.addEventListener('click', (e) => {
        const chip = e.target.closest('.subgroup-chip');
        if (chip) {
            const group = chip.closest('.item-group');
            if (!group) return;
            const key = chip.dataset.chip;
            openSub(group, key);
            const gk = group.dataset.key;
            if (gk) {
                try {
                    localStorage.setItem(SUB_KEY + gk, key);
                } catch (err) {}
            }
            return;
        }
        const toggle = e.target.closest('.item-group-toggle');
        if (!toggle) return;
        const group = toggle.closest('.item-group');
        if (!group) return;
        group.classList.toggle('folded');
        const key = group.dataset.key;
        if (!key) return;
        try {
            localStorage.setItem(KEY + key, group.classList.contains('folded') ? '1' : '0');
        } catch (e) {}
    });

    // ---------- 分批加载 ----------
    const load = async () => {
        const more = document.getElementById('list-more');
        if (!more || more.dataset.busy === '1') return;
        const base = more.dataset.base;
        const from = more.dataset.from;
        if (!base || !from) return;
        more.dataset.busy = '1';
        try {
            // 星级筛选得跟着翻页一起带过去，否则第二批又是全部
            const rate = more.dataset.rate;
            const query = `from=${encodeURIComponent(from)}&fragment=true`
                + (rate ? `&rate=${encodeURIComponent(rate)}` : '');
            const res = await fetch(`${base}?${query}`, {
                credentials: 'same-origin',
                headers: { 'X-Requested-With': 'fetch' },
            });
            if (!res.ok) {
                more.dataset.busy = '';
                return;
            }
            const html = await res.text();
            const holder = document.createElement('div');
            holder.innerHTML = html;
            // 返回的那段自带新的锚点（或没有锚点=到底了），整段插到旧锚点前面
            while (holder.firstElementChild) {
                more.parentNode.insertBefore(holder.firstElementChild, more);
            }
            more.remove();
            // 新插进来的分类也要套用记住的折叠状态
            applyFolds();
        } catch (e) {
            more.dataset.busy = '';
        }
    };

    const watch = () => {
        const more = document.getElementById('list-more');
        if (!more) return;
        if (!('IntersectionObserver' in window)) {
            load();
            return;
        }
        // 离底 300px 开始取：顺手就绪，又不会一进页面就把下一批拉下来
        const io = new IntersectionObserver((entries) => {
            if (entries.some((e) => e.isIntersecting)) {
                io.disconnect();
                load().then(watch);
            }
        }, { rootMargin: '300px 0px' });
        io.observe(more);
    };

    // 等布局稳定再挂观察器，否则首屏就可能被判定成「到底了」
    const start = () => {
        applyFolds();
        if (document.readyState === 'complete') watch();
        else window.addEventListener('load', watch, { once: true });
    };
    start();
})();
