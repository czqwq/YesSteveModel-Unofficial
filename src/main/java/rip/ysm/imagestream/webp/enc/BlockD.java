package rip.ysm.imagestream.webp.enc;

class BlockD {
   static final boolean SEGMENT_DELTADATA = false;
   static final boolean SEGMENT_ABSDATA = true;
   static final int MAX_MB_SEGMENTS = 4;
   static final int MB_FEATURE_TREE_PROBS = 3;
   static final int MAX_REF_LF_DELTAS = 4;
   static final int MAX_MODE_LF_DELTAS = 4;
   static final int VP8_YMODES = MBPredictionMode.B_PRED.ordinal() + 1;
   static final int VP8_UV_MODES = MBPredictionMode.TM_PRED.ordinal() + 1;
   static final short[] vp8_block2left = new short[]{0, 0, 0, 0, 1, 1, 1, 1, 2, 2, 2, 2, 3, 3, 3, 3, 4, 4, 5, 5, 6, 6, 7, 7, 8};
   static final short[] vp8_block2above = new short[]{0, 1, 2, 3, 0, 1, 2, 3, 0, 1, 2, 3, 0, 1, 2, 3, 4, 5, 4, 5, 6, 7, 6, 7, 8};
   FullGetSetPointer qcoeff;
   FullGetSetPointer dqcoeff;
   final FullGetSetPointer predictor;
   FullGetSetPointer dequant;
   private int offset;
   FullGetSetPointer eob;
   private int prevPos = -1;
   private FullGetSetPointer prevBase;
   private FullGetSetPointer currPointer;
   final BModeInfo bmi = new BModeInfo();

   BlockD(FullGetSetPointer pred) {
      this.predictor = pred;
   }

   void calcBlockYOffset(int blockIdx, int stride) {
      this.offset = (blockIdx >> 2) * stride + (blockIdx & 3) << 2;
      this.prevBase = null;
   }

   void calcBlockUVOffset(int blockIdx, int stride) {
      this.offset = (blockIdx - 16 >> 1) * stride + (blockIdx & 1) << 2;
      this.prevBase = null;
   }

   int getOffset() {
      return this.offset;
   }

   void setOffset(int offset) {
      this.offset = offset;
      this.prevBase = null;
   }

   FullGetSetPointer getOffsetPointer(FullGetSetPointer base) {
      if (base != this.prevBase || base.getPos() != this.prevPos) {
         this.prevBase = base;
         this.prevPos = base.getPos();
         this.currPointer = base.shallowCopyWithPosInc(this.offset);
      }

      return this.currPointer;
   }
}
