#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""两个页面快照目录的逐页比对：消掉「无语义差异」再 diff。

忽略三类东西：
  1. `;jsessionid=…`（每个会话不一样，anonymous 尤其明显）
  2. CSRF token（`name="_csrf"` 那一行的 value / content，每个会话一个）
  3. 开始标签里属性的顺序（`Map.of` 填充的属性顺序每启动一次都可能变，
     2026-11-05 就因为这个在术语浮窗的 `<span>` 上假报了一次差异）

用法：
    python3 deploy/snap-diff.py <改之前目录> <改之后目录> [归一结果目录]
退出码 1 表示有页面不同（打印文件名和差异行数）。
"""
import pathlib
import re
import sys

TAG = re.compile(r'<([a-zA-Z][a-zA-Z0-9-]*)((?:\s+[a-zA-Z-]+(?:="[^"]*")?)*)\s*/?>')
ATTR = re.compile(r'([a-zA-Z-]+)(?:="([^"]*)")?')
SESSION = re.compile(r';jsessionid=[0-9A-F]+')


def normalize_html(text):
    text = SESSION.sub('', text)

    def fix(m):
        name, raw = m.group(1), m.group(2)
        attrs = ATTR.findall(raw)
        if not attrs:
            return m.group(0)
        values = dict(attrs)
        # CSRF token 每个会话都不一样，抹成 T 再比
        if values.get('name') == '_csrf':
            for key in ('value', 'content'):
                if key in values:
                    values[key] = 'T'
        parts = [k if v is None else k + '="' + v + '"' for k, v in sorted(values.items())]
        return '<' + name + ' ' + ' '.join(parts) + '>'

    return TAG.sub(fix, text)


def main() -> int:
    if len(sys.argv) < 3:
        sys.exit('用法：snap-diff.py <改之前目录> <改之后目录> [归一结果目录]')
    before = pathlib.Path(sys.argv[1])
    after = pathlib.Path(sys.argv[2])
    out = pathlib.Path(sys.argv[3]) if len(sys.argv) > 3 else None
    if out:
        out.mkdir(parents=True, exist_ok=True)

    bad = 0
    names = sorted(p.name for p in before.glob('*.html'))
    for name in names:
        other = after / name
        if not other.exists():
            print('缺文件（改之后没有抓到）:', name)
            bad += 1
            continue
        a = normalize_html((before / name).read_text(encoding='utf-8')).splitlines()
        b = normalize_html(other.read_text(encoding='utf-8')).splitlines()
        if a == b:
            continue
        bad += 1
        diff = [(i, x, y) for i, (x, y) in enumerate(zip(a, b), 1) if x != y]
        print('不同: %-26s %d 行不同（也可能只是行的先后顺序）' % (name, len(diff) + abs(len(a) - len(b))))
        for i, x, y in diff[:3]:
            print('   %s 第 %d 行' % (name, i))
            print('     - ' + x.strip()[:150])
            print('     + ' + y.strip()[:150])
        if out:
            (out / ('a-' + name)).write_text('\n'.join(a), encoding='utf-8')
            (out / ('b-' + name)).write_text('\n'.join(b), encoding='utf-8')
    print('比对完成：%d 个页面，%d 个有差异' % (len(names), bad))
    return 1 if bad else 0


if __name__ == '__main__':
    sys.exit(main())
