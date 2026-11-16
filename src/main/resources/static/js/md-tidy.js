/*
 * 整理排版：把「粘坏了的」纯文本 Markdown 还原出结构。
 * 只做结构还原，不改文字：
 *   ① 补回列表符号（·/•/・/* → -）
 *   ② 把「编号标题 + 第一句」被空格粘成一行的拆开
 *   ③ 编号重排（1 1 1 → 1 2 3）
 *   ④ 短标题行升成 ## n. 标题；总结 / 小结 转成 ## 总结 + 列表
 * 用法：window.MdTidy.tidy(markdown)
 * 编辑页的「整理排版」按钮和正文页的菜单项都用它，逻辑只保留这一份。
 */
(() => {
    'use strict';

    const NUMBERED = /^(\d{1,2})[.、．]\s*/;
    const BULLET = /^[\s\u3000]*[·•・\-*+]\s*/;
    // 「标题 + 第一句」被空格粘成一行，拆成「标题 + 一条 bullet」
    const STUCK_BULLET = /^(.{4,}?[。？：）】」』])\s{1,}([\u4e00-\u9fa5A-Za-z0-9（）()]{2,8}[：:？?])\s*(.+)$/;
    // 「标题 + 一整段」粘成一行，拆成「标题 + 一段正文」
    const STUCK_PARA = /^(.{4,30}?[。？）】」』])\s{1,}(.{20,})$/;
    // 收尾小节：总结 / 小结 后面跟一串「短标签：内容」
    const SUMMARY = /^(总结|小结)[：:\s]+(.+)$/;
    const LABEL = /^[\u4e00-\u9fa5A-Za-z0-9（）()]{2,10}[：:]/;

    /**
     * 整理排版：只做「结构还原」，不改文字。
     * 处理四件事：① 补回列表符号 ② 把粘成一行的「编号标题 + 第一句」拆开
     * ③ 编号顺手重排（1 1 1 → 1 2 3） ④ 段落 / 列表之间补空行
     */
    const tidyMarkdown = (md) => {
        const src = md.replace(/\r\n/g, '\n').split('\n');
        const out = [];
        let sectionNo = 0;
        let inList = false;

        const blank = () => {
            if (out.length && out[out.length - 1] !== '') out.push('');
        };

        for (const raw of src) {
            let line = raw.replace(/\s+$/, '');
            if (!line.trim()) {
                blank();
                continue;
            }

            // ① 列表：各种项目符号统一成 "- "
            if (BULLET.test(line) && !NUMBERED.test(line)) {
                if (!/^- /.test(out[out.length - 1] || '')) blank();
                out.push('- ' + line.replace(BULLET, '').trim());
                inList = true;
                continue;
            }

            // ② 编号小节：重排编号，并把粘在标题后面的第一句拆出去
            const num = NUMBERED.exec(line);
            if (num) {
                sectionNo += 1;
                const rest = line.replace(NUMBERED, '').trim();
                blank();
                const asBullet = STUCK_BULLET.exec(rest);
                const asPara = STUCK_PARA.exec(rest);
                const head = asBullet ? asBullet[1] : (asPara ? asPara[1] : rest);
                const plain = head.trim();
                // 短、且不以句号收尾的，当小标题（## 1. xxx）；长的才留成有序列表项
                if (plain.length <= 40 && !/[。；]$/.test(plain)) {
                    out.push('## ' + sectionNo + '. ' + plain);
                } else {
                    out.push(sectionNo + '. ' + plain);
                }
                if (asBullet) {
                    blank();
                    out.push('- ' + asBullet[2] + ' ' + asBullet[3].trim());
                } else if (asPara) {
                    blank();
                    out.push(asPara[2].trim());
                }
                blank();
                inList = false;
                continue;
            }

            // ③ 收尾小节：## 总结 + 后面几条「标签：内容」变成 bullet
            const summary = SUMMARY.exec(line);
            if (summary) {
                blank();
                out.push('## ' + summary[1]);
                blank();
                out.push('- ' + summary[2].trim());
                blank();
                inList = true;
                continue;
            }

            // ④ 跟在列表后面的「标签：内容」继续当 bullet
            if (inList && LABEL.test(line)) {
                out.push('- ' + line.trim());
                continue;
            }

            inList = false;
            out.push(line);
        }

        return out.join('\n')
            .replace(/：[ 　\t]+/g, '：')
            .replace(/\n{3,}/g, '\n\n')
            .replace(/^\n+/, '')
            .replace(/\n+$/, '') + '\n';
    };



    window.MdTidy = { tidy: tidyMarkdown };
})();
