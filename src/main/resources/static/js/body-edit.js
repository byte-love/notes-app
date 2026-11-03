// 正文页的就地编辑：点「编辑」把正文换成 Markdown 源码，改完保存回这一页
(() => {
    const btn = document.getElementById('body-edit');
    const box = document.getElementById('body-editor');
    const view = document.querySelector('.doc .markdown');
    if (!btn || !box || !view) return;

    const source = box.querySelector('.body-source');
    const meta = document.querySelector('meta[name="_csrf"]');
    const csrf = meta ? meta.getAttribute('content') : '';

    const open = () => {
        view.hidden = true;
        box.hidden = false;
        source.focus();
        // 光标放到开头附近，改错别字时不用从文末往上翻
        source.setSelectionRange(0, 0);
        source.scrollTop = 0;
    };

    const close = () => {
        view.hidden = false;
        box.hidden = true;
        btn.textContent = '编辑';
    };

    btn.addEventListener('click', () => {
        if (box.hidden) {
            open();
            btn.textContent = '收起';
        } else {
            close();
        }
    });

    box.addEventListener('click', async (e) => {
        const act = e.target.closest('[data-act]');
        if (!act) return;
        if (act.dataset.act === 'cancel') {
            close();
            return;
        }
        act.disabled = true;
        act.textContent = '保存中…';
        try {
            await fetch(box.dataset.save, {
                method: 'POST',
                credentials: 'same-origin',
                headers: { 'content-type': 'application/x-www-form-urlencoded' },
                body: new URLSearchParams({
                    _csrf: csrf,
                    body: source.value,
                    back: location.pathname,
                }).toString(),
            });
            location.reload();
        } catch (err) {
            act.disabled = false;
            act.textContent = '保存';
        }
    });

    source.addEventListener('keydown', (e) => {
        if (e.key === 'Escape') close();
    });
})();
