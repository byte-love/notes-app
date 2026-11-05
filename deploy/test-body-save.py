#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""正文保存自测：确认「在正文里改一个字再保存」不会破坏代码块。

背景（2026-11-04 踩过两次）：代码块高亮后每一行是 <span class="line">，换行只在 CSS 里；
正文保存走的是把 .markdown 序列化回 Markdown，如果从显示 DOM 里取 textContent，
所有行会被粘成一行，代码块顶上的语言条还会被当成正文段落写进去。

做法：登录 → 打开指定正文页 → 拦掉保存请求（**不写库**）→ 往正文敲一个字触发自动保存
→ 把 POST 里的 body 解出来，比对「页面里每块代码的行数」和「保存内容里 ``` 块的行数」。

跑两遍：
  第一遍 正常页面；
  第二遍 先把 .line 的 class 拆掉（模拟以后改高亮结构），验证序列化取的是 el.dataset.raw
         而不是显示 DOM —— 这一遍挂了说明「保存依赖了显示结构」，得改回 dataset.raw。

用法：
    NOTES_USER=owner NOTES_PASS=**** python3 deploy/test-body-save.py \\
        http://47.104.210.93:8180 /app/bagu/bagu-java-050

也可以用 NOTES_BASE / NOTES_PATH 传地址和路径。退出码 0 = 通过。
"""
import os
import sys
from urllib.parse import parse_qs

try:
    from playwright.sync_api import sync_playwright
except ImportError:
    sys.exit('需要 playwright：pip install playwright && playwright install chromium')

TRIGGER = """() => {
    const body = document.querySelector('.doc .markdown');
    body.focus();
    const p = body.querySelector('p') || body;
    const r = document.createRange();
    r.selectNodeContents(p);
    r.collapse(false);
    const s = window.getSelection();
    s.removeAllRanges();
    s.addRange(r);
    document.execCommand('insertText', false, 'X');
    body.dispatchEvent(new InputEvent('input', { bubbles: true }));
}"""

INSPECT = """() => {
    const md = document.querySelector('.doc .markdown');
    if (!md) return null;
    return {
        editable: md.getAttribute('contenteditable') === 'true',
        blocks: [...md.querySelectorAll('pre')].map(
            (pre) => pre.querySelectorAll('span.line').length),
        stray: [...md.querySelectorAll('p')].filter(
            (p) => /^(java|Java|javascript|JavaScript|sql|SQL)$/.test(p.textContent.trim())).length,
    };
}"""


def fail(msg: str) -> int:
    print('失败：' + msg)
    return 1


def main() -> int:
    base = sys.argv[1] if len(sys.argv) > 1 else os.environ.get('NOTES_BASE', '')
    path = sys.argv[2] if len(sys.argv) > 2 else os.environ.get('NOTES_PATH', '')
    if not base or not path:
        sys.exit('用法：test-body-save.py <base> <path>（或设 NOTES_BASE / NOTES_PATH）')
    user = os.environ.get('NOTES_USER')
    pwd = os.environ.get('NOTES_PASS')
    if not user or not pwd:
        sys.exit('需要 NOTES_USER / NOTES_PASS 环境变量')

    base = base.rstrip('/')
    captured = {}

    with sync_playwright() as p:
        browser = p.chromium.launch()
        ctx = browser.new_context(viewport={'width': 1705, 'height': 939},
                                  ignore_https_errors=True)
        page = ctx.new_page()

        def on_request(req):
            if req.method == 'POST' and '/app/admin/' in req.url and req.url.endswith('/body'):
                try:
                    captured['body'] = req.post_data or ''
                except Exception:
                    captured['body'] = ''

        page.on('request', on_request)
        # 拦掉真正写库的请求：只观察，不落库
        page.route('**/app/admin/*/body', lambda route: route.abort())

        page.goto(base + '/app/login', wait_until='domcontentloaded', timeout=45000)
        page.fill("input[name='username']", user)
        page.fill("input[name='password']", pwd)
        page.click("button[type='submit']")
        page.wait_for_load_state('networkidle', timeout=45000)

        page.goto(base + path, wait_until='networkidle', timeout=45000)
        page.wait_for_timeout(400)

        dom = page.evaluate(INSPECT)
        if not dom:
            browser.close()
            return fail('页面里没有 .doc .markdown')
        if not dom['editable']:
            browser.close()
            return fail('正文不可编辑（要登录作者本人的账号）')
        if not dom['blocks']:
            browser.close()
            print('这篇没有代码块，跳过')
            return 0

        dom_lines = dom['blocks']
        print('页面代码块行数:', dom_lines)

        for round_no in (1, 2):
            if round_no == 2:
                # 模拟「以后高亮结构变了」：把行标记拆掉，序列化仍应从 dataset.raw 取到换行
                page.evaluate("() => document.querySelectorAll('.markdown pre span.line')"
                              ".forEach((s) => s.classList.remove('line'))")
            captured.pop('body', None)
            page.evaluate(TRIGGER)
            page.wait_for_timeout(2600)
            label = '第一遍' if round_no == 1 else '第二遍（拆掉行标记）'
            if 'body' not in captured:
                return fail('%s：没抓到保存请求（正文没触发 input，或账号不是作者本人）' % label)
            md_text = parse_qs(captured['body']).get('body', [''])[0]
            if not md_text:
                return fail('%s：保存请求里没有 body 字段' % label)
            saved = [len(chunk.split('\n')) for chunk in md_text.split('```')[1::2]]
            print('%s 保存内容行数: %s' % (label, saved))
            # 每块至少一行，且不能比页面里的行数少（差 1~2 行是语言行 / 结尾换行）
            if any(n < d for n, d in zip(saved, dom_lines)) or len(saved) < len(dom_lines):
                return fail('%s：保存后代码块行数对不上，代码被压扁了' % label)
            if round_no == 1 and dom['stray']:
                return fail('正文里多出 %d 个只有语言名的段落（语言条被写进正文了）' % dom['stray'])

        browser.close()

    print('通过：两遍保存都保留了代码块的行，没有语言条残留')
    return 0


if __name__ == '__main__':
    sys.exit(main())
