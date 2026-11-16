/*
 * 正文页的「整理排版」：作者读自己的旧笔记时，一键把塌掉的层级重建回来。
 * 原始 Markdown 由模板塞在 <template id="raw-body"> 里（只有作者才渲染）；
 * 保存走和就地编辑同一个接口（.doc 上的 data-save）。
 */
(() => {
    'use strict';
    const btn = document.getElementById('doc-tidy');
    const tpl = document.getElementById('raw-body');
    if (!btn || !tpl || !window.MdTidy) return;

    const saveHost = document.querySelector('[data-save]');

    btn.addEventListener('click', async () => {
        const raw = tpl.content.textContent || '';
        const next = window.MdTidy.tidy(raw);
        if (next === raw) return;
        if (!window.confirm('整理排版只改格式、不改文字，确定吗？')) return;
        if (!saveHost) return;
        btn.disabled = true;
        try {
            await App.post(saveHost.dataset.save, { body: next, back: location.pathname });
            location.reload();
        } catch (e) {
            btn.disabled = false;
        }
    });
})();
