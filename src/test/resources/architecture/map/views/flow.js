'use strict';
(() => {
  const laneIndex = Object.fromEntries(MAP.lanes.map((l, i) => [l.id, i]));
  const laneLabel = Object.fromEntries(MAP.lanes.map(l => [l.id, l.label]));
  let currentFlow = null, selected = -1, timer = null, renderedModule = null; // renderedModule: 그릴 때 반영한 모듈 필터

  /* ---------- 2. 스윔레인 ---------- */
  $('flow-chips').innerHTML = Object.entries(MAP.flows).map(([id, f]) =>
    `<button class="chip" data-flow="${esc(id)}">${esc(f.title)}</button>`).join('');
  document.querySelectorAll('#flow-chips .chip').forEach(c => c.onclick = () => location.hash = 'flow/' + c.dataset.flow);

  function renderFlow(id) {
    currentFlow = id; selected = -1;
    const flow = MAP.flows[id];
    document.querySelectorAll('#flow-chips .chip').forEach(c => c.setAttribute('aria-pressed', c.dataset.flow === id));
    $('flow-title').textContent = flow.title;
    $('flow-sub').innerHTML = `<b>계기</b> ${esc(flow.trigger)} · ${esc(flow.summary)}`;

    const cols = flow.steps.length;
    let html = `<div class="lanes" id="lanes" style="grid-template-columns: 110px repeat(${cols}, 160px); grid-template-rows: repeat(${MAP.lanes.length}, minmax(64px, auto))">`;
    // 레인 배경 줄
    MAP.lanes.forEach((l, r) => {
      const cls = (r === 0 ? ' first' : '') + (r % 2 ? ' alt' : '');
      html += `<div class="lane-label${cls}" style="grid-row:${r + 1};grid-column:1">${esc(l.label)}</div>`;
      html += `<div class="lane-cell${cls}" style="grid-row:${r + 1};grid-column:2 / span ${cols}"></div>`;
    });
    // 단계 카드 + 상대 레인 접점
    flow.steps.forEach((s, i) => {
      const marks = [s.state && '<i class="mark state">상태</i>', s.fail && '<i class="mark fail">실패</i>', s.cond && '<i class="mark cond">조건</i>'].filter(Boolean).join('');
      html += `<button class="step ${esc(s.lane)}" id="step-${i}" data-i="${i}" style="grid-row:${laneIndex[s.lane] + 1};grid-column:${i + 2}">
        <span class="n">${i + 1}</span><b>${esc(s.title)}</b>${marks ? `<span class="marks">${marks}</span>` : ''}</button>`;
      s.to.forEach(t => html += `<i class="touch" data-from="${i}" data-to="${esc(t)}" style="grid-row:${laneIndex[t] + 1};grid-column:${i + 2}" title="${esc(laneLabel[t])}"></i>`);
    });
    html += `<svg class="links" id="links"></svg></div>`;
    $('swim').innerHTML = html;
    // 구조 탭에서 고른 모듈을 거치는 단계만 강조
    const m = renderedModule = SHARED.module;
    $('lanes').classList.toggle('modfilter', !!m);
    flow.steps.forEach((s, i) => $('step-' + i).classList.toggle('mod-hit', !!m && s.modules.includes(m)));
    $('mod-filter').hidden = !m;
    $('mod-filter').textContent = m ? `모듈: ${m} ✕` : '';
    document.querySelectorAll('.step').forEach(b => b.onclick = () => location.hash = `flow/${id}/${flow.steps[b.dataset.i].id}`);
    requestAnimationFrame(drawLinks);
  }

  // 순서 연결선(단계→다음 단계)과 호출선(단계→상대 레인 접점)을 그린다
  function drawLinks() {
    const lanes = $('lanes'), svg = $('links');
    if (!lanes || lanes.offsetParent === null) return;
    const base = lanes.getBoundingClientRect();
    const box = el => { const r = el.getBoundingClientRect(); return { l: r.left - base.left, r: r.right - base.left, t: r.top - base.top, b: r.bottom - base.top, cx: (r.left + r.right) / 2 - base.left, cy: (r.top + r.bottom) / 2 - base.top }; };
    const steps = [...lanes.querySelectorAll('.step')].map(box);
    let d = '';
    for (let i = 0; i + 1 < steps.length; i++) {
      const a = steps[i], b = steps[i + 1], mx = (a.r + b.l) / 2;
      d += `<path class="seq" d="M${a.r},${a.cy} C${mx},${a.cy} ${mx},${b.cy} ${b.l},${b.cy}"/>`;
    }
    lanes.querySelectorAll('.touch').forEach(t => {
      const s = steps[t.dataset.from], p = box(t);
      const y1 = p.cy > s.cy ? s.b : s.t;
      d += `<path class="call" d="M${s.cx},${y1} L${p.cx},${p.cy}"/>`;
    });
    svg.innerHTML = d;
  }
  window.addEventListener('resize', drawLinks);

  function select(i, scroll = true) {
    const flow = MAP.flows[currentFlow];
    selected = i;
    document.querySelectorAll('.step').forEach(b => b.classList.toggle('selected', +b.dataset.i === i));
    $('lanes')?.classList.toggle('dim', i >= 0);
    $('prev').disabled = i <= 0;
    $('next').disabled = i >= flow.steps.length - 1;
    if (i < 0) { SHARED.step = null; return panelFlow(flow); }
    const el = $('step-' + i);
    if (scroll) el.scrollIntoView({ block: 'nearest', inline: 'center', behavior: 'smooth' });
    SHARED.step = { flow: currentFlow, step: flow.steps[i].id };
    stepPanel(currentFlow, i, k => hash('flow', currentFlow, flow.steps[k].id));
  }

  function panelFlow(flow) {
    const states = flow.steps.filter(s => s.state).length, fails = flow.steps.filter(s => s.fail).length;
    $('panel').innerHTML = `<div class="p-head"><h2>${esc(flow.title)}</h2><p class="hint">${esc(flow.trigger)}</p></div>
      <div class="p-body"><p class="desc">${esc(flow.summary)}</p>
        <h3>구성</h3><p class="desc">단계 ${flow.steps.length}개 · 상태 변화 ${states}곳 · 실패 경로 ${fails}곳</p>
        <h3>보는 법</h3><p class="hint">단계를 누르면 업무 설명과 담당 코드가 보입니다. ▶ 재생은 단계를 순서대로 하이라이트합니다.</p></div>`;
  }

  $('mod-filter').onclick = () => { SHARED.module = null; renderFlow(currentFlow); select(selected, false); };

  const go = i => location.hash = `flow/${currentFlow}/${MAP.flows[currentFlow].steps[i].id}`;
  $('prev').onclick = () => selected > 0 && go(selected - 1);
  $('next').onclick = () => go(Math.min(selected + 1, MAP.flows[currentFlow].steps.length - 1));

  // ▶ 재생 — 1.4초 간격으로 다음 단계 하이라이트, 끝나면 정지
  function stop() {
    clearInterval(timer); timer = null;
    $('play').textContent = '▶ 재생';
    document.querySelectorAll('.step.playing').forEach(b => b.classList.remove('playing'));
  }
  $('play').onclick = () => {
    if (timer) return stop();
    const n = MAP.flows[currentFlow].steps.length;
    let i = selected >= 0 && selected < n - 1 ? selected : -1;
    const tick = () => {
      i++;
      if (i >= n) return stop();
      document.querySelectorAll('.step').forEach(b => b.classList.toggle('playing', +b.dataset.i === i));
      select(i);
      history.replaceState(null, '', `#flow/${currentFlow}/${MAP.flows[currentFlow].steps[i].id}`);
    };
    tick();
    timer = setInterval(tick, 1400);
    $('play').textContent = '■ 정지';
  };

  VIEWS.flow = {
    show([flowId, stepId]) {
      const id = Object.hasOwn(MAP.flows, flowId ?? '') ? flowId : Object.keys(MAP.flows)[0];
      // 흐름이나 구조 탭에서 고른 모듈 필터가 바뀔 때만 다시 그린다
      if (id !== currentFlow || SHARED.module !== renderedModule) renderFlow(id); else requestAnimationFrame(drawLinks);
      select(MAP.flows[id].steps.findIndex(s => s.id === stepId));
    },
    leave: stop,
    // 공유 단계 → 그 단계, 고른 모듈 → 그 모듈을 처음 거치는 흐름, 아니면 현재 흐름
    home: () => SHARED.step ? hash('flow', SHARED.step.flow, SHARED.step.step)
      : hash('flow', (SHARED.module && IDX.modSteps[SHARED.module]?.[0]?.flow) ?? currentFlow ?? Object.keys(MAP.flows)[0]),
  };
})();
