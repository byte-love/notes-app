#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""给页面拍图，用来人眼确认排版（改样式之后别只看 DOM）。

    NOTES_USER=owner NOTES_PASS=**** python3 deploy/shot.py <base-url> <路径…>

产物：$SHOT_DIR（默认 ./_shots）下每个路径两张：`<name>-top.png`（首屏）
和 `<name>-full.png`（整页）。路径里的斜杠换成短横线。
"""
import os
import sys

from playwright.sync_api import sync_playwright

BASE = sys.argv[1] if len(sys.argv) > 1 else 'http://127.0.0.1:8180/app'
PATHS = sys.argv[2:] or ['/notes']
OUT = os.environ.get('SHOT_DIR', '_shots')
USER = os.environ.get('NOTES_USER')
PASS = os.environ.get('NOTES_PASS')
WIDTH = int(os.environ.get('SHOT_WIDTH', '1600'))
HEIGHT = int(os.environ.get('SHOT_HEIGHT', '1000'))

if not USER or not PASS:
    sys.exit('需要 NOTES_USER / NOTES_PASS')
os.makedirs(OUT, exist_ok=True)

with sync_playwright() as p:
    browser = p.chromium.launch(channel='msedge', headless=True)
    ctx = browser.new_context(viewport={'width': WIDTH, 'height': HEIGHT}, ignore_https_errors=True)
    page = ctx.new_page()
    page.goto(BASE + '/login', wait_until='domcontentloaded')
    page.fill('input[name=username]', USER)
    page.fill('input[name=password]', PASS)
    with page.expect_navigation(wait_until='domcontentloaded'):
        page.click('button[type=submit]')
    for path in PATHS:
        page.goto(BASE + path, wait_until='load')
        page.wait_for_timeout(350)
        name = path.strip('/').replace('/', '-') or 'home'
        page.screenshot(path=f'{OUT}/{name}-top.png')
        page.screenshot(path=f'{OUT}/{name}-full.png', full_page=True)
        print(f'{name}: 节点 {page.evaluate("document.getElementsByTagName(\'*\').length")} 个')
    browser.close()
