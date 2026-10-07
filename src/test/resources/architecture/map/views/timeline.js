'use strict';
(() => {
  // 실행 프로세스별 점 색 — 모듈 그래프의 서브프로젝트 색과 맞춘다 (trading-core=주황, root=파랑)
  const PROC_COLOR = { 'kista-trading': '--tc', 'kista-scheduler': '--api' };
  const procColor = p => `var(${PROC_COLOR[p] ?? '--default'})`;

  /* ---------- 3. 하루 타임라인 ---------- */
  const toMin = t => { const [h, m] = t.split(':').map(Number); return h * 60 + m; };
  const pct = m => (m / 1440 * 100).toFixed(3) + '%';
  function renderTimeline() {
    const grid = [...Array(25).keys()].map(h => `<i class="grid-line" style="left:${pct(h * 60)}"></i>`).join('');
    const kstNow = new Date(Date.now() + 9 * 3600e3);
    const now = `<i class="now" style="left:${pct(kstNow.getUTCHours() * 60 + kstNow.getUTCMinutes())}" title="현재 KST"></i>`;
    let html = `<div class="tl-row tl-axis"><div class="tl-name"></div><div class="tl-track">${[0, 3, 6, 9, 12, 15, 18, 21].map(h =>
      `<span style="left:${pct(h * 60)}">${String(h).padStart(2, '0')}</span>`).join('')}</div></div>`;
    const byProc = Object.groupBy(DATA.jobs.map((j, i) => ({ ...j, i })), j => j.process);
    // 매매 → 배치 → 공용(주기 복구) 순
    const rank = p => ['kista-trading', 'kista-scheduler'].indexOf(p) >>> 0;
    const order = Object.keys(byProc).sort((a, b) => rank(a) - rank(b));
    for (const proc of order) {
      const jobs = byProc[proc];
      const color = procColor(proc);
      html += `<div class="tl-row"><div class="tl-proc"><i class="job" style="position:static;transform:none;background:${color}"></i>${esc(proc)} <span class="hint" style="margin:0">${jobs.length}개</span></div></div>`;
      for (const j of jobs) {
        const dots = j.times.length
          ? j.times.map(t => `<button class="job" data-i="${j.i}" style="left:${pct(toMin(t))};background:${color}" aria-label="${esc(j.name)} ${t}"></button><span class="job-time" style="left:${pct(toMin(t))}">${t}</span>`).join('')
          : `<i class="periodic"></i>`;
        html += `<div class="tl-row clickable" data-i="${j.i}"><div class="tl-name" title="${esc(j.name)}">${esc(j.name.replace(/#.*/, ''))}<small>#${esc(j.name.replace(/.*#/, ''))} · ${esc(j.days)}${j.times.length ? '' : ' ' + esc(j.schedule)}</small></div>
          <div class="tl-track">${grid}${now}${dots}</div></div>`;
      }
    }
    $('tl').innerHTML = html;
    $('tl').querySelectorAll('.tl-row.clickable').forEach(r => r.onclick = () => location.hash = hash('timeline', DATA.jobs[+r.dataset.i].name));
  }

  function panelTimeline() {
    const procs = Object.groupBy(DATA.jobs, j => j.process);
    $('panel').innerHTML = `<div class="p-head"><h2>하루 타임라인</h2><p class="hint">모든 시각은 KST입니다. 행을 누르면 cron 원문과 담당 메서드가 보입니다.</p></div>
      <div class="p-body"><h3>실행 프로세스</h3>${Object.entries(procs).map(([p, js]) =>
        `<p class="desc"><i class="job" style="position:static;display:inline-block;transform:none;background:${procColor(p)}"></i> <b>${esc(p)}</b> — ${js.length}개</p>`).join('')}
        <h3>참고</h3><p class="hint">매매 배치는 cron 발화 뒤에 내부 대기(주문 시각·장 마감)가 이어집니다. 실제 접수 시각은 마감 배치 흐름의 "주문 시각까지 대기" 단계를 보세요.</p>
        <a class="btn" href="#flow/close-batch">마감 배치 흐름 →</a></div>`;
  }

  function panelJob(i) {
    const j = DATA.jobs[i];
    document.querySelectorAll('.tl-row.clickable').forEach(r => r.classList.toggle('sel', +r.dataset.i === i));
    $('panel').innerHTML = `<div class="p-head"><span class="hint">${esc(j.process)} · ${esc(j.module)} 모듈</span><h2>${esc(j.name.replace(/#.*/, ''))}</h2></div>
      <div class="p-body">
        <h3>실행</h3><p class="desc">${esc(j.days)}${j.times.length ? ' ' + j.times.join(', ') + ' KST' : ' · ' + esc(j.schedule)}</p>
        <h3>스케줄 원문</h3><code class="tag">${esc(j.schedule)}</code>
        <h3>담당 메서드</h3><code class="tag">${esc(j.name)}</code>
        ${connections([['시작하는 흐름 단계', (IDX.jobSteps[j.name] ?? []).map(r => chipLink(hash('flow', r.flow, r.step), stepLabel(r)))]])}</div>`;
  }

  renderTimeline();
  // 원형 24h 시계 — 매매 구간 띠는 개장·마감 잡의 실제 cron 시각에서 도출(없으면 띠 없음)
  const BAND = ['TradingOpenScheduler#run', 'TradingCloseScheduler#run'];
  const RING = { 'kista-trading': 120, 'kista-scheduler': 170 }; // 그 외 프로세스 = 220
  const R = 250, C = 260; // 바깥 반지름·중심(viewBox 520)
  const ang = min => min / 1440 * 2 * Math.PI - Math.PI / 2; // 0시 = 12시 방향, 시계방향
  const pt = (r, min) => [C + r * Math.cos(ang(min)), C + r * Math.sin(ang(min))];
  function arc(r, a, b) { // a→b 시계방향(자정 넘김 허용)
    const sweep = (b - a + 1440) % 1440, [x0, y0] = pt(r, a), [x1, y1] = pt(r, b);
    return `M${x0},${y0} A${r},${r} 0 ${sweep > 720 ? 1 : 0} 1 ${x1},${y1}`;
  }

  function renderDial() {
    const ringOf = p => RING[p] ?? 220;
    const procs = [...new Set(DATA.jobs.map(j => j.process))];
    const [open, close] = BAND.map(n => DATA.jobs.find(j => j.name === n));
    let svg = '';
    if (open?.times.length && close?.times.length) {
      const a = Math.min(...open.times.map(toMin)), b = Math.max(...close.times.map(toMin));
      svg += `<path class="band" d="${arc(RING['kista-trading'], a, b)}"><title>매매 구간 ${open.times[0]} → ${close.times.at(-1)}</title></path>`;
    }
    for (const p of procs) {
      const periodic = DATA.jobs.some(j => j.process === p && !j.times.length);
      svg += `<circle class="ring${periodic ? ' periodic' : ''}" cx="${C}" cy="${C}" r="${ringOf(p)}"><title>${esc(p)}${periodic ? ' · 주기 실행 잡 있음' : ''}</title></circle>`;
    }
    for (let h = 0; h < 24; h += 3) {
      const [x, y] = pt(R, h * 60);
      svg += `<text class="hour" x="${x}" y="${y}">${String(h).padStart(2, '0')}</text>`;
    }
    DATA.jobs.forEach(j => j.times.forEach(t => {
      const [x, y] = pt(ringOf(j.process), toMin(t));
      svg += `<a href="${esc(hash('timeline', j.name))}"><circle class="dot-job" cx="${x}" cy="${y}" r="7" style="fill:${procColor(j.process)}"><title>${esc(j.name)} ${t}</title></circle></a>`;
    }));
    const kst = new Date(Date.now() + 9 * 3600e3), [hx, hy] = pt(R - 24, kst.getUTCHours() * 60 + kst.getUTCMinutes());
    svg += `<line class="hand" x1="${C}" y1="${C}" x2="${hx}" y2="${hy}"><title>현재 KST</title></line>`;
    $('tl-dial').innerHTML = `<svg viewBox="0 0 520 520" role="img" aria-label="24시간 잡 시계">${svg}</svg>
      <div class="legend">${procs.map(p => `<span><i class="job" style="position:static;transform:none;background:${procColor(p)}"></i>${esc(p)} · 반지름 ${ringOf(p)}</span>`).join('')}
      <span>점선 링 = 주기 실행 잡 · 띠 = 매매 구간</span></div>`;
  }

  let linear = false;
  $('tl-mode').onclick = () => {
    linear = !linear;
    $('tl').hidden = !linear;
    $('tl-dial').hidden = linear;
    $('tl-mode').textContent = linear ? '원형 보기' : '선형 보기';
  };
  renderDial();
  $('tl').hidden = true;

  VIEWS.timeline = {
    show([name]) {
      const i = DATA.jobs.findIndex(j => j.name === name);
      if (i >= 0) return panelJob(i);
      document.querySelectorAll('.tl-row.sel').forEach(r => r.classList.remove('sel'));
      panelTimeline();
    },
  };
})();
