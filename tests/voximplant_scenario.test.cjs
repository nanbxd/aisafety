const {test} = require('node:test');
const assert = require('node:assert/strict');
const vm = require('node:vm');
const fs = require('node:fs');
function fixture() {
  const handlers = {}, calls = {}, sent = [], attached = [], detached = [];
  const socket = {readyState:'open', addEventListener:(key,fn)=>handlers[key]=fn,
    send:text=>sent.push(JSON.parse(text)), close(){this.closed=true}};
  const call = {addEventListener:(key,fn)=>calls[key]=fn,
    sendMediaTo:(ws,params)=>attached.push(params), stopMediaTo:ws=>detached.push(ws)};
  const context = {require(){}, Modules:{WebSocket:1}, VoxEngine:{createWebSocket:()=>socket},
    WebSocketEvents:{OPEN:'open',MESSAGE:'message',CLOSE:'close',ERROR:'error'},
    CallEvents:{Disconnected:'disconnected',Failed:'failed'}, WebSocketReadyState:{OPEN:'open'},
    WebSocketAudioEncoding:{PCM16_16KHZ:'PCM16'},setTimeout:()=>1,clearTimeout(){}};
  vm.createContext(context);
  vm.runInContext(fs.readFileSync('voximplant/media-stream.js','utf8'),context);
  return {context,handlers,calls,sent,attached,detached,call,socket};
}
test('VoxEngine waits for ready and selects 16 kHz; stop detaches without hanging up call',()=>{
  const f=fixture();
  const monitor=f.context.attachFraudMonitor(f.call,'test-call-123',true,'wss://demo.example/media-stream');
  f.handlers.open();
  assert.equal(f.sent[0].user_id,'test-call-123');
  assert.equal(f.attached.length,0);
  f.handlers.message({text:JSON.stringify({type:'ready'})});
  assert.equal(f.attached[0].encoding,'PCM16');
  f.handlers.message({text:JSON.stringify({type:'ready'})});
  assert.equal(f.attached.length,1);
  monitor.stop(); monitor.stop();
  assert.equal(f.detached.length,1);
  assert.equal(f.sent.at(-1).type,'stop');
});
test('VoxEngine refuses missing consent',()=>{
  const f=fixture();
  assert.throws(()=>f.context.attachFraudMonitor(f.call,'test-call-123',false,'wss://demo.example/media-stream'));
  assert.equal(f.attached.length,0);
});
test('late ready after ending does not restart streaming',()=>{
  const f=fixture();
  const monitor=f.context.attachFraudMonitor(f.call,'test-call-123',true,'wss://demo.example/media-stream');
  f.handlers.open();monitor.stop();f.handlers.message({text:JSON.stringify({type:'ready'})});
  assert.equal(f.attached.length,0);
});
