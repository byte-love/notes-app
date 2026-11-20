// 页签末尾的「+」：就地输入分类名，按回车直接建。
// 建完由服务端重定向回新建的分类页，所以这里只负责把名字发出去。
(() => {
    const tabs = document.querySelector('.mine-tabs');
    if (!tabs) return;

    // 应用挂在 /app 下：从样式表地址里取上下文路径（/app/css/app.css -> /app）
    const sheet = document.querySelector('link[rel="stylesheet"]');
    const href = sheet ? sheet.getAttribute('href') : '';
    const ctx = href.includes('/css/') ? href.slice(0, href.indexOf('/css/')) : '';

    // 设置页那条「新建」是普通表单，不用这里管
    if (!document.querySelector('.mine-tab-add')) return;

    const start = () => {
        if (tabs.querySelector('.mine-tab-input')) return;
        const input = document.createElement('input');
        input.type = 'text';
        input.className = 'mine-tab-input';
        input.maxLength = 40;
        input.setAttribute('aria-label', '新分类名');
        tabs.appendChild(input);
        input.focus();

        let done = false;
        const submit = async () => {
            if (done) return;
            done = true;
            const name = input.value.trim();
            if (!name) {
                input.remove();
                return;
            }
            const body = new URLSearchParams({ _csrf: App.csrf(), name });
            const res = await fetch(ctx + '/settings/categories', {
                method: 'POST',
                credentials: 'same-origin',
                headers: { 'content-type': 'application/x-www-form-urlencoded' },
                body: body.toString(),
            });
            if (res.redirected) {
                location.href = res.url;
            } else {
                location.reload();
            }
        };
        input.addEventListener('keydown', (e) => {
            if (e.key === 'Enter') {
                e.preventDefault();
                submit();
            } else if (e.key === 'Escape') {
                done = true;
                input.remove();
            }
        });
        input.addEventListener('blur', () => {
            if (!done) {
                done = true;
                input.remove();
            }
        });
    };

    tabs.addEventListener('click', (e) => {
        if (e.target.closest('.mine-tab-add')) {
            e.preventDefault();
            start();
        }
    });
})();
