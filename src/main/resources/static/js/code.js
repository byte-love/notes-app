// 代码块增强：行号 + 语言条 + 复制按钮 + 轻量高亮（不引第三方库，全文只有几十行规则）
(() => {
    const LANGS = {
        java: 'Java', js: 'JavaScript', javascript: 'JavaScript', ts: 'TypeScript',
        sql: 'SQL', lua: 'Lua', bash: 'Shell', sh: 'Shell', shell: 'Shell',
        json: 'JSON', xml: 'XML', html: 'HTML', yaml: 'YAML', yml: 'YAML',
        python: 'Python', py: 'Python', vue: 'Vue', css: 'CSS', text: 'Text',
    };

    const KEYWORDS = {
        java: 'abstract assert boolean break byte case catch char class const continue default do double else enum extends final finally float for goto if implements import instanceof int interface long native new package private protected public return short static strictfp super switch synchronized this throw throws transient try void volatile while var record sealed true false null',
        sql: 'select from where update set insert into values delete create table alter drop index join left right inner outer on group by order having limit offset and or not null is in exists as distinct count sum avg max min begin commit rollback case when then else end with union all',
        lua: 'and break do else elseif end false for function goto if in local nil not or repeat return then true until while',
        js: 'const let var function return if else for while new class extends import export default await async try catch finally throw typeof instanceof null undefined true false this of in',
        bash: 'if then else fi for while do done case esac function return export local echo cd sudo grep awk sed cat',
        python: 'def class import from return if elif else for while try except finally with as lambda None True False and or not in',
    };

    // 注释/字符串/数字/关键字；注释和字符串都限制在单行内，才能按行切分后加行号
    const build = (lang) => {
        const kw = KEYWORDS[lang];
        const comment = lang === 'sql' || lang === 'lua' ? '--[^\\n]*|#[^\\n]*'
            : lang === 'bash' || lang === 'python' || lang === 'yaml' || lang === 'yml' ? '#[^\\n]*'
            : '\\/\\/[^\\n]*|\\/\\*.*?\\*\\/';
        const str = '"(?:\\\\.|[^"\\\\\\n])*"|\'(?:\\\\.|[^\'\\\\\\n])*\'|`(?:\\\\.|[^`\\\\\\n])*`';
        const parts = [
            `(${comment})`,
            `(${str})`,
            '(\\b\\d+(?:\\.\\d+)?\\b)',
        ];
        if (kw) {
            parts.push(`(\\b(?:${kw.replace(/ /g, '|')})\\b)`);
        }
        return new RegExp(parts.join('|'), 'g');
    };

    const cache = {};

    const escapeHtml = (s) => s.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;');

    const highlight = (text, lang) => {
        const re = cache[lang] || (cache[lang] = build(lang));
        re.lastIndex = 0;
        let out = '';
        let last = 0;
        let m;
        while ((m = re.exec(text)) !== null) {
            out += escapeHtml(text.slice(last, m.index));
            const cls = m[1] ? 'tok-c' : m[2] ? 'tok-s' : m[3] ? 'tok-n' : 'tok-k';
            out += `<span class="${cls}">${escapeHtml(m[0])}</span>`;
            last = m.index + m[0].length;
        }
        out += escapeHtml(text.slice(last));
        return out;
    };

    const copyIcon = '<svg viewBox="0 0 16 16" aria-hidden="true" fill="none" stroke="currentColor" stroke-width="1.4">'
        + '<rect x="5.5" y="5.5" width="8" height="8" rx="1.5"/><path d="M10.5 5.5V3.5A1.5 1.5 0 0 0 9 2H3.5A1.5 1.5 0 0 0 2 3.5V9a1.5 1.5 0 0 0 1.5 1.5h2"/></svg>';

    const enhance = (pre) => {
        const code = pre.querySelector('code');
        if (!code || pre.dataset.enhanced === '1') return;
        pre.dataset.enhanced = '1';

        const cls = [...code.classList].find((c) => c.startsWith('language-'));
        const lang = cls ? cls.slice('language-'.length).toLowerCase() : '';

        const raw = code.textContent.replace(/\n$/, '');
        const lines = raw.split('\n');
        const html = lines
            .map((line) => `<span class="line">${lang ? highlight(line, lang) : escapeHtml(line)}</span>`)
            .join('');

        const wrap = document.createElement('div');
        wrap.className = 'code-block';
        const head = document.createElement('div');
        head.className = 'code-head';
        const label = document.createElement('span');
        label.className = 'code-lang';
        label.textContent = LANGS[lang] || lang || '';
        const btn = document.createElement('button');
        btn.type = 'button';
        btn.className = 'code-copy';
        btn.title = '复制';
        btn.setAttribute('aria-label', '复制代码');
        btn.innerHTML = copyIcon;
        btn.addEventListener('click', () => {
            const done = () => {
                btn.style.color = 'var(--accent)';
                setTimeout(() => { btn.style.color = ''; }, 900);
            };
            if (navigator.clipboard && navigator.clipboard.writeText) {
                navigator.clipboard.writeText(raw).then(done, () => {});
            }
        });
        head.appendChild(label);
        head.appendChild(btn);

        code.innerHTML = html;
        pre.parentNode.insertBefore(wrap, pre);
        wrap.appendChild(head);
        wrap.appendChild(pre);
    };

    const run = () => document.querySelectorAll('.markdown pre > code').forEach((code) => enhance(code.parentNode));
    if (document.readyState === 'complete') run();
    else window.addEventListener('load', run, { once: true });
})();
