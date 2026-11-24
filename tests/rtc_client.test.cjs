'use strict';
// Run with node --test tests/rtc_client.test.cjs; no npm dependencies.
const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');
const path = require('node:path');
const { PcmResampler } = require('../server/static/rtc/pcm-core.js');
const client = fs.readFileSync(path.join(__dirname, '../server/static/rtc/client.js'), 'utf8');

test('HTML phone pattern accepts formatted numbers and rejects invalid addresses', () => {
  const html = fs.readFileSync(path.join(__dirname, '../server/static/rtc/index.html'), 'utf8');
  const pattern = html.match(/id="room"[^>]*pattern="([^"]+)"/)[1];
  const validPhone = new RegExp(`^(?:${pattern})$`, 'v');
  for (const value of ['+7 701 123 45 67', '87011234567', '+44 (7911) 123-456']) assert.equal(validPhone.test(value), true);
  for (const value of ['123', 'call-me', '+7/701/123']) assert.equal(validPhone.test(value), false);
});

test('PCM is little-endian, bounded, mono and independent of input block boundaries', () => {
  for (const rate of [16000, 44100, 48000, 96000]) {
    const input = Float32Array.from({ length: rate * 2 }, (_, i) => Math.sin(i * 0.07));
    function render(blockSize) {
      const resampler = new PcmResampler(rate);
      const frames = [];
      for (let i = 0; i < input.length; i += blockSize) resampler.push([input.subarray(i, i + blockSize)], frame => frames.push(Buffer.from(frame)));
      assert.ok(frames.every(frame => frame.length <= 8000 && frame.length % 2 === 0));
      return Buffer.concat(frames);
    }
    const full = render(input.length);
    assert.equal(full.length, 64000);
    assert.deepEqual(render(128), full);
    assert.deepEqual(render(173), full);
  }
  const frames = [];
  new PcmResampler(48000, 16000, 1).push([new Float32Array([1, 1, 1]), new Float32Array([-1, -1, -1])], frame => frames.push(Buffer.from(frame)));
  assert.equal(frames[0].readInt16LE(), 0);
  const clipped = [];
  new PcmResampler(16000, 16000, 1).push([new Float32Array([-2, 2])], frame => clipped.push(Buffer.from(frame).readInt16LE()));
  assert.deepEqual(clipped, [-32768, 32767]);
  assert.throws(() => new PcmResampler(48000, 16000, 4001));
});

function harness(role = 'caller') {
  const elements = {};
  const timers = new Map();
  const sockets = [];
  const peers = [];
  const contexts = [];
  const nodes = [];
  const tracks = [];
  const listeners = {};
  let micRequests = 0;
  let resolveMic;
  let deferMic = false;
  const element = id => elements[id] ||= {
    value: id === 'role' ? role : id === 'room' ? '+7 701 123 45 67' : '', disabled: false, hidden: false, textContent: '', children: [], handlers: {}, srcObject: null,
    addEventListener(type, fn) { this.handlers[type] = fn; },
    setAttribute() {}, replaceChildren() { this.children = []; }, append(value) { this.children.push(value); },
    setCustomValidity(value) { this.validationMessage = value; }, reportValidity() { return true; }, focus() { this.focused = true; }, pause() {}, play() { return this.rejectPlayback ? Promise.reject(new Error('autoplay')) : Promise.resolve(); }
  };
  function stream() {
    const track = { kind: 'audio', enabled: true, stopped: false, stop() { this.stopped = true; } };
    tracks.push(track);
    return { getTracks: () => [track], getAudioTracks: () => [track] };
  }
  class Socket {
    static OPEN = 1;
    constructor(url) { this.url = url; this.readyState = 0; this.bufferedAmount = 0; this.sent = []; sockets.push(this); }
    open() { this.readyState = 1; this.onopen?.(); }
    send(value) { this.sent.push(typeof value === 'string' ? JSON.parse(value) : value); }
    close() { this.readyState = 3; }
    event(value) { this.onmessage?.({ data: JSON.stringify(value) }); }
  }
  class Peer {
    constructor() { this.candidates = []; this.receivers = []; peers.push(this); }
    addTrack(track, source) { this.localTrack = track; this.localSource = source; }
    getReceivers() { return this.receivers; }
    close() { this.closed = true; }
    async createOffer() { return { type: 'offer', sdp: 'offer-sdp' }; }
    async createAnswer() { return { type: 'answer', sdp: 'answer-sdp' }; }
    async setLocalDescription(value) { this.localDescription = value; this.onicecandidate?.({ candidate: { toJSON: () => ({ candidate: 'local-ice' }) } }); }
    async setRemoteDescription(value) { this.remoteDescription = value; }
    async addIceCandidate(value) { assert.ok(this.remoteDescription); this.candidates.push(value); }
  }
  class Context {
    constructor() { this.state = 'suspended'; this.audioWorklet = { addModule: async () => {} }; contexts.push(this); }
    resume() { this.state = 'running'; return Promise.resolve(); }
    close() { this.state = 'closed'; return Promise.resolve(); }
    createMediaStreamSource(source) { this.captured = source; return { connect() {}, disconnect() {} }; }
    createGain() { return { gain: {}, connect() {}, disconnect() {} }; }
  }
  class Worklet {
    constructor() { this.port = { close() {} }; nodes.push(this); }
    connect() {} disconnect() {}
  }
  const sandbox = {
    document: { getElementById: element, createElement: () => element(`li-${Math.random()}`), addEventListener(type, fn) { listeners[type] = fn; }, hidden: false },
    location: { protocol: 'https:', host: 'test.example', hostname: 'test.example', search: `?role=${role}` },
    navigator: { mediaDevices: { getUserMedia: () => { micRequests++; return deferMic ? new Promise(resolve => { resolveMic = resolve; }) : Promise.resolve(stream()); } } },
    crypto: require('node:crypto').webcrypto, URLSearchParams, WebSocket: Socket, RTCPeerConnection: Peer,
    AudioContext: Context, AudioWorkletNode: Worklet, MediaStream: class { constructor(sourceTracks) { this.getTracks = () => sourceTracks; } },
    ArrayBuffer, Uint8Array, setTimeout(fn, ms) { const id = Symbol(); timers.set(id, { fn, ms }); return id; }, clearTimeout(id) { timers.delete(id); },
    addEventListener(type, fn) { listeners[type] = fn; }, isSecureContext: true
  };
  sandbox.window = sandbox;
  vm.runInNewContext(client, sandbox);
  const tick = async () => { for (let i = 0; i < 20; i++) await Promise.resolve(); };
  const click = async id => { await element(id).handlers.click?.(); await tick(); };
  async function join(peer = true) {
    element('setup').handlers.submit({ preventDefault() {} });
    sockets[0].open();
    sockets[0].event({ type: 'ready', role, room: 'tel_77011234567', ice_servers: [], peer_present: peer });
    await tick();
  }
  return { elements, sockets, peers, tracks, contexts, nodes, sandbox, timers, tick, click, join, stream, listeners,
    get micRequests() { return micRequests; }, defer() { deferMic = true; }, resolve() { resolveMic(stream()); } };
}

test('second-joining caller can invite; SDP precedes ICE; early ICE waits for remote SDP', async () => {
  const h = harness();
  await h.join();
  assert.equal(h.micRequests, 0);
  assert.equal(h.elements.invite.disabled, false);
  assert.deepEqual(Object.keys(h.sockets[0].sent[0]).sort(), ['consent', 'role', 'room', 'type']);
  assert.equal(h.sockets[0].url, 'wss://test.example/ws/rtc');
  await h.click('invite');
  const ws = h.sockets[0];
  ws.event({ type: 'incoming', call_id: 'one' });
  ws.event({ type: 'accepted', call_id: 'one' });
  ws.event({ type: 'ice', call_id: 'one', candidate: { candidate: 'early' } });
  await h.tick();
  assert.equal(h.peers[0].candidates.length, 0);
  assert.deepEqual(ws.sent.slice(-2).map(event => event.type), ['offer', 'ice']);
  ws.event({ type: 'answer', call_id: 'one', sdp: 'answer' });
  await h.tick();
  assert.equal(h.peers[0].candidates[0].candidate, 'early');
  await h.click('mute');
  assert.equal(h.tracks[0].enabled, false);
  await h.click('hangup');
  assert.equal(h.tracks[0].stopped, true);
  assert.equal(h.peers[0].closed, true);
  assert.equal(h.elements.invite.disabled, true);
  ws.event({ type: 'ended', call_id: 'one' });
  await h.tick();
  assert.equal(h.elements.invite.disabled, false);
});

test('joined UI hides setup, identifies address and role, focuses heading and restores on disconnect', async () => {
  const h = harness('receiver');
  h.elements.consent.checked = true;
  assert.equal(h.elements.setup.hidden, false);
  assert.equal(h.elements['joined-summary'].hidden, true);
  await h.join();
  assert.equal(h.elements.setup.hidden, true);
  assert.equal(h.elements['joined-summary'].hidden, false);
  assert.ok(h.elements['joined-summary'].textContent.includes(h.elements.room.value));
  assert.match(h.elements['joined-summary'].textContent, /принимаю звонок/);
  assert.equal(h.elements['call-title'].focused, true);
  assert.equal(h.elements['joined-summary'].textContent.includes('test-only-placeholder'), false);
  await h.click('disconnect');
  assert.equal(h.elements.setup.hidden, false);
  assert.equal(h.elements['joined-summary'].hidden, true);
  assert.equal(h.elements['joined-summary'].textContent, '');
  assert.equal(h.elements['page-title'].focused, true);
  assert.equal(h.elements.role.disabled, false);
  assert.equal(h.elements.connect.disabled, false);
  assert.equal(h.elements.consent.checked, false);
  const html = fs.readFileSync(path.join(__dirname, '../server/static/rtc/index.html'), 'utf8');
  assert.match(html, /id="consent"[^>]*required/);
  assert.match(html, /id="call-title" tabindex="-1"/);
});

test('signaling error restores setup and clears consent for reconnect', async () => {
  const h = harness();
  h.elements.consent.checked = true;
  await h.join();
  h.sockets[0].event({ type: 'error', message: 'Disconnected' });
  await h.tick();
  assert.equal(h.elements.setup.hidden, false);
  assert.equal(h.elements['joined-summary'].hidden, true);
  assert.equal(h.elements['page-title'].focused, true);
  assert.equal(h.elements.consent.checked, false);
});

test('late microphone resolution after synchronous Android stop releases its track', async () => {
  const h = harness();
  await h.join();
  h.defer();
  const pending = h.click('invite');
  h.sandbox.safetyRtcStop();
  assert.equal(h.sockets[0].readyState, 3);
  h.resolve();
  await pending;
  assert.equal(h.tracks[0].stopped, true);
  assert.equal(h.sockets[0].sent.some(event => event.type === 'invite'), false);
});

test('receiver uses only remote track; gates PCM on ready; backpressure warning persists and resets next call', async () => {
  const h = harness('receiver');
  await h.join();
  const ws = h.sockets[0];
  ws.event({ type: 'incoming', call_id: 'one' });
  await h.tick();
  assert.equal(h.micRequests, 0);
  await h.click('accept');
  assert.equal(h.micRequests, 1);
  assert.equal(h.contexts[0].state, 'running');
  assert.equal(ws.sent.at(-1).type, 'accept');
  ws.event({ type: 'accepted', call_id: 'one', audio_ticket: 'single-use-placeholder' });
  ws.event({ type: 'offer', call_id: 'one', sdp: 'offer' });
  await h.tick();
  const remote = h.stream().getTracks()[0];
  h.elements.remote.rejectPlayback = true;
  h.peers[0].receivers.push({ track: remote });
  h.peers[0].ontrack({ track: remote });
  await h.tick();
  assert.equal(h.elements.playback.hidden, false);
  assert.equal(h.contexts[0].captured.getTracks()[0], remote);
  assert.notEqual(remote, h.tracks[0]);
  const audio = h.sockets[1];
  audio.open();
  assert.equal(audio.sent[0].sample_rate, 16000);
  assert.equal(audio.sent[0].consent, true);
  assert.equal(audio.sent[0].analysis_provider, 'google');
  const node = h.nodes[0];
  node.port.onmessage({ data: new ArrayBuffer(6400) });
  assert.equal(audio.sent.length, 1);
  audio.event({ type: 'ready', mode: 'gemini' });
  node.port.onmessage({ data: new ArrayBuffer(6400) });
  assert.equal(audio.sent.length, 2);
  audio.bufferedAmount = 30000;
  node.port.onmessage({ data: new ArrayBuffer(6400) });
  assert.equal(h.elements.incomplete.hidden, false);
  assert.equal(h.peers[0].closed, undefined);
  ws.event({ type: 'ended', call_id: 'one' });
  await h.tick();
  assert.equal(h.elements.incomplete.hidden, false);
  assert.equal(h.tracks.every(track => track.stopped), true);
  assert.equal(h.contexts[0].state, 'closed');
  ws.event({ type: 'incoming', call_id: 'two' });
  await h.tick();
  assert.equal(h.elements.incomplete.hidden, true);
});

test('connection timeout releases media and names TURN; hidden page synchronously closes socket', async () => {
  const h = harness();
  await h.join();
  await h.click('invite');
  h.sockets[0].event({ type: 'incoming', call_id: 'one' });
  h.sockets[0].event({ type: 'accepted', call_id: 'one' });
  await h.tick();
  const timeout = [...h.timers.values()].find(timer => timer.ms === 30000);
  assert.ok(timeout);
  timeout.fn();
  assert.match(h.elements['call-state'].textContent, /TURN/);
  assert.ok(h.tracks.every(track => track.stopped));
  assert.equal(h.peers[0].closed, true);
  h.sandbox.document.hidden = true;
  h.listeners.visibilitychange();
  assert.equal(h.sockets[0].readyState, 3);
  assert.match(h.elements['call-state'].textContent, /Экран скрыт/);
});

test('diagnostic ASR never implies recognition and does not terminate the call', async () => {
  const h = harness('receiver');
  await h.join();
  const ws = h.sockets[0];
  ws.event({ type: 'incoming', call_id: 'one' });
  await h.tick();
  await h.click('accept');
  ws.event({ type: 'accepted', call_id: 'one', audio_ticket: 'single-use-placeholder' });
  await h.tick();
  const audio = h.sockets[1];
  audio.open();
  audio.event({ type: 'ready', mode: 'diagnostic' });
  assert.equal(h.elements.incomplete.hidden, false);
  assert.match(h.elements['analysis-state'].textContent, /распознавание и оценка риска отсутствуют/);
  assert.equal(h.peers[0].closed, undefined);
  assert.equal(audio.readyState, 3);
  h.sandbox.safetyRtcStop();
});

async function receivingCall() {
  const h = harness('receiver');
  await h.join();
  h.sockets[0].event({ type: 'incoming', call_id: 'one' });
  await h.tick();
  await h.click('accept');
  h.sockets[0].event({ type: 'accepted', call_id: 'one', audio_ticket: 'unconsumed-ticket-placeholder' });
  await h.tick();
  return h;
}

test('unknown, missing and legacy ready modes stop analysis without ending the call', async () => {
  for (const mode of [undefined, 'unknown', 'whisper']) {
    const h = await receivingCall();
    const audio = h.sockets[1];
    audio.open();
    audio.event({ type: 'ready', mode });
    assert.equal(h.elements.incomplete.hidden, false);
    assert.match(h.elements['analysis-state'].textContent, /Режим анализа не поддерживается/);
    assert.equal(audio.readyState, 3);
    assert.equal(h.peers[0].closed, undefined);
    h.sandbox.safetyRtcStop();
  }
});

test('analysis requires Gemini readiness and never replaces peer audio with generated output', async () => {
  const h = await receivingCall();
  const audio = h.sockets[1];
  audio.open();
  const initialText = h.elements.transcript.textContent;
  const initialRisk = h.elements.risk.textContent;
  audio.event({ type: 'transcript', text: 'premature' });
  audio.event({ type: 'risk', level: 'low' });
  assert.equal(h.elements.transcript.textContent, initialText);
  assert.equal(h.elements.risk.textContent, initialRisk);
  audio.event({ type: 'ready', mode: 'gemini' });
  audio.event({ type: 'transcript', text: '<b>synthetic demo</b>' });
  audio.event({ type: 'risk', level: 'high', reasons: ['Synthetic warning'] });
  assert.equal(h.elements.transcript.textContent, '<b>synthetic demo</b>');
  assert.match(h.elements['analysis-state'].textContent, /Google Gemini Live/);
  assert.doesNotMatch(h.elements['analysis-state'].textContent, /перекрываться/);
  assert.match(h.elements.risk.textContent, /тревожные признаки/);
  assert.equal(h.elements.reasons.children[0].textContent, 'Synthetic warning');
  audio.event({ type: 'risk', level: 'low' });
  assert.equal(h.elements.risk.textContent, 'Модель не отметила явных признаков — это не гарантия безопасности.');
  const remote = h.elements.remote.srcObject;
  audio.event({ type: 'audio', data: 'generated-output' });
  audio.event({ serverContent: { modelTurn: { parts: [{ inlineData: { mimeType: 'audio/pcm', data: 'generated-output' } }] } } });
  assert.equal(h.elements.remote.srcObject, remote);
  assert.equal(h.peers[0].closed, undefined);
  h.sandbox.safetyRtcStop();
});

test('consent identifies external Google processing and free-tier demo privacy limits', () => {
  const html = fs.readFileSync(path.join(__dirname, '../server/static/rtc/index.html'), 'utf8');
  assert.match(html, /id="consent"[^>]*required[^>]*aria-describedby="privacy-help"/);
  assert.match(html, /во внешний сервис Google Gemini Live/);
  assert.match(html, /только с синтетической речью/);
  assert.match(html, /бесплатном тарифе Google может использовать переданные данные/);
  assert.match(html, /Ответы Gemini голосом не воспроизводятся/);
});

function fireTimer(h, ms) {
  const entry = [...h.timers].find(([, timer]) => timer.ms === ms);
  assert.ok(entry, `Expected ${ms}ms timer`);
  h.timers.delete(entry[0]);
  entry[1].fn();
}

test('Gemini error codes explain failure without retrying or ending the call', async () => {
  const cases = {
    quota_exceeded: /Лимит Google Gemini исчерпан/,
    model_unavailable: /Модель Gemini Live недоступна/,
    authentication_failed: /Google отклонил доступ/,
    session_expired: /Сеанс Gemini Live истёк/,
    upstream_closed: /Связь с Google Gemini потеряна/,
    processing_timeout: /не завершил обработку вовремя/,
    upstream_error: /Ошибка Google Gemini/,
    unknown: /Распознавание остановлено сервером/,
    toString: /Распознавание остановлено сервером/
  };
  for (const [code, expected] of Object.entries(cases)) {
    const h = await receivingCall();
    const audio = h.sockets[1];
    audio.open();
    audio.event({ type: 'ready', mode: 'gemini' });
    audio.event({ type: 'error', code, message: 'raw provider detail must not appear' });
    assert.match(h.elements['analysis-state'].textContent, expected);
    assert.match(h.elements['analysis-state'].textContent, /Разговор продолжается; анализ неполный/);
    assert.doesNotMatch(h.elements['analysis-state'].textContent, /raw provider/);
    assert.equal(h.elements.incomplete.hidden, false);
    assert.equal(audio.readyState, 3);
    assert.equal(h.peers[0].closed, undefined);
    assert.equal([...h.timers.values()].some(timer => timer.ms === 750), false);
    h.sandbox.safetyRtcStop();
  }
});

test('explicit busy retries same unconsumed ticket then resumes ASR while retaining gap warning', async () => {
  const h = await receivingCall();
  for (let attempt = 1; attempt <= 3; attempt++) {
    const audio = h.sockets[attempt];
    audio.open();
    assert.equal(audio.sent[0].audio_ticket, 'unconsumed-ticket-placeholder');
    assert.equal(audio.sent[0].consent, true);
    assert.equal(audio.sent[0].analysis_provider, 'google');
    audio.event({ type: 'error', code: 'busy', message: 'Previous inference still finishing' });
    assert.equal(audio.readyState, 3);
    assert.equal(h.peers[0].closed, undefined);
    assert.equal(h.elements.incomplete.hidden, false);
    fireTimer(h, 750);
  }
  const audio = h.sockets[4];
  audio.open();
  assert.equal(audio.sent[0].audio_ticket, 'unconsumed-ticket-placeholder');
  audio.event({ type: 'ready', mode: 'gemini' });
  assert.equal(h.elements.incomplete.hidden, false);
  assert.match(h.elements['analysis-state'].textContent, /Распознавание включено/);
  assert.equal([...h.timers.values()].some(timer => [750, 12000, 15000].includes(timer.ms)), false);
  audio.event({ type: 'transcript', text: 'Речь после подключения' });
  assert.equal(h.elements.transcript.textContent, 'Речь после подключения');
  h.sandbox.safetyRtcStop();
});

test('generic audio errors including busy wording do not retry; busy deadline keeps call alive', async () => {
  const generic = await receivingCall();
  generic.sockets[1].open();
  generic.sockets[1].event({ type: 'error', message: 'Invalid audio, busy ASR, or processing limit exceeded' });
  assert.equal(generic.sockets.length, 2);
  assert.equal([...generic.timers.values()].some(timer => timer.ms === 750), false);
  assert.equal(generic.elements.incomplete.hidden, false);
  generic.sandbox.safetyRtcStop();

  const busy = await receivingCall();
  busy.sockets[1].open();
  busy.sockets[1].event({ type: 'error', code: 'busy' });
  fireTimer(busy, 15000);
  assert.equal(busy.elements.incomplete.hidden, false);
  assert.match(busy.elements['analysis-state'].textContent, /15 секунд/);
  assert.equal(busy.peers[0].closed, undefined);
  assert.equal([...busy.timers.values()].some(timer => timer.ms === 750), false);
  busy.sandbox.safetyRtcStop();
});

test('stop cancels busy retries and stale callback cannot open sockets for next call', async () => {
  const h = await receivingCall();
  h.sockets[1].open();
  h.sockets[1].event({ type: 'error', code: 'busy' });
  const stale = [...h.timers.values()].find(timer => timer.ms === 750).fn;
  h.sockets[0].event({ type: 'ended', call_id: 'one' });
  await h.tick();
  assert.equal([...h.timers.values()].some(timer => [750, 12000, 15000].includes(timer.ms)), false);
  h.sockets[0].event({ type: 'incoming', call_id: 'two' });
  await h.tick();
  stale();
  assert.equal(h.sockets.length, 2);
  assert.equal(h.elements.incomplete.hidden, true);
  h.sandbox.safetyRtcStop();
  assert.equal(h.timers.size, 0);
});
