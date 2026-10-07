'use strict';
(() => {
  // 의존 종류별 표시 이름·색 변수
  const TYPES = {
    USES_COMPONENT: ['컴포넌트 사용', '--uses'],
    EVENT_LISTENER: ['이벤트 구독', '--event'],
    ENTITY: ['엔티티 참조', '--entity'],
    DEFAULT: ['타입 참조', '--default'],
  };
  const TYPE_PRIORITY = ['EVENT_LISTENER', 'USES_COMPONENT', 'ENTITY', 'DEFAULT']; // 엣지 색은 가장 의미 있는 종류로
  const INFRA = new Set(['platform', 'sharedkernel']); // 거의 모든 모듈이 참조하는 노이즈 모듈
  // Gradle 서브프로젝트별 색·배치 영역(모델 좌표) — api 좌상, trading-core 우상, shared 하단 중앙
  const PROJECTS = {
    ':api': { accent: '--api', tint: '--api-tint', box: { x1: 0, y1: 0, x2: 520, y2: 520 } },
    ':trading-core': { accent: '--tc', tint: '--tc-tint', box: { x1: 640, y1: 0, x2: 1240, y2: 520 } },
    ':shared': { accent: '--shared', tint: '--shared-tint', box: { x1: 300, y1: 640, x2: 940, y2: 720 } },
  };
  const byName = Object.fromEntries(DATA.modules.map(m => [m.name, m]));
  const enabled = new Set(Object.keys(TYPES));

  // 의존 종류 필터 칩
  $('types').insertAdjacentHTML('beforeend', Object.entries(TYPES).map(([k, [label, color]]) =>
    `<label class="chip check"><input type="checkbox" data-type="${k}" checked><i class="dot" style="background:var(${color})"></i>${label}</label>`).join(''));

  const hidden = name => $('hideInfra').checked && INFRA.has(name);
  const visible = d => enabled.has(d.type) && !hidden(d.target); // 패널도 그래프 필터와 동일하게
  const outgoing = name => byName[name].deps.filter(visible);
  const incoming = name => DATA.modules.filter(s => !hidden(s.name))
    .flatMap(s => s.deps.filter(d => d.target === name && visible(d)).map(d => ({ ...d, from: s.name })));

  const expanded = new Set(); // 펼친 서브프로젝트 — 처음엔 모두 접힘
  const shown = name => expanded.has(byName[name].project) ? name : byName[name].project; // 모듈이 그려지는 노드 id
  const visibleModules = () => DATA.modules.filter(m => !hidden(m.name));

  // 현재 필터 기준으로 표시 노드 쌍별 엣지 집계 — 접힌 프로젝트는 박스 하나로 합산
  function edges() {
    const groups = {};
    for (const m of visibleModules()) {
      for (const d of outgoing(m.name)) {
        const s = shown(m.name), t = shown(d.target);
        if (s !== t) (groups[`${s}->${t}`] ??= { source: s, target: t, deps: [] }).deps.push(d);
      }
    }
    return Object.entries(groups).map(([id, { source, target, deps }]) => {
      const type = TYPE_PRIORITY.find(t => deps.some(d => d.type === t));
      return { data: { id, source, target, count: deps.length, color: css(TYPES[type][1]),
                       width: Math.min(1 + Math.log2(deps.length + 1) * 1.2, 8) } };
    });
  }

  function elements() {
    const mods = visibleModules();
    const projects = Object.entries(PROJECTS).map(([p, c]) => {
      const n = mods.filter(m => m.project === p).length;
      return { data: { id: p, label: expanded.has(p) ? p : `${p} · ${n}`, accent: css(c.accent), tint: css(c.tint) },
               classes: expanded.has(p) ? '' : 'proj' };
    });
    const nodes = mods.filter(m => expanded.has(m.project)).map(m => ({
      data: { id: m.name, label: m.name, parent: m.project, accent: css(PROJECTS[m.project].accent), tint: css(PROJECTS[m.project].tint),
              weight: outgoing(m.name).length + incoming(m.name).length },
    }));
    return [...projects, ...nodes, ...edges()];
  }


  // 같은 해시면 hashchange가 안 나므로 직접 라우팅
  const go = h => location.hash === h ? route() : location.hash = h;
  // 스타일 — css() 값을 박아 두므로 테마 전환 시 다시 만든다
  function styles() {
    const shadow = isDark() ? 0 : 0.06; // 랜드스케이프·흐름 카드의 옅은 그림자 — 다크에선 없음
    return [
      // 모듈 = 카드: 흰 바탕·옅은 프로젝트색 테두리·둥근 모서리
      { selector: 'node', style: {
          label: 'data(label)', 'text-valign': 'center', 'font-size': 13, 'font-weight': 600, color: css('--fg'),
          'font-family': getComputedStyle(document.body).fontFamily,
          'background-color': css('--surface'), 'border-width': 1.5, 'border-color': 'data(accent)', 'border-opacity': 0.45,
          shape: 'round-rectangle', 'corner-radius': 10, width: 'label', height: 36, padding: 14,
          'underlay-color': '#000', 'underlay-opacity': shadow, 'underlay-padding': 2, 'underlay-shape': 'round-rectangle',
          'transition-property': 'opacity, border-opacity, background-color', 'transition-duration': 150 } },
      // 프로젝트 영역 = 그룹: 옅은 틴트 바탕·실선 테두리·작은 회색 제목(랜드스케이프 그룹 제목 톤)
      { selector: ':parent', style: {
          'background-color': 'data(tint)', 'background-opacity': 0.55, 'border-width': 1, 'border-style': 'solid',
          'border-color': css('--line'), 'border-opacity': 1, color: css('--muted'), 'text-valign': 'top', 'text-halign': 'left',
          'text-margin-x': 14, 'text-margin-y': 22, 'font-size': 12, 'font-weight': 600, padding: 30,
          shape: 'round-rectangle', 'corner-radius': 14, 'underlay-opacity': 0 } },
      { selector: 'edge', style: {
          width: 'mapData(count, 1, 60, 1, 4)', 'line-color': 'data(color)', 'target-arrow-color': 'data(color)',
          'target-arrow-shape': 'triangle', 'arrow-scale': 0.7, 'curve-style': 'bezier', opacity: 0.3,
          'transition-property': 'opacity', 'transition-duration': 150 } },
      { selector: 'edge.hover', style: { opacity: 0.85 } },
      { selector: 'edge.flowing', style: { opacity: 0.85, 'line-style': 'dashed', 'line-dash-pattern': [6, 4] } },
      { selector: 'node.hover', style: { 'border-opacity': 1, 'underlay-opacity': shadow * 2 } },
      { selector: '.faded', style: { opacity: 0.15 } },
      { selector: ':parent.faded', style: { opacity: 0.5 } },
      { selector: 'node.focus', style: { 'border-opacity': 1 } },
      // 선택 = 카드 hover 톤(틴트 바탕·진한 테두리), 글자는 그대로
      { selector: 'node.selected', style: { 'background-color': 'data(tint)', 'border-width': 2, 'border-opacity': 1 } },
      { selector: 'edge.focus', style: {
          opacity: 0.9, label: 'data(count)', 'font-size': 11, 'font-weight': 700, color: css('--fg'),
          'text-background-color': css('--surface'), 'text-background-opacity': 1, 'text-background-padding': 3,
          'text-background-shape': 'round-rectangle', 'text-border-width': 1, 'text-border-color': css('--line'), 'text-border-opacity': 1 } },
      // 접힌 프로젝트 = 큰 카드
      { selector: 'node.proj', style: {
          'background-color': css('--surface'), 'border-color': 'data(accent)', 'border-opacity': 0.55, color: css('--fg'),
          'font-size': 15, 'font-weight': 700, width: 180, height: 64, 'corner-radius': 12 } },
      { selector: 'node.proj.hover', style: { 'background-color': 'data(tint)', 'border-opacity': 1 } },
      { selector: 'node.ov-trail', style: { 'border-opacity': 1 } },
      { selector: 'node.ov-on', style: { 'background-color': css('--tc-tint'), 'border-color': css('--tc'), 'border-width': 2, 'border-opacity': 1 } },
      { selector: 'edge.ov-trail', style: { opacity: 0.35, 'line-color': css('--tc'), 'target-arrow-color': css('--tc') } },
      { selector: 'edge.ov-on', style: { opacity: 1, width: 3, 'line-color': css('--tc'), 'target-arrow-color': css('--tc') } },
      { selector: 'edge.ov-virtual', style: { 'line-style': 'dashed', width: 1.5 } },
      { selector: 'node.ov-dot', style: { width: 12, height: 12, padding: 0, label: '', shape: 'ellipse',
          'background-color': css('--tc'), 'border-width': 0, 'underlay-opacity': 0, events: 'no' } },
    ];
  }

  let cy; // 첫 show()에서 생성 — 숨긴 컨테이너에서 만들면 0×0으로 잡혀 fit이 깨진다
  function init() {
    cy = cytoscape({
      container: $('cy'),
      wheelSensitivity: 0.25,
      minZoom: 0.3, maxZoom: 2.5,
      style: styles(),
    });

    cy.on('tap', 'node', e => {
      if (e.target.hasClass('proj')) { expanded.add(e.target.id()); return render(); } // 접힌 박스 → 펼침
      if (!e.target.isParent()) go(hash('structure', e.target.id()));
    });
    cy.on('tap', 'edge', e => {
      // 박스 합계 엣지는 클래스 쌍 대상이 아니다 — 양끝 박스를 펼친다
      const ends = e.target.connectedNodes();
      if (ends.some(n => n.hasClass('proj'))) { ends.forEach(n => { if (n.hasClass('proj')) expanded.add(n.id()); }); return render(); }
      if (e.target.hasClass('ov-virtual')) return; // 오버레이 가상 엣지엔 클래스 의존이 없다
      if (ov) clearOverlay();
      // 엣지 선택은 해시로 표현하지 않는다 — 모듈 해시를 비워 같은 모듈 재탭이 다시 라우팅되게
      SHARED.module = null;
      history.replaceState(null, '', '#structure');
      focus(e.target); showEdge(e.target.data('source'), e.target.data('target'));
    });
    cy.on('tap', e => { if (e.target === cy) go('#structure'); });
    // 노드 hover → 연결 엣지에 흐르는 dash(방향 표시), 모션 감소 설정이면 정적 dash
    let dashOffset = 0, dashRaf = 0;
    const dashLoop = () => {
      const flowing = cy.edges('.flowing');
      if (!flowing.length) { dashRaf = 0; return; } // 재렌더로 hover 노드가 사라져 mouseout이 안 온 경우
      dashOffset = (dashOffset + 0.6) % 1000;
      flowing.style('line-dash-offset', -dashOffset);
      dashRaf = requestAnimationFrame(dashLoop);
    };
    cy.on('mouseover', 'node, edge', e => {
      if (e.target.isNode() && e.target.isParent()) return;
      e.target.addClass('hover');
      $('cy').style.cursor = 'pointer';
      if (e.target.isNode()) {
        e.target.connectedEdges().addClass('flowing');
        if (!REDUCED && !dashRaf) dashRaf = requestAnimationFrame(dashLoop);
      }
    });
    cy.on('mouseout', 'node, edge', e => {
      e.target.removeClass('hover');
      $('cy').style.cursor = '';
      cy.edges('.flowing').removeClass('flowing');
      cancelAnimationFrame(dashRaf); dashRaf = 0;
    });
    render();
  }

  // 서브프로젝트 영역마다 의존이 가장 많은 모듈을 가운데, 나머지를 원형으로 배치 — 매번 같은 위치(결정적)
  function layout() {
    for (const [p, { box }] of Object.entries(PROJECTS)) {
      if (!expanded.has(p)) { cy.getElementById(p).position({ x: (box.x1 + box.x2) / 2, y: (box.y1 + box.y2) / 2 }); continue; }
      const kids = cy.getElementById(p).children();
      if (!kids.length) continue;
      const hub = kids.max(n => n.data('weight')).ele;
      const opts = kids.length <= 3
        ? { name: 'grid', rows: 1 }
        : { name: 'concentric', concentric: n => (n === hub ? 2 : 1), levelWidth: () => 1, minNodeSpacing: 40 };
      kids.layout({ ...opts, boundingBox: box, animate: false, fit: false }).run();
    }
    cy.fit(undefined, 40);
  }

  function render() {
    cy.elements().remove();
    cy.add(elements());
    layout();
    $('summary').textContent = `· 모듈 ${visibleModules().length} · 의존 쌍 ${cy.edges().length}`;
    $('expand').textContent = expanded.size === Object.keys(PROJECTS).length ? '모두 접기' : '모두 펼치기';
    clearFocus();
    if (ov) showOverlay(ov.fid, ov.i); // 요소를 갈아엎었으니 오버레이 재적용
  }

  const typeDot = t => `<i class="dot" style="background:var(${TYPES[t][1]})"></i>`;
  const projectBadge = p => `<span class="badge" style="color:var(${PROJECTS[p].accent});background:var(${PROJECTS[p].tint})">${esc(p)}</span>`;
  const modLink = name => `<a class="mod-link" href="${esc(hash('structure', name))}">${esc(name)}</a>`;
  const tags = xs => xs.length ? `<div class="tags">${xs.map(x => `<span class="tag mono">${esc(x)}</span>`).join('')}</div>` : '<p class="empty">없음</p>';

  // 종류 비율 막대
  function bar(deps) {
    return `<span class="bar">${TYPE_PRIORITY.map(t => {
      const n = deps.filter(d => d.type === t).length;
      return n ? `<i style="width:${n / deps.length * 100}%;background:var(${TYPES[t][1]})" title="${TYPES[t][0]} ${n}"></i>` : '';
    }).join('')}</span>`;
  }

  // 상대 모듈 → 종류 → 클래스 쌍
  function depList(deps, keyOf, open = false) {
    const groups = Object.entries(Object.groupBy(deps, keyOf)).sort((a, b) => b[1].length - a[1].length);
    if (!groups.length) return '<p class="empty">없음</p>';
    return groups.map(([mod, ds]) => `
      <details class="dep"${open ? ' open' : ''}>
        <summary>${modLink(mod)}${bar(ds)}<span class="count">${ds.length}</span></summary>
        <div class="dep-body">${TYPE_PRIORITY.filter(t => ds.some(d => d.type === t)).map(t => {
          const pairs = ds.filter(d => d.type === t);
          return `<div class="type-row">${typeDot(t)}${TYPES[t][0]} <span class="count">${pairs.length}</span></div>
            <ul class="pairs mono">${pairs.map(d => `<li>${esc(d.source)}<span class="arrow">→</span>${esc(d.targetType)}</li>`).join('')}</ul>`;
        }).join('')}</div>
      </details>`).join('');
  }

  function showModule(name) {
    const m = byName[name];
    const out = outgoing(name), inc = incoming(name);
    $('panel').innerHTML = `
      <div class="p-head">
        <h2>${esc(name)} ${projectBadge(m.project)}</h2>
        <div class="stats">
          <div class="stat"><b>${out.length}</b><span>나가는 의존</span></div>
          <div class="stat"><b>${inc.length}</b><span>들어오는 의존</span></div>
          <div class="stat"><b>${m.listenedEvents.length}</b><span>구독 이벤트</span></div>
        </div>
      </div>
      <div class="p-body">
        <h3>NamedInterface</h3>${tags(m.namedInterfaces)}
        <h3>이 모듈이 의존하는 곳</h3>${depList(out, d => d.target)}
        <h3>이 모듈에 의존하는 곳</h3>${depList(inc, d => d.from)}
        <h3>구독 이벤트</h3>${tags(m.listenedEvents)}
        ${connections([
          ...Object.entries(Object.groupBy(IDX.modSteps[name] ?? [], r => r.flow)).map(([fid, refs]) =>
            [MAP.flows[fid].title, refs.map(r => chipLink(hash('flow', fid, r.step), findStep(r).title))]),
          ['소속 잡', DATA.jobs.filter(j => j.module === name).map(j => chipLink(hash('timeline', j.name), j.name))],
        ])}
      </div>`;
    $('panel').scrollTop = 0;
  }

  function showEdge(source, target) {
    const deps = outgoing(source).filter(d => d.target === target);
    $('panel').innerHTML = `
      <div class="p-head">
        <h2>${modLink(source)}<span class="arrow">→</span>${modLink(target)}</h2>
        <p class="hint">클래스 단위 의존 ${deps.length}건</p>
      </div>
      <div class="p-body"><h3>상세</h3>${depList(deps, d => d.target, true)}</div>`;
    $('panel').scrollTop = 0;
  }

  // 초기 화면: 의존이 많은 순 모듈 목록 — 어디서부터 볼지 안내
  function showOverview() {
    const rows = visibleModules().map(({ name }) => {
      return { name, project: byName[name].project, out: outgoing(name).length, inc: incoming(name).length };
    }).sort((a, b) => (b.out + b.inc) - (a.out + a.inc));
    $('panel').innerHTML = `
      <div class="p-head">
        <h2>모듈 개요</h2>
        <p class="hint">노드를 클릭하면 모듈 상세, 엣지를 클릭하면 두 모듈 사이 클래스 의존을 본다. 빈 곳을 클릭하면 이 화면으로 돌아온다.</p>
      </div>
      <div class="p-body">
        <h3>의존이 많은 순</h3>
        <table class="rank"><thead><tr><th>모듈</th><th>프로젝트</th><th class="num">나감</th><th class="num">들어옴</th></tr></thead>
        <tbody>${rows.map(r => `<tr data-mod="${esc(r.name)}"><td><b>${esc(r.name)}</b></td><td>${projectBadge(r.project)}</td>
          <td class="num">${r.out}</td><td class="num">${r.inc}</td></tr>`).join('')}</tbody></table>
        <p class="hint" style="margin-top:16px">테스트(<code>ArchitectureMapTest</code>) 실행 시 자동 생성 — 직접 수정하지 말 것.</p>
      </div>`;
  }

  // 선택 요소와 이웃만 남기고 흐리게
  function focus(ele) {
    cy.elements().removeClass('focus faded selected');
    const keep = ele.isNode() ? ele.closedNeighborhood() : ele.union(ele.connectedNodes());
    cy.elements().not(keep).addClass('faded');
    keep.addClass('focus');
    if (ele.isNode()) ele.addClass('selected');
  }
  function clearFocus() {
    SHARED.module = null;
    cy.elements().removeClass('focus faded selected');
    showOverview();
  }
  function selectModule(name) {
    if (!expanded.has(byName[name].project)) { expanded.add(byName[name].project); render(); } // 딥링크 진입 시 자동 펼침
    const n = cy.getElementById(name);
    if (!n.length) return;
    SHARED.module = name; SHARED.step = null;
    focus(n);
    showModule(name);
    cy.animate({ center: { eles: n }, duration: 250 });
  }

  // 패널 안 모듈 이름·개요 행 클릭 → 해당 모듈로 이동
  $('panel').addEventListener('click', e => {
    const el = e.target.closest('[data-mod]');
    if (!el) return;
    location.hash = hash('structure', el.dataset.mod);
  });

  document.querySelectorAll('[data-type]').forEach(cb => cb.addEventListener('change', () => {
    cb.checked ? enabled.add(cb.dataset.type) : enabled.delete(cb.dataset.type);
    if (!cy) return;
    render();
    resetHash();
  }));
  $('hideInfra').addEventListener('change', () => { if (cy) { render(); resetHash(); } });
  $('fit').addEventListener('click', () => cy && cy.animate({ fit: { padding: 40 }, duration: 250 }));
  // 필터 변경으로 포커스가 풀렸으면 모듈 해시도 지운다(라우팅 없이) — 오버레이 중엔 해시가 오버레이 위치라 유지
  const resetHash = () => { if (!ov && parseHash().rest.length) history.replaceState(null, '', '#structure'); };

  function expandAll(on = true) {
    const before = expanded.size;
    Object.keys(PROJECTS).forEach(p => on ? expanded.add(p) : expanded.delete(p));
    if (expanded.size !== before) render();
  }
  $('expand').onclick = () => cy && expandAll(expanded.size < Object.keys(PROJECTS).length);

  /* ---------- 흐름 오버레이 ---------- */
  let ov = null, ovTimer = null; // ov: { fid, i }
  $('ov-flow').insertAdjacentHTML('beforeend', Object.entries(MAP.flows).map(([id, f]) =>
    `<option value="${esc(id)}">${esc(f.title)}</option>`).join(''));
  const ovHash = (fid, i) => `${hash('structure')}?flow=${encodeURIComponent(fid)}&step=${encodeURIComponent(MAP.flows[fid].steps[i].id)}`;
  // 단계 i가 그래프에 올리는 모듈 — 숨김·필터 밖은 건너뛴다(스트립·패널에는 남는다)
  const stepNodes = (fid, i) => MAP.flows[fid].steps[i].modules.filter(m => byName[m] && !hidden(m));

  function ovStop() {
    clearInterval(ovTimer); ovTimer = null;
    $('ov-play').textContent = '▶ 재생';
  }

  function clearOverlay() {
    ov = null;
    SHARED.step = null;
    $('expand').disabled = false;
    $('ov-bar').hidden = true;
    $('ov-flow').value = '';
    cy.remove('.ov-virtual, .ov-dot');
    cy.elements().removeClass('ov-on ov-trail');
  }

  function showOverlay(fid, i) {
    const prevOv = ov;
    ov = { fid, i };
    SHARED.step = { flow: fid, step: MAP.flows[fid].steps[i].id };
    expandAll(); // 펼칠 게 있으면 render()가 이 함수를 다시 부른다 — ov를 먼저 세워 둔 이유
    const steps = MAP.flows[fid].steps;
    $('ov-flow').value = fid;
    $('ov-bar').hidden = false;
    $('expand').disabled = true; // 오버레이는 모두 펼친 상태가 전제 — 접기 불가
    $('ov-slider').max = steps.length - 1;
    $('ov-slider').value = i;
    $('ov-prev').disabled = i <= 0;
    $('ov-next').disabled = i >= steps.length - 1;
    $('ov-strip').innerHTML = steps.map((s, k) =>
      `<button data-k="${k}" class="${k === i ? 'on' : k < i ? 'done' : ''}${s.modules.length ? '' : ' nomod'}" title="${esc(s.modules.join(', ') || '모듈 없음')}">${k + 1}. ${esc(s.title)}</button>`).join('');
    $('ov-strip').querySelector('.on')?.scrollIntoView({ block: 'nearest', inline: 'center' });

    // 0..i 단계를 훑으며 연속(모듈 있는) 단계 사이 경로를 만든다 — 의존 엣지가 있으면 그것, 없으면 점선 가상 엣지
    cy.remove('.ov-virtual, .ov-dot');
    cy.elements().removeClass('focus faded selected ov-on ov-trail');
    let prev = [], last = [], from = null;
    const trail = cy.collection();
    for (let k = 0; k <= i; k++) {
      const cur = stepNodes(fid, k);
      if (!cur.length) continue;
      for (const a of prev) for (const b of cur) {
        if (a === b) continue;
        let e = cy.getElementById(`${a}->${b}`).union(cy.getElementById(`${b}->${a}`));
        if (!e.length) e = cy.add({ data: { id: `ov:${k}:${a}->${b}`, source: a, target: b }, classes: 'ov-virtual' });
        trail.merge(e);
        if (k === i) e.addClass('ov-on');
      }
      cur.forEach(m => trail.merge(cy.getElementById(m)));
      if (k === i) { from = prev[0] ?? null; last = cur; }
      prev = cur;
    }
    cy.elements().not(trail).not(':parent').addClass('faded');
    trail.addClass('ov-trail');
    last.forEach(m => cy.getElementById(m).addClass('ov-on'));

    // 이전 단계 모듈 → 현재 단계 모듈로 점 하나 이동 (바로 다음 단계로 넘어갈 때만)
    const to = last[0];
    if (!REDUCED && from && to && prevOv?.fid === fid && prevOv.i === i - 1) {
      const dot = cy.add({ data: { id: 'ov-dot', accent: css('--tc') }, classes: 'ov-dot', position: { ...cy.getElementById(from).position() } });
      dot.animate({ position: { ...cy.getElementById(to).position() } }, { duration: 600, easing: 'ease-in-out-cubic',
        complete: () => dot.remove() });
    }
    stepPanel(fid, i, k => ovHash(fid, k));
  }

  $('ov-flow').onchange = e => location.hash = e.target.value ? ovHash(e.target.value, 0) : '#structure';
  $('ov-slider').oninput = e => { if (ov) location.hash = ovHash(ov.fid, +e.target.value); };
  $('ov-prev').onclick = () => { if (ov && ov.i > 0) location.hash = ovHash(ov.fid, ov.i - 1); };
  $('ov-next').onclick = () => { if (ov) location.hash = ovHash(ov.fid, Math.min(ov.i + 1, MAP.flows[ov.fid].steps.length - 1)); };
  $('ov-strip').onclick = e => { const b = e.target.closest('[data-k]'); if (b) location.hash = ovHash(ov.fid, +b.dataset.k); };
  // ▶ 재생 — 흐름 탭과 같은 1.4초 간격, 해시는 replaceState(라우팅이 재생을 멈추지 않도록)
  $('ov-play').onclick = () => {
    if (ovTimer) return ovStop();
    const n = MAP.flows[ov.fid].steps.length;
    let i = ov.i < n - 1 ? ov.i : -1;
    const tick = () => {
      i++;
      if (i >= n) return ovStop();
      showOverlay(ov.fid, i);
      history.replaceState(null, '', ovHash(ov.fid, i));
    };
    tick();
    ovTimer = setInterval(tick, 1400);
    $('ov-play').textContent = '■ 정지';
  };

  // 테마 전환 — 스타일·엣지 색이 생성 시점 값으로 박혀 있어 다시 계산하고 해시 상태를 복원
  window.addEventListener('themechange', () => {
    if (!cy) return;
    cy.style(styles());
    render();
    if (!$('view-structure').hidden) route();
  });

  VIEWS.structure = {
    show([mod], params) {
      if (!cy) init(); else cy.resize(); // 숨김 중 창 크기 변경 반영
      const fid = params.get('flow');
      if (fid && Object.hasOwn(MAP.flows, fid)) {
        const i = Math.max(0, MAP.flows[fid].steps.findIndex(s => s.id === params.get('step')));
        return showOverlay(fid, i);
      }
      if (ov) clearOverlay();
      if (mod && byName[mod]) {
        if (hidden(mod)) { $('hideInfra').checked = false; render(); } // 숨긴 infra 모듈 딥링크
        selectModule(mod);
      } else {
        clearFocus();
      }
    },
    leave: ovStop,
    // 흐름에서 고른 단계가 있으면 오버레이를 그 단계에 멈춘 채로, 아니면 마지막 모듈
    home: () => SHARED.step
      ? `${hash('structure', findStep(SHARED.step)?.modules.find(m => !hidden(m)))}?flow=${encodeURIComponent(SHARED.step.flow)}&step=${encodeURIComponent(SHARED.step.step)}`
      : hash('structure', SHARED.module ?? undefined),
  };
})();
