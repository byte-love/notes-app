// 管理列表滚到底再取一批：服务端按 60 行切好，这里只管把返回的 <tr> 插到锚点前面
(() => {
    const load = async () => {
        const more = document.getElementById('admin-more');
        if (!more || more.dataset.busy === '1') return;
        const base = more.dataset.base;
        const from = more.dataset.from;
        if (!base || !from) return;
        more.dataset.busy = '1';
        try {
            const res = await fetch(`${base}${base.includes('?') ? '&' : '?'}from=${encodeURIComponent(from)}&fragment=true`, {
                credentials: 'same-origin',
                headers: { 'X-Requested-With': 'fetch' },
            });
            if (!res.ok) {
                more.dataset.busy = '';
                return;
            }
            const html = await res.text();
            // <tr> 必须放进 tbody 里解析，否则会被丢掉
            const holder = document.createElement('tbody');
            holder.innerHTML = html;
            while (holder.firstElementChild) {
                more.parentNode.insertBefore(holder.firstElementChild, more);
            }
            more.remove();
        } catch (e) {
            more.dataset.busy = '';
        }
    };

    const watch = () => {
        const more = document.getElementById('admin-more');
        if (!more) return;
        if (!('IntersectionObserver' in window)) {
            load();
            return;
        }
        const io = new IntersectionObserver((entries) => {
            if (entries.some((e) => e.isIntersecting)) {
                io.disconnect();
                load().then(watch);
            }
        }, { rootMargin: '300px 0px' });
        io.observe(more);
    };

    // 等布局稳定再挂观察器，否则首屏就可能被判成「到底了」
    const start = () => (document.readyState === 'complete' ? watch() : window.addEventListener('load', watch, { once: true }));
    start();
})();
