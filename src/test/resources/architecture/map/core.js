'use strict';
// 공용 헬퍼·라우터 — 뷰 파일은 IIFE 안에서 VIEWS[이름] = { show(rest, params), leave?, home?, enabled? }를 등록한다
const $ = id => document.getElementById(id);
const esc = s => String(s ?? '').replace(/[&<>"]/g, c => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;' }[c]));
const css = v => getComputedStyle(document.documentElement).getPropertyValue(v).trim();
const MAP = DATA.map;
const VIEWS = {};
const VIEW_ORDER = ['structure', 'landscape', 'flow', 'timeline', 'lifecycle']; // 첫 항목이 기본 탭

const setPanel = html => { $('panel').innerHTML = html; $('panel').scrollTop = 0; };
// 해시 생성 — segment별 인코드(잡 이름의 #·흐름 id 등), null/undefined segment는 버린다
const hash = (view, ...parts) => '#' + [view, ...parts.filter(p => p != null).map(p => encodeURIComponent(p))].join('/');

// 단계 → 모듈(Java 도출)을 단계에 붙인다
for (const [fid, f] of Object.entries(MAP.flows)) {
  for (const s of f.steps) s.modules = DATA.stepModules[fid]?.[s.id] ?? [];
}

// 교차 색인 — 전부 DATA에서 자동 도출
const IDX = {
  modSteps: {},        // 모듈 → [{flow, step}]
  stepJobs: {},        // "flow/step" → [job index]  (잡 이름 = 단계 code 항목)
  jobSteps: {},        // 잡 이름 → [{flow, step}]
  stepTransitions: {}, // "flow/step" → [{lc, i}]
};
for (const [fid, f] of Object.entries(MAP.flows)) {
  for (const s of f.steps) {
    for (const m of s.modules) (IDX.modSteps[m] ??= []).push({ flow: fid, step: s.id });
    DATA.jobs.forEach((j, i) => {
      if (!s.code.includes(j.name)) return;
      (IDX.stepJobs[`${fid}/${s.id}`] ??= []).push(i);
      (IDX.jobSteps[j.name] ??= []).push({ flow: fid, step: s.id });
    });
  }
}
for (const [lid, lc] of Object.entries(MAP.lifecycles)) {
  lc.transitions.forEach((t, i) => { if (t.flow?.includes('/')) (IDX.stepTransitions[t.flow] ??= []).push({ lc: lid, i }); });
}

// 탭 사이 공유 선택 — 구조 탭에서 고른 모듈, 흐름·오버레이에서 고른 단계({flow, step})
const SHARED = { module: null, step: null };

const findStep = ({ flow, step }) => MAP.flows[flow]?.steps.find(s => s.id === step);
const stepLabel = ref => `${MAP.flows[ref.flow].title} · ${findStep(ref).title}`;
const chipLink = (href, label) => `<a class="tag link" href="${esc(href)}">${esc(label)}</a>`;

// 패널 하단 "연결" 섹션 — [[소제목, [html...]], ...], 빈 항목은 생략
function connections(sections) {
  const body = sections.filter(([, items]) => items.length)
    .map(([title, items]) => `<h4>${esc(title)}</h4><div class="tags">${items.join('')}</div>`).join('');
  return body ? `<h3>연결</h3>${body}` : '';
}

// 흐름 단계 상세 패널 — 흐름 탭·구조 오버레이 공용, hrefOf(k) = k번째 단계로 가는 해시
function stepPanel(fid, i, hrefOf) {
  const flow = MAP.flows[fid], s = flow.steps[i], key = `${fid}/${s.id}`;
  const laneLabel = id => MAP.lanes.find(l => l.id === id)?.label ?? id;
  setPanel(`<div class="p-head"><span class="hint">${i + 1} / ${flow.steps.length} · <span class="lane-pill">${esc(laneLabel(s.lane))}</span></span>
      <h2>${esc(s.title)}</h2></div>
    <div class="p-body">
      ${s.cond ? `<h3>실행 조건</h3><div class="box">${esc(s.cond)}</div>` : ''}
      <p class="desc">${esc(s.desc)}</p>
      ${s.state ? `<h3>상태 변화</h3><div class="box state">${esc(s.state)}</div>` : ''}
      ${s.fail ? `<h3>실패 경로</h3><div class="box fail">${esc(s.fail)}</div>` : ''}
      ${s.to.length ? `<h3>호출·기록 대상</h3><div class="tags">${s.to.map(t => `<span class="tag">${esc(laneLabel(t))}</span>`).join('')}</div>` : ''}
      ${s.code.length ? `<h3>담당 코드</h3><div class="tags">${s.code.map(c => `<code class="tag">${esc(c)}</code>`).join('')}</div>` : ''}
      <div class="navs">${i > 0 ? `<a class="btn" href="${esc(hrefOf(i - 1))}">◀ ${esc(flow.steps[i - 1].title)}</a>` : ''}
        ${i < flow.steps.length - 1 ? `<a class="btn" href="${esc(hrefOf(i + 1))}">${esc(flow.steps[i + 1].title)} ▶</a>` : ''}</div>
      ${connections([
        ['모듈', s.modules.map(m => chipLink(hash('structure', m), m))],
        ['생명주기 전이', (IDX.stepTransitions[key] ?? []).map(({ lc, i: t }) => {
          const tr = MAP.lifecycles[lc].transitions[t];
          return chipLink(hash('lifecycle', lc, t), `${MAP.lifecycles[lc].title}: ${tr.from ?? '생성'} → ${tr.to ?? '삭제'}`);
        })],
        ['시작 잡', (IDX.stepJobs[key] ?? []).map(j => chipLink(hash('timeline', DATA.jobs[j].name), DATA.jobs[j].name))],
      ])}
    </div>`);
}

// 통합 검색 색인 — 모듈·흐름·단계(제목·code)·잡·상태 enum
const SEARCH = [
  ...DATA.modules.map(m => ({ kind: '모듈', label: m.name, sub: m.project, href: hash('structure', m.name) })),
  ...Object.entries(MAP.flows).flatMap(([fid, f]) => [
    { kind: '흐름', label: f.title, sub: f.trigger, href: hash('flow', fid) },
    ...f.steps.map(s => ({ kind: '단계', label: s.title, sub: `${f.title} · ${s.code.join(' ')}`, href: hash('flow', fid, s.id) })),
  ]),
  ...DATA.jobs.map(j => ({ kind: '잡', label: j.name, sub: `${j.process} · ${j.days}`, href: hash('timeline', j.name) })),
  ...Object.entries(MAP.lifecycles).flatMap(([lid, lc]) => lc.states.map(st =>
    ({ kind: '상태', label: `${st.id} ${st.label}`, sub: `${lc.title} · ${lc.enum}`, href: hash('lifecycle', lid) }))),
].map(e => ({ ...e, text: `${e.label} ${e.sub ?? ''}`.toLowerCase() }));

function search(q) {
  const box = $('q-results');
  const words = q.trim().toLowerCase().split(/\s+/).filter(Boolean);
  const hits = words.length ? SEARCH.filter(e => words.every(w => e.text.includes(w))).slice(0, 30) : [];
  box.hidden = !hits.length;
  box.innerHTML = hits.map(e => `<a href="${esc(e.href)}"><span class="kind">${esc(e.kind)}</span><b>${esc(e.label)}</b><small>${esc(e.sub ?? '')}</small></a>`).join('');
}

// #view/a/b?k=v → { view, rest: ['a','b'], params } — segment별 디코드(잡 이름의 #는 %23으로 온다)
function parseHash() {
  const [path, query = ''] = location.hash.slice(1).split('?');
  const decode = s => { try { return decodeURIComponent(s); } catch { return s; } }; // 깨진 %-인코딩은 원문 그대로
  const [view, ...rest] = path.split('/').map(decode);
  return { view, rest, params: new URLSearchParams(query) };
}

function route() {
  const { view, rest, params } = parseHash();
  const names = VIEW_ORDER.filter(v => VIEWS[v] && VIEWS[v].enabled !== false);
  const v = names.includes(view) ? view : names[0];
  document.querySelectorAll('.tab').forEach(t => t.setAttribute('aria-selected', t.dataset.view === v));
  document.querySelectorAll('.view').forEach(s => s.hidden = s.id !== 'view-' + v);
  Object.values(VIEWS).forEach(x => x.leave?.());
  VIEWS[v].show(v === view ? rest : [], params);
}

window.addEventListener('DOMContentLoaded', () => {
  // 비활성 뷰(예: 생명주기 정의 없음)는 탭·섹션 제거
  for (const v of VIEW_ORDER) {
    if (VIEWS[v]?.enabled === false) {
      document.querySelector(`.tab[data-view="${v}"]`)?.remove();
      $('view-' + v)?.remove();
    }
  }
  document.querySelectorAll('.tab').forEach(t => t.onclick = () => {
    location.hash = VIEWS[t.dataset.view].home?.() ?? '#' + t.dataset.view;
  });
  const q = $('q');
  q.addEventListener('input', () => search(q.value));
  q.addEventListener('keydown', e => {
    if (e.key === 'Enter') $('q-results').querySelector('a')?.click();
    if (e.key === 'Escape') { q.value = ''; search(''); q.blur(); }
  });
  $('q-results').addEventListener('click', () => { q.value = ''; search(''); });
  document.addEventListener('keydown', e => {
    if (e.key === '/' && !/^(INPUT|TEXTAREA|SELECT)$/.test(document.activeElement.tagName)) { e.preventDefault(); q.focus(); }
  });
  window.addEventListener('hashchange', route);
  route();
});
