// 编辑页的正文编辑器：左边写 Markdown，右边用服务端同一个渲染器实时预览；插图走上传接口
// 预览请求和上传请求都带 CSRF；上传是 multipart，body 里的 _csrf 在过滤器那层读不到，所以走请求头
(() => {
    const src = document.getElementById('md-source');
    const box = document.getElementById('md-preview');
    if (!src || !box) return;

    const previewUrl = src.dataset.preview || '';
    const uploadUrl = src.dataset.upload || '';

    const csrf = () => {
        const meta = document.querySelector('meta[name="_csrf"]');
        return meta ? meta.content : '';
    };

    let timer = 0;
    const render = async () => {
        if (!previewUrl) return;
        const body = new URLSearchParams();
        body.set('body', src.value);
        body.set('_csrf', csrf());
        try {
            const res = await fetch(previewUrl, { method: 'POST', credentials: 'same-origin', body });
            if (res.ok) box.innerHTML = await res.text();
        } catch (e) {
            // 预览挂了不影响保存，用户照样能把正文存下来
        }
    };
    const schedule = () => {
        clearTimeout(timer);
        timer = setTimeout(render, 320);
    };
    src.addEventListener('input', schedule);
    render();

    const insert = (text) => {
        const start = typeof src.selectionStart === 'number' ? src.selectionStart : src.value.length;
        const end = typeof src.selectionEnd === 'number' ? src.selectionEnd : start;
        src.value = src.value.slice(0, start) + text + src.value.slice(end);
        src.selectionStart = src.selectionEnd = start + text.length;
        src.focus();
        schedule();
    };

    const btn = document.getElementById('md-image');
    if (btn && uploadUrl) {
        const picker = document.createElement('input');
        picker.type = 'file';
        picker.accept = 'image/png,image/jpeg,image/gif,image/webp';
        picker.style.display = 'none';
        document.body.appendChild(picker);

        btn.addEventListener('click', () => picker.click());
        picker.addEventListener('change', async () => {
            const file = picker.files && picker.files[0];
            if (!file) return;
            const data = new FormData();
            data.append('file', file);
            btn.disabled = true;
            try {
                const res = await fetch(uploadUrl, {
                    method: 'POST',
                    credentials: 'same-origin',
                    headers: { 'X-CSRF-TOKEN': csrf() },
                    body: data,
                });
                if (res.ok) {
                    const out = await res.json();
                    if (out && out.url) insert('![](' + out.url + ')');
                }
            } catch (e) {
                // 上传失败就什么都不插，正文原样留着
            } finally {
                btn.disabled = false;
                picker.value = '';
            }
        });
    }

    // 「补充笔记」行末的清除：把标题和正文清空，保存时那一篇就进回收站
    const supClear = document.getElementById('supplement-clear');
    const supBody = document.querySelector('form textarea[name="supplementBody"]');
    if (supClear && supBody) {
        const supTitle = document.querySelector('form input[name="supplementTitle"]');
        const sync = () => {
            supClear.hidden = supBody.value.trim() === '';
        };
        supClear.addEventListener('click', () => {
            supBody.value = '';
            if (supTitle) supTitle.value = '';
            sync();
        });
        supBody.addEventListener('input', sync);
        sync();
    }
})();
