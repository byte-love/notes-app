// 一关两种玩法：
//   挖空   —— 读原文 → 填空 → 提交
//   模糊背 —— 读原文 → 整篇糊掉、划到哪儿哪儿清楚 → 背完了看原文 → 自评四档
// 状态全在前端，提交时才回服务端。
(() => {
    const root = document.querySelector('.quest-level');
    if (!root) return;

    const checkUrl = root.dataset.check;
    const mode = root.dataset.mode;
    const doc = document.getElementById('q-doc');
    const mark = document.getElementById('q-markdown');
    const check = document.getElementById('step-check');
    const selfStep = document.getElementById('step-self');
    const resultStep = document.getElementById('step-result');
    const startBtn = document.getElementById('q-start');
    const beginBtn = document.getElementById('q-begin');
    const doneBtn = document.getElementById('q-done-recall');
    const submitBtn = document.getElementById('q-submit');
    const againBtn = document.getElementById('q-again');
    const againRecall = document.getElementById('q-again-recall');
    const hintLine = document.getElementById('q-hint');

    const show = (el, on) => {
        if (!el) return;
        el.hidden = !on;
        el.style.display = on ? '' : 'none';
    };

    // ---------------------------------------------------------------- 挖空

    const submitCloze = async () => {
        const inputs = [...check.querySelectorAll('.q-blank')];
        const given = inputs.map((i) => i.value);
        submitBtn.disabled = true;
        try {
            const data = await post({ answers: given });
            renderCloze(data, given);
        } catch (e) {
            fail(e);
        } finally {
            submitBtn.disabled = false;
        }
    };

    if (startBtn) {
        startBtn.addEventListener('click', () => {
            show(doc, false);
            show(document.getElementById('q-actions'), false);
            show(check, true);
            const first = check.querySelector('.q-blank');
            if (first) first.focus();
        });
    }
    if (submitBtn) submitBtn.addEventListener('click', submitCloze);
    if (check) {
        check.addEventListener('keydown', (e) => {
            if (e.key === 'Enter') {
                e.preventDefault();
                submitCloze();
            }
        });
    }

    // ---------------------------------------------------------------- 模糊背

    /** 把正文按 8 字一块包成 span，模糊作用在块上；擦开也是按块。 */
    const wrapChunks = (container) => {
        const walker = document.createTreeWalker(container, NodeFilter.SHOW_TEXT, {
            acceptNode: (n) => (n.nodeValue && n.nodeValue.trim() ? NodeFilter.FILTER_ACCEPT
                : NodeFilter.FILTER_REJECT),
        });
        const nodes = [];
        while (walker.nextNode()) nodes.push(walker.currentNode);
        // 长文切粗一点：两万字按 8 字一块就是两千多个节点，模糊会拖慢滚动
        const long = nodes.reduce((n, node) => n + node.nodeValue.length, 0) > 6000;
        const size = long ? 16 : 8;
        let total = 0;
        nodes.forEach((node) => {
            const text = node.nodeValue;
            const frag = document.createDocumentFragment();
            for (let i = 0; i < text.length; i += size) {
                const span = document.createElement('span');
                span.className = 'fog';
                span.textContent = text.slice(i, i + size);
                frag.appendChild(span);
                total += span.textContent.replace(/\s/g, '').length;
            }
            node.parentNode.replaceChild(frag, node);
        });
        return total;
    };

    let totalChars = 0;
    let hinted = 0;
    let scratching = false;

    const hintPercent = () => (totalChars ? Math.round((hinted * 100) / totalChars) : 0);

    const reveal = (el) => {
        if (!el || !el.classList || !el.classList.contains('fog') || el.classList.contains('hit')) return;
        el.classList.add('hit');
        hinted += el.textContent.replace(/\s/g, '').length;
    };

    // 手指不是一个点：中心上下左右各探一下，擦出来的范围才像真的擦
    const probe = (x, y) => {
        const rx = 9;
        const ry = 12;
        [[0, 0], [rx, 0], [-rx, 0], [0, ry], [0, -ry]].forEach(([dx, dy]) => {
            const el = document.elementFromPoint(x + dx, y + dy);
            if (el && el.classList && el.classList.contains('fog')) reveal(el);
        });
    };

    const clearAllFog = () => {
        mark.querySelectorAll('.fog').forEach((el) => el.classList.add('showed'));
        doc.classList.remove('scratching');
    };

    if (beginBtn) {
        beginBtn.addEventListener('click', () => {
            if (!totalChars) totalChars = wrapChunks(mark);
            doc.classList.add('scratching');
            show(beginBtn, false);
            show(doneBtn, true);
        });
    }

    doc.addEventListener('pointerdown', (e) => {
        if (!doc.classList.contains('scratching')) return;
        scratching = true;
        doc.setPointerCapture?.(e.pointerId);
        probe(e.clientX, e.clientY);
    });
    doc.addEventListener('pointermove', (e) => {
        if (!scratching || !doc.classList.contains('scratching')) return;
        probe(e.clientX, e.clientY);
    });
    const stop = () => {
        scratching = false;
    };
    doc.addEventListener('pointerup', stop);
    doc.addEventListener('pointercancel', stop);
    doc.addEventListener('pointerleave', stop);

    if (doneBtn) {
        doneBtn.addEventListener('click', () => {
            clearAllFog();
            show(doneBtn, false);
            show(selfStep, true);
            if (hintLine) hintLine.textContent = `提示 ${hintPercent()}%`;
        });
    }

    selfStep?.querySelectorAll('[data-self]').forEach((btn) => {
        btn.addEventListener('click', async () => {
            selfStep.querySelectorAll('[data-self]').forEach((b) => { b.disabled = true; });
            try {
                const data = await post({ self: btn.dataset.self, hint: hintPercent() });
                renderRecall(data);
            } catch (e) {
                fail(e);
            } finally {
                selfStep.querySelectorAll('[data-self]').forEach((b) => { b.disabled = false; });
            }
        });
    });

    if (againRecall) {
        againRecall.addEventListener('click', () => location.reload());
    }
    if (againBtn) {
        againBtn.addEventListener('click', () => {
            show(resultStep, false);
            check.querySelectorAll('.q-blank').forEach((i) => { i.value = ''; });
            show(check, true);
            const first = check.querySelector('.q-blank');
            if (first) first.focus();
        });
    }

    // ---------------------------------------------------------------- 提交与结果

    const post = async (payload) => {
        const res = await fetch(checkUrl, {
            method: 'POST',
            credentials: 'same-origin',
            headers: { 'content-type': 'application/json', 'X-CSRF-TOKEN': App.csrf() },
            body: JSON.stringify(payload),
        });
        if (!res.ok) throw new Error(String(res.status));
        return res.json();
    };

    const fillResult = (data, given) => {
        const verdict = document.getElementById('q-verdict');
        const gain = document.getElementById('q-xp');
        const rate = document.getElementById('q-rate');
        const list = document.getElementById('q-answers');

        verdict.textContent = data.passed ? '过关' : '差一点';
        verdict.classList.toggle('on', !!data.passed);
        gain.textContent = data.xp > 0 ? `+${data.xp} XP` : '';
        rate.textContent = data.rate;

        list.textContent = '';
        (data.answers || []).forEach((answer, i) => {
            const li = document.createElement('li');
            const ok = data.marks && data.marks.length ? data.marks[i] : null;
            if (ok === true) li.className = 'ok';
            if (ok === false) li.className = 'bad';
            const no = document.createElement('span');
            no.className = 'quest-ans-no';
            no.textContent = String(i + 1);
            const val = document.createElement('span');
            val.className = 'quest-ans-value';
            val.textContent = answer;
            li.appendChild(no);
            li.appendChild(val);
            if (ok === false && given && given[i]) {
                const mine = document.createElement('span');
                mine.className = 'quest-ans-mine';
                mine.textContent = given[i];
                li.appendChild(mine);
            }
            list.appendChild(li);
        });

        show(check, false);
        show(selfStep, false);
        show(resultStep, true);
        window.scrollTo({ top: 0, behavior: 'smooth' });
    };

    const renderCloze = (data, given) => {
        const m = data.marks || [];
        data.rate = m.length
            ? `答对 ${m.filter(Boolean).length}/${m.length} · ${data.accuracy}%`
            : `正确率 ${data.accuracy}%`;
        fillResult(data, given);
    };

    const renderRecall = (data) => {
        data.answers = [];
        data.marks = [];
        data.rate = `提示 ${data.hint}%`;
        fillResult(data, null);
    };

    const fail = (err) => {
        const rate = document.getElementById('q-rate');
        if (rate) rate.textContent = '提交失败，再试一次';
        console.warn('quest check failed', err);
    };
})();
