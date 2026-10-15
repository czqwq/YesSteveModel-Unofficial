package rip.ysm.imagestream.avif.dec;

class ERecon {
   static void dc_predict(E.Array2dInt pixels, int y0, int x0, int h, int w) {
      boolean haveLeft = x0 > 0;
      boolean haveAbove = y0 > 0;
      int sum = 0;
      if (haveAbove) {
         int[] pix = pixels.data[y0 - 1];

         for (int j = 0; j < w; j++) {
            sum += pix[x0 + j];
         }
      }

      if (haveLeft) {
         for (int i = 0; i < h; i++) {
            sum += pixels.data[y0 + i][x0 - 1];
         }
      }

      int avg = 0;
      if (haveAbove && haveLeft) {
         avg = (sum + (w + h) / 2) / (w + h);
      } else if (haveAbove) {
         avg = (sum + w / 2) / w;
      } else if (haveLeft) {
         avg = (sum + h / 2) / h;
      } else {
         avg = 128;
      }

      int pred = E.clamp(avg, 0, 255);
      pixels.fill_region(y0, x0, h, w, pred);
   }

   static E.Array2dInt compute_residual(E.Array2dInt source, E.Array2dInt pred, int y0, int x0, int h, int w) {
      E.Array2dInt residual = E.Array2dInt.zeroed(h, w);

      for (int i = 0; i < residual.rows; i++) {
         int[] resY = residual.data[i];
         int[] srcY = source.data[y0 + i];
         int[] predY = pred.data[y0 + i];

         for (int j = 0; j < residual.cols; j++) {
            resY[j] = srcY[x0 + j] - predY[x0 + j];
         }
      }

      ETxfm.fwd_txfm2d(residual, h, w);
      return residual;
   }

   static void quantize(E.Array2dInt residual, int qindex) {
      int dc_q = E.qindex_to_dc_q[qindex];
      int ac_q = E.qindex_to_ac_q[qindex];

      for (int i = 0; i < residual.rows; i++) {
         int[] res = residual.data[i];

         for (int j = 0; j < residual.cols; j++) {
            int coeff = res[j];
            int q = i == 0 && j == 0 ? dc_q : ac_q;
            int abs = Math.abs(coeff);
            int sign = (int)Math.signum((float)coeff);
            sign *= (abs + (q - 1) / 2) / q;
            res[j] = sign;
         }
      }
   }

   static void dequantize(E.Array2dInt residual, int qindex) {
      int dc_q = E.qindex_to_dc_q[qindex];
      int ac_q = E.qindex_to_ac_q[qindex];

      for (int i = 0; i < residual.rows; i++) {
         int[] res = residual.data[i];

         for (int j = 0; j < residual.cols; j++) {
            int coeff = res[j];
            int q = i == 0 && j == 0 ? dc_q : ac_q;
            res[j] = coeff * q;
         }
      }
   }

   static void apply_residual(E.Array2dInt recon, E.Array2dInt residual, int y0, int x0, int h, int w) {
      ETxfm.inv_txfm2d(residual, h, w);

      for (int i = 0; i < h; i++) {
         int[] rec = recon.data[y0 + i];
         int[] res = residual.data[i];

         for (int j = 0; j < w; j++) {
            rec[x0 + j] = E.clamp(rec[x0 + j] + res[j], 0, 255);
         }
      }
   }
}
