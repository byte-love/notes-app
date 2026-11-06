// 术语浮窗（项目页版）：鼠标移到带 data-tip 的词上就浮出解释。
// 正文页那套在 detail.js 里，绑的是 .markdown；项目页的条目和注解不在 .markdown 里，
// 所以这里单独绑 .project 和注解弹窗，样式仍是同一套 .gl-tip。
(() => {
    const marks = document.querySelectorAll('.project [data-tip], .wrap [data-tip]');
    if (!marks.length && !document.querySelector('[data-tip]')) return;

    const tip = document.createElement('div');
    tip.className = 'gl-tip';
    document.body.appendChild(tip);

    let current = null;

    const show = (el) => {
        current = el;
        tip.textContent = el.getAttribute('data-tip');
        tip.style.left = '0px';
        tip.style.top = '0px';
        tip.classList.add('on');

        // 注解是 <dialog> 里弹出来的，dialog 在浏览器的 top layer 上：
        // 浮窗挂在 body 下会被盖住，所以跟着目标走 —— 目标在对话框里就挂进对话框。
        const host = el.closest('dialog') || document.body;
        if (tip.parentElement !== host) host.appendChild(tip);

        const r = el.getBoundingClientRect();
        const tw = tip.offsetWidth;
        const th = tip.offsetHeight;
        let left = r.left + r.width / 2 - tw / 2;
        left = Math.max(12, Math.min(left, window.innerWidth - tw - 12));
        let top = r.bottom + 8;
        if (top + th > window.innerHeight - 12) top = r.top - th - 8;
        tip.style.left = Math.round(left) + 'px';
        tip.style.top = Math.round(Math.max(12, top)) + 'px';
    };

    const hide = () => {
        current = null;
        tip.classList.remove('on');
    };

    const pick = (target) => (target.closest ? target.closest('[data-tip]') : null);

    document.addEventListener('mouseover', (e) => {
        const el = pick(e.target);
        if (el) {
            if (el !== current) show(el);
        } else if (current) {
            hide();
        }
    });

    document.addEventListener('mouseout', (e) => {
        const el = pick(e.target);
        if (el && el === current && !el.contains(e.relatedTarget)) hide();
    });

    // 手机上点一下也能看
    document.addEventListener('click', (e) => {
        const el = pick(e.target);
        if (el) show(el);
        else hide();
    });

    window.addEventListener('scroll', hide, { passive: true });
    window.addEventListener('resize', hide);
})();
