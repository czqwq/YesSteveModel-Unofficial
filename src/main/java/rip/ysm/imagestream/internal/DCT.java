package rip.ysm.imagestream.internal;

import rip.ysm.imagestream.jpeg.data.Component;

public class DCT {

   @FunctionalInterface
   public interface TRANSFORM {
      void call(Object input, Object output, int stride);
   }

   public static final TRANSFORM vp8_short_fdct4x4 = (input, output, stride) -> {};

   public static final TRANSFORM vp8_short_fdct8x4 = (input, output, stride) -> {};

   public static final TRANSFORM vp8_short_walsh4x4 = (input, output, stride) -> {};

   public static void IDCTQ(int[] block, int[] quant) {
      int[] temp = new int[64];
      for (int i = 0; i < 64; i++) {
         temp[i] = block[i] * quant[i];
      }
      for (int i = 0; i < 8; i++) { idctRow(temp, i * 8); }
      for (int i = 0; i < 8; i++) { idctCol(temp, i); }
      for (int i = 0; i < 64; i++) {
         block[i] = clamp((temp[i] + 128 * 8 + 4) >> 3);
      }
   }

   public static void IDCTQ(Component comp, int offset, int[] p) {
      short[] codeBlock = comp.codeBlock;
      int[] qTable = comp.qTable;
      for (int i = 0; i < 64; i++) {
         p[i] = codeBlock[offset + i] * qTable[i];
      }
      for (int i = 0; i < 8; i++) { idctRow(p, i * 8); }
      for (int i = 0; i < 8; i++) { idctCol(p, i); }
      for (int i = 0; i < 64; i++) {
         int val = (p[i] + 128 * 8 + 4) >> 3;
         // Progressive scans never allocate codeBytes; decodeSamplesWithOutput() reads the samples from
         // codeBlock for this path (baseline goes through JpegScannerInts + decodeSamplesWithOutputBytes).
         comp.codeBlock[offset + i] = (short)clamp(val);
      }
   }

   public static void IDCTQ12(Component comp, int offset) {
      short[] codeBlock = comp.codeBlock;
      int[] qTable = comp.qTable;
      int[] temp = new int[64];
      for (int i = 0; i < 64; i++) {
         temp[i] = codeBlock[offset + i] * qTable[i];
      }
      for (int i = 0; i < 8; i++) { idctRow(temp, i * 8); }
      for (int i = 0; i < 8; i++) { idctCol(temp, i); }
      for (int i = 0; i < 64; i++) {
         codeBlock[offset + i] = (short)clamp12((temp[i] + 2048 * 8 + 4) >> 3);
      }
   }

   public static void IDCTQInts(Component comp, int[] p) {
      int[] qTable = comp.qTable;
      int[] codeInts = comp.codeInts;
      for (int i = 0; i < 64; i++) {
         p[i] = codeInts[i] * qTable[i];
      }
      for (int i = 0; i < 8; i++) { idctRow(p, i * 8); }
      for (int i = 0; i < 8; i++) { idctCol(p, i); }
      for (int i = 0; i < 64; i++) {
         codeInts[i] = clamp((p[i] + 128 * 8 + 4) >> 3);
      }
   }

   // IDCT_MATRIX[u][x] = sqrt(2) * C(u) * cos((2x+1)u*pi/16).
   // One 1-D pass is scaled by 2*sqrt(2); the two passes together give 8x the true sample value, which the
   // callers' (v + 128 * 8 + 4) >> 3 (8-bit) / (v + 2048 * 8 + 4) >> 3 (12-bit) turn into round(v) + levelShift.
   private static final double[][] IDCT_MATRIX = new double[8][8];

   static {
      for (int u = 0; u < 8; u++) {
         double c = u == 0 ? 1.0 / Math.sqrt(2.0) : 1.0;
         for (int x = 0; x < 8; x++) {
            IDCT_MATRIX[u][x] = Math.sqrt(2.0) * c * Math.cos((2 * x + 1) * u * Math.PI / 16.0);
         }
      }
   }

   private static void idctRow(int[] block, int off) {
      if (block[off + 1] == 0 && block[off + 2] == 0 && block[off + 3] == 0 &&
          block[off + 4] == 0 && block[off + 5] == 0 && block[off + 6] == 0 && block[off + 7] == 0) {
         int val = block[off];
         block[off] = val; block[off+1] = val; block[off+2] = val; block[off+3] = val;
         block[off+4] = val; block[off+5] = val; block[off+6] = val; block[off+7] = val;
         return;
      }

      int[] in = new int[8];
      for (int u = 0; u < 8; u++) {
         in[u] = block[off + u];
      }
      for (int x = 0; x < 8; x++) {
         double sum = 0.0;
         for (int u = 0; u < 8; u++) {
            sum += IDCT_MATRIX[u][x] * in[u];
         }
         block[off + x] = (int)Math.round(sum);
      }
   }

   private static void idctCol(int[] block, int off) {
      if (block[off + 8] == 0 && block[off + 16] == 0 && block[off + 24] == 0 &&
          block[off + 32] == 0 && block[off + 40] == 0 && block[off + 48] == 0 && block[off + 56] == 0) {
         int val = block[off];
         block[off] = val; block[off+8] = val; block[off+16] = val; block[off+24] = val;
         block[off+32] = val; block[off+40] = val; block[off+48] = val; block[off+56] = val;
         return;
      }

      int[] in = new int[8];
      for (int v = 0; v < 8; v++) {
         in[v] = block[off + v * 8];
      }
      for (int y = 0; y < 8; y++) {
         double sum = 0.0;
         for (int v = 0; v < 8; v++) {
            sum += IDCT_MATRIX[v][y] * in[v];
         }
         block[off + y * 8] = (int)Math.round(sum);
      }
   }

   private static int clamp(int val) { return val < 0 ? 0 : Math.min(val, 255); }
   private static int clamp12(int val) { return val < 0 ? 0 : Math.min(val, 4095); }
}
