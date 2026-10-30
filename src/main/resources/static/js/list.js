// 列表页：① 滚到底取下一批 ② 分类标题可收起/展开，状态记在 localStorage
(() => {
    // ---------- 分类折叠 ----------
    const KEY = 'group-fold:';

    const applyFold = (group) => {
        const key = group.dataset.key;
        if (!key) return;
        try {
            if (localStorage.getItem(KEY + key) === '1') group.classList.add('folded');
        } catch (e) {}
    };

    const applyFolds = (root) => {
        (root || document).querySelectorAll('.item-group[data-key]').forEach(applyFold);
    };

    document.addEventListener('click', (e) => {
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
