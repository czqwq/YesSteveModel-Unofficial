package rip.ysm.imagestream.webp.enc;

class QuantDetails {
   final FullGetSetPointer[] quant = new FullGetSetPointer[128];
   final FullGetSetPointer[] quant_shift = new FullGetSetPointer[128];
   final FullGetSetPointer[] zbin = new FullGetSetPointer[128];
   final FullGetSetPointer[] round = new FullGetSetPointer[128];
   final FullGetSetPointer[] zrun_zbin_boost = new FullGetSetPointer[128];
   final FullGetSetPointer[] quant_fast = new FullGetSetPointer[128];

   QuantDetails() {
      FullGetSetPointer[][] temp = new FullGetSetPointer[][]{this.quant, this.quant_shift, this.zbin, this.round, this.zrun_zbin_boost, this.quant_fast};

      for (FullGetSetPointer[] q : temp) {
         for (int i = 0; i < q.length; i++) {
            q[i] = new FullGetSetPointer(16);
         }
      }
   }

   void shallowCopyTo(Block where, int Q) {
      where.quant_fast = this.quant_fast[Q].shallowCopy();
      where.quant_shift = this.quant_shift[Q].shallowCopy();
      where.zbin = this.zbin[Q].shallowCopy();
      where.round = this.round[Q].shallowCopy();
      where.zrun_zbin_boost = this.zrun_zbin_boost[Q].shallowCopy();
   }
}
