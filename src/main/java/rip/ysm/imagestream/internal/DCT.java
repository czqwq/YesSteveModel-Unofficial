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
         comp.codeBytes[offset + i] = (byte)clamp(val);
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

   private static void idctRow(int[] block, int off) {
      if (block[off + 1] == 0 && block[off + 2] == 0 && block[off + 3] == 0 &&
          block[off + 4] == 0 && block[off + 5] == 0 && block[off + 6] == 0 && block[off + 7] == 0) {
         int val = block[off];
         block[off] = val; block[off+1] = val; block[off+2] = val; block[off+3] = val;
         block[off+4] = val; block[off+5] = val; block[off+6] = val; block[off+7] = val;
         return;
      }
      int x0 = (block[off] << 11) + 128;
      int x1 = block[off + 4] << 11;
      int x2 = block[off + 6];
      int x3 = block[off + 2];
      int x4 = block[off + 1];
      int x5 = block[off + 7];
      int x6 = block[off + 5];
      int x7 = block[off + 3];
      int x8 = x4 + x5;
      x4 = x4 - x5;
      x5 = x6 + x7;
      x6 = x6 - x7;
      x7 = x0 + x1;
      x0 = x0 - x1;
      x1 = (x3 * 1108 + x2 * 2676) >> 11;  // W6*x3 + W2*x2
      x2 = (x3 * 2676 - x2 * 1108) >> 11;  // W2*x3 - W6*x2
      x3 = x7 + x1;
      x7 = x7 - x1;
      x1 = x0 + x2;
      x0 = x0 - x2;
      x2 = (181 * (x4 + x6) + 128) >> 8;
      x4 = (181 * (x4 - x6) + 128) >> 8;
      block[off] = (x3 + (x8 * 2276 + x5 * 3406 >> 11) + 128) >> 8;
      block[off + 1] = (x1 + x2 + 128) >> 8;
      block[off + 2] = (x0 + x4 + 128) >> 8;
      block[off + 3] = (x7 + (x8 * -3406 + x5 * 2276 >> 11) + 128) >> 8;
      block[off + 4] = (x7 - (x8 * -3406 + x5 * 2276 >> 11) + 128) >> 8;
      block[off + 5] = (x0 - x4 + 128) >> 8;
      block[off + 6] = (x1 - x2 + 128) >> 8;
      block[off + 7] = (x3 - (x8 * 2276 + x5 * 3406 >> 11) + 128) >> 8;
   }

   private static void idctCol(int[] block, int off) {
      if (block[off + 8] == 0 && block[off + 16] == 0 && block[off + 24] == 0 &&
          block[off + 32] == 0 && block[off + 40] == 0 && block[off + 48] == 0 && block[off + 56] == 0) {
         int val = block[off];
         block[off] = val; block[off+8] = val; block[off+16] = val; block[off+24] = val;
         block[off+32] = val; block[off+40] = val; block[off+48] = val; block[off+56] = val;
         return;
      }
      int x0 = (block[off] << 8) + 8192;
      int x1 = block[off + 32] << 8;
      int x2 = block[off + 48];
      int x3 = block[off + 16];
      int x4 = block[off + 8];
      int x5 = block[off + 56];
      int x6 = block[off + 40];
      int x7 = block[off + 24];
      int x8 = x4 + x5;
      x4 = x4 - x5;
      x5 = x6 + x7;
      x6 = x6 - x7;
      x7 = x0 + x1;
      x0 = x0 - x1;
      x1 = (x3 * 1108 + x2 * 2676) >> 11;
      x2 = (x3 * 2676 - x2 * 1108) >> 11;
      x3 = x7 + x1;
      x7 = x7 - x1;
      x1 = x0 + x2;
      x0 = x0 - x2;
      x2 = (181 * (x4 + x6) + 128) >> 8;
      x4 = (181 * (x4 - x6) + 128) >> 8;
      block[off] = (x3 + (x8 * 2276 + x5 * 3406 >> 11)) >> 14;
      block[off + 8] = (x1 + x2) >> 14;
      block[off + 16] = (x0 + x4) >> 14;
      block[off + 24] = (x7 + (x8 * -3406 + x5 * 2276 >> 11)) >> 14;
      block[off + 32] = (x7 - (x8 * -3406 + x5 * 2276 >> 11)) >> 14;
      block[off + 40] = (x0 - x4) >> 14;
      block[off + 48] = (x1 - x2) >> 14;
      block[off + 56] = (x3 - (x8 * 2276 + x5 * 3406 >> 11)) >> 14;
   }

   private static int clamp(int val) { return val < 0 ? 0 : Math.min(val, 255); }
   private static int clamp12(int val) { return val < 0 ? 0 : Math.min(val, 4095); }
}
