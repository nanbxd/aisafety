// VoxEngine helper: include in your existing two-party call routing scenario.
// Call only after both participants consent and Android reports SUBSCRIBED.
require(Modules.WebSocket);

function attachFraudMonitor(call, userId, consent, serverUrl) {
  if (consent !== true) throw new Error('Both participants must consent to Google analysis');
  if (!/^[A-Za-z0-9_+@.\-]{1,128}$/.test(userId)) throw new Error('Invalid user_id');
  if (!/^wss:\/\/[^/]+\/media-stream$/.test(serverUrl)) throw new Error('Expected wss://HOST/media-stream');
  const ws = VoxEngine.createWebSocket(serverUrl, {trace: false});
  let streaming = false;
  let ending = false;
  let timer = setTimeout(() => ws.close(), 20000);
  function detach() {
    clearTimeout(timer);
    if (streaming) call.stopMediaTo(ws);
    streaming = false;
  }
  ws.addEventListener(WebSocketEvents.OPEN, () => {
    if (ending) { ws.close(); return; }
    ws.send(JSON.stringify({customEvent: 'safety-start', type: 'start', user_id: userId,
      consent: true, analysis_provider: 'google', format: 'pcm_s16le',
      sample_rate: 16000, channels: 1, language: 'ru'}));
  });
  ws.addEventListener(WebSocketEvents.MESSAGE, event => {
    let message;
    try { message = JSON.parse(event.text); } catch (_) { return; }
    if (message.type === 'ready' && !streaming && !ending) {
      clearTimeout(timer);
      streaming = true;
      // Only this leg's incoming voice is copied; keep the existing call bridge intact.
      call.sendMediaTo(ws, {encoding: WebSocketAudioEncoding.PCM16_16KHZ});
    } else if (message.type === 'ERROR' || message.type === 'done') {
      detach(); ws.close();
    }
  });
  ws.addEventListener(WebSocketEvents.CLOSE, detach);
  ws.addEventListener(WebSocketEvents.ERROR, detach);
  function stop() {
    if (ending) return;
    ending = true;
    detach();
    if (ws.readyState === WebSocketReadyState.OPEN) {
      ws.send(JSON.stringify({customEvent: 'safety-stop', type: 'stop'}));
      timer = setTimeout(() => ws.close(), 18000);
    } else ws.close();
  }
  call.addEventListener(CallEvents.Disconnected, stop);
  call.addEventListener(CallEvents.Failed, stop);
  return {stop};
}
