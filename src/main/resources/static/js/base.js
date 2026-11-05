// 页面通用小工具。以前这几段在 7 个 js 里各写一遍（读 CSRF、拼 urlencoded POST、转义 &<>），
// 改一处容易漏，所以收到这里，其它文件用 App.csrf() / App.post() / App.esc()。
// 挂在 window 上，由 layout.html 的 foot 片段第一个加载。
window.App = (() => {
    // 页面里的 token 会随会话换新（csrf.js 会改写 <meta>），所以每次提交时现读，别在加载时缓存
    const csrf = () => {
        const meta = document.querySelector('meta[name="_csrf"]');
        return meta ? meta.getAttribute('content') : '';
    };

    // 表单式 POST：自动带 _csrf、same-origin、urlencoded
    const post = (url, data, init) => {
        const body = new URLSearchParams(Object.assign({ _csrf: csrf() }, data || {}));
        return fetch(url, Object.assign({
            method: 'POST',
            credentials: 'same-origin',
            headers: { 'content-type': 'application/x-www-form-urlencoded' },
            body: body.toString(),
        }, init || {}));
    };

    const esc = (value) => String(value == null ? '' : value)
        .replace(/&/g, '&amp;')
        .replace(/</g, '&lt;')
        .replace(/>/g, '&gt;');

    return { csrf, post, esc };
})();
