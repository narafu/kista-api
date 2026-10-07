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
    $('tl').querySelectorAll('.tl-row.clickable').forEach(r => r.onclick = () => panelJob(+r.dataset.i));
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
        <h3>담당 메서드</h3><code class="tag">${esc(j.name)}</code></div>`;
  }

  renderTimeline();
  VIEWS.timeline = { show: () => panelTimeline() };
})();
