'use strict';
(() => {
  let currentLc = null, selectedT = -1;

  /* ---------- 4. 상태 생명주기 (#lifecycle/<id>/<전이 번호>) ---------- */
  const START = '__start', END = '__end'; // 생성·삭제 가상 노드
  $('lc-chips').innerHTML = Object.entries(MAP.lifecycles).map(([id, l]) =>
    `<button class="chip" data-lc="${esc(id)}">${esc(l.title)}</button>`).join('');
  document.querySelectorAll('#lc-chips .chip').forEach(c => c.onclick = () => location.hash = 'lifecycle/' + c.dataset.lc);

  function renderLifecycle(id) {
    currentLc = id; selectedT = -1;
    const lc = MAP.lifecycles[id];
    document.querySelectorAll('#lc-chips .chip').forEach(c => c.setAttribute('aria-pressed', c.dataset.lc === id));
    $('lc-title').textContent = lc.title;
    $('lc-sub').innerHTML = `<code>${esc(lc.enum)}</code> · ${esc(lc.summary)}`;
    const hasStart = lc.transitions.some(t => !t.from), hasEnd = lc.transitions.some(t => !t.to);
    $('lc').innerHTML = `<div class="lc" id="lc-row">
      ${hasStart ? `<div class="st pseudo" data-s="${START}">● 생성</div>` : ''}
      ${lc.states.map(st => `<div class="st${st.terminal ? ' terminal' : ''}" data-s="${esc(st.id)}" title="${esc(st.desc)}"><b>${esc(st.id)}</b><small>${esc(st.label)}</small></div>`).join('')}
      ${hasEnd ? `<div class="st pseudo" data-s="${END}">삭제 ⊗</div>` : ''}
      <svg class="arcs" id="arcs"></svg></div>`;
    $('lc-table').innerHTML = `<tr><th>#</th><th>전이</th><th>계기</th><th>구분</th></tr>` + lc.transitions.map((t, i) =>
      `<tr class="row-t" data-i="${i}"><td>${i + 1}</td><td class="mono">${esc(t.from ?? '생성')} → ${esc(t.to ?? '삭제')}</td><td>${esc(t.title)}</td><td>${esc(t.tag ?? '')}</td></tr>`).join('');
    $('lc-table').querySelectorAll('.row-t').forEach(r => r.onclick = () => location.hash = `lifecycle/${id}/${r.dataset.i}`);
  }

  // 상태를 한 줄에 놓고 전이를 호로 그린다 — 앞으로 가는 전이는 위, 되돌아가는 전이·자기 전이는 아래
  function drawArcs() {
    const row = $('lc-row'), svg = $('arcs');
    if (!row || row.offsetParent === null) return;
    const base = row.getBoundingClientRect();
    const pos = Object.fromEntries([...row.querySelectorAll('.st')].map(el => {
      const r = el.getBoundingClientRect();
      return [el.dataset.s, { x: (r.left + r.right) / 2 - base.left, t: r.top - base.top, b: r.bottom - base.top, w: r.width }];
    }));
    const order = Object.keys(pos);
    const seen = {}; // 같은 노드 쌍·같은 방향 전이가 겹치지 않도록 높이를 늘린다
    let d = '';
    MAP.lifecycles[currentLc].transitions.forEach((t, i) => {
      const f = t.from ?? START, to = t.to ?? END, a = pos[f], b = pos[to];
      const self = f === to, fwd = !self && order.indexOf(f) < order.indexOf(to);
      const key = [f, to].sort().join('|') + fwd;
      const k = seen[key] = (seen[key] ?? 0) + 1;
      const on = i === selectedT ? ' class="on"' : '';
      // 높이 상한 — .lc 위아래 여백(130px) 안에 호·번호가 들어가도록
      const h = Math.min((self ? 30 : 24 + Math.abs(b.x - a.x) * 0.16) + (k - 1) * 16, 108);
      const dir = fwd ? -1 : 1, y0 = fwd ? a.t : a.b, y1 = fwd ? b.t : b.b; // 가상 노드는 높이가 달라 끝점을 각자 잡는다
      const x0 = self ? a.x - a.w / 4 : a.x + (fwd ? 6 : -6), x1 = self ? a.x + a.w / 4 : b.x + (fwd ? -6 : 6);
      const peak = (fwd ? Math.min(y0, y1) : Math.max(y0, y1)) + dir * h;
      const cy = peak + dir * h / 3; // 3차 베지어 꼭짓점이 대략 peak가 되도록
      d += `<path${on} d="M${x0},${y0} C${x0},${cy} ${x1},${cy} ${x1},${y1}" marker-end="url(#ah${on ? '-on' : ''})"/>`;
      d += `<text${on} x="${(x0 + x1) / 2}" y="${peak + (fwd ? -4 : 12)}">${i + 1}</text>`;
    });
    svg.innerHTML = `<defs>${['', '-on'].map(s => `<marker id="ah${s}" viewBox="0 0 10 10" refX="9" refY="5" markerWidth="7" markerHeight="7" orient="auto-start-reverse">
      <path d="M0,0 L10,5 L0,10 z" style="fill:var(${s ? '--entity' : '--muted'});stroke:none;opacity:1"/></marker>`).join('')}</defs>` + d;
  }
  window.addEventListener('resize', drawArcs);

  function selectTransition(i) {
    const lc = MAP.lifecycles[currentLc];
    selectedT = i >= 0 && i < lc.transitions.length ? i : -1;
    $('lc-table').querySelectorAll('.row-t').forEach(r => r.classList.toggle('sel', +r.dataset.i === selectedT));
    const t = lc.transitions[selectedT];
    document.querySelectorAll('#lc-row .st').forEach(el => el.classList.toggle('on', !!t && [t.from ?? START, t.to ?? END].includes(el.dataset.s)));
    requestAnimationFrame(drawArcs);
    if (!t) return panelLifecycle(lc);
    const [fid, sid] = (t.flow ?? '').split('/');
    const link = t.flow ? `${MAP.flows[fid].title}${sid ? ' · ' + MAP.flows[fid].steps.find(s => s.id === sid).title : ''}` : '';
    $('panel').innerHTML = `<div class="p-head"><span class="hint">${selectedT + 1} / ${lc.transitions.length}${t.tag ? ' · ' + esc(t.tag) : ''}</span>
        <h2>${esc(t.from ?? '생성')} → ${esc(t.to ?? '삭제')}</h2></div>
      <div class="p-body"><h3>계기</h3><p class="desc" style="margin-top:0"><b>${esc(t.title)}</b></p>
        ${t.desc ? `<p class="desc">${esc(t.desc)}</p>` : ''}
        ${t.code.length ? `<h3>담당 코드</h3><div class="tags">${t.code.map(c => `<code class="tag">${esc(c)}</code>`).join('')}</div>` : ''}
        ${t.flow ? `<h3>흐름</h3><a class="btn" href="#flow/${esc(t.flow)}">${esc(link)} →</a>` : ''}</div>`;
  }

  function panelLifecycle(lc) {
    $('panel').innerHTML = `<div class="p-head"><h2>${esc(lc.title)}</h2><p class="hint"><code>${esc(lc.enum)}</code></p></div>
      <div class="p-body"><p class="desc">${esc(lc.summary)}</p>
        <h3>상태</h3>${lc.states.map(st => `<p class="desc" style="margin-top:6px"><code class="tag">${esc(st.id)}</code> ${esc(st.label)}${st.desc ? ` — <span class="hint">${esc(st.desc)}</span>` : ''}</p>`).join('')}
        <h3>보는 법</h3><p class="hint">표의 행을 누르면 전이 계기와 담당 코드가 보입니다. 상태 목록은 enum 상수와 같은지 테스트가 검증합니다.</p></div>`;
  }

  VIEWS.lifecycle = {
    enabled: Object.keys(MAP.lifecycles).length > 0, // 정의가 없으면 탭 숨김
    show([lcId, t]) {
      const id = Object.hasOwn(MAP.lifecycles, lcId ?? '') ? lcId : Object.keys(MAP.lifecycles)[0];
      if (id !== currentLc) renderLifecycle(id);
      selectTransition(/^\d+$/.test(t ?? '') ? +t : -1);
    },
    home: () => '#lifecycle/' + (currentLc ?? Object.keys(MAP.lifecycles)[0]),
  };
})();
