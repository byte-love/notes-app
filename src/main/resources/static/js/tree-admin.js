// 左树上的就地操作：+ 新建（当场命名）、⋯ 菜单（重命名 / 编辑 / 复制链接 / 删除）
// 页面上的数据都在 .tree-row 的 data-* 里，这里只管弹输入框和提交
(() => {
    const meta = document.querySelector('meta[name="_csrf"]');
    const pane = document.querySelector('.tree-pane');
    if (!meta || !pane) return;

    // 应用挂在 /app 下：从样式表地址里取上下文路径（/app/css/app.css -> /app）
    const sheet = document.querySelector('link[rel="stylesheet"]');
    const href = sheet ? sheet.getAttribute('href') : '';
    const ctx = href.includes('/css/') ? href.slice(0, href.indexOf('/css/')) : '';
    const back = location.pathname + location.search;

    // token 每次现取（App.csrf），别在加载时抓一份存着——会话换新后那份就作废了
    const post = (path, params) => App.post(ctx + path, { back, ...params });

    // ---- 就地命名：+ 新建 ----
    let editing = null;

    const closeEdit = () => {
        if (editing) {
            editing.remove();
            editing = null;
        }
    };

    const startCreate = (row) => {
        closeEdit();
        const parentId = row ? row.dataset.id : '';
        const li = document.createElement('li');
        li.className = 'tree-edit-row';
        const input = document.createElement('input');
        input.type = 'text';
        input.className = 'tree-input';
        input.placeholder = '名字，回车确定';
        li.appendChild(input);

        if (row) {
            // 先把这个目录展开：折叠着的时候输入框藏在看不到的地方，用户会以为按钮没反应
            const owner = row.closest('li');
            if (owner) {
                owner.classList.remove('folded');
                const group = row.querySelector('.tree-group');
                // 懒加载的节点还没取子列表，借分组的点击把子节点拉下来
                if (group && group.dataset.lazy && !owner.querySelector(':scope > ul')) {
                    group.click();
                }
            }
        }

        const place = () => {
            if (row) {
                const owner = row.closest('li');
                const kids = owner ? owner.querySelector(':scope > ul') : null;
                if (kids) kids.prepend(li);
                else if (owner) owner.appendChild(li);
                else pane.appendChild(li);
            } else {
                const root = pane.querySelector(':scope > ul.tree');
                (root || pane).prepend(li);
            }
            editing = li;
            input.focus();
        };

        // 懒加载是异步的：等子列表出现再插输入框，最多等一会儿
        let tries = 0;
        const tryPlace = () => {
            const owner = row ? row.closest('li') : null;
            const needsKids = owner && owner.querySelector('.tree-group[data-lazy]');
            if (owner && needsKids && !owner.querySelector(':scope > ul') && tries++ < 20) {
                setTimeout(tryPlace, 60);
                return;
            }
            place();
        };
        tryPlace();

        let done = false;
        const submit = async () => {
            if (done) return;
            done = true;
            const title = input.value.trim();
            if (!title) {
                closeEdit();
                return;
            }
            await post('/admin/quick', { parentId, title, kind: pane.dataset.kind || '' });
            location.reload();
        };
        input.addEventListener('keydown', (e) => {
            if (e.key === 'Enter') {
                e.preventDefault();
                submit();
            } else if (e.key === 'Escape') {
                done = true;
                closeEdit();
            }
        });
        input.addEventListener('blur', () => {
            if (!done) {
                done = true;
                closeEdit();
            }
        });
    };

    // ---- 就地改名 ----
    const startRename = (row) => {
        const titleEl = row.querySelector('.tree-group-title') || row.querySelector('a:not(.tree-act) span:last-child');
        if (!titleEl || row.querySelector('.tree-rename')) return;
        const input = document.createElement('input');
        input.type = 'text';
        input.className = 'tree-input tree-rename';
        input.value = row.dataset.title || titleEl.textContent.trim();
        titleEl.style.display = 'none';
        titleEl.parentElement.insertBefore(input, titleEl.nextSibling);
        input.focus();
        input.select();

        let done = false;
        const finish = async (save) => {
            if (done) return;
            done = true;
            const title = input.value.trim();
            if (save && title && title !== row.dataset.title) {
                await post('/admin/' + row.dataset.id + '/rename', { title });
                location.reload();
                return;
            }
            input.remove();
            titleEl.style.display = '';
        };
        input.addEventListener('keydown', (e) => {
            if (e.key === 'Enter') {
                e.preventDefault();
                finish(true);
            } else if (e.key === 'Escape') {
                finish(false);
            }
        });
        input.addEventListener('blur', () => finish(false));
    };

    // ---- 目录旁边的数字：默认不显示，菜单里可以打开 ----
    try {
        document.documentElement.classList.toggle('tree-no-count', localStorage.getItem('tree-counts') !== 'on');
    } catch (e) {
        document.documentElement.classList.add('tree-no-count');
    }

    // ---- ⋯ 菜单 ----
    const menu = document.createElement('div');
    menu.className = 'tree-menu';
    menu.hidden = true;
    document.body.appendChild(menu);

    const closeMenu = () => {
        menu.hidden = true;
        menu.innerHTML = '';
    };

    const item = (text, onClick, danger) => {
        const btn = document.createElement('button');
        btn.type = 'button';
        btn.className = 'tree-menu-item' + (danger ? ' danger' : '');
        btn.textContent = text;
        btn.addEventListener('mousedown', (e) => e.preventDefault());
        btn.addEventListener('click', () => {
            closeMenu();
            onClick();
        });
        return btn;
    };

    const openMenu = (row, x, y) => {
        menu.innerHTML = '';
        menu.appendChild(item('重命名', () => startRename(row)));
        menu.appendChild(item('编辑', () => {
            location.href = ctx + '/admin/' + row.dataset.id + '/edit';
        }));
        menu.appendChild(item('复制链接', () => {
            const url = location.origin + ctx + row.dataset.url;
            if (navigator.clipboard) navigator.clipboard.writeText(url);
        }));
        const hideCounts = document.documentElement.classList.contains('tree-no-count');
        menu.appendChild(item(hideCounts ? '显示数字' : '隐藏数字', () => {
            const root = document.documentElement;
            root.classList.toggle('tree-no-count');
            try {
                localStorage.setItem('tree-counts', root.classList.contains('tree-no-count') ? 'off' : 'on');
            } catch (e) {}
        }));
        const del = item('删除', async () => {
            const ask = '删除「' + (row.dataset.title || '') + '」？进回收站，30 天内能恢复。';
            const ok = window.uiConfirm ? await window.uiConfirm(ask) : confirm(ask);
            if (!ok) return;
            await post('/admin/' + row.dataset.id + '/delete', {});
            location.reload();
        }, true);
        menu.appendChild(del);

        menu.hidden = false;
        const w = menu.offsetWidth;
        const h = menu.offsetHeight;
        menu.style.left = Math.min(x, window.innerWidth - w - 8) + 'px';
        menu.style.top = Math.min(y, window.innerHeight - h - 8) + 'px';
    };

    document.addEventListener('click', (e) => {
        const add = e.target.closest('.tree-add');
        if (add) {
            e.preventDefault();
            const row = add.closest('.tree-row');
            startCreate(row);
            return;
        }
        const more = e.target.closest('.tree-more');
        if (more) {
            e.preventDefault();
            const row = more.closest('.tree-row');
            const r = more.getBoundingClientRect();
            openMenu(row, r.left - 120, r.bottom + 4);
            return;
        }
        if (!e.target.closest('.tree-menu')) closeMenu();
    });

    document.addEventListener('keydown', (e) => {
        if (e.key === 'Escape') closeMenu();
    });
})();
