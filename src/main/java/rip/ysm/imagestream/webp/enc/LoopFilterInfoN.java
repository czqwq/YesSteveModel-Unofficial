package rip.ysm.imagestream.webp.enc;

import java.util.Arrays;
import java.util.EnumMap;
import java.util.HashMap;

class LoopFilterInfoN {
   static final int MAX_LOOP_FILTER = 63;
   static final int SIMD_WIDTH = 16;
   final short[][] mblim = new short[64][16];
   final short[][] blim = new short[64][16];
   final short[][] lim = new short[64][16];
   final short[][] hev_thr = new short[4][16];
   final short[][][] lvl = new short[4][4][4];
   final HashMap<Integer, short[]> hev_thr_lut = new HashMap<>();
   final EnumMap<MBPredictionMode, Short> mode_lf_lut = new EnumMap<>(MBPredictionMode.class);

   LoopFilterInfoN(CommonData cm) {
      this.vp8_loop_filter_update_sharpness(cm.sharpness_level);
      cm.last_sharpness_level = cm.sharpness_level;
      this.lf_init_lut();

      for (short i = 0; i < 4; i++) {
         Arrays.fill(this.hev_thr[i], i);
      }
   }

   private void lf_init_lut() {
      short[] kfTHRLut = new short[64];
      short[] ifTHRLut = new short[64];

      for (int filt_lvl = 0; filt_lvl <= 63; filt_lvl++) {
         if (filt_lvl >= 40) {
            kfTHRLut[filt_lvl] = 2;
            ifTHRLut[filt_lvl] = 3;
         } else if (filt_lvl >= 20) {
            kfTHRLut[filt_lvl] = 1;
            ifTHRLut[filt_lvl] = 2;
         } else if (filt_lvl >= 15) {
            kfTHRLut[filt_lvl] = 1;
            ifTHRLut[filt_lvl] = 1;
         } else {
            kfTHRLut[filt_lvl] = 0;
            ifTHRLut[filt_lvl] = 0;
         }
      }

      this.hev_thr_lut.put(0, kfTHRLut);
      this.hev_thr_lut.put(1, ifTHRLut);
      this.mode_lf_lut.put(MBPredictionMode.DC_PRED, (short)1);
      this.mode_lf_lut.put(MBPredictionMode.V_PRED, (short)1);
      this.mode_lf_lut.put(MBPredictionMode.H_PRED, (short)1);
      this.mode_lf_lut.put(MBPredictionMode.TM_PRED, (short)1);
      this.mode_lf_lut.put(MBPredictionMode.B_PRED, (short)0);
      this.mode_lf_lut.put(MBPredictionMode.ZEROMV, (short)1);
      this.mode_lf_lut.put(MBPredictionMode.NEARESTMV, (short)2);
      this.mode_lf_lut.put(MBPredictionMode.NEARMV, (short)2);
      this.mode_lf_lut.put(MBPredictionMode.NEWMV, (short)2);
      this.mode_lf_lut.put(MBPredictionMode.SPLITMV, (short)3);
   }

   void vp8_loop_filter_update_sharpness(int sharpness_lvl) {
      for (int i = 0; i <= 63; i++) {
         int block_inside_limit = i >> (sharpness_lvl > 0 ? 1 : 0);
         block_inside_limit >>= sharpness_lvl > 4 ? 1 : 0;
         if (sharpness_lvl > 0 && block_inside_limit > 9 - sharpness_lvl) {
            block_inside_limit = 9 - sharpness_lvl;
         }

         if (block_inside_limit < 1) {
            block_inside_limit = 1;
         }

         Arrays.fill(this.lim[i], (short)block_inside_limit);
         Arrays.fill(this.blim[i], (short)(2 * i + block_inside_limit));
         Arrays.fill(this.mblim[i], (short)(2 * (i + 2) + block_inside_limit));
      }
   }
}
