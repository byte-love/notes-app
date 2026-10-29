// 列表页滚到底就取下一批：服务端按整组切好，这里只管把返回的那段插进去
(() => {
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
        // 提前 600px 开始取，滚到之前就准备好了
        const io = new IntersectionObserver((entries) => {
            if (entries.some((e) => e.isIntersecting)) {
                io.disconnect();
                load().then(watch);
            }
        }, { rootMargin: '600px 0px' });
        io.observe(more);
    };

    if (document.readyState === 'loading') {
        document.addEventListener('DOMContentLoaded', watch);
    } else {
        watch();
    }
})();
