// 学习计时 + 番茄钟：只在页面可见、且当前标签是「主标签」时累计。
// 时长每 30 秒批量上报；提醒和 1 分钟休息都在前端，服务端只收数字。
(() => {
    const meta = document.querySelector('meta[name="user-name"]');
    if (!meta) return;
    const user = meta.getAttribute('content') || '';

    const PREF_KEY = 'pomodoro:' + user;
    const LEADER_KEY = 'study-leader:' + user;
    const STATE_KEY = 'pomo-state:' + user;
    const todayKey = () => {
        const d = new Date();
        return d.getFullYear() + '-' + (d.getMonth() + 1) + '-' + d.getDate();
    };
    const loadSeconds = () => {
        try {
            const raw = sessionStorage.getItem(STATE_KEY);
            if (!raw) return 0;
            const s = JSON.parse(raw);
            if (!s || s.day !== todayKey()) return 0;
            return Math.max(0, Math.min(1199, Number(s.seconds) || 0));
        } catch (e) {
            return 0;
        }
    };

    const path = location.pathname.replace(/\/+$/, '');
    const ROUTES = [
        ['/app/algo', 'algo'],
        ['/app/bagu', 'bagu'],
        ['/app/projects', 'project'],
        ['/app/notes', 'notes'],
    ];
    const hit = ROUTES.find(([p]) => path === p || path.startsWith(p + '/'));
    if (!hit) return;
    const category = hit[1];

    const pomodoroOn = () => {
        try {
            return localStorage.getItem(PREF_KEY) !== 'off';
        } catch (e) {
            return true;
        }
    };

    const buffer = Object.create(null);
    const tabId = Math.random().toString(36).slice(2);
    let visibleSeconds = pomodoroOn() ? loadSeconds() : 0;
    let leader = false;
    let onBreak = false;
    let breakEnd = 0;
    let reminding = false;

    const saveSeconds = () => {
        try {
            sessionStorage.setItem(STATE_KEY, JSON.stringify({ seconds: visibleSeconds, day: todayKey() }));
        } catch (e) {}
    };

    const isLeader = () => {
        try {
            const now = Date.now();
            const raw = localStorage.getItem(LEADER_KEY);
            const cur = raw ? JSON.parse(raw) : null;
            if (!cur || cur.id === tabId || now - cur.ts > 6000) {
                localStorage.setItem(LEADER_KEY, JSON.stringify({ id: tabId, ts: now }));
                return true;
            }
            return false;
        } catch (e) {
            return true;
        }
    };

    const releaseLeader = () => {
        try {
            const cur = JSON.parse(localStorage.getItem(LEADER_KEY) || 'null');
            if (cur && cur.id === tabId) localStorage.removeItem(LEADER_KEY);
        } catch (e) {}
    };

    const flush = (beacon) => {
        Object.keys(buffer).forEach((cat) => {
            const sec = buffer[cat];
            if (!sec || sec <= 0) return;
            if (beacon) {
                const body = new URLSearchParams({ category: cat, seconds: String(sec), _csrf: App.csrf() });
                navigator.sendBeacon('/app/api/study/tick', body);
                buffer[cat] = 0;
                return;
            }
            App.post('/app/api/study/tick', { category: cat, seconds: sec }).then((res) => {
                if (res.ok) buffer[cat] = Math.max(0, buffer[cat] - sec);
            }).catch(() => {});
        });
    };

    setInterval(() => {
        if (document.visibilityState === 'visible') leader = isLeader();
    }, 2000);

    setInterval(() => flush(false), 30000);

    document.addEventListener('visibilitychange', () => {
        if (document.visibilityState === 'hidden') {
            flush(true);
            saveSeconds();
            releaseLeader();
            leader = false;
        } else {
            leader = isLeader();
        }
    });
    window.addEventListener('pagehide', () => {
        flush(true);
        saveSeconds();
    });

    /* ---------- 提醒卡片 ---------- */
    const mask = document.createElement('div');
    mask.className = 'pomo-mask';
    mask.hidden = true;
    mask.innerHTML = `
        <div class="pomo-card" role="dialog" aria-modal="true" aria-labelledby="pomo-title">
            <h2 id="pomo-title">看了 20 分钟，歇一下</h2>
            <p>看看窗外，让眼睛放松 1 分钟。</p>
            <div class="pomo-actions">
                <button type="button" class="btn-quiet" data-act="break">知道了</button>
                <button type="button" class="btn-quiet" data-act="snooze">再看 10 分钟</button>
            </div>
            <p class="pomo-hint">可以在设置里关掉番茄钟</p>
        </div>`;
    document.body.appendChild(mask);

    const breakCard = document.createElement('div');
    breakCard.className = 'pomo-break';
    breakCard.hidden = true;
    breakCard.innerHTML = `
        <div class="pomo-break-main">
            <span class="pomo-break-title">眺望远方</span>
            <strong class="pomo-break-time">01:00</strong>
        </div>
        <button type="button" class="btn-quiet" data-act="resume">回来继续</button>`;
    document.body.appendChild(breakCard);

    const breakTitle = breakCard.querySelector('.pomo-break-title');
    const breakTime = breakCard.querySelector('.pomo-break-time');

    /* ---------- 右下角常驻计时器 ---------- */
    const RING = 150.8;
    const fab = document.createElement('button');
    fab.type = 'button';
    fab.className = 'study-fab';
    fab.setAttribute('aria-label', '学习计时');
    fab.innerHTML = `
        <svg viewBox="0 0 64 64" class="study-clock" aria-hidden="true">
            <path d="M14 15a9 9 0 0 1 9-7" class="clock-bell"></path>
            <path d="M50 15a9 9 0 0 0-9-7" class="clock-bell"></path>
            <path d="M24 55 21 60" class="clock-bell"></path>
            <path d="M40 55 43 60" class="clock-bell"></path>
            <circle cx="32" cy="34" r="24" class="ring-bg" transform="rotate(-90 32 34)"></circle>
            <circle cx="32" cy="34" r="24" class="ring-fg" transform="rotate(-90 32 34)"></circle>
        </svg>
        <span class="study-time">20:00</span>`;
    document.body.appendChild(fab);

    const panel = document.createElement('div');
    panel.className = 'study-panel';
    panel.hidden = true;
    panel.innerHTML = `
        <div class="study-panel-row"><span>今日学习</span><b>--</b></div>
        <a href="/app/stats">学习统计</a>`;
    document.body.appendChild(panel);

    const fabTime = fab.querySelector('.study-time');
    const ringFg = fab.querySelector('.ring-fg');
    let todayText = '';

    const fmtDuration = (sec) => {
        const m = Math.round(sec / 60);
        const h = Math.floor(m / 60);
        return h ? `${h} 小时 ${m % 60} 分` : `${m} 分`;
    };

    const loadToday = async () => {
        if (todayText) return;
        try {
            const res = await fetch('/app/api/study/stats?days=1', { credentials: 'same-origin', cache: 'no-store' });
            const data = await res.json();
            todayText = fmtDuration(data.totals.today);
        } catch (e) {
            todayText = '--';
        }
        panel.querySelector('b').textContent = todayText;
    };

    fab.addEventListener('click', (e) => {
        e.stopPropagation();
        panel.hidden = !panel.hidden;
        if (!panel.hidden) loadToday();
    });

    document.addEventListener('click', (e) => {
        if (panel.hidden) return;
        if (panel.contains(e.target) || fab.contains(e.target)) return;
        panel.hidden = true;
    });

    if (!pomodoroOn()) {
        fab.hidden = true;
    }

    setInterval(() => {
        const left = Math.max(0, 1200 - visibleSeconds);
        fabTime.textContent = String(Math.floor(left / 60)).padStart(2, '0') + ':' + String(left % 60).padStart(2, '0');
        ringFg.style.strokeDashoffset = String(RING * (1 - Math.min(1, visibleSeconds / 1200)));
    }, 1000);

    const showReminder = () => {
        if (reminding) return;
        reminding = true;
        mask.hidden = false;
    };

    const startBreak = () => {
        mask.hidden = true;
        panel.hidden = true;
        fab.hidden = true;
        reminding = false;
        visibleSeconds = 0;
        saveSeconds();
        onBreak = true;
        breakEnd = Date.now() + 60000;
        breakTitle.textContent = '眺望远方';
        breakTime.textContent = '01:00';
        breakCard.hidden = false;
    };

    const endBreak = () => {
        onBreak = false;
        breakTitle.textContent = '休息结束，继续吧';
        breakTime.textContent = '00:00';
        if (pomodoroOn()) fab.hidden = false;
        setTimeout(() => {
            if (!onBreak) breakCard.hidden = true;
        }, 1500);
    };

    mask.addEventListener('click', (e) => {
        const act = e.target.closest('[data-act]');
        if (!act) return;
        if (act.dataset.act === 'break') {
            startBreak();
        } else {
            mask.hidden = true;
            reminding = false;
            visibleSeconds = 1200 - 600;
            saveSeconds();
        }
    });

    breakCard.addEventListener('click', (e) => {
        if (!e.target.closest('[data-act="resume"]')) return;
        endBreak();
        breakCard.hidden = true;
    });

    setInterval(() => {
        if (!onBreak) return;
        const left = Math.max(0, Math.ceil((breakEnd - Date.now()) / 1000));
        breakTime.textContent = String(Math.floor(left / 60)).padStart(2, '0') + ':' + String(left % 60).padStart(2, '0');
        if (left <= 0) endBreak();
    }, 250);

    /* ---------- 每秒累计 ---------- */
    setInterval(() => {
        if (document.visibilityState !== 'visible') return;
        if (onBreak) return;
        if (!leader) return;
        buffer[category] = (buffer[category] || 0) + 1;
        visibleSeconds++;
        if (visibleSeconds % 5 === 0) saveSeconds();
        if (visibleSeconds < 1200) return;
        if (pomodoroOn()) {
            showReminder();
        } else {
            visibleSeconds = 0;
            saveSeconds();
        }
    }, 1000);
})();
