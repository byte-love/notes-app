// 星级：点第 n 颗设成 n 星，再点当前那一颗清回 0 星（增 / 改 / 删都在这里）
// 只改 data-v，颜色由 CSS 按它上色；请求失败就不动，别让页面和服务端对不上
(() => {
    const csrf = () => {
        const meta = document.querySelector('meta[name="_csrf"]');
        return meta ? meta.content : '';
    };

    document.addEventListener('click', async (e) => {
        const star = e.target.closest('.rate-star');
        if (!star) return;
        const box = star.closest('.rate');
        if (!box || box.classList.contains('readonly') || !box.dataset.url) return;
        if (box.dataset.busy === '1') return;

        const value = Number(star.dataset.v);
        const now = Number(box.dataset.v || 0);
        const next = now === value ? 0 : value;

        const body = new URLSearchParams();
        body.set('rating', String(next));
        body.set('_csrf', csrf());

        box.dataset.busy = '1';
        try {
            const res = await fetch(box.dataset.url, {
                method: 'POST',
                credentials: 'same-origin',
                headers: { 'X-Requested-With': 'fetch' },
                body,
            });
            if (res.ok) box.dataset.v = String(next);
        } catch (err) {
            // 网络断了就保持原样，下一次点再试
        } finally {
            box.dataset.busy = '';
        }
    });
})();
