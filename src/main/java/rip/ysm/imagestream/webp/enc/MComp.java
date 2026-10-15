package rip.ysm.imagestream.webp.enc;

final class MComp {
   static final Compressor.FractionalMVStepIF vp8_find_best_sub_pixel_step_iteratively = MComp::find_best_sub_pixel_step_iteratively;
   static final Compressor.FractionalMVStepIF vp8_find_best_sub_pixel_step = (x, b, d, bestmv, ref_mv, error_per_bit, vfp, mvcost, ret) -> find_best_sub_pixel_step(
      x, b, d, bestmv, ref_mv, error_per_bit, vfp, mvcost, ret, true
   );
   static final Compressor.FractionalMVStepIF vp8_find_best_half_pixel_step = (x, b, d, bestmv, ref_mv, error_per_bit, vfp, mvcost, ret) -> find_best_sub_pixel_step(
      x, b, d, bestmv, ref_mv, error_per_bit, vfp, mvcost, ret, false
   );
   static final Compressor.FractionalMVStepIF vp8_skip_fractional_mv_step = (x, b, d, bestmv, ref_mv, error_per_bit, vfp, mvcost, ret) -> {
      bestmv.set(bestmv.mul8());
      return 0L;
   };
   static final short MAX_MVSEARCH_STEPS = 8;
   static final short MAX_FULL_PEL_VAL = 255;
   static final short MAX_FIRST_STEP = 128;

   private MComp() {
   }

   static int mv_err_cost(MV mv, MV ref, GetPointer[] mvcost, int error_per_bit) {
      MV mv_idx = getRelCoords(mv, ref);
      return mv_err_cost(mv_idx.row, mv_idx.col, mvcost, error_per_bit);
   }

   private static MV getRelCoords(MV mv, MV ref) {
      return new MV(CUtils.clamp((short)(mv.row - ref.row >> 1), (short)0, (short)2047), CUtils.clamp((short)(mv.col - ref.col >> 1), (short)0, (short)2047));
   }

   static int mv_err_cost(int r, int c, GetPointer[] mvcost, int error_per_bit) {
      return mvcost != null ? (mvcost[0].getRel(r) + mvcost[1].getRel(c)) * error_per_bit + 128 >> 8 : 0;
   }

   static long find_best_sub_pixel_step_iteratively(
      Macroblock x, Block b, BlockD d, MV bestmv, MV ref_mv, int error_per_bit, VarianceFNs vfp, GetPointer[] mvcost, VarianceResults ret
   ) {
      SearchForBetterMV cb = new SearchForBetterMV();
      cb.z = b.getSrcPtr();
      cb.rr = ref_mv.row >> 1;
      cb.rc = ref_mv.col >> 1;
      cb.br = (short)(bestmv.row << 2);
      cb.bc = (short)(bestmv.col << 2);
      cb.moveToBest();
      cb.minc = Math.max(x.mv_col_min * 4, (ref_mv.col >> 1) - 1023);
      cb.maxc = Math.min(x.mv_col_max * 4, (ref_mv.col >> 1) + 1023);
      cb.minr = Math.max(x.mv_row_min * 4, (ref_mv.row >> 1) - 1023);
      cb.maxr = Math.min(x.mv_row_max * 4, (ref_mv.row >> 1) + 1023);
      cb.error_per_bit = error_per_bit;
      cb.vfp = vfp;
      cb.mvcost = mvcost;
      cb.b = b;
      cb.sse1.variance = ret.variance;
      cb.sse1.sse = ret.sse;
      int pre_stride = x.e_mbd.pre.y_stride;
      MacroblockD xd = x.e_mbd;
      int buf_r1 = bestmv.row - 3 < x.mv_row_min ? bestmv.row - x.mv_row_min : 3;
      int buf_r2 = bestmv.row + 3 > x.mv_row_max ? x.mv_row_max - bestmv.row : 3;
      int buf_c1 = bestmv.col - 3 < x.mv_col_min ? bestmv.col - x.mv_col_min : 3;
      FullGetSetPointer y_0 = d.getOffsetPointer(x.e_mbd.pre.y_buffer)
         .shallowCopyWithPosInc(bestmv.row * pre_stride + bestmv.col - buf_c1 - pre_stride * buf_r1);
      vfp.copymem.call(y_0, pre_stride, xd.y_buf, 32, 16 + buf_r1 + buf_r2);
      cb.y = xd.y_buf.shallowCopyWithPosInc(32 * buf_r1 + buf_c1);
      cb.offset = bestmv.row * 32 + bestmv.col;
      bestmv.set(bestmv.mul8());
      vfp.vf.call(cb.y, 32, cb.z, b.src_stride, cb.sse1);
      cb.besterr = cb.sse1.variance;
      ret.variance = cb.besterr;
      cb.besterr = cb.besterr + mv_err_cost(bestmv, ref_mv, mvcost, error_per_bit);
      cb.lookAround();
      bestmv.row = (short)(cb.br << 1);
      bestmv.col = (short)(cb.bc << 1);
      if (Math.abs(bestmv.col - ref_mv.col) <= 2040 && Math.abs(bestmv.row - ref_mv.row) <= 2040) {
         ret.sse = cb.sse1.sse;
         ret.variance = cb.sse1.variance;
         return cb.besterr;
      } else {
         return 2147483647L;
      }
   }

   static long find_best_sub_pixel_step(
      Macroblock x, Block b, BlockD d, MV bestmv, MV ref_mv, int error_per_bit, VarianceFNs vfp, GetPointer[] mvcost, VarianceResults ret, boolean doquarter
   ) {
      final SearchForBetterMV cb = new SearchForBetterMV();
      cb.z = b.getSrcPtr();
      cb.error_per_bit = error_per_bit;
      cb.vfp = vfp;
      cb.b = b;
      cb.sse1.variance = ret.variance;
      cb.sse1.sse = ret.sse;
      MV startmv = new MV();
      int pre_stride = x.e_mbd.pre.y_stride;
      MacroblockD xd = x.e_mbd;
      FullGetSetPointer y_0 = d.getOffsetPointer(x.e_mbd.pre.y_buffer).shallowCopyWithPosInc(bestmv.row * pre_stride + bestmv.col);
      vfp.copymem.call(y_0.shallowCopyWithPosInc(-1 - pre_stride), pre_stride, xd.y_buf, 32, 18);
      cb.y = xd.y_buf.shallowCopyWithPosInc(33);
      bestmv.set(bestmv.mul8());
      startmv.set(bestmv);
      vfp.vf.call(cb.y, 32, cb.z, b.src_stride, cb.sse1);
      cb.besterr = cb.sse1.variance;
      ret.variance = cb.besterr;
      cb.besterr = cb.besterr + mv_err_cost(bestmv, ref_mv, mvcost, error_per_bit);
      cb.bc = bestmv.col;
      cb.br = bestmv.row;
      cb.rr = ref_mv.row;
      cb.rc = ref_mv.col;

      class DirSpec extends MV {
         final FullGetSetPointer y;
         final int xoff;
         final int yoff;

         DirSpec(int r, int c, int ypshift, int xo, int yo) {
            super(r, c);
            this.y = cb.y.shallowCopyWithPosInc(ypshift);
            if (xo == -1) {
               this.xoff = c & 7;
            } else {
               this.xoff = xo;
            }

            if (yo == -1) {
               this.yoff = r & 7;
            } else {
               this.yoff = yo;
            }
         }

         void dircheck() {
            cb.actualCheck(this.y, this.xoff, this.yoff, this.row, this.col);
         }
      }

      DirSpec[] diags = new DirSpec[]{
         new DirSpec(startmv.row - 8 | 4, startmv.col - 8 | 4, -33, 4, 4),
         new DirSpec(startmv.row - 8 | 4, startmv.col + 4, -32, 4, 4),
         new DirSpec(startmv.row + 4, startmv.col - 8 | 4, -1, 4, 4),
         new DirSpec(startmv.row + 4, startmv.col + 4, 0, 4, 4)
      };
      DirSpec[] basedirs = new DirSpec[]{
         new DirSpec(startmv.row, startmv.col - 8 | 4, -1, 4, 0),
         new DirSpec(startmv.row, (startmv.col - 8 | 4) + 8, 0, 4, 0),
         new DirSpec(startmv.row - 8 | 4, startmv.col, -32, 0, 4),
         new DirSpec((startmv.row - 8 | 4) + 8, startmv.col, 0, 0, 4)
      };

      for (DirSpec dir : basedirs) {
         dir.dircheck();
      }

      int whichdir = 3;
      diags[3].dircheck();
      if (doquarter) {
         if (bestmv.row < startmv.row) {
            cb.y.incBy(-32);
         }

         if (bestmv.col < startmv.col) {
            cb.y.dec();
         }

         startmv.set(bestmv);
         if ((startmv.col & 7) != 0) {
            basedirs[0] = new DirSpec(startmv.row, startmv.col - 2, 0, -1, -1);
         } else {
            basedirs[0] = new DirSpec(startmv.row, startmv.col - 8 | 6, -1, 6, -1);
         }

         basedirs[1] = new DirSpec(basedirs[0].row, basedirs[0].col + 4, 0, -1, -1);
         if ((startmv.row & 7) != 0) {
            basedirs[2] = new DirSpec(startmv.row - 2, startmv.col, 0, -1, -1);
         } else {
            basedirs[2] = new DirSpec(startmv.row - 8 | 6, startmv.col, -32, -1, 6);
         }

         basedirs[3] = new DirSpec(basedirs[2].row + 4, basedirs[0].col, 0, -1, -1);

         for (DirSpec dir : basedirs) {
            dir.dircheck();
         }

         diags[0] = new DirSpec(startmv.row + 2, startmv.col + 2, 0, -1, -1);
         diags[0].dircheck();
      }

      bestmv.row = cb.br;
      bestmv.col = cb.bc;
      ret.variance = cb.sse1.variance;
      ret.sse = cb.sse1.sse;
      return cb.besterr;
   }
}
