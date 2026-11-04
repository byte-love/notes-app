// 会话被换掉之后（闲置太久、进程重启、别处重新登录），页面里那份 CSRF token 就作废了：
// 写好的评论 / 注释点提交会被拦成 403，字白写。这里在页面回到前台、定时、以及提交前
// 跟服务端换一份新的，填回 meta 和所有表单的 _csrf 字段。
(() => {
    const endpointMeta = document.querySelector('meta[name="csrf-endpoint"]');
    const meta = document.querySelector('meta[name="_csrf"]');
    if (!endpointMeta || !meta) return;

    const endpoint = endpointMeta.getAttribute('content');
    const FRESH_MS = 30000;
    const PERIOD_MS = 240000;

    let token = meta.getAttribute('content') || '';
    let at = Date.now();
    let inflight = null;

    const fill = () => {
        document.querySelectorAll('meta[name="_csrf"]').forEach((m) => m.setAttribute('content', token));
        document.querySelectorAll('input[name="_csrf"]').forEach((i) => {
            i.value = token;
        });
    };

    const refresh = () => {
        if (inflight) return inflight;
        inflight = fetch(endpoint, { credentials: 'same-origin', cache: 'no-store' })
            .then((res) => (res.ok ? res.json() : null))
            .then((data) => {
                if (data && data.token) {
                    token = data.token;
                    at = Date.now();
                    fill();
                }
            })
            .catch(() => {})
            .then(() => {
                inflight = null;
            });
        return inflight;
    };

    const fresh = () => !inflight && Date.now() - at < FRESH_MS;

    document.addEventListener('visibilitychange', () => {
        if (!document.hidden) refresh();
    });
    window.addEventListener('focus', refresh);
    window.addEventListener('pageshow', refresh);
    setInterval(() => {
        if (!document.hidden) refresh();
    }, PERIOD_MS);

    // 提交前兜一道：token 不够新就等换完再提交，别让用户写完的字丢掉
    const bypass = new WeakSet();
    document.addEventListener('submit', (e) => {
        const form = e.target;
        if (!(form instanceof HTMLFormElement)) return;
        if (!form.querySelector('input[name="_csrf"]')) return;
        if (bypass.has(form)) {
            bypass.delete(form);
            return;
        }
        if (fresh()) {
            fill();
            return;
        }
        e.preventDefault();
        const submitter = e.submitter;
        refresh().then(() => {
            fill();
            bypass.add(form);
            try {
                if (typeof form.requestSubmit === 'function') form.requestSubmit(submitter || undefined);
                else form.submit();
            } catch (err) {
                form.submit();
            }
        });
    }, true);
})();
