'use strict';
const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');
const path = require('node:path');
function scene() {
  const elements = {};
  const el = id => elements[id] ||= {dataset:{},setAttribute(k,v){this[k]=v},addEventListener(){},focus(){}};
  const document = {body:{dataset:{}},getElementById:el,addEventListener(){}};
  const window = {};
  vm.runInNewContext(fs.readFileSync(path.join(__dirname, '../server/static/rtc/scene.js'), 'utf8'), {
    document,window,matchMedia:()=>({matches:false,addEventListener(){}}), fetch:async()=>({ok:true,json:async()=>({})})
  });
  const update = overrides => window.SkepticallScene.update({joined:true,busy:true,ringing:false,receiver:true,level:'unknown',unavailable:false,...overrides});
  return {update,elements,document};
}
test('character follows risk, analysis failure takes priority, next call resets', () => {
  const s=scene();
  for(const level of ['low','medium','high']){s.update({level});assert.equal(s.elements.scene.dataset.state,level)}
  s.update({level:'high',unavailable:true});assert.equal(s.elements.scene.dataset.state,'unavailable');
  s.update({level:'high',busy:false});assert.equal(s.elements.scene.dataset.state,'idle');
  s.update({ringing:true});assert.equal(s.elements.scene.dataset.state,'ringing');
  s.update({});assert.equal(s.elements.scene.dataset.state,'listening');
});
test('caller does not claim an assessment and disconnected state returns to lobby', () => {
  const s=scene();s.update({receiver:false,level:'high'});
  assert.equal(s.elements.scene.dataset.state,'listening');
  s.update({joined:false,busy:false});assert.equal(s.document.body.dataset.joined,'false');
  assert.equal(s.elements['scene-fallback'].hidden,false);
});
