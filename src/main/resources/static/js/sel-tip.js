// 选中术语 → 浮出解释卡片（手机上没法悬停，靠这个看解释）。
// 卡片带「×」；Esc、点空白处、滚动都会关掉；关掉的那个词不会因为鼠标一松又弹回来。
(() => {
    if (!document.querySelector('[data-tip]')) return;

    const card = document.createElement('div');
    card.className = 'sel-tip';
    card.hidden = true;
    card.innerHTML = '<div class="sel-tip-head"><span class="sel-tip-term"></span>'
        + '<button type="button" class="sel-tip-close" aria-label="关闭">×</button></div>'
        + '<p class="sel-tip-body"></p>';
    document.body.appendChild(card);

    const termEl = card.querySelector('.sel-tip-term');
    const bodyEl = card.querySelector('.sel-tip-body');
    const closeBtn = card.querySelector('.sel-tip-close');

    // 刚被关掉的那个词：选区还在它里面时不再自动弹出来
    let dismissed = null;

    const hide = () => {
        card.hidden = true;
    };

    const hit = () => {
        const sel = window.getSelection();
        if (!sel || sel.isCollapsed || !sel.rangeCount) return null;
        const range = sel.getRangeAt(0);
        const start = range.startContainer;
        const el = start && (start.nodeType === 1 ? start : start.parentElement);
        const mark = el && el.closest ? el.closest('[data-tip]') : null;
        // 拖选一大段时多半跨出了这个词，这种不弹
        if (!mark || !mark.contains(range.endContainer)) return null;
        const tip = (mark.getAttribute('data-tip') || '').trim();
        if (!tip) return null;
        return { mark: mark, range: range, tip: tip };
    };

    const place = (range) => {
        const rect = range.getBoundingClientRect();
        if (!rect || (!rect.width && !rect.height)) return false;
        card.hidden = false;
        const w = card.offsetWidth;
        const h = card.offsetHeight;
        const left = Math.max(12, Math.min(rect.left + rect.width / 2 - w / 2, window.innerWidth - w - 12));
        // 评论工具条在选区上方，这个放下面；下面放不下再翻到上面
        let top = rect.bottom + 10;
        if (top + h > window.innerHeight - 12) top = Math.max(12, rect.top - h - 10);
        card.style.left = Math.round(left) + 'px';
        card.style.top = Math.round(top) + 'px';
        return true;
    };

    const show = () => {
        const found = hit();
        if (!found) {
            hide();
            return;
        }
        if (dismissed && found.mark === dismissed) return;
        dismissed = null;
        termEl.textContent = found.mark.getAttribute('data-term') || found.mark.textContent || '术语';
        bodyEl.textContent = found.tip;
        if (!place(found.range)) hide();
    };

    const dismiss = () => {
        const found = hit();
        dismissed = found ? found.mark : dismissed;
        hide();
    };

    document.addEventListener('mouseup', () => setTimeout(show, 0));
    document.addEventListener('touchend', () => setTimeout(show, 260));
    let timer = null;
    document.addEventListener('selectionchange', () => {
        clearTimeout(timer);
        timer = setTimeout(show, 300);
    });

    // 点卡片不能把选区弄没，否则卡片跟着消失
    card.addEventListener('mousedown', (e) => e.preventDefault());
    card.addEventListener('touchstart', (e) => e.stopPropagation(), { passive: true });
    closeBtn.addEventListener('click', (e) => {
        e.preventDefault();
        e.stopPropagation();
        dismiss();
    });

    document.addEventListener('keydown', (e) => {
        if (e.key === 'Escape') dismiss();
    });
    document.addEventListener('mousedown', (e) => {
        if (!card.hidden && !card.contains(e.target)) dismiss();
    });
    window.addEventListener('scroll', hide, { passive: true });
    window.addEventListener('resize', hide);
})();
