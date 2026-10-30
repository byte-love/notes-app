// 句子锚定：把「被评论过的那句话」在正文里找出来加下划线（参考 Hypothesis 的 TextQuoteSelector 思路）
// 只处理正文里的文本节点，代码块/链接/标题不动；同一句话只标第一次出现
(() => {
    const pane = document.getElementById('comments');
    const article = document.querySelector('.markdown');
    if (!pane || !article) return;

    const threads = [...pane.querySelectorAll('.cmt-thread[data-quote]')]
        .filter((t) => t.dataset.quote && t.dataset.quote.trim());

    const skip = new Set(['PRE', 'CODE', 'A', 'H1', 'H2', 'H3', 'H4', 'SCRIPT', 'STYLE']);

    const focusThread = (quote) => {
        pane.querySelectorAll('.cmt-thread.on').forEach((el) => el.classList.remove('on'));
        const target = [...pane.querySelectorAll('.cmt-thread[data-quote]')]
            .find((t) => t.dataset.quote === quote);
        if (!target) return;
        target.classList.add('on');
        target.scrollIntoView({ block: 'nearest', behavior: 'smooth' });
        target.animate?.([{ background: 'var(--accent-soft)' }, { background: 'transparent' }], 900);
    };

    const mark = (quote) => {
        const walker = document.createTreeWalker(article, NodeFilter.SHOW_TEXT, {
            acceptNode(node) {
                if (!node.nodeValue || !node.nodeValue.includes(quote)) return NodeFilter.FILTER_REJECT;
                for (let el = node.parentElement; el && el !== article; el = el.parentElement) {
                    if (skip.has(el.tagName) || el.classList.contains('cmt-mark')) return NodeFilter.FILTER_REJECT;
                }
                return NodeFilter.FILTER_ACCEPT;
            },
        });
        const node = walker.nextNode();
        if (!node) return false;

        const start = node.nodeValue.indexOf(quote);
        const range = document.createRange();
        range.setStart(node, start);
        range.setEnd(node, start + quote.length);
        const span = document.createElement('span');
        span.className = 'cmt-mark';
        span.dataset.quote = quote;
        span.title = '有评论';
        range.surroundContents(span);
        span.addEventListener('click', () => focusThread(quote));
        return true;
    };

    threads.forEach((t) => mark(t.dataset.quote));
})();
