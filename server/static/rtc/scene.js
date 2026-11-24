/* Presentation only. Call and analysis state come from client.js. */
(() => {
  const $ = id => document.getElementById(id);
  const copy = {
    idle: ['Готовы к разговору', 'Здесь начинается разговор', 'Позвоните, и помощник будет рядом'],
    ringing: ['Соединяем', 'Ожидаем собеседника', 'Помощник подключится после начала разговора'],
    listening: ['Разговор идёт', 'Слушаем вместе с вами', 'Оценки пока нет. Продолжайте разговор.'],
    low: ['Низкий риск', 'Явных признаков не отмечено', 'Это не гарантия безопасности. Будьте внимательны.'],
    medium: ['Обратите внимание', 'Стоит проверить просьбу', 'Не торопитесь. Уточните детали через официальный канал.'],
    high: ['Высокий риск', 'Возможное мошенничество', 'Не сообщайте коды и пароли. Проверьте, кто вам звонит.'],
    unavailable: ['Без оценки', 'Анализ недоступен', 'Разговор продолжается без проверки.']
  };
  let current, animation, generation = 0;
  const manifest = fetch('/rtc-assets/assets/animations/manifest.json').then(r => r.ok ? r.json() : {}).catch(() => ({}));
  const reduced = matchMedia('(prefers-reduced-motion: reduce)');
  function motion() { if (animation) reduced.matches || document.hidden ? animation.pause() : animation.play(); }
  reduced.addEventListener('change', motion);
  document.addEventListener('visibilitychange', motion);
  window.SkepticallScene = {
    update({ joined, busy, ringing, level, unavailable, receiver }) {
      document.body.dataset.joined = String(joined || busy);
      const state = !busy ? 'idle' : ringing ? 'ringing' : !receiver ? 'listening' : unavailable ? 'unavailable' : ['low', 'medium', 'high'].includes(level) ? level : 'listening';
      if (current === state) return;
      current = state;
      const ticket = ++generation;
      if (animation) animation.destroy();
      animation = null;
      $('scene').dataset.state = state;
      $('scene-chip').textContent = copy[state][0];
      $('scene-title').textContent = copy[state][1];
      $('scene-description').textContent = busy && !receiver ? 'Анализ доступен принимающему участнику.' : copy[state][2];
      $('scene-fallback').src = `/rtc-assets/assets/icons/face-${state}.svg`;
      $('scene-fallback').hidden = false;
      // Missing user animation is expected: retain the SVG fallback.
      if (!window.lottie) return;
      manifest.then(files => {
        const file = files[state];
        if (typeof file !== 'string' || !/^[a-zA-Z0-9_-]+\.json$/.test(file)) return null;
        return fetch(`/rtc-assets/assets/animations/${file}`).then(r => r.ok ? r.json() : null);
      }).then(data => {
        if (!data || ticket !== generation) return;
        animation = lottie.loadAnimation({container: $('lottie-scene'), renderer: 'canvas', loop: true, autoplay: false, animationData: data});
        animation.addEventListener('DOMLoaded', () => { if (ticket === generation) { $('scene-fallback').hidden = true; animation.goToAndStop(0, true); motion(); } });
        animation.addEventListener('data_failed', () => { if (ticket === generation) $('scene-fallback').hidden = false; });
      }).catch(() => {});
    }
  };
  function panel(open) { document.body.dataset.analysisOpen = String(open); $('toggle-analysis').setAttribute('aria-expanded', String(open)); }
  $('toggle-analysis').addEventListener('click', () => panel(document.body.dataset.analysisOpen === 'false'));
  $('close-analysis').addEventListener('click', () => { panel(false); $('toggle-analysis').focus(); });
  panel(true);
})();
