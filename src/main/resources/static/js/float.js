// 右下角浮动按钮的通用行为：拖动换位置、右键隐藏；手机上第一次点击先问「打开 / 隐藏」，
// 勾了「不再显示此提示」以后直接打开；长按也能叫出这个菜单。
(() => {
    const coarse = window.matchMedia('(pointer: coarse)').matches;
    const hiddenKey = (id) => 'float:' + id;
    const tipKey = (id) => 'float-tip:' + id;
    const posKey = (id) => 'float-pos:' + id;

    const read = (key) => {
        try {
            return localStorage.getItem(key);
        } catch (e) {
            return null;
        }
    };
    const write = (key, value) => {
        try {
            localStorage.setItem(key, value);
        } catch (e) {}
    };

    const isHidden = (id) => read(hiddenKey(id)) === 'off';
    const setHidden = (id, hide) => {
        if (hide) {
            write(hiddenKey(id), 'off');
        } else {
            try {
                localStorage.removeItem(hiddenKey(id));
            } catch (e) {}
        }
        document.dispatchEvent(new CustomEvent('float-change', { detail: { id, hidden: hide } }));
    };

    const clamp = (el) => {
        if (!el.style.left) return;
        const r = el.getBoundingClientRect();
        const x = Math.min(Math.max(8, r.left), Math.max(8, window.innerWidth - r.width - 8));
        const y = Math.min(Math.max(8, r.top), Math.max(8, window.innerHeight - r.height - 8));
        el.style.left = x + 'px';
        el.style.top = y + 'px';
    };

    const restorePos = (el, id) => {
        let p = null;
        try {
            p = JSON.parse(read(posKey(id)) || 'null');
        } catch (e) {}
        if (!p) return;
        el.style.left = p.x + 'px';
        el.style.top = p.y + 'px';
        el.style.right = 'auto';
        el.style.bottom = 'auto';
        clamp(el);
    };

    let menu = null;

    const closeMenu = () => {
        if (menu) {
            menu.remove();
            menu = null;
        }
    };

    const place = (el, box) => {
        const r = el.getBoundingClientRect();
        box.style.right = Math.max(8, window.innerWidth - r.right) + 'px';
        box.style.bottom = (window.innerHeight - r.top + 8) + 'px';
    };

    const showMenu = (el, id, onOpen) => {
        closeMenu();
        menu = document.createElement('div');
        menu.className = 'float-menu';
        const open = document.createElement('button');
        open.type = 'button';
        open.textContent = '打开';
        const hide = document.createElement('button');
        hide.type = 'button';
        hide.textContent = '隐藏';
        const tip = document.createElement('label');
        tip.className = 'float-menu-tip';
        const cb = document.createElement('input');
        cb.type = 'checkbox';
        tip.append(cb, document.createTextNode('不再显示此提示'));
        menu.append(open, hide, tip);
        document.body.appendChild(menu);
        place(el, menu);

        open.addEventListener('click', () => {
            if (cb.checked) write(tipKey(id), '1');
            closeMenu();
            onOpen();
        });
        hide.addEventListener('click', () => {
            if (cb.checked) write(tipKey(id), '1');
            closeMenu();
            setHidden(id, true);
        });
        setTimeout(() => {
            document.addEventListener('click', (e) => {
                if (menu && !menu.contains(e.target)) closeMenu();
            }, { once: true });
        }, 0);
    };

    const attach = (el, id, onOpen) => {
        if (isHidden(id)) el.hidden = true;
        restorePos(el, id);
        window.addEventListener('resize', () => clamp(el));

        document.addEventListener('float-change', (e) => {
            if (e.detail.id === id) {
                el.hidden = e.detail.hidden;
                if (!e.detail.hidden) restorePos(el, id);
            }
        });

        el.addEventListener('contextmenu', (e) => {
            e.preventDefault();
            setHidden(id, true);
        });

        let press = 0;
        let longFired = false;
        let drag = null;
        let suppress = false;

        el.addEventListener('pointerdown', (e) => {
            if (e.button !== 0) return;
            longFired = false;
            const r = el.getBoundingClientRect();
            drag = { x: e.clientX, y: e.clientY, left: r.left, top: r.top, moved: false };
            try {
                el.setPointerCapture(e.pointerId);
            } catch (err) {}
            if (coarse) {
                press = setTimeout(() => {
                    longFired = true;
                    showMenu(el, id, onOpen);
                }, 600);
            }
        });

        el.addEventListener('pointermove', (e) => {
            if (!drag) return;
            if (!drag.moved && Math.abs(e.clientX - drag.x) + Math.abs(e.clientY - drag.y) > 6) {
                drag.moved = true;
                clearTimeout(press);
            }
            if (!drag.moved) return;
            el.style.left = drag.left + (e.clientX - drag.x) + 'px';
            el.style.top = drag.top + (e.clientY - drag.y) + 'px';
            el.style.right = 'auto';
            el.style.bottom = 'auto';
        });

        const endDrag = (save) => {
            if (!drag) return;
            const moved = drag.moved;
            drag = null;
            clearTimeout(press);
            if (!moved) return;
            suppress = true;
            clamp(el);
            if (save) {
                write(posKey(id), JSON.stringify({
                    x: parseFloat(el.style.left),
                    y: parseFloat(el.style.top),
                }));
            }
        };

        el.addEventListener('pointerup', () => endDrag(true));
        el.addEventListener('pointercancel', () => endDrag(false));

        // 捕获阶段先拦：拖动过、长按过、或者还没勾过提示时，这次 click 不该继续往下走
        el.addEventListener('click', (e) => {
            if (suppress) {
                suppress = false;
                e.preventDefault();
                e.stopImmediatePropagation();
                return;
            }
            if (longFired) {
                longFired = false;
                e.preventDefault();
                e.stopImmediatePropagation();
                return;
            }
            if (!coarse || read(tipKey(id))) return;
            e.preventDefault();
            e.stopImmediatePropagation();
            showMenu(el, id, onOpen);
        }, true);
    };

    window.Float = { attach, isHidden, setHidden, closeMenu };
})();
