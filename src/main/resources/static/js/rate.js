// 星级：点第 n 颗设成 n 星，再点当前那一颗清回 0 星
// 列表页还可以用键盘刷：鼠标停在哪张卡上，按 1 / 2 / 3 打星，按 0 清空
// 只改 data-v，颜色由 CSS 按它上色；请求失败就不动，别让页面和服务端对不上
(() => {
    // 鼠标现在停在哪张卡片上（键盘刷星用），移开卡片就清掉
    let current = null;
    document.addEventListener('mouseover', (e) => {
        const li = e.target.closest('.item-list li');
        current = li ? li.querySelector('.rate:not(.readonly)') : null;
    }, { passive: true });

    // 左侧目录里同一篇的那几颗星跟着改，不用等刷新
    const syncTree = (slug, value) => {
        const node = document.querySelector('.tree .tree-rate[data-slug="' + slug + '"]');
        if (!node) return;
        node.dataset.v = String(value);
        node.textContent = '★'.repeat(value);
    };

    const apply = async (box, next) => {
        if (!box || !box.dataset.url || box.dataset.busy === '1') return;
        box.dataset.busy = '1';
        try {
            const res = await App.post(box.dataset.url, { rating: String(next) }, {
                headers: { 'X-Requested-With': 'fetch' },
            });
            if (!res.ok) return;
            box.dataset.v = String(next);
            syncTree(box.dataset.url.split('/').pop(), next);
        } catch (err) {
            // 网络断了就保持原样，下一次再试
        } finally {
            box.dataset.busy = '';
        }
    };

    const valueOf = (box) => Number(box.dataset.v || 0);

    document.addEventListener('click', (e) => {
        const star = e.target.closest('.rate-star');
        if (!star) return;
        const box = star.closest('.rate');
        if (!box || box.classList.contains('readonly') || !box.dataset.url) return;
        const value = Number(star.dataset.v);
        apply(box, valueOf(box) === value ? 0 : value);
    });

    document.addEventListener('keydown', (e) => {
        if (e.ctrlKey || e.metaKey || e.altKey || e.shiftKey) return;
        if (!['0', '1', '2', '3'].includes(e.key)) return;
        const t = e.target;
        if (t && (t.isContentEditable || /^(input|textarea|select)$/i.test(t.tagName || ''))) return;
        // 详情页只有一颗星控件，不在卡片上时也认它
        const box = current || document.querySelector('.doc-meta .rate:not(.readonly)');
        if (!box || !box.dataset.url) return;
        e.preventDefault();
        apply(box, Number(e.key));
    });
})();
