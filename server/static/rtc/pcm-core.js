/* Streaming area-average resampler: preserves phase and filter history across blocks. */
(function (root) {
  'use strict';
  class PcmResampler {
    constructor(rate, outputRate = 16000, frameSamples = 3200) {
      if (!Number.isFinite(rate) || rate < outputRate || outputRate <= 0 || !Number.isInteger(frameSamples) || frameSamples < 1 || frameSamples > 4000) throw new Error('Unsupported PCM configuration');
      this.ratio = rate / outputRate;
      this.remaining = this.ratio;
      this.sum = 0;
      this.frameSamples = frameSamples;
      this.frame = new ArrayBuffer(frameSamples * 2);
      this.view = new DataView(this.frame);
      this.used = 0;
    }
    push(channels, emit) {
      if (!channels.length || !channels[0].length) return;
      for (let i = 0; i < channels[0].length; i++) {
        let value = 0;
        for (const channel of channels) value += channel[i] || 0;
        value /= channels.length;
        let available = 1;
        while (available > 1e-9) {
          const take = Math.min(available, this.remaining);
          this.sum += value * take;
          this.remaining -= take;
          available -= take;
          if (this.remaining < 1e-9) {
            const sample = Math.max(-1, Math.min(1, this.sum / this.ratio));
            this.view.setInt16(this.used * 2, Math.round(sample * (sample < 0 ? 32768 : 32767)), true);
            this.used++;
            this.sum = 0;
            this.remaining = this.ratio;
            if (this.used === this.frameSamples) {
              emit(this.frame);
              this.frame = new ArrayBuffer(this.frameSamples * 2);
              this.view = new DataView(this.frame);
              this.used = 0;
            }
          }
        }
      }
    }
  }
  root.PcmResampler = PcmResampler;
  if (typeof module !== 'undefined') module.exports = { PcmResampler };
})(globalThis);
