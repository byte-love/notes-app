// 项目页的「i」按钮：点开看那一条的细节。注解不占版面，看的时候才浮出来。
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
        const btn = e.target.closest('.note-btn');
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
})();
