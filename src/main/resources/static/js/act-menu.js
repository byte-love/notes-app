/*
 * 「⋯」功能菜单：把一组小动作（收藏 / 加书签 / 插入笔记卡片 / 插入图片 / 整理排版…）收进一个按钮。
 * 用法：<button data-act-menu="菜单元素 id">⋯</button> + <div class="act-menu" id="...">…
 */
(() => {
    'use strict';
    const triggers = [...document.querySelectorAll('[data-act-menu]')];
    if (!triggers.length) return;

    const close = (btn, menu) => {
        menu.hidden = true;
        btn.setAttribute('aria-expanded', 'false');
    };

    const closeOthers = (keepMenu) => {
        triggers.forEach((btn) => {
            const menu = document.getElementById(btn.dataset.actMenu);
            if (menu && menu !== keepMenu) close(btn, menu);
        });
    };

    triggers.forEach((btn) => {
        const menu = document.getElementById(btn.dataset.actMenu);
        if (!menu) return;

        btn.addEventListener('click', (e) => {
            e.stopPropagation();
            const willOpen = menu.hidden;
            closeOthers(willOpen ? menu : null);
            menu.hidden = !willOpen;
            btn.setAttribute('aria-expanded', String(willOpen));
        });

        // 点了菜单里的普通按钮就收起（表单按钮除外：提交后整页会跳走）
        menu.addEventListener('click', (e) => {
            const act = e.target.closest('button');
            if (act && !act.closest('form')) close(btn, menu);
        });

        document.addEventListener('click', (e) => {
            if (!btn.contains(e.target) && !menu.contains(e.target)) close(btn, menu);
        });

        document.addEventListener('keydown', (e) => {
            if (e.key === 'Escape') close(btn, menu);
        });
    });
})();
