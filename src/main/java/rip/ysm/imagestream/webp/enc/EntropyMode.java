package rip.ysm.imagestream.webp.enc;

final class EntropyMode {
   static final GetSetPointer vp8_bmode_tree = new GetSetPointer(new short[]{0, 2, -1, 4, -2, 6, 8, 12, -3, 10, -5, -6, -4, 14, -7, 16, -8, -9}, 0);
   static final GetSetPointer vp8_ymode_tree = new GetSetPointer(
      new short[]{
         (short)(-MBPredictionMode.DC_PRED.ordinal()),
         2,
         4,
         6,
         (short)(-MBPredictionMode.V_PRED.ordinal()),
         (short)(-MBPredictionMode.H_PRED.ordinal()),
         (short)(-MBPredictionMode.TM_PRED.ordinal()),
         (short)(-MBPredictionMode.B_PRED.ordinal())
      },
      0
   );
   static final GetSetPointer vp8_kf_ymode_tree = new GetSetPointer(
      new short[]{
         (short)(-MBPredictionMode.B_PRED.ordinal()),
         2,
         4,
         6,
         (short)(-MBPredictionMode.DC_PRED.ordinal()),
         (short)(-MBPredictionMode.V_PRED.ordinal()),
         (short)(-MBPredictionMode.H_PRED.ordinal()),
         (short)(-MBPredictionMode.TM_PRED.ordinal())
      },
      0
   );
   static final GetSetPointer vp8_uv_mode_tree = new GetSetPointer(
      new short[]{
         (short)(-MBPredictionMode.DC_PRED.ordinal()),
         2,
         (short)(-MBPredictionMode.V_PRED.ordinal()),
         4,
         (short)(-MBPredictionMode.H_PRED.ordinal()),
         (short)(-MBPredictionMode.TM_PRED.ordinal())
      },
      0
   );
   static final GetSetPointer vp8_sub_mv_ref_tree = new GetSetPointer(new short[]{-10, 2, -11, 4, -12, -13}, 0);
   static final GetSetPointer vp8_small_mvtree = new GetSetPointer(new short[]{2, 8, 4, 6, 0, -1, -2, -3, 10, 12, -4, -5, -6, -7}, 0);
   static final GetSetPointer vp8_kf_ymode_prob = new GetSetPointer(new short[]{145, 156, 163, 128}, 0);
   static final GetSetPointer vp8_kf_uv_mode_prob = new GetSetPointer(new short[]{142, 114, 183}, 0);
   static final GetPointer vp8_bmode_prob = new GetPointer(new short[]{120, 90, 79, 133, 87, 85, 80, 111, 151}, 0);
   static final GetPointer vp8_ymode_prob = new GetPointer(new short[]{112, 86, 140, 37}, 0);
   static final GetPointer vp8_uv_mode_prob = new GetPointer(new short[]{162, 101, 204}, 0);
   static final GetPointer sub_mv_ref_prob = new GetPointer(new short[]{180, 162, 25}, 0);

   private EntropyMode() {
   }
}
