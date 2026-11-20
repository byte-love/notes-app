// 学习统计：取一次数据，画三张原生 SVG 图（柱状 / 环形 / 折线），不引第三方库。
(() => {
    const CATS = [
        ['algo', '算法'],
        ['bagu', '八股'],
        ['project', '项目'],
        ['notes', '闪念'],
        ['interview', '面试'],
    ];
    const NAME = Object.fromEntries(CATS);

    const fmt = (sec) => {
        const m = Math.round(sec / 60);
        const h = Math.floor(m / 60);
        return h ? `${h} 小时 ${m % 60} 分` : `${m} 分`;
    };
    const svgEl = (tag, attrs = {}, text) => {
        const n = document.createElementNS('http://www.w3.org/2000/svg', tag);
        for (const [k, v] of Object.entries(attrs)) n.setAttribute(k, v);
        if (text != null) n.textContent = text;
        return n;
    };
    const chart = (w, h) => svgEl('svg', { viewBox: `0 0 ${w} ${h}`, class: 'chart-svg', role: 'img' });

    const niceStep = (raw) => {
        const pow = Math.pow(10, Math.floor(Math.log10(Math.max(raw, 1))));
        const n = raw / pow;
        const m = n <= 1 ? 1 : n <= 2 ? 2 : n <= 5 ? 5 : 10;
        return m * pow;
    };

    const axis = (s, max, y, W, L, R) => {
        const step = niceStep(max / 4);
        for (let v = 0; v <= max; v += step) {
            s.appendChild(svgEl('line', { x1: L, x2: W - R, y1: y(v), y2: y(v), class: 'chart-grid' }));
            s.appendChild(svgEl('text', { x: L - 10, y: y(v) + 4, 'text-anchor': 'end', class: 'chart-axis' }, Math.round(v)));
        }
    };

    const renderBar = (days) => {
        const data = days.slice(-14);
        const W = 920, H = 240, L = 44, R = 12, T = 22, B = 28;
        const plotW = W - L - R, plotH = H - T - B;
        const peak = Math.max(30, ...data.map((d) => d.total / 60));
        const tick = niceStep(peak / 4);
        const max = Math.ceil(peak / tick) * tick;
        const s = chart(W, H);
        const y = (v) => T + plotH - (v / max) * plotH;

        axis(s, max, y, W, L, R);
        const step = plotW / data.length;
        const bw = Math.min(30, step * 0.52);
        data.forEach((d, i) => {
            const cx = L + step * i + step / 2;
            const mins = d.total / 60;
            const isToday = i === data.length - 1;
            s.appendChild(svgEl('rect', {
                x: cx - bw / 2, y: y(mins), width: bw, height: Math.max(y(0) - y(mins), 1), rx: 5,
                class: isToday ? 'chart-bar today' : 'chart-bar',
            }));
            if (d.total > 0) {
                s.appendChild(svgEl('text', { x: cx, y: y(mins) - 6, 'text-anchor': 'middle', class: 'chart-value' }, Math.round(mins)));
            }
            if (i % 2 === 0 || isToday) {
                s.appendChild(svgEl('text', { x: cx, y: H - 8, 'text-anchor': 'middle', class: 'chart-axis' }, d.day.slice(5)));
            }
        });
        document.getElementById('barChart').appendChild(s);
    };

    const renderPie = (todayBy) => {
        const items = CATS.map(([key, name]) => [key, name, todayBy[key] || 0]).filter(([, , v]) => v > 0);
        const total = items.reduce((a, [, , v]) => a + v, 0);
        if (!total) return;

        const W = 220, H = 220, cx = W / 2, cy = H / 2, r = 78, thick = 20;
        const s = chart(W, H);
        s.classList.add('chart-donut');
        s.appendChild(svgEl('circle', { cx, cy, r: r - thick / 2, class: 'chart-donut-bg', 'stroke-width': thick }));
        let a0 = -Math.PI / 2;
        const pad = 0.02;
        items.forEach(([key, , v]) => {
            const a1 = a0 + (v / total) * Math.PI * 2;
            const large = a1 - a0 > Math.PI ? 1 : 0;
            const x0 = cx + Math.cos(a0 + pad) * r, y0 = cy + Math.sin(a0 + pad) * r;
            const x1 = cx + Math.cos(a1 - pad) * r, y1 = cy + Math.sin(a1 - pad) * r;
            s.appendChild(svgEl('path', {
                d: `M ${x0} ${y0} A ${r} ${r} 0 ${large} 1 ${x1} ${y1}`,
                class: `chart-arc c-${key}`, 'stroke-width': thick,
            }));
            a0 = a1;
        });
        s.appendChild(svgEl('text', { x: cx, y: cy - 4, 'text-anchor': 'middle', class: 'chart-total' }, fmt(total)));
        s.appendChild(svgEl('text', { x: cx, y: cy + 18, 'text-anchor': 'middle', class: 'chart-axis' }, '今日合计'));
        document.getElementById('pieChart').appendChild(s);

        document.getElementById('pieLegend').innerHTML = items.map(([key, name, v]) =>
            `<div class="stats-legend-row">
                <span><i class="stats-dot c-${key}"></i>${name}</span>
                <span>${fmt(v)} · ${Math.round(v / total * 100)}%</span>
            </div>`).join('');
    };

    const renderLine = (days) => {
        const W = 920, H = 220, L = 44, R = 12, T = 16, B = 28;
        const plotW = W - L - R, plotH = H - T - B;
        const peak = Math.max(30, ...days.map((d) => d.total / 60));
        const tick = niceStep(peak / 4);
        const max = Math.ceil(peak / tick) * tick;
        const s = chart(W, H);
        const y = (v) => T + plotH - (v / max) * plotH;
        axis(s, max, y, W, L, R);
        const x = (i) => L + (i / Math.max(1, days.length - 1)) * plotW;
        const pts = days.map((d, i) => `${x(i)},${y(d.total / 60)}`).join(' ');
        const area = `M ${x(0)},${y(0)} L ${days.map((d, i) => `${x(i)},${y(d.total / 60)}`).join(' L ')} L ${x(days.length - 1)},${y(0)} Z`;
        s.appendChild(svgEl('path', { d: area, class: 'chart-area' }));
        s.appendChild(svgEl('polyline', { points: pts, class: 'chart-line' }));
        days.forEach((d, i) => {
            if (i % 5 === 0 || i === days.length - 1) {
                s.appendChild(svgEl('circle', { cx: x(i), cy: y(d.total / 60), r: 3, class: 'chart-dot' }));
            }
            if (i % 7 === 0) {
                s.appendChild(svgEl('text', { x: x(i), y: H - 8, 'text-anchor': 'middle', class: 'chart-axis' }, d.day.slice(5)));
            }
        });
        document.getElementById('lineChart').appendChild(s);
    };

    const run = async () => {
        let data;
        try {
            const res = await fetch('/app/api/study/stats?days=30', { credentials: 'same-origin', cache: 'no-store' });
            if (!res.ok) return;
            data = await res.json();
        } catch (e) {
            return;
        }
        document.getElementById('totalToday').textContent = fmt(data.totals.today);
        document.getElementById('totalWeek').textContent = fmt(data.totals.week);
        document.getElementById('totalMonth').textContent = fmt(data.totals.month);

        if (!data.totals.month) {
            document.getElementById('statsEmpty').hidden = false;
            ['barSection', 'pieSection', 'lineSection'].forEach((id) => {
                document.getElementById(id).hidden = true;
            });
            return;
        }

        const today = data.days[data.days.length - 1].by || {};
        renderBar(data.days);
        if (data.totals.today > 0) {
            renderPie(today);
        } else {
            document.getElementById('pieSection').hidden = true;
        }
        renderLine(data.days);
    };

    run();
})();
