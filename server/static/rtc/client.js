/* Same-origin foreground-only WebRTC. Audio tickets never leave memory or enter URLs. */
(() => {
  'use strict';
  const $ = id => document.getElementById(id);
  const ui = Object.fromEntries(['setup', 'page-title', 'call-title', 'joined-summary', 'role', 'room', 'consent', 'connect', 'invite', 'accept', 'reject', 'hangup', 'mute', 'playback', 'disconnect', 'remote', 'call-state', 'analysis-state', 'incomplete', 'risk', 'reasons', 'transcript', 'empty-illustration', 'analysis'].map(id => [id, $(id)]));
  let session = null;
  let call = null;
  let connecting = false;
  let showingJoined = false;
  const active = c => call === c && session === c.session && !c.stopped;
  const wsUrl = path => `${location.protocol === 'https:' ? 'wss:' : 'ws:'}//${location.host}${path}`;
  const callState = text => { ui['call-state'].textContent = text; };
  const analysisState = text => { ui['analysis-state'].textContent = text; };
  const closeSocket = socket => { if (socket) { try { socket.close(); } catch (_) { /* already closed */ } } };
  const clearTimer = id => { if (id) clearTimeout(id); };
  function phoneAddress(value) {
    if ([...value].some(char => !/[0-9+\-() ]/.test(char))) return null;
    let digits = value.replace(/\D/g, '');
    if (digits.length === 11 && digits.startsWith('8')) digits = `7${digits.slice(1)}`;
    if (digits.length === 10) digits = `7${digits}`;
    return digits.length >= 10 && digits.length <= 15 ? { display: `+${digits}`, key: `tel_${digits}` } : null;
  }

  function controls() {
    const joined = Boolean(session && session.ready);
    const busy = Boolean(call);
    for (const field of ['role', 'room', 'consent']) ui[field].disabled = Boolean(session) || connecting;
    ui.connect.disabled = Boolean(session) || connecting;
    ui.disconnect.disabled = !session;
    ui.invite.hidden = ui.role.value !== 'caller';
    ui.invite.disabled = !joined || !session.peer || busy;
    const ringing = Boolean(call && !call.ending && call.phase === 'ringing' && session.role === 'receiver');
    ui.accept.hidden = !ringing;
    ui.reject.hidden = !ringing;
    ui.accept.disabled = Boolean(call && call.decision);
    ui.reject.disabled = Boolean(call && call.decision);
    ui.hangup.disabled = !busy || !call.id || Boolean(call.ending);
    ui.mute.disabled = !busy || !call.stream || Boolean(call.ending);
    ui.mute.setAttribute('aria-pressed', String(Boolean(call && call.muted)));
    ui.mute.textContent = call && call.muted ? 'Включить микрофон' : 'Выключить микрофон';
    ui.setup.hidden = joined;
    ui['joined-summary'].hidden = !joined;
    ui['joined-summary'].textContent = joined ? `Адрес: ${session.displayAddress}. Режим: ${session.role === 'caller' ? 'звоню' : 'принимаю звонки'}.` : '';
    if (joined !== showingJoined) {
      showingJoined = joined;
      if (!document.hidden) ui[joined ? 'call-title' : 'page-title'].focus();
    }
  }

  function resetAnalysis() {
    ui.incomplete.hidden = true;
    ui.risk.textContent = 'Оценки пока нет.';
    ui.risk.setAttribute('data-level', 'unknown');
    ui['empty-illustration'].hidden = false;
    ui.reasons.replaceChildren();
    ui.transcript.textContent = 'Текст появится после начала разговора.';
    analysisState(session.role === 'receiver' ? 'Ожидание ответа на звонок.' : 'Анализ доступен принимающему участнику.');
  }

  function newCall(phase) {
    const c = { session, phase, id: null, stream: null, pc: null, context: null, source: null, node: null, sink: null, audio: null, ice: [], localIce: [], sdpSent: false, stopped: false, analysisFailed: false, audioReady: false, text: [], decision: false };
    call = c;
    resetAnalysis();
    return c;
  }

  function stopAnalysis(c) {
    clearTimer(c.audioTimer);
    clearTimer(c.audioRetryTimer);
    clearTimer(c.audioDeadlineTimer);
    c.audioTicket = null;
    if (c.node) { c.node.port.onmessage = null; c.node.port.close(); c.node.disconnect(); }
    if (c.source) c.source.disconnect();
    if (c.sink) c.sink.disconnect();
    if (c.context) { void c.context.close().catch(() => {}); c.context = null; }
    c.node = c.source = c.sink = null;
    c.audioReady = false;
    const socket = c.audio;
    c.audio = null;
    closeSocket(socket);
  }

  function releaseMedia(c) {
    if (c.stream) c.stream.getTracks().forEach(track => track.stop());
    if (c.pc) {
      c.pc.ontrack = c.pc.onicecandidate = c.pc.onconnectionstatechange = null;
      c.pc.getReceivers().forEach(receiver => { if (receiver.track) receiver.track.stop(); });
      c.pc.close();
    }
    stopAnalysis(c);
    ui.remote.pause();
    if (ui.remote.srcObject) ui.remote.srcObject.getTracks().forEach(track => track.stop());
    ui.remote.srcObject = null;
    ui.playback.hidden = true;
  }

  function analysisFailure(c, message) {
    if (!active(c) || c.analysisFailed) return;
    c.analysisFailed = true;
    ui.incomplete.hidden = false;
    analysisState(message);
    stopAnalysis(c);
  }

  function stopCall(message) {
    const c = call;
    call = null; // Invalidate every pending async continuation before releasing resources.
    if (c) {
      c.stopped = true;
      clearTimer(c.timer);
      releaseMedia(c);
      if (c.session.role === 'receiver' && !c.analysisFailed) analysisState('Анализ завершён вместе со звонком. Последние фразы могли не попасть в обработку.');
    }
    ui.remote.pause();
    if (ui.remote.srcObject) ui.remote.srcObject.getTracks().forEach(track => track.stop());
    ui.remote.srcObject = null;
    ui.playback.hidden = true;
    if (message) callState(message);
    controls();
  }

  function stopAll(message = 'Звонок остановлен. Подключитесь заново для нового разговора.') {
    const s = session;
    session = null;
    connecting = false;
    if (s) clearTimer(s.timer);
    stopCall(message);
    if (s) closeSocket(s.socket);
    ui.consent.checked = false;
    controls();
  }
  // Called synchronously by Android on pause, Back and rotation.
  window.safetyRtcStop = () => stopAll('Звонок остановлен приложением. Для нового звонка подключитесь заново.');

  function send(s, event) {
    if (session !== s || !s.ready || s.socket.readyState !== WebSocket.OPEN) throw new Error('Сигнальное соединение недоступно. Подключитесь заново.');
    if (s.socket.bufferedAmount > 32768) throw new Error('Сигнальный канал перегружен. Подключитесь заново.');
    s.socket.send(JSON.stringify(event));
  }
  function callSend(c, type, fields = {}) {
    if (!active(c) || !c.id || c.ending) return;
    send(c.session, { type, call_id: c.id, ...fields });
  }
  function failCall(c, message) {
    if (!active(c)) return;
    if (c.id) {
      try { callSend(c, 'hangup'); } catch (_) { stopAll(message); return; }
      c.ending = true;
      // Retain the call until acknowledgement, so a new invite cannot race server teardown.
      releaseMedia(c);
      c.phase = 'ending';
      c.endMessage = message;
      clearTimer(c.timer);
      c.timer = setTimeout(() => { if (active(c)) stopAll(message); }, 5000);
      callState(message);
      controls();
    } else {
      // An invite may already be in flight; close signaling instead of reusing its state.
      stopAll(message);
    }
  }

  async function microphone(c) {
    const stream = await navigator.mediaDevices.getUserMedia({ audio: { echoCancellation: true, noiseSuppression: true }, video: false });
    if (!active(c)) { stream.getTracks().forEach(track => track.stop()); return false; }
    c.stream = stream;
    controls();
    return true;
  }

  function createPeer(c) {
    if (c.pc) return c.pc;
    const pc = new RTCPeerConnection({ iceServers: c.session.iceServers });
    c.pc = pc;
    c.stream.getAudioTracks().forEach(track => pc.addTrack(track, c.stream));
    pc.onicecandidate = event => {
      if (!active(c) || c.ending) return;
      const candidate = event.candidate ? event.candidate.toJSON() : null;
      // SDP always precedes its candidates on the wire.
      if (!c.sdpSent) c.localIce.push(candidate);
      else { try { callSend(c, 'ice', { candidate }); } catch (error) { failCall(c, error.message); } }
    };
    pc.ontrack = event => {
      if (!active(c) || event.track.kind !== 'audio') return;
      const remote = new MediaStream([event.track]);
      ui.remote.srcObject = remote;
      playRemote(c);
      event.track.onended = () => { if (active(c) && !c.ending) failCall(c, 'Аудиопоток собеседника завершён.'); };
      if (c.session.role === 'receiver') void captureRemote(c, remote);
    };
    pc.onconnectionstatechange = () => {
      if (!active(c) || c.ending) return;
      if (pc.connectionState === 'connected') { clearTimer(c.timer); callState('Разговор идёт'); }
      else if (pc.connectionState === 'failed') failCall(c, 'Не удалось установить аудиосвязь. Проверьте сеть и настройки TURN сервера.');
      else if (pc.connectionState === 'disconnected') {
        callState('Аудиосвязь прервана. Ожидание восстановления…');
        clearTimer(c.timer);
        c.timer = setTimeout(() => { if (active(c)) failCall(c, 'Аудиосвязь потеряна. Позвоните заново.'); }, 10000);
      }
    };
    clearTimer(c.timer);
    c.timer = setTimeout(() => { if (active(c)) failCall(c, 'Время установки аудиосвязи истекло. Проверьте сеть и TURN.'); }, 30000);
    return pc;
  }

  function playRemote(c) {
    void ui.remote.play().then(() => {
      if (active(c)) ui.playback.hidden = true;
    }).catch(() => {
      if (active(c)) ui.playback.hidden = false;
    });
  }

  function sentSdp(c, type) {
    callSend(c, type, { sdp: c.pc.localDescription.sdp });
    c.sdpSent = true;
    for (const candidate of c.localIce.splice(0)) callSend(c, 'ice', { candidate });
  }
  async function setRemote(c, description) {
    await c.pc.setRemoteDescription(description);
    if (!active(c) || c.ending) return;
    for (const candidate of c.ice.splice(0)) {
      await c.pc.addIceCandidate(candidate);
      if (!active(c) || c.ending) return;
    }
  }

  async function captureRemote(c, remote) {
    if (!active(c) || c.analysisFailed || c.source) return;
    try {
      if (!c.context || c.context.state !== 'running') throw new Error('Аудиоанализ недоступен: звуковой контекст не запущен.');
      await c.context.audioWorklet.addModule('/rtc-assets/pcm-worklet.js');
      if (!active(c) || c.analysisFailed) return;
      c.source = c.context.createMediaStreamSource(remote);
      c.node = new AudioWorkletNode(c.context, 'remote-pcm', { numberOfInputs: 1, numberOfOutputs: 1, outputChannelCount: [1] });
      c.sink = c.context.createGain();
      c.sink.gain.value = 0;
      c.node.onprocessorerror = () => analysisFailure(c, 'Анализ остановлен: ошибка обработки звука.');
      c.node.port.onmessage = event => {
        if (!active(c) || !c.audioReady || c.analysisFailed || c.ending) return;
        const frame = event.data;
        if (!(frame instanceof ArrayBuffer) || !frame.byteLength || frame.byteLength > 8000 || frame.byteLength % 2) { analysisFailure(c, 'Анализ остановлен: неверный аудиоформат.'); return; }
        if (!c.audio || c.audio.readyState !== WebSocket.OPEN || c.audio.bufferedAmount + frame.byteLength > 32768) { analysisFailure(c, 'Анализ остановлен: сеть не успевает передавать звук. Разговор продолжается.'); return; }
        try { c.audio.send(frame); } catch (_) { analysisFailure(c, 'Анализ остановлен: передача звука недоступна.'); }
      };
      c.source.connect(c.node);
      c.node.connect(c.sink);
      c.sink.connect(c.context.destination);
    } catch (error) { analysisFailure(c, error.message || 'Анализ остановлен: обработка звука недоступна.'); }
  }

  function openAnalysis(c, ticket) {
    if (!active(c) || c.analysisFailed || c.ending) return;
    if (typeof ticket !== 'string' || !ticket) { analysisFailure(c, 'Анализ недоступен: сервер не выдал разрешение.'); return; }
    c.audioTicket = ticket;
    c.audioAttempts = 0;
    analysisState('Подключение распознавания…');
    c.audioDeadlineTimer = setTimeout(() => analysisFailure(c, 'Анализ не запустился за 15 секунд: сервер занят или недоступен. Разговор продолжается, но его начало не проверено.'), 15000);
    attemptAnalysis(c);
  }

  function attemptAnalysis(c) {
    if (!active(c) || c.analysisFailed || c.ending || !c.audioTicket) return;
    c.audioAttempts++;
    let socket;
    try { socket = new WebSocket(wsUrl('/ws/rtc-audio')); }
    catch (_) { analysisFailure(c, 'Канал анализа недоступен. Разговор продолжается.'); return; }
    c.audio = socket;
    c.audioTimer = setTimeout(() => analysisFailure(c, 'Сервер анализа не ответил. Разговор продолжается.'), 12000);
    socket.onopen = () => {
      if (!active(c) || c.analysisFailed || c.ending || c.audio !== socket) { closeSocket(socket); return; }
      try { socket.send(JSON.stringify({ type: 'start', audio_ticket: c.audioTicket, room: c.session.room, call_id: c.id, sample_rate: 16000, channels: 1, format: 'pcm_s16le', consent: true, analysis_provider: 'google' })); }
      catch (_) { analysisFailure(c, 'Не удалось запустить анализ. Разговор продолжается.'); }
    };
    socket.onmessage = event => {
      if (!active(c) || c.analysisFailed || c.ending || c.audio !== socket) return;
      let data;
      try { data = JSON.parse(event.data); } catch (_) { analysisFailure(c, 'Сервер анализа прислал неверный ответ.'); return; }
      if (data.type === 'error' && data.code === 'busy' && !c.audioReady) {
        // Only this explicit rejection guarantees the single-use ticket is unconsumed.
        clearTimer(c.audioTimer);
        c.audio = null;
        socket.onopen = socket.onmessage = socket.onerror = socket.onclose = null;
        closeSocket(socket);
        ui.incomplete.hidden = false;
        if (c.audioAttempts >= 20) { analysisFailure(c, 'Анализ не запустился: сервер остаётся занят. Разговор продолжается без проверки.'); return; }
        analysisState('Сервер завершает предыдущий анализ. Повторяем подключение; начало разговора пока не проверяется.');
        c.audioRetryTimer = setTimeout(() => attemptAnalysis(c), 750);
      } else if (data.type === 'ready') {
        clearTimer(c.audioTimer);
        clearTimer(c.audioDeadlineTimer);
        clearTimer(c.audioRetryTimer);
        c.audioTicket = null;
        if (data.mode !== 'gemini') {
          analysisFailure(c, data.mode === 'diagnostic'
            ? 'Диагностический режим: распознавание и оценка риска отсутствуют. Разговор продолжается.'
            : 'Режим анализа не поддерживается: распознавание и оценка риска недоступны. Разговор продолжается.');
          return;
        }
        c.audioReady = true;
        analysisState('Распознавание включено через Google Gemini Live. Ожидание речи собеседника…');
      } else if (data.type === 'transcript' && c.audioReady && typeof data.text === 'string') {
        c.text.push(data.text.slice(0, 12000));
        while (c.text.join('\n').length > 24000) c.text.shift();
        ui.transcript.textContent = c.text.join('\n');
        ui['empty-illustration'].hidden = true;
        analysisState('Речь собеседника распознаётся через Google Gemini Live.');
      } else if (data.type === 'risk' && c.audioReady) {
        const labels = { low: 'Модель не отметила явных признаков — это не гарантия безопасности.', medium: 'Есть повод проверить просьбу.', high: 'Обнаружены тревожные признаки.' };
        ui.risk.setAttribute('data-level', Object.hasOwn(labels, data.level) ? data.level : 'unknown');
        ui.risk.textContent = labels[data.level] || 'Оценка риска недоступна.';
        if (typeof data.message === 'string') ui.risk.textContent += ` ${data.message.slice(0, 4000)}`;
        ui.reasons.replaceChildren();
        if (Array.isArray(data.reasons)) for (const reason of data.reasons.slice(0, 20)) {
          if (typeof reason !== 'string') continue;
          const li = document.createElement('li');
          li.textContent = reason.slice(0, 2000);
          ui.reasons.append(li);
        }
      } else if (data.type === 'error' || data.type === 'done') {
        const errors = {
          quota_exceeded: 'Лимит Google Gemini исчерпан. Попросите организатора проверить квоту.',
          model_unavailable: 'Модель Gemini Live недоступна. Попросите организатора проверить настройки модели.',
          authentication_failed: 'Google отклонил доступ к Gemini. Попросите организатора проверить ключ и права доступа.',
          session_expired: 'Сеанс Gemini Live истёк. Для нового анализа начните новый звонок.',
          upstream_closed: 'Связь с Google Gemini потеряна. Для нового анализа начните новый звонок.',
          processing_timeout: 'Google Gemini не завершил обработку вовремя. Для нового анализа начните новый звонок.',
          upstream_error: 'Ошибка Google Gemini. Для нового анализа начните новый звонок.'
        };
        const detail = data.type === 'error' && Object.hasOwn(errors, data.code) ? errors[data.code] : 'Распознавание остановлено сервером.';
        analysisFailure(c, `${detail} Разговор продолжается; анализ неполный.`);
      }
    };
    socket.onerror = () => { if (c.audio === socket) analysisFailure(c, 'Канал анализа недоступен. Разговор продолжается.'); };
    socket.onclose = () => { if (c.audio === socket) analysisFailure(c, 'Связь с анализом потеряна. Разговор продолжается.'); };
  }

  async function signal(s, data) {
    if (session !== s) return;
    if (data.type === 'ready') {
      if (s.ready || data.role !== s.role || data.room !== s.room || !Array.isArray(data.ice_servers)) throw new Error('Неверный ответ подключения.');
      clearTimer(s.timer);
      s.ready = true;
      s.peer = data.peer_present === true;
      s.iceServers = data.ice_servers;
      callState(s.role === 'receiver' ? 'Входящие включены.' : (s.peer ? 'Адрес найден. Можно позвонить.' : 'Адрес пока недоступен.'));
      controls();
      return;
    }
    if (data.type === 'error') throw new Error('Сервер отклонил подключение или действие. Проверьте адрес; подключитесь заново.');
    if (data.type === 'peer-joined') { s.peer = true; if (!call) callState(s.role === 'caller' ? 'Адрес найден. Можно позвонить.' : 'Входящие включены.'); controls(); return; }
    if (data.type === 'peer-left') { s.peer = false; stopCall('Собеседник отключился. Ожидание нового подключения.'); return; }
    if (data.type === 'incoming') {
      if (typeof data.call_id !== 'string' || !data.call_id) throw new Error('Неверное приглашение.');
      if (s.role === 'receiver') { if (call) throw new Error('Повторное приглашение.'); newCall('ringing'); }
      if (!call || call.id || call.phase !== (s.role === 'caller' ? 'inviting' : 'ringing')) throw new Error('Неожиданное приглашение.');
      call.id = data.call_id;
      call.phase = 'ringing';
      clearTimer(call.timer);
      const c = call;
      c.timer = setTimeout(() => { if (active(c)) failCall(c, 'Время ожидания ответа истекло.'); }, 70000);
      callState(s.role === 'receiver' ? 'Входящий звонок. Примите или отклоните.' : 'Вызов отправлен. Ожидание ответа…');
      controls();
      return;
    }
    const c = call;
    if (!c || data.call_id !== c.id) return; // Late events for a released call cannot affect its successor.
    if (data.type === 'ended' || data.type === 'rejected') {
      stopCall(c.endMessage || (data.type === 'rejected' ? (data.reason === 'timeout' ? 'Нет ответа. Можно позвонить снова.' : 'Звонок отклонён. Можно позвонить снова.') : 'Звонок завершён. Можно позвонить снова.'));
      return;
    }
    if (c.ending) return;
    if (data.type === 'accepted') {
      if (!c.stream || (s.role === 'receiver' && !c.decision)) throw new Error('Микрофон не подготовлен к звонку.');
      c.phase = 'accepted';
      createPeer(c);
      callState('Установка аудиосвязи…');
      controls();
      if (s.role === 'receiver') openAnalysis(c, data.audio_ticket);
      else {
        const offer = await c.pc.createOffer();
        if (!active(c) || c.ending) return;
        await c.pc.setLocalDescription(offer);
        if (active(c) && !c.ending) sentSdp(c, 'offer');
      }
    } else if (data.type === 'offer' && s.role === 'receiver') {
      if (!c.pc) throw new Error('Аудиосвязь не подготовлена.');
      await setRemote(c, { type: 'offer', sdp: data.sdp });
      if (!active(c) || c.ending) return;
      const answer = await c.pc.createAnswer();
      if (!active(c) || c.ending) return;
      await c.pc.setLocalDescription(answer);
      if (active(c) && !c.ending) sentSdp(c, 'answer');
    } else if (data.type === 'answer' && s.role === 'caller') {
      await setRemote(c, { type: 'answer', sdp: data.sdp });
    } else if (data.type === 'ice') {
      if (!c.pc || !c.pc.remoteDescription) {
        if (c.ice.length >= 256) throw new Error('Слишком много сетевых кандидатов.');
        c.ice.push(data.candidate);
      } else await c.pc.addIceCandidate(data.candidate);
    }
  }

  function connect(event) {
    event.preventDefault();
    if (session || connecting || !ui.setup.reportValidity()) return;
    const loopback = ['localhost', '127.0.0.1', '[::1]'].includes(location.hostname);
    if ((location.protocol !== 'https:' && !(location.protocol === 'http:' && loopback)) || !window.isSecureContext) { callState('Для звонка нужен доверенный HTTPS сервер. HTTP разрешён только на localhost для разработки.'); return; }
    if (!navigator.mediaDevices || !navigator.mediaDevices.getUserMedia || !window.RTCPeerConnection || !window.WebSocket) { callState('Этот браузер не поддерживает звонки. Обновите браузер или Android System WebView.'); return; }
    const address = phoneAddress(ui.room.value);
    if (!address) { ui.room.setCustomValidity('Введите номер от 10 до 15 цифр.'); ui.room.reportValidity(); return; }
    ui.room.setCustomValidity('');
    ui.room.value = address.display;
    connecting = true;
    const s = { role: ui.role.value, room: address.key, displayAddress: address.display, ready: false, peer: false, socket: null, chain: Promise.resolve() };
    session = s;
    try {
      const socket = new WebSocket(wsUrl('/ws/rtc'));
      s.socket = socket;
      s.timer = setTimeout(() => { if (session === s) stopAll('Сервер не ответил. Проверьте сеть и подключитесь заново.'); }, 12000);
      socket.onopen = () => {
        if (session !== s) { closeSocket(socket); return; }
        socket.send(JSON.stringify({ type: 'join', room: s.room, role: s.role, consent: true }));
      };
      socket.onmessage = event => {
        s.chain = s.chain.then(() => signal(s, JSON.parse(event.data))).catch(() => { if (session === s) stopAll('Соединение отклонено или прервано. Проверьте адрес и сеть. Подключитесь заново.'); });
      };
      socket.onclose = () => { if (session === s) stopAll('Соединение закрыто. Проверьте адрес и сеть; подключитесь заново.'); };
      socket.onerror = () => { if (session === s) stopAll('Не удалось связаться с сервером. Проверьте сеть и подключитесь заново.'); };
      callState('Подключение адреса…');
    } catch (_) { stopAll('Не удалось открыть соединение. Проверьте адрес сервера.'); }
    connecting = false;
    controls();
  }

  ui.setup.addEventListener('submit', connect);
  ui.role.addEventListener('change', controls);
  ui.disconnect.addEventListener('click', () => stopAll('Отключено. Для нового звонка подключитесь заново.'));
  ui.invite.addEventListener('click', async () => {
    if (!session || !session.ready || !session.peer || call) return;
    const c = newCall('preparing');
    controls();
    callState('Разрешите доступ к микрофону…');
    try {
      if (!await microphone(c)) return;
      c.phase = 'inviting';
      send(c.session, { type: 'invite' });
      c.timer = setTimeout(() => { if (active(c)) failCall(c, 'Сервер не подтвердил вызов. Подключитесь заново.'); }, 12000);
      callState('Отправка вызова…');
    } catch (_) {
      if (active(c)) {
        if (c.phase === 'inviting') stopAll('Вызов не отправлен. Подключитесь заново.');
        else stopCall('Микрофон недоступен. Разрешите микрофон и попробуйте снова.');
      }
    }
  });
  ui.accept.addEventListener('click', async () => {
    const c = call;
    if (!c || c.phase !== 'ringing' || c.decision) return;
    c.decision = true;
    controls();
    callState('Разрешите доступ к микрофону…');
    // Both calls begin in the explicit acceptance gesture, before any awaited operation.
    let resumed;
    try {
      const Context = window.AudioContext || window.webkitAudioContext;
      if (!Context) throw new Error('AudioContext unavailable');
      c.context = new Context();
      c.context.onstatechange = () => { if (active(c) && c.phase === 'accepted' && c.context && c.context.state === 'suspended') analysisFailure(c, 'Анализ остановлен: звуковой контекст приостановлен.'); };
      resumed = c.context.resume().catch(() => analysisFailure(c, 'Не удалось включить обработку звука. Анализ неполный.'));
    } catch (_) { analysisFailure(c, 'Обработка звука недоступна в этом браузере. Анализ неполный.'); }
    try {
      const acquired = await microphone(c);
      if (!acquired) return;
      if (resumed) await resumed;
      if (!active(c) || c.ending) return;
      callSend(c, 'accept');
      c.phase = 'accepting';
      callState('Ответ отправлен. Установка аудиосвязи…');
      controls();
    } catch (_) {
      if (!active(c)) return;
      c.decision = false;
      stopAnalysis(c);
      callState('Микрофон недоступен. Разрешите микрофон и примите снова либо отклоните звонок.');
      controls();
    }
  });
  ui.reject.addEventListener('click', () => {
    const c = call;
    if (!c || c.decision) return;
    try {
      callSend(c, 'reject');
      c.decision = true;
      c.ending = true;
      c.endMessage = 'Вы отклонили звонок.';
      clearTimer(c.timer);
      c.timer = setTimeout(() => { if (active(c)) stopAll('Ответ сервера не получен. Соединение закрыто.'); }, 5000);
      controls();
    } catch (_) { stopAll('Не удалось отклонить звонок. Соединение закрыто.'); }
  });
  ui.hangup.addEventListener('click', () => { if (call) failCall(call, 'Вы завершили звонок.'); });
  ui.mute.addEventListener('click', () => {
    if (!call || !call.stream) return;
    call.muted = !call.muted;
    call.stream.getAudioTracks().forEach(track => { track.enabled = !call.muted; });
    controls();
  });
  ui.playback.addEventListener('click', () => { if (call) playRemote(call); });
  document.addEventListener('visibilitychange', () => { if (document.hidden) stopAll('Экран скрыт: звонок остановлен. Звонки работают только при открытом экране; подключитесь заново.'); });
  window.addEventListener('pagehide', () => stopAll('Страница закрыта: звонок остановлен.'));
  const requestedRole = new URLSearchParams(location.search).get('role');
  if (requestedRole === 'caller' || requestedRole === 'receiver') ui.role.value = requestedRole;
  controls();
})();
