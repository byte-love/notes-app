// 页面通用小工具。以前这几段在 7 个 js 里各写一遍（读 CSRF、拼 urlencoded POST、转义 &<>），
// 改一处容易漏，所以收到这里，其它文件用 App.csrf() / App.post() / App.esc()。
// 挂在 window 上，由 layout.html 的 foot 片段第一个加载。
window.App = (() => {
    // 页面里的 token 会随会话换新（csrf.js 会改写 <meta>），所以每次提交时现读，别在加载时缓存
    const csrf = () => {
        const meta = document.querySelector('meta[name="_csrf"]');
        return meta ? meta.getAttribute('content') : '';
    };

    // 从 /csrf 换一份新 token，写回 meta 和页面里所有表单
    const refreshCsrf = async () => {
        const endpointMeta = document.querySelector('meta[name="csrf-endpoint"]');
        if (!endpointMeta) return '';
        try {
            const res = await fetch(endpointMeta.getAttribute('content'),
                { credentials: 'same-origin', cache: 'no-store' });
            if (!res.ok) return '';
            const data = await res.json();
            const token = (data && data.token) || '';
            if (!token) return '';
            document.querySelectorAll('meta[name="_csrf"]').forEach((m) => m.setAttribute('content', token));
            document.querySelectorAll('input[name="_csrf"]').forEach((i) => {
                i.value = token;
            });
            return token;
        } catch (e) {
            return '';
        }
    };

    // 表单式 POST：自动带 _csrf、same-origin、urlencoded。
    // 进程重启 / 会话过期之后页面里那份 token 就作废，第一次提交会被拦成 403；
    // 这里换一份新的重试一次，用户不用刷新、也不用重写一遍。
    // 注意头是合并不是替换：调用方传 headers（比如 X-Requested-With）时，
    // 不能把 content-type 顶掉，否则 body 变成 text/plain，服务端读不到 _csrf，直接 403。
    const post = async (url, data, init) => {
        const send = (token) => {
            const extra = init || {};
            const base = {
                method: 'POST',
                credentials: 'same-origin',
                body: new URLSearchParams(Object.assign({ _csrf: token }, data || {})).toString(),
            };
            return fetch(url, Object.assign({}, base, extra, {
                headers: Object.assign(
                    { 'content-type': 'application/x-www-form-urlencoded' },
                    extra.headers || {},
                ),
            }));
        };

        let res = await send(csrf());
        if (res.status === 403) {
            const fresh = await refreshCsrf();
            if (fresh) res = await send(fresh);
        }
        return res;
    };

    const esc = (value) => String(value == null ? '' : value)
        .replace(/&/g, '&amp;')
        .replace(/</g, '&lt;')
        .replace(/>/g, '&gt;');

    return { csrf, post, refreshCsrf, esc };
})();
