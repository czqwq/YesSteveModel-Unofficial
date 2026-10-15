package rip.ysm.imagestream.avif.dec;

import rip.ysm.imagestream.internal.LogWriter;

class Intra {
   private static final int KERNEL_TAPS = 5;
   private static final int[][] KERNELS = new int[][]{{0, 4, 8, 4, 0}, {0, 5, 6, 5, 0}, {2, 4, 4, 4, 2}};
   private static final int kMaxUpsampleSize = 16;
   private static final int kSmoothWeightScale = 8;
   private static final int bitdepth = 8;
   private static int[] SMOOTHWEIGHTS = new int[]{
      255,
      149,
      85,
      64,
      255,
      197,
      146,
      105,
      73,
      50,
      37,
      32,
      255,
      225,
      196,
      170,
      145,
      123,
      102,
      84,
      68,
      54,
      43,
      33,
      26,
      20,
      17,
      16,
      255,
      240,
      225,
      210,
      196,
      182,
      169,
      157,
      145,
      133,
      122,
      111,
      101,
      92,
      83,
      74,
      66,
      59,
      52,
      45,
      39,
      34,
      29,
      25,
      21,
      17,
      14,
      12,
      10,
      9,
      8,
      8,
      255,
      248,
      240,
      233,
      225,
      218,
      210,
      203,
      196,
      189,
      182,
      176,
      169,
      163,
      156,
      150,
      144,
      138,
      133,
      127,
      121,
      116,
      111,
      106,
      101,
      96,
      91,
      86,
      82,
      77,
      73,
      69,
      65,
      61,
      57,
      54,
      50,
      47,
      44,
      41,
      38,
      35,
      32,
      29,
      27,
      25,
      22,
      20,
      18,
      16,
      15,
      13,
      12,
      10,
      9,
      8,
      7,
      6,
      6,
      5,
      5,
      4,
      4,
      4
   };
   private static final int[][] WH_TABLE = new int[][]{
      {4, 4},
      {4, 8},
      {4, 16},
      {8, 4},
      {8, 8},
      {8, 16},
      {8, 32},
      {16, 4},
      {16, 8},
      {16, 16},
      {16, 32},
      {16, 64},
      {32, 8},
      {32, 16},
      {32, 32},
      {32, 64},
      {64, 16},
      {64, 32},
      {64, 64}
   };
   static boolean debug = false;

   private static int[] getWH(int txsize) {
      return WH_TABLE[txsize];
   }

   static void edgeFilter(int[] buffer, int bufferPos, int size, int strength) {
      if (debug) {
         LogWriter.writeLog("Edge filter " + bufferPos + " " + size);
      }

      int[] edge = new int[129];
      Mem.cpy(edge, 0, buffer, bufferPos, size);
      int[] dst = buffer;
      int dstPos = bufferPos;
      int kernel_index = strength - 1;

      for (int i = 1; i < size; i++) {
         int sum = 0;

         for (int j = 0; j < 5; j++) {
            int k = D.Clip3(i + j - 2, 0, size - 1);
            sum += KERNELS[kernel_index][j] * edge[k];
         }

         dst[dstPos + i] = D.RightShiftWithRounding(sum, 4);
      }
   }

   static void edgeUpsampler(int[] buffer, int bufferPos, int size) {
      if (debug) {
         LogWriter.writeLog("Edge upsampler " + bufferPos + " " + size);
      }

      int[] pixel_buffer = buffer;
      int pixelPos = bufferPos;
      int[] temp = new int[19];
      temp[0] = temp[1] = buffer[bufferPos - 1];
      Mem.cpy(temp, 2, buffer, bufferPos, size);
      temp[size + 2] = buffer[bufferPos + size - 1];
      buffer[bufferPos - 2] = temp[0];

      for (int i = 0; i < size; i++) {
         int sum = -temp[i] + 9 * temp[i + 1] + 9 * temp[i + 2] - temp[i + 3];
         pixel_buffer[pixelPos + 2 * i - 1] = D.Clip3(D.RightShiftWithRounding(sum, 4), 0, 255);
         pixel_buffer[pixelPos + 2 * i] = temp[i + 2];
      }
   }

   static void predDcTop(int[] dst, int dstPos, int stride, int[] top, int topPos, int bw, int bh) {
      if (debug) {
         LogWriter.writeLog("Dc top");
      }

      int sum = bw >> 1;

      for (int x = 0; x < bw; x++) {
         sum += top[topPos + x];
      }

      int dc = sum >> D.FloorLog2(bw);

      for (int y = 0; y < bh; y++) {
         Mem.set(dst, dstPos, dc, bw);
         dstPos += stride;
      }
   }

   static void predDcLeft(int[] dst, int dstPos, int stride, int[] left, int leftPos, int bw, int bh) {
      if (debug) {
         LogWriter.writeLog("Dc left");
      }

      int sum = bh >> 1;

      for (int y = 0; y < bh; y++) {
         sum += left[leftPos + y];
      }

      int dc = sum >> D.FloorLog2(bh);

      for (int y = 0; y < bh; y++) {
         Mem.set(dst, dstPos, dc, bw);
         dstPos += stride;
      }
   }

   static void predDc(int[] dst, int dstPos, int stride, int[] top, int topPos, int[] left, int leftPos, int bw, int bh) {
      if (debug) {
         LogWriter.writeLog("Dc");
      }

      int divisor = bw + bh;
      int sum = divisor >> 1;

      for (int x = 0; x < bw; x++) {
         sum += top[topPos + x];
      }

      for (int y = 0; y < bh; y++) {
         sum += left[leftPos + y];
      }

      int dc = sum / divisor;

      for (int y = 0; y < bh; y++) {
         Mem.set(dst, dstPos, dc, bw);
         dstPos += stride;
      }
   }

   static void predVertical(int[] dst, int dstPos, int stride, int[] top, int topPos, int bw, int bh) {
      if (debug) {
         LogWriter.writeLog("Pred Vertical");
      }

      for (int y = 0; y < bh; y++) {
         Mem.cpy(dst, dstPos, top, topPos, bw);
         dstPos += stride;
      }
   }

   static void predHorizontal(int[] dst, int dstPos, int stride, int[] left, int leftPos, int bw, int bh) {
      if (debug) {
         LogWriter.writeLog("Pred Horizontal");
      }

      for (int y = 0; y < bh; y++) {
         Mem.set(dst, dstPos, left[leftPos + y], bw);
         dstPos += stride;
      }
   }

   static void predPaeth(int[] dst, int dstPos, int stride, int[] top, int topPos, int[] left, int leftPos, int bw, int bh) {
      if (debug) {
         LogWriter.writeLog("Pred Paeth");
      }

      int topLeft = top[topPos - 1];
      int topLeftX2 = topLeft + topLeft;

      for (int y = 0; y < bh; y++) {
         int leftPixel = left[leftPos + y];

         for (int x = 0; x < bw; x++) {
            int leftDist = Math.abs(top[topPos + x] - topLeft);
            int topDist = Math.abs(leftPixel - topLeft);
            int topLeftDist = Math.abs(top[topPos + x] + leftPixel - topLeftX2);
            if (leftDist <= topDist && leftDist <= topLeftDist) {
               dst[dstPos + x] = leftPixel;
            } else if (topDist <= topLeftDist) {
               dst[dstPos + x] = top[topPos + x];
            } else {
               dst[dstPos + x] = topLeft;
            }
         }

         dstPos += stride;
      }
   }

   static void predDcFill(int[] dst, int dstPos, int stride, int bw, int bh, int fill) {
      if (debug) {
         LogWriter.writeLog("DC Fill");
      }

      for (int y = 0; y < bh; y++) {
         Mem.set(dst, dstPos, fill, bw);
         dstPos += stride;
      }
   }

   static void doPrediction(int txsize, int predType, int[] dst, int dstPos, int stride, int[] top, int topPos, int[] left, int leftPos) {
      int[] wh = getWH(txsize);
      int bw = wh[0];
      int bh = wh[1];
      switch (predType) {
         case 0:
            predDcFill(dst, dstPos, stride, bw, bh, 128);
            break;
         case 1:
            predDcTop(dst, dstPos, stride, top, topPos, bw, bh);
            break;
         case 2:
            predDcLeft(dst, dstPos, stride, left, leftPos, bw, bh);
            break;
         case 3:
            predDc(dst, dstPos, stride, top, topPos, left, leftPos, bw, bh);
            break;
         case 4:
            predVertical(dst, dstPos, stride, top, topPos, bw, bh);
            break;
         case 5:
            predHorizontal(dst, dstPos, stride, left, leftPos, bw, bh);
            break;
         case 6:
            predPaeth(dst, dstPos, stride, top, topPos, left, leftPos, bw, bh);
            break;
         case 7:
            smooth(dst, dstPos, stride, top, topPos, left, leftPos, bw, bh);
            break;
         case 8:
            smoothVertical(dst, dstPos, stride, top, topPos, left, leftPos, bw, bh);
            break;
         case 9:
            smoothHorizontal(dst, dstPos, stride, top, topPos, left, leftPos, bw, bh);
            break;
         default:
            LogWriter.writeLog("Intra: unsupported prediction type " + predType);
      }
   }

   static void doCflIntraPredictor(int txsize, int[] dst, int dstPos, int stride, int[][] luma, int alpha) {
      if (debug) {
         LogWriter.writeLog("Cfl Intra");
      }

      int[] wh = getWH(txsize);
      int bw = wh[0];
      int bh = wh[1];
      int bitdepth = 8;
      int dc = dst[dstPos];
      int maxValue = (1 << bitdepth) - 1;

      for (int y = 0; y < bh; y++) {
         for (int x = 0; x < bw; x++) {
            dst[dstPos + x] = D.Clip3(dc + D.RightShiftWithRoundingSigned(alpha * luma[y][x], 6), 0, maxValue);
         }

         dstPos += stride;
      }
   }

   static void cflSubsampler(
      int[][] luma, int maxLumaWidth, int maxLumaHeight, int[] src, int srcPos, int stride, int bw, int bh, int subsamplingX, int subsamplingY
   ) {
      if (debug) {
         LogWriter.writeLog("Cfl Sampler");
      }

      int sum = 0;

      for (int y = 0; y < bh; y++) {
         for (int x = 0; x < bw; x++) {
            int lumaX = Math.min(x << subsamplingX, maxLumaWidth - (1 << subsamplingX));
            int lumaXNext = lumaX + stride;
            luma[y][x] = src[srcPos + lumaX]
                  + (subsamplingX != 0 ? src[srcPos + lumaX + 1] : 0)
                  + (subsamplingY != 0 ? src[srcPos + lumaXNext] + src[srcPos + lumaXNext + 1] : 0)
               << 3 - subsamplingX - subsamplingY;
            sum += luma[y][x];
         }

         if (y << subsamplingY < maxLumaHeight - (1 << subsamplingY)) {
            srcPos += stride << subsamplingY;
         }
      }

      int average = D.RightShiftWithRounding(sum, D.FloorLog2(bw) + D.FloorLog2(bh));

      for (int y = 0; y < bh; y++) {
         for (int x = 0; x < bw; x++) {
            luma[y][x] = luma[y][x] - average;
         }
      }
   }

   static void doCflSubsampler(int txsize, int sampleType, int[][] luma, int maxLumaWidth, int maxLumaHeight, int[] src, int srcPos, int stride) {
      int[] wh = getWH(txsize);
      int bw = wh[0];
      int bh = wh[1];
      switch (sampleType) {
         case 0:
            cflSubsampler(luma, maxLumaWidth, maxLumaHeight, src, srcPos, stride, bw, bh, 0, 0);
            break;
         case 1:
            cflSubsampler(luma, maxLumaWidth, maxLumaHeight, src, srcPos, stride, bw, bh, 1, 0);
            break;
         case 2:
            cflSubsampler(luma, maxLumaWidth, maxLumaHeight, src, srcPos, stride, bw, bh, 1, 1);
      }
   }

   static void predDirectionalZone1(int[] dst, int dstPos, int stride, int[] top, int topPos, int width, int height, int xstep, boolean upsampledTop) {
      if (debug) {
         LogWriter.writeLog("PredDirectionalZone1");
      }

      if (xstep == 64) {
         int[] top_ptr = top;
         int top_ptrPos = topPos + 1;

         for (int y = 0; y < height; top_ptrPos++) {
            Mem.cpy(dst, dstPos, top_ptr, top_ptrPos, width);
            y++;
            dstPos += stride;
         }
      } else {
         int upsampleShift = upsampledTop ? 1 : 0;
         int maxBaseX = width + height - 1 << upsampleShift;
         int scaleBits = 6 - upsampleShift;
         int baseStep = 1 << upsampleShift;
         int topX = xstep;
         int y = 0;

         do {
            int topBaseX = topX >> scaleBits;
            if (topBaseX >= maxBaseX) {
               for (int i = y; i < height; i++) {
                  Mem.set(dst, dstPos, top[topPos + maxBaseX], width);
                  dstPos += stride;
               }

               return;
            }

            int shift = (topX << upsampleShift & 63) >> 1;
            int x = 0;

            do {
               if (topBaseX >= maxBaseX) {
                  Mem.set(dst, dstPos + x, top[topPos + maxBaseX], width - x);
                  break;
               }

               int val = top[topPos + topBaseX] * (32 - shift) + top[topPos + topBaseX + 1] * shift;
               dst[dstPos + x] = D.RightShiftWithRounding(val, 5);
               topBaseX += baseStep;
            } while (++x < width);

            dstPos += stride;
            topX += xstep;
         } while (++y < height);
      }
   }

   static void predDirectionalZone2(
      int[] dst,
      int dstPos,
      int stride,
      int[] top,
      int topPos,
      int[] left,
      int leftPos,
      int width,
      int height,
      int xstep,
      int ystep,
      boolean upsampledTop,
      boolean upsampledLeft
   ) {
      if (debug) {
         LogWriter.writeLog("PredDirectionalZone2");
      }

      int upsampleTopShift = upsampledTop ? 1 : 0;
      int upsampleLeftShift = upsampledLeft ? 1 : 0;
      int scaleBitsX = 6 - upsampleTopShift;
      int scaleBitsY = 6 - upsampleLeftShift;
      int minBaseX = -(1 << upsampleTopShift);
      int baseStepX = 1 << upsampleTopShift;
      int top_x = -xstep;
      int y = 0;

      do {
         int topBaseX = top_x >> scaleBitsX;
         int leftY = (y << 6) - ystep;
         int x = 0;

         do {
            int val;
            if (topBaseX >= minBaseX) {
               int shift = (top_x * (1 << upsampleTopShift) & 63) >> 1;
               val = top[topPos + topBaseX] * (32 - shift) + top[topPos + topBaseX + 1] * shift;
            } else {
               int leftBaseY = leftY >> scaleBitsY;
               int shift = (leftY * (1 << upsampleLeftShift) & 63) >> 1;
               val = left[leftPos + leftBaseY] * (32 - shift) + left[leftPos + leftBaseY + 1] * shift;
            }

            dst[dstPos + x] = D.RightShiftWithRounding(val, 5);
            topBaseX += baseStepX;
            leftY -= ystep;
         } while (++x < width);

         top_x -= xstep;
         dstPos += stride;
      } while (++y < height);
   }

   static void predDirectionalZone3(int[] dst, int dstPosGiven, int stride, int[] left, int leftPos, int width, int height, int ystep, boolean upsampledLeft) {
      if (debug) {
         LogWriter.writeLog("PredDirectionalZone3");
      }

      int upsampleShift = upsampledLeft ? 1 : 0;
      int scaleBits = 6 - upsampleShift;
      int baseStep = 1 << upsampleShift;
      int leftY = ystep;
      int x = 0;

      do {
         int dstPos = dstPosGiven;
         int leftBaseY = leftY >> scaleBits;
         int y = 0;

         do {
            int shift = (leftY << upsampleShift & 63) >> 1;
            int val = left[leftPos + leftBaseY] * (32 - shift) + left[leftPos + leftBaseY + 1] * shift;
            dst[dstPos + x] = D.RightShiftWithRounding(val, 5);
            dstPos += stride;
            leftBaseY += baseStep;
         } while (++y < height);

         leftY += ystep;
      } while (++x < width);
   }

   static void filterIntraPredictor(int[] dst, int dstPos, int stride, int[] top, int topPos, int[] left, int leftPos, int pred, int width, int height) {
      if (debug) {
         LogWriter.writeLog("FilterIntraPredictor ");
      }

      int kMaxPixel = 255;
      int[][] buffer = new int[3][33];
      Mem.cpy(buffer[0], 0, top, topPos - 1, width + 1);
      int row0 = 0;
      int row2 = 2;
      int ystep = 1;
      int y = 0;

      do {
         buffer[1][0] = left[leftPos + y];
         buffer[row2][0] = left[leftPos + y + 1];
         int x = 1;

         do {
            int p0 = buffer[row0][x - 1];
            int p1 = buffer[row0][x + 0];
            int p2 = buffer[row0][x + 1];
            int p3 = buffer[row0][x + 2];
            int p4 = buffer[row0][x + 3];
            int p5 = buffer[1][x - 1];
            int p6 = buffer[row2][x - 1];

            for (int i = 0; i < 8; i++) {
               int xoffset = i & 3;
               int yoffset = (i >> 2) * ystep;
               int value = D.kFilterIntraTaps[pred][i][0] * p0
                  + D.kFilterIntraTaps[pred][i][1] * p1
                  + D.kFilterIntraTaps[pred][i][2] * p2
                  + D.kFilterIntraTaps[pred][i][3] * p3
                  + D.kFilterIntraTaps[pred][i][4] * p4
                  + D.kFilterIntraTaps[pred][i][5] * p5
                  + D.kFilterIntraTaps[pred][i][6] * p6;
               buffer[1 + yoffset][x + xoffset] = D.Clip3(D.RightShiftWithRounding(value, 4), 0, kMaxPixel);
            }

            x += 4;
         } while (x < width);

         Mem.cpy(dst, dstPos, buffer[1], 1, width);
         dstPos += stride;
         Mem.cpy(dst, dstPos, buffer[row2], 1, width);
         dstPos += stride;
         row0 ^= 2;
         row2 ^= 2;
         ystep = -ystep;
         y += 2;
      } while (y < height);
   }

   static void smooth(int[] dst, int dstPos, int stride, int[] top, int topPos, int[] left, int leftPos, int bw, int bh) {
      if (debug) {
         LogWriter.writeLog("Smooth");
      }

      int topRight = top[topPos + bw - 1];
      int bottomLeft = left[leftPos + bh - 1];
      int[] weightsX = SMOOTHWEIGHTS;
      int wxPos = bw - 4;
      int[] weightsY = SMOOTHWEIGHTS;
      int wyPos = bh - 4;
      int scaleValue = 256;

      for (int y = 0; y < bh; y++) {
         for (int x = 0; x < bw; x++) {
            long pred = weightsY[wyPos + y] * top[topPos + x];
            pred += weightsX[wxPos + x] * left[leftPos + y];
            pred += (256 - weightsY[wyPos + y] & 0xFF) * bottomLeft;
            pred += (256 - weightsX[wxPos + x] & 0xFF) * topRight;
            dst[dstPos + x] = D.RightShiftWithRounding(pred, 9);
         }

         dstPos += stride;
      }
   }

   static void smoothVertical(int[] dst, int dstPos, int stride, int[] top, int topPos, int[] left, int leftPos, int bw, int bh) {
      if (debug) {
         LogWriter.writeLog("SmoothVertical");
      }

      int bottomLeft = left[leftPos + bh - 1];
      int[] weightsY = SMOOTHWEIGHTS;
      int wyPos = bh - 4;
      int scale_value = 256;

      for (int y = 0; y < bh; y++) {
         for (int x = 0; x < bw; x++) {
            long pred = weightsY[wyPos + y] * top[topPos + x];
            pred += (256 - weightsY[wyPos + y] & 0xFF) * bottomLeft;
            dst[dstPos + x] = D.RightShiftWithRounding(pred, 8);
         }

         dstPos += stride;
      }
   }

   static void smoothHorizontal(int[] dst, int dstPos, int stride, int[] top, int topPos, int[] left, int leftPos, int bw, int bh) {
      if (debug) {
         LogWriter.writeLog("SmoothHorizontal");
      }

      int topRight = top[topPos + bw - 1];
      int[] weightsX = SMOOTHWEIGHTS;
      int wxPos = bw - 4;
      int scale_value = 256;

      for (int y = 0; y < bh; y++) {
         for (int x = 0; x < bw; x++) {
            long pred = weightsX[wxPos + x] * left[leftPos + y];
            pred += (256 - weightsX[wxPos + x] & 0xFF) * topRight;
            dst[dstPos + x] = D.RightShiftWithRounding(pred, 8);
         }

         dstPos += stride;
      }
   }
}
