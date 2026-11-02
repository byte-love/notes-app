// 列表页：① 滚到底取下一批 ② 分类标题可收起/展开，状态记在 localStorage
// ③ 大分类里的二级分档做成标签行：点标签展开那一档，一次只开一档
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

    // 二级分档：记住每个分组上次打开的那一档（只认第一档，默认全关）
    const openSub = (group, chipKey) => {
        group.querySelectorAll('.subgroup-chip').forEach((c) => {
            c.classList.toggle('on', chipKey != null && c.dataset.chip === chipKey);
        });
        group.querySelectorAll('.subgroup-panel').forEach((p) => {
            p.classList.toggle('on', chipKey != null && p.dataset.panel === chipKey);
        });
    };

    const applySubOpen = (group) => {
        const key = group.dataset.key;
        if (!key) return;
        let saved = null;
        try {
            saved = localStorage.getItem(SUB_KEY + key);
        } catch (e) {}
        if (saved) openSub(group, saved);
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
            const willOpen = !chip.classList.contains('on');
            openSub(group, willOpen ? key : null);
            const gk = group.dataset.key;
            if (gk) {
                try {
                    if (willOpen) localStorage.setItem(SUB_KEY + gk, key);
                    else localStorage.removeItem(SUB_KEY + gk);
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
            const res = await fetch(`${base}?from=${encodeURIComponent(from)}&fragment=true`, {
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
