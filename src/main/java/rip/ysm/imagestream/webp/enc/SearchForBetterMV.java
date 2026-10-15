package rip.ysm.imagestream.webp.enc;

class SearchForBetterMV {
   static final MV[] neighbors = new MV[]{new MV(0, -2), new MV(0, 2), new MV(-2, 0), new MV(2, 0)};
   static final MV[] diagonals = new MV[]{new MV(-2, -2), new MV(-2, 2), new MV(2, -2), new MV(2, 2)};
   static final int[] iters = new int[]{4, 4};
   int tr;
   int tc;
   int minc;
   int maxc;
   int minr;
   int maxr;
   int rr;
   int rc;
   int error_per_bit;
   VarianceFNs vfp;
   GetPointer[] mvcost;
   FullGetSetPointer y;
   FullGetSetPointer z;
   Block b;
   static final int y_stride = 32;
   int offset;
   long besterr;
   short br;
   short bc;
   final VarianceResults sse = new VarianceResults();
   final VarianceResults sse1 = new VarianceResults();
   final long[] results = new long[neighbors.length];

   void moveToBest() {
      this.tr = this.br;
      this.tc = this.bc;
   }

   void lookAround() {
      for (short divisor = 1; divisor < 3; divisor++) {
         for (int i = 0; i < iters[divisor - 1]; i++) {
            for (int j = 0; j < neighbors.length; j++) {
               this.results[j] = this.checkbetter(neighbors[j], divisor);
            }

            int whichdir = (this.results[0] < this.results[1] ? 0 : 1) + (this.results[2] < this.results[3] ? 0 : 2);
            this.checkbetter(diagonals[whichdir], divisor);
            if (this.tr == this.br && this.tc == this.bc) {
               break;
            }

            this.moveToBest();
         }
      }
   }

   void saveIfBetter(long v, short r, short c) {
      if (v < this.besterr) {
         this.besterr = v;
         this.br = r;
         this.bc = c;
         this.sse1.variance = this.sse.variance;
         this.sse1.sse = this.sse.sse;
      }
   }

   long actualCheck(FullGetSetPointer y, int xO, int yO, short r, short c) {
      this.vfp.svf.call(y, 32, xO, yO, this.z, this.b.src_stride, this.sse);
      long v = this.sse.variance + MComp.mv_err_cost(r - this.rr, c - this.rc, this.mvcost, this.error_per_bit);
      this.saveIfBetter(v, r, c);
      return v;
   }

   long checkbetter(MV direction, short divisor) {
      short c = (short)(this.tc - direction.col / divisor);
      short r = (short)(this.tr - direction.row / divisor);
      long v;
      if (c >= this.minc && c <= this.maxc && r >= this.minr && r <= this.maxr) {
         v = this.actualCheck(this.y.shallowCopyWithPosInc((r >> 2) * 32 + (c >> 2) - this.offset), (c & 3) << 1, (r & 3) << 1, r, c);
      } else {
         v = 2147483647L;
      }

      return v;
   }
}
