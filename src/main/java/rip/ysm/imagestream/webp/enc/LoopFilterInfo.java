package rip.ysm.imagestream.webp.enc;

class LoopFilterInfo {
   final GetPointer mblim;
   final GetPointer blim;
   final GetPointer lim;
   final GetPointer hev_thr;

   LoopFilterInfo(int frame_type, LoopFilterInfoN lfi_n, int filter_level) {
      int hev_index = lfi_n.hev_thr_lut.get(frame_type)[filter_level];
      this.mblim = new GetPointer(lfi_n.mblim[filter_level], 0);
      this.blim = new GetPointer(lfi_n.blim[filter_level], 0);
      this.lim = new GetPointer(lfi_n.lim[filter_level], 0);
      this.hev_thr = new GetPointer(lfi_n.hev_thr[hev_index], 0);
   }
}
