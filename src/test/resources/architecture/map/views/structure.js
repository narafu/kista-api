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

  // 현재 필터 기준으로 모듈 쌍별 엣지 집계
  function edges() {
    const out = [];
    for (const m of DATA.modules) {
      if (hidden(m.name)) continue;
      for (const [target, deps] of Object.entries(Object.groupBy(outgoing(m.name), d => d.target))) {
        const type = TYPE_PRIORITY.find(t => deps.some(d => d.type === t));
        out.push({ data: { id: `${m.name}->${target}`, source: m.name, target, count: deps.length,
                           color: css(TYPES[type][1]), width: Math.min(1 + Math.log2(deps.length + 1) * 1.2, 8) } });
      }
    }
    return out;
  }

  function elements() {
    const parents = Object.entries(PROJECTS).map(([p, c]) =>
      ({ data: { id: p, label: p, accent: css(c.accent), tint: css(c.tint) } }));
    const nodes = DATA.modules.filter(m => !hidden(m.name)).map(m => ({
      data: { id: m.name, label: m.name, parent: m.project, accent: css(PROJECTS[m.project].accent),
              weight: outgoing(m.name).length + incoming(m.name).length },
    }));
    return [...parents, ...nodes, ...edges()];
  }


  // 같은 해시면 hashchange가 안 나므로 직접 라우팅
  const go = h => location.hash === h ? route() : location.hash = h;
  let cy; // 첫 show()에서 생성 — 숨긴 컨테이너에서 만들면 0×0으로 잡혀 fit이 깨진다
  function init() {
    cy = cytoscape({
      container: $('cy'),
      wheelSensitivity: 0.25,
      minZoom: 0.3, maxZoom: 2.5,
      style: [
        { selector: 'node', style: {
            label: 'data(label)', 'text-valign': 'center', 'font-size': 13, 'font-weight': 600, color: css('--fg'),
            'font-family': getComputedStyle(document.body).fontFamily,
            'background-color': css('--surface'), 'border-width': 2, 'border-color': 'data(accent)',
            shape: 'round-rectangle', width: 'label', height: 30, padding: 12,
            'transition-property': 'opacity, border-width', 'transition-duration': 150 } },
        { selector: ':parent', style: {
            'background-color': 'data(tint)', 'background-opacity': 1, 'border-width': 1.5, 'border-style': 'dashed',
            'border-color': 'data(accent)', color: 'data(accent)', 'text-valign': 'top', 'text-halign': 'center',
            'text-margin-y': -6, 'font-size': 15, 'font-weight': 700, padding: 28, shape: 'round-rectangle' } },
        { selector: 'edge', style: {
            width: 'data(width)', 'line-color': 'data(color)', 'target-arrow-color': 'data(color)',
            'target-arrow-shape': 'triangle', 'arrow-scale': 0.9, 'curve-style': 'bezier', opacity: 0.45,
            'transition-property': 'opacity', 'transition-duration': 150 } },
        { selector: 'edge.hover', style: { opacity: 0.9 } },
        { selector: 'node.hover', style: { 'border-width': 3 } },
        { selector: '.faded', style: { opacity: 0.08 } },
        { selector: ':parent.faded', style: { opacity: 0.35 } },
        { selector: 'node.focus', style: { 'border-width': 3 } },
        { selector: 'node.selected', style: { 'background-color': 'data(accent)', color: '#fff', 'border-color': css('--focus') } },
        { selector: 'edge.focus', style: {
            opacity: 1, label: 'data(count)', 'font-size': 11, 'font-weight': 700, color: css('--fg'),
            'text-background-color': css('--surface'), 'text-background-opacity': 1, 'text-background-padding': 3,
            'text-background-shape': 'round-rectangle', 'text-border-width': 1, 'text-border-color': css('--line'), 'text-border-opacity': 1 } },
      ],
    });

    cy.on('tap', 'node', e => { if (!e.target.isParent()) go(hash('structure', e.target.id())); });
    cy.on('tap', 'edge', e => {
      // 엣지 선택은 해시로 표현하지 않는다 — 모듈 해시를 비워 같은 모듈 재탭이 다시 라우팅되게
      SHARED.module = null;
      history.replaceState(null, '', '#structure');
      focus(e.target); showEdge(e.target.data('source'), e.target.data('target'));
    });
    cy.on('tap', e => { if (e.target === cy) go('#structure'); });
    cy.on('mouseover', 'node, edge', e => {
      if (e.target.isNode() && e.target.isParent()) return;
      e.target.addClass('hover');
      $('cy').style.cursor = 'pointer';
    });
    cy.on('mouseout', 'node, edge', e => { e.target.removeClass('hover'); $('cy').style.cursor = ''; });
    render();
  }

  // 서브프로젝트 영역마다 의존이 가장 많은 모듈을 가운데, 나머지를 원형으로 배치 — 매번 같은 위치(결정적)
  function layout() {
    for (const [p, { box }] of Object.entries(PROJECTS)) {
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
    $('summary').textContent = `· 모듈 ${cy.nodes().not(':parent').length} · 의존 쌍 ${cy.edges().length}`;
    clearFocus();
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
    const rows = cy.nodes().not(':parent').map(n => {
      const name = n.id();
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
  // 필터 변경으로 포커스가 풀렸으면 모듈 해시도 지운다(라우팅 없이)
  const resetHash = () => { if (parseHash().rest.length) history.replaceState(null, '', '#structure'); };

  // 흐름에서 고른 단계가 있으면 그 단계의 (보이는) 첫 모듈, 아니면 마지막으로 고른 모듈
  const homeModule = () => SHARED.step ? findStep(SHARED.step)?.modules.find(m => !hidden(m)) : SHARED.module;
  VIEWS.structure = {
    show([mod]) {
      if (!cy) init(); else cy.resize(); // 숨김 중 창 크기 변경 반영
      if (mod && byName[mod]) {
        if (hidden(mod)) { $('hideInfra').checked = false; render(); } // 숨긴 infra 모듈 딥링크
        selectModule(mod);
      } else {
        clearFocus();
      }
    },
    home: () => hash('structure', homeModule() ?? undefined),
  };
})();
