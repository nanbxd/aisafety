import './pcm-core.js';
class RemotePcmProcessor extends AudioWorkletProcessor {
  constructor() {
    super();
    this.resampler = new globalThis.PcmResampler(sampleRate);
  }
  process(inputs, outputs) {
    // The graph remains active through a silent output. Playback uses the audio element.
    for (const channel of outputs[0] || []) channel.fill(0);
    if (inputs[0]) this.resampler.push(inputs[0], frame => this.port.postMessage(frame, [frame]));
    return true;
  }
}
registerProcessor('remote-pcm', RemotePcmProcessor);
