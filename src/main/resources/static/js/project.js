// 项目页的「i」按钮和步骤正文：点开看那一条的细节。注解不占版面，看的时候才浮出来。
// 内容在按钮的 data-note 上（服务端已经转义好），这里只负责塞进 <dialog>。
(() => {
    const dlg = document.getElementById('project-note');
    if (!dlg) return;
    const body = dlg.querySelector('.note-body');
    const closeBtn = dlg.querySelector('.note-close');

    const open = (btn) => {
        body.innerHTML = btn.getAttribute('data-note') || '';
        if (!dlg.open) dlg.showModal();
    };

    document.addEventListener('click', (e) => {
        // 正在拖选文字时不弹：选完会派发一次 click，弹出来很烦
        const sel = window.getSelection();
        if (sel && sel.toString().trim()) return;
        // 词条浮窗自己会处理，别再叠一层弹窗
        if (e.target.closest('.gl')) return;
        const btn = e.target.closest('.note-btn, .project-step-text[data-note]');
        if (btn) {
            e.preventDefault();
            open(btn);
            return;
        }
        if (!dlg.open) return;
        if (closeBtn && closeBtn.contains(e.target)) {
            dlg.close();
            return;
        }
        // 点遮罩关闭：只有落点在对话框矩形之外才算点到遮罩。
        // 不能「点正文就关」——拖选文字结束时浏览器也会派发一次 click。
        if (e.target === dlg) {
            const r = dlg.getBoundingClientRect();
            if (e.clientX < r.left || e.clientX > r.right || e.clientY < r.top || e.clientY > r.bottom) {
                dlg.close();
            }
        }
    });

    // 键盘可达：正文获得焦点后回车/空格同样打开
    document.addEventListener('keydown', (e) => {
        if (e.key !== 'Enter' && e.key !== ' ') return;
        const el = e.target.closest && e.target.closest('.project-step-text[data-note]');
        if (!el) return;
        e.preventDefault();
        open(el);
    });
})();
