// 站内的确认弹窗。浏览器原生 confirm() 是系统画的，配色和站里完全不搭，
// 所以自己做一个：页面元素上写 data-confirm="提示语"（表单或按钮都认）就会走这里，
// JS 里需要确认时用 await uiConfirm('提示语')。
(() => {
    if (window.uiConfirm) return;

    let mask = null;
    let msgEl = null;
    let okBtn = null;
    let cancelBtn = null;
    let settle = null;
    let lastFocus = null;
    let savedOverflow = '';

    // 已经确认过、正在重新触发的元素：靠这个避免弹窗自己套住自己
    const bypass = new WeakSet();

    const close = (result) => {
        if (!settle) return;
        const done = settle;
        settle = null;
        mask.hidden = true;
        document.body.style.overflow = savedOverflow;
        if (lastFocus && lastFocus.isConnected) lastFocus.focus();
        lastFocus = null;
        done(result);
    };

    const build = () => {
        mask = document.createElement('div');
        mask.className = 'dlg-mask';
        mask.hidden = true;
        mask.innerHTML = '<div class="dlg" role="dialog" aria-modal="true">'
            + '<p class="dlg-msg"></p>'
            + '<div class="dlg-actions">'
            + '<button type="button" class="dlg-btn dlg-cancel">取消</button>'
            + '<button type="button" class="dlg-btn dlg-ok">确定</button>'
            + '</div></div>';
        document.body.appendChild(mask);

        msgEl = mask.querySelector('.dlg-msg');
        okBtn = mask.querySelector('.dlg-ok');
        cancelBtn = mask.querySelector('.dlg-cancel');

        okBtn.addEventListener('click', () => close(true));
        cancelBtn.addEventListener('click', () => close(false));
        mask.addEventListener('mousedown', (e) => {
            if (e.target === mask) close(false);
        });

        document.addEventListener('keydown', (e) => {
            if (!settle) return;
            if (e.key === 'Escape') {
                e.preventDefault();
                close(false);
            }
        });

        // 开着的时候别让 Tab 把焦点带到弹窗外面的链接上
        document.addEventListener('focusin', (e) => {
            if (settle && !mask.contains(e.target)) okBtn.focus();
        });
    };

    const open = (message, opts) => new Promise((resolve) => {
        if (settle) {
            resolve(false);
            return;
        }
        if (!mask) build();
        msgEl.textContent = message;
        okBtn.textContent = (opts && opts.ok) || '确定';
        cancelBtn.textContent = (opts && opts.cancel) || '取消';
        okBtn.classList.toggle('is-danger', !opts || opts.danger !== false);

        lastFocus = document.activeElement;
        savedOverflow = document.body.style.overflow;
        document.body.style.overflow = 'hidden';

        mask.hidden = false;
        settle = resolve;
        okBtn.focus();
    });

    window.uiConfirm = (message, opts) => open(message, opts);

    // 表单：<form data-confirm="…">，确认后带着原来的提交按钮再提交一次
    document.addEventListener('submit', (e) => {
        const form = e.target;
        if (!(form instanceof HTMLFormElement)) return;
        if (bypass.has(form)) {
            bypass.delete(form);
            return;
        }
        const text = form.getAttribute('data-confirm');
        if (!text) return;
        e.preventDefault();
        const submitter = e.submitter && e.submitter.form === form ? e.submitter : null;
        open(text).then((ok) => {
            if (!ok) return;
            bypass.add(form);
            if (typeof form.requestSubmit === 'function') form.requestSubmit(submitter || undefined);
            else form.submit();
        });
    }, true);

    // 按钮：<button data-confirm="…">，带 formaction 的提交按钮也走这条
    document.addEventListener('click', (e) => {
        const t = e.target;
        if (!(t instanceof Element)) return;
        const btn = t.closest('button[data-confirm], input[type="submit"][data-confirm]');
        if (!btn) return;
        if (bypass.has(btn)) {
            bypass.delete(btn);
            return;
        }
        const text = btn.getAttribute('data-confirm');
        if (!text) return;
        e.preventDefault();
        open(text).then((ok) => {
            if (!ok) return;
            bypass.add(btn);
            const form = btn.form || btn.closest('form');
            if (form && btn.type === 'submit' && typeof form.requestSubmit === 'function') {
                form.requestSubmit(btn);
            } else {
                btn.click();
            }
        });
    }, true);
})();
