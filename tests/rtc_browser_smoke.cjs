/* Optional real Chromium smoke. Start Python fixture with --serve-only first.
 * Usage: node tests/rtc_browser_smoke.cjs <playwright-module> <fixture-url>
 * Synthetic 440Hz caller / 880Hz receiver; fixture asserts caller-only PCM.
 * This is NOT a live Gemini accuracy, handset, or NAT/TURN test. */
const { chromium } = require(process.argv[2] || 'playwright');
const base = process.argv[3];
if (!base || !/^http:\/\/localhost:\d+$/.test(base)) throw new Error('Expected localhost fixture URL');
const assert = require('node:assert/strict');
const path = require('node:path');
const fs = require('node:fs');
const shots = path.resolve(__dirname, '../.impeccable/review');
fs.mkdirSync(shots, {recursive:true});
(async () => {
  const browser = await chromium.launch({executablePath:process.env.CHROME_PATH || 'C:/Program Files/Google/Chrome/Application/chrome.exe', headless:true, args:['--autoplay-policy=no-user-gesture-required']});
  try {
    const contexts = await Promise.all([browser.newContext({viewport:{width:1440,height:1000}}),browser.newContext({viewport:{width:390,height:844}})]);
    const pages=[]; const errors=[];
    for(let i=0;i<2;i++) {
      await contexts[i].addInitScript(() => {
        window.testTracks=[];window.testPeers=[];window.testSockets=[];
        const Peer=RTCPeerConnection,Socket=WebSocket;
        window.RTCPeerConnection=class extends Peer {constructor(...a){super(...a);window.testPeers.push(this)}};
        window.WebSocket=class extends Socket {constructor(...a){super(...a);window.testSockets.push(this)}};
        navigator.mediaDevices.getUserMedia=async()=>{
          const c=new AudioContext(),o=c.createOscillator(),g=c.createGain(),d=c.createMediaStreamDestination();
          o.frequency.value=location.search.includes('caller')?440:880;g.gain.value=.25;
          o.connect(g);g.connect(d);o.start();await c.resume();
          for(const t of d.stream.getTracks()){const stop=t.stop.bind(t);t.stop=()=>{stop();void c.close().catch(()=>{})};window.testTracks.push(t)}
          return d.stream;
        };
      });
      const p=await contexts[i].newPage();p.on('pageerror',e=>errors.push(e.message));
      p.on('console',m=>{if(m.type()==='error' && !m.location().url.endsWith('/favicon.ico')) errors.push(m.text())});
      await p.goto(base+'/rtc?role='+(i===0?'caller':'receiver'));pages.push(p);
    }
    const [caller,receiver]=pages;
    for(const p of [receiver,caller]){await p.locator('#room').fill('synthetic-room-123');await p.locator('#consent').check();await p.locator('#connect').click()}
    for(const p of pages) await p.locator('#setup').waitFor({state:'hidden'});
    await caller.locator('#invite').click();
    await receiver.locator('#accept').waitFor({state:'visible'});
    assert(await receiver.locator('#accept').evaluate(e=>{const r=e.getBoundingClientRect();return r.top>=0&&r.bottom<=innerHeight}),'Answer button must be in first viewport');
    await receiver.locator('#reject').click();
    if(process.argv[4]==='--busy-redial') {
      await caller.locator('#invite').click();await receiver.locator('#accept').click();
      await receiver.waitForFunction(()=>document.querySelector('#call-state').textContent==='Разговор идёт');
      // The fixture delays first transport cleanup to exercise busy admission.
      await new Promise(resolve=>setTimeout(resolve,6000));
      await receiver.locator('#hangup').click();
    }
    for(let i=0;i<2;i++){
      await caller.locator('#invite').click();await receiver.locator('#accept').click();
      await receiver.waitForFunction(()=>document.querySelector('#transcript').textContent.includes('безопасный'),null,{timeout:30000}).catch(async error=>{ console.error('Receiver state:', await receiver.locator('main').innerText()); console.error('Browser errors:',errors); throw error; });
      if(i===0 && process.argv[4]==='--busy-redial') assert(await receiver.locator('#incomplete').isVisible(),'Busy retry must preserve initial audio gap warning');
      assert.match(await receiver.locator('#risk').innerText(),/тревожные/);
      assert.equal(await caller.locator('#call-state').innerText(),'Разговор идёт');
      for(const p of pages) assert(await p.evaluate(()=>document.documentElement.scrollWidth<=innerWidth));
      await caller.locator('#mute').click();assert.equal(await caller.locator('#mute').getAttribute('aria-pressed'),'true');
      await caller.locator('#mute').click();
      if(i===0){await receiver.locator('#hangup').click();}
    }
    for(let i=0;i<2;i++)await pages[i].screenshot({path:path.join(shots,i?'mobile-native.png':'desktop-native.png'),fullPage:true});
    // Close only analysis transport: P2P must survive, with a sticky warning.
    await receiver.evaluate(()=>window.testSockets.find(s=>s.url.endsWith('/ws/rtc-audio')&&s.readyState===1).close());
    await receiver.locator('#incomplete').waitFor({state:'visible'});
    assert.equal(await receiver.locator('#call-state').innerText(),'Разговор идёт');
    await receiver.evaluate(()=>window.safetyRtcStop());
    assert(await receiver.locator('#setup').isVisible(),'Disconnect must restore setup');
    assert(await receiver.evaluate(()=>window.testTracks.every(t=>t.readyState==='ended')&&window.testPeers.every(p=>p.connectionState==='closed')));
    await caller.waitForFunction(()=>document.querySelector('#call-state').textContent.includes('отключился'));
    await caller.evaluate(()=>window.safetyRtcStop());
    assert(await caller.evaluate(()=>window.testTracks.every(t=>t.readyState==='ended')));
    assert.deepEqual(errors,[]);
    console.log('PASS local P2P, remote440Hz PCM, synthetic risk, reject/redial, mute, analysis transport loss isolation, cleanup, no overflow/page errors');
  } finally {await browser.close()}
})().catch(e=>{console.error(e);process.exitCode=1});
