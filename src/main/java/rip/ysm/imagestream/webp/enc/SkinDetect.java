package rip.ysm.imagestream.webp.enc;

final class SkinDetect {
   static final int[][] skin_mean = new int[][]{{7463, 9614}, {6400, 10240}, {7040, 10240}, {8320, 9280}, {6800, 9614}};
   static final int[] skin_inv_cov = new int[]{4107, 1663, 1663, 2157};
   static final int[] skin_threshold = new int[]{1570636, 1400000, 800000, 800000, 800000, 800000};
   static final int y_low = 40;
   static final int y_high = 220;

   private SkinDetect() {
   }

   static int avg_2x2(GetPointer s, int p) {
      int sum = 0;

      for (int i = 0; i < 2; i++) {
         for (int j = 0; j < 2; j++) {
            sum += s.getRel(p * i + j);
         }
      }

      return sum + 2 >> 2;
   }

   static boolean vp8_compute_skin_block(
      GetPointer y, GetPointer u, GetPointer v, int stride, int strideuv, SkinDetectionBlockSize bsize, int consec_zeromv, int curr_motion_magn
   ) {
      if (consec_zeromv > 60 && curr_motion_magn == 0) {
         return false;
      } else {
         int motion = 1;
         if (consec_zeromv > 25 && curr_motion_magn == 0) {
            motion = 0;
         }

         GetSetPointer yptr = GetSetPointer.makePositionable(y);
         GetSetPointer uptr = GetSetPointer.makePositionable(u);
         GetSetPointer vptr = GetSetPointer.makePositionable(v);
         if (bsize == SkinDetectionBlockSize.SKIN_16x16) {
            yptr.incBy(7 * stride + 7);
            uptr.incBy(3 * strideuv + 3);
            vptr.incBy(3 * strideuv + 3);
            int ysource = avg_2x2(yptr, stride);
            int usource = avg_2x2(uptr, strideuv);
            int vsource = avg_2x2(vptr, strideuv);
            return vpx_skin_pixel(ysource, usource, vsource, motion);
         } else {
            int num_skin = 0;

            for (int i = 0; i < 2; i++) {
               for (int j = 0; j < 2; j++) {
                  yptr.savePos();
                  uptr.savePos();
                  vptr.savePos();
                  yptr.incBy(3 * stride + 3);
                  uptr.incBy(strideuv + 1);
                  vptr.incBy(strideuv + 1);
                  int ysource = avg_2x2(yptr, stride);
                  int usource = avg_2x2(uptr, strideuv);
                  int vsource = avg_2x2(vptr, strideuv);
                  num_skin += vpx_skin_pixel(ysource, usource, vsource, motion) ? 1 : 0;
                  if (num_skin >= 2) {
                     return true;
                  }

                  yptr.incBy(8);
                  uptr.incBy(4);
                  vptr.incBy(4);
               }

               yptr.incBy((stride << 3) - 16);
               uptr.incBy((strideuv << 2) - 8);
               vptr.incBy((strideuv << 2) - 8);
            }

            return false;
         }
      }
   }

   static int vpx_evaluate_skin_color_difference(int cb, int cr, int idx) {
      int cb_q6 = cb << 6;
      int cr_q6 = cr << 6;
      int cb_diff_q12 = (cb_q6 - skin_mean[idx][0]) * (cb_q6 - skin_mean[idx][0]);
      int cbcr_diff_q12 = (cb_q6 - skin_mean[idx][0]) * (cr_q6 - skin_mean[idx][1]);
      int cr_diff_q12 = (cr_q6 - skin_mean[idx][1]) * (cr_q6 - skin_mean[idx][1]);
      int cb_diff_q2 = cb_diff_q12 + 512 >> 10;
      int cbcr_diff_q2 = cbcr_diff_q12 + 512 >> 10;
      int cr_diff_q2 = cr_diff_q12 + 512 >> 10;
      return skin_inv_cov[0] * cb_diff_q2 + skin_inv_cov[1] * cbcr_diff_q2 + skin_inv_cov[2] * cbcr_diff_q2 + skin_inv_cov[3] * cr_diff_q2;
   }

   static boolean vpx_skin_pixel(int y, int cb, int cr, int motion) {
      if (y >= 40 && y <= 220 && (cb != 128 || cr != 128) && (cb <= 150 || cr >= 110)) {
         for (int i = 0; i < 5; i++) {
            int skin_color_diff = vpx_evaluate_skin_color_difference(cb, cr, i);
            if (skin_color_diff < skin_threshold[i + 1]) {
               return (y >= 60 || skin_color_diff <= 3 * (skin_threshold[i + 1] >> 2)) && (motion != 0 || skin_color_diff <= skin_threshold[i + 1] >> 1);
            }

            if (skin_color_diff > skin_threshold[i + 1] << 3) {
               return false;
            }
         }
      }

      return false;
   }
}
