"""列表页在真实浏览器里的开销：首屏、每个「加载更多」批次、DOM 规模、长任务。

    $env:NOTES_USER / $env:NOTES_PASS  登录凭据（不写进文件）
    python deploy/perf-probe.py http://47.104.210.93:8180/app /app/bagu /app/notes

输出每个页面：
    server   —— 导航请求的 TTFB / 传输字节 / 资源个数
    render   —— DOMContentLoaded、load、DOM 节点数
    batch    —— 每滚一批的耗时与累计节点数（滚到底才停）
    long     —— 长任务（>50ms）个数与最长一个
"""
import json
import os
import sys

from playwright.sync_api import sync_playwright

USER = os.environ.get('NOTES_USER')
PASS = os.environ.get('NOTES_PASS')

MEASURE = """
() => {
  const nav = performance.getEntriesByType('navigation')[0] || {};
  const res = performance.getEntriesByType('resource');
  let bytes = 0, n = 0;
  for (const r of res) { bytes += r.transferSize || 0; n += 1; }
  return {
    ttfb: Math.round(nav.responseStart || 0),
    dom: Math.round(nav.domContentLoadedEventEnd || 0),
    load: Math.round(nav.loadEventEnd || 0),
    bytes: bytes + (nav.transferSize || 0),
    requests: n + 1,
    nodes: document.getElementsByTagName('*').length,
    cards: document.querySelectorAll('.item-title').length,
    more: !!document.getElementById('list-more'),
  };
}
"""

LONG_TASKS = """
() => {
  window.__long = window.__long || [];
  try {
    new PerformanceObserver((list) => {
      for (const e of list.getEntries()) window.__long.push(Math.round(e.duration));
    }).observe({ entryTypes: ['longtask'] });
  } catch (e) {}
  return true;
}
"""


def main():
    base = sys.argv[1].rstrip('/')
    paths = sys.argv[2:] or ['/app/bagu']
    # 允许写成 /app/bagu：base 里已经带了 context path 就别再拼一遍
    ctx_path = base[len('http://'):].split('/', 1)
    ctx_path = '/' + ctx_path[1] if len(ctx_path) > 1 else ''
    paths = [p[len(ctx_path):] if ctx_path and p.startswith(ctx_path + '/') else p for p in paths]
    if not USER or not PASS:
        sys.exit('需要 NOTES_USER / NOTES_PASS')

    with sync_playwright() as p:
        browser = p.chromium.launch(channel='msedge', headless=True)
        ctx = browser.new_context(viewport={'width': 1440, 'height': 900})
        page = ctx.new_page()
        page.goto(base + '/login', wait_until='domcontentloaded')
        page.fill('input[name=username]', USER)
        page.fill('input[name=password]', PASS)
        with page.expect_navigation(wait_until='domcontentloaded'):
            page.click('button[type=submit]')
        if '/login' in page.url:
            sys.exit('登录失败，停在 ' + page.url)

        for path in paths:
            page.goto(base + path, wait_until='load')
            page.wait_for_timeout(300)
            first = page.evaluate(MEASURE)
            page.evaluate(LONG_TASKS)
            print(f'\n=== {path}')
            print(f"  首屏 ttfb={first['ttfb']}ms dcl={first['dom']}ms load={first['load']}ms "
                  f"requests={first['requests']} bytes={first['bytes']} "
                  f"nodes={first['nodes']} cards={first['cards']} more={first['more']}")

            rounds = 0
            while rounds < 12 and page.evaluate("!!document.getElementById('list-more')"):
                before = page.evaluate("document.getElementsByTagName('*').length")
                spent = page.evaluate(
                    """async () => {
                        const t0 = performance.now();
                        const more = document.getElementById('list-more');
                        more.scrollIntoView({ block: 'end' });
                        const stop = document.getElementById('list-more') === more;
                        // 等这一批插入完成（锚点被换掉或消失）
                        await new Promise((resolve) => {
                            const tick = () => {
                                if (document.getElementById('list-more') !== more) resolve();
                                else requestAnimationFrame(tick);
                            };
                            tick();
                            setTimeout(resolve, 3000);
                        });
                        return Math.round(performance.now() - t0);
                    }"""
                )
                rounds += 1
                after = page.evaluate("document.getElementsByTagName('*').length")
                page.wait_for_timeout(120)
                print(f'  批次 {rounds}: {spent}ms  nodes {before} -> {after} '
                      f'cards={page.evaluate("document.querySelectorAll(\".item-title\").length")}')
            longs = page.evaluate('window.__long || []')
            longs = sorted(longs, reverse=True)
            print(f"  长任务 {len(longs)} 个" + (f"，最长 {longs[0]}ms，前五 {longs[:5]}" if longs else ''))
        browser.close()


if __name__ == '__main__':
    main()
