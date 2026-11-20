// 右下角浮动按钮的通用行为：右键隐藏；手机上第一次点击先问「打开 / 隐藏」，
// 勾了「不再显示此提示」以后直接打开；长按也能叫出这个菜单。
(() => {
    const coarse = window.matchMedia('(pointer: coarse)').matches;
    const hiddenKey = (id) => 'float:' + id;
    const tipKey = (id) => 'float-tip:' + id;

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

        document.addEventListener('float-change', (e) => {
            if (e.detail.id === id) el.hidden = e.detail.hidden;
        });

        el.addEventListener('contextmenu', (e) => {
            e.preventDefault();
            setHidden(id, true);
        });

        if (!coarse) return;

        let press = 0;
        let longFired = false;
        el.addEventListener('pointerdown', () => {
            longFired = false;
            press = setTimeout(() => {
                longFired = true;
                showMenu(el, id, onOpen);
            }, 600);
        });
        ['pointerup', 'pointercancel', 'pointerleave'].forEach((ev) => {
            el.addEventListener(ev, () => clearTimeout(press));
        });

        // 捕获阶段先拦：还没勾过提示时，一次点击只出菜单，不直接打开
        el.addEventListener('click', (e) => {
            if (longFired) {
                longFired = false;
                e.preventDefault();
                e.stopImmediatePropagation();
                return;
            }
            if (read(tipKey(id))) return;
            e.preventDefault();
            e.stopImmediatePropagation();
            showMenu(el, id, onOpen);
        }, true);
    };

    window.Float = { attach, isHidden, setHidden, closeMenu };
})();
