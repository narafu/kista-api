'use strict';
(() => {
  /* ---------- 1. 랜드스케이프 ---------- */
  $('landscape').innerHTML = MAP.landscape.map((g, gi) => `
    <div class="group"><h3>${esc(g.group)}</h3>
      <div class="row${gi === 0 ? ' journey' : ''}">${g.steps.map(s => s.flow
        ? `<button class="card linked" data-flow="${esc(s.flow)}"><b>${esc(s.title)}</b>${s.when ? `<small>${esc(s.when)}</small>` : ''}<small><span class="badge go">흐름 보기 →</span></small></button>`
        : `<div class="card pending"><b>${esc(s.title)}</b>${s.when ? `<small>${esc(s.when)}</small>` : ''}<small><span class="badge soon">준비 중</span></small></div>`).join('')}
      </div></div>`).join('');
  document.querySelectorAll('.card.linked').forEach(c => c.onclick = () => location.hash = 'flow/' + c.dataset.flow);

  function panelLandscape() {
    const all = MAP.landscape.flatMap(g => g.steps);
    const linked = all.filter(s => s.flow).length;
    $('panel').innerHTML = `<div class="p-head"><h2>업무 흐름 맵</h2>
      <p class="hint">모듈 그래프가 정적 구조를 보여 준다면, 이 맵은 무엇이 계기가 되어 어떤 순서로 어디까지 가는지를 보여 줍니다.</p></div>
      <div class="p-body">
        <h3>현황</h3><p class="desc">단계 ${all.length}개 중 상세 흐름 ${linked}개가 연결돼 있습니다.</p>
        <h3>흐름 목록</h3><div class="tags">${Object.entries(MAP.flows).map(([id, f]) =>
          `<a class="btn" href="#flow/${esc(id)}">${esc(f.title)}</a>`).join('')}</div>
        <h3>원본</h3><p class="hint"><code>src/test/resources/architecture/flows.yml</code>을 수정하면 <code>ArchitectureMapTest</code>가 참조한 클래스·메서드를 검증한 뒤 이 페이지를 다시 생성합니다.</p>
      </div>`;
  }

  VIEWS.landscape = { show: () => panelLandscape() };
})();
