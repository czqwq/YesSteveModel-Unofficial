package rip.ysm.imagestream.webp.enc;

class Block {
   static final int MAX_MODES = 20;
   final FullGetSetPointer src_diff;
   FullGetSetPointer coeff;
   FullGetSetPointer quant_fast;
   FullGetSetPointer quant_shift;
   FullGetSetPointer zbin;
   FullGetSetPointer zrun_zbin_boost;
   FullGetSetPointer round;
   int zbin_extra;
   FullGetSetPointer base_src;
   int src;
   int src_stride;
   private int base_pos = -1;
   FullGetSetPointer prev_base_src;
   private FullGetSetPointer actSrcptr;

   Block(FullGetSetPointer srcd) {
      this.src_diff = srcd;
   }

   FullGetSetPointer getSrcPtr() {
      if (this.prev_base_src != this.base_src || this.base_src.getPos() != this.base_pos) {
         this.base_pos = this.base_src.getPos();
         this.prev_base_src = this.base_src;
         this.actSrcptr = this.base_src.shallowCopyWithPosInc(this.src);
      }

      return this.actSrcptr;
   }
}
