'use strict';
// 공용 헬퍼·라우터 — 뷰 파일은 IIFE 안에서 VIEWS[이름] = { show(rest, params), leave?, home?, enabled? }를 등록한다
const $ = id => document.getElementById(id);
const esc = s => String(s ?? '').replace(/[&<>"]/g, c => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;' }[c]));
const css = v => getComputedStyle(document.documentElement).getPropertyValue(v).trim();
const MAP = DATA.map;
const VIEWS = {};
const VIEW_ORDER = ['structure', 'landscape', 'flow', 'timeline', 'lifecycle']; // 첫 항목이 기본 탭

const setPanel = html => { $('panel').innerHTML = html; $('panel').scrollTop = 0; };

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
  window.addEventListener('hashchange', route);
  route();
});
