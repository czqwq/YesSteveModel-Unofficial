package rip.ysm.imagestream.avif.dec;

import rip.ysm.imagestream.internal.LogWriter;

class Loop {
   static final int bitdepth = 8;
   static final int kMaxPixel = 255;
   static final int kMinSignedPixel = -128;
   static final int kMaxSignedPixel = 127;
   static final int kFlatThresh = 1;
   static final int VERTICAL = 0;
   static final int HORIZONTAL = 1;
   static final int[] SGRMATRIX = new int[]{
      255,
      128,
      85,
      64,
      51,
      43,
      37,
      32,
      28,
      26,
      23,
      21,
      20,
      18,
      17,
      16,
      15,
      14,
      13,
      13,
      12,
      12,
      11,
      11,
      10,
      10,
      9,
      9,
      9,
      9,
      8,
      8,
      8,
      8,
      7,
      7,
      7,
      7,
      7,
      6,
      6,
      6,
      6,
      6,
      6,
      6,
      5,
      5,
      5,
      5,
      5,
      5,
      5,
      5,
      5,
      5,
      4,
      4,
      4,
      4,
      4,
      4,
      4,
      4,
      4,
      4,
      4,
      4,
      4,
      4,
      4,
      4,
      4,
      3,
      3,
      3,
      3,
      3,
      3,
      3,
      3,
      3,
      3,
      3,
      3,
      3,
      3,
      3,
      3,
      3,
      3,
      3,
      3,
      3,
      3,
      3,
      3,
      3,
      3,
      3,
      3,
      3,
      2,
      2,
      2,
      2,
      2,
      2,
      2,
      2,
      2,
      2,
      2,
      2,
      2,
      2,
      2,
      2,
      2,
      2,
      2,
      2,
      2,
      2,
      2,
      2,
      2,
      2,
      2,
      2,
      2,
      2,
      2,
      2,
      2,
      2,
      2,
      2,
      2,
      2,
      2,
      2,
      2,
      2,
      2,
      2,
      2,
      2,
      2,
      2,
      2,
      2,
      2,
      2,
      2,
      2,
      2,
      2,
      2,
      2,
      2,
      2,
      2,
      2,
      2,
      2,
      2,
      2,
      2,
      2,
      1,
      1,
      1,
      1,
      1,
      1,
      1,
      1,
      1,
      1,
      1,
      1,
      1,
      1,
      1,
      1,
      1,
      1,
      1,
      1,
      1,
      1,
      1,
      1,
      1,
      1,
      1,
      1,
      1,
      1,
      1,
      1,
      1,
      1,
      1,
      1,
      1,
      1,
      1,
      1,
      1,
      1,
      1,
      1,
      1,
      1,
      1,
      1,
      1,
      1,
      1,
      1,
      1,
      1,
      1,
      1,
      1,
      1,
      1,
      1,
      1,
      1,
      1,
      1,
      1,
      1,
      1,
      1,
      1,
      1,
      1,
      1,
      1,
      1,
      1,
      1,
      1,
      1,
      1,
      1,
      1,
      1,
      1,
      1,
      1,
      0
   };

   static void WienerHorizontal(
      int[] source, int srcPos, int source_stride, int width, int height, int[] filter, int number_zero_coefficients, int[] wiener_buffer, int wPos
   ) {
      int kCenterTap = 3;
      int kRoundBitsHorizontal = 3;
      int offset = 2048;
      int limit = 8191;

      for (int y = 0; y < height; y++) {
         int x = 0;

         do {
            int sum = 0;

            for (int k = number_zero_coefficients; k < 3; k++) {
               sum += filter[k] * (source[srcPos + x + k] + source[srcPos + x + 7 - 1 - k]);
            }

            sum += filter[3] * source[srcPos + x + 3];
            int rounded_sum = D.RightShiftWithRounding(sum, 3);
            wiener_buffer[wPos + x] = D.Clip3(rounded_sum, -2048, 6143);
         } while (++x != width);

         srcPos += source_stride;
         wPos += width;
      }
   }

   static void WienerVertical(
      int[] wiener_buffer, int wPos, int width, int height, int[] filter, int number_zero_coefficients, int[] dst, int dstPos, int dst_stride
   ) {
      int kCenterTap = 3;
      int kRoundBitsVertical = 11;
      int y = height;

      do {
         int x = 0;

         do {
            int sum = 0;

            for (int k = number_zero_coefficients; k < 3; k++) {
               sum += filter[k] * (wiener_buffer[wPos + k * width + x] + wiener_buffer[wPos + (6 - k) * width + x]);
            }

            sum += filter[3] * wiener_buffer[wPos + 3 * width + x];
            int rounded_sum = D.RightShiftWithRounding(sum, 11);
            dst[dstPos + x] = D.Clip3(rounded_sum, 0, 255);
         } while (++x != width);

         wPos += width;
         dstPos += dst_stride;
      } while (--y != 0);
   }

   static void WienerFilter(
      D.RestorationUnitInfo restorationInfo,
      int[] src,
      int srcPos,
      int stride,
      int[] top,
      int topPos,
      int top_stride,
      int[] bottom,
      int bottomPos,
      int bottom_stride,
      int width,
      int height,
      D.RestorationBuffer restorationBuffer,
      int[] dst,
      int dstPos
   ) {
      int kCenterTap = 3;
      int[] number_leading_zero_coefficients = restorationInfo.wiener_info.number_leading_zero_coefficients;
      int number_rows_to_skip = Math.max(number_leading_zero_coefficients[0], 1);
      int[] w_buffer_org = restorationBuffer.wiener_buffer;
      int w_buffer_orgPos = 0;
      int height_horizontal = height + 7 - 1 - 2 * number_rows_to_skip;
      int height_extra = height_horizontal - height >> 1;
      int[] filter_horizontal = restorationInfo.wiener_info.filter[1];
      int filter_horizontalPos = 0;
      srcPos -= 3;
      topPos -= 3;
      bottomPos -= 3;
      int w_bufferPos = number_rows_to_skip * width;
      if (number_leading_zero_coefficients[1] == 0) {
         WienerHorizontal(top, topPos + (2 - height_extra) * top_stride, top_stride, width, height_extra, filter_horizontal, 0, w_buffer_org, w_bufferPos);
         WienerHorizontal(src, srcPos, stride, width, height, filter_horizontal, 0, w_buffer_org, w_bufferPos);
         WienerHorizontal(bottom, bottomPos, bottom_stride, width, height_extra, filter_horizontal, 0, w_buffer_org, w_bufferPos);
      } else if (number_leading_zero_coefficients[1] == 1) {
         WienerHorizontal(top, topPos + (2 - height_extra) * top_stride, top_stride, width, height_extra, filter_horizontal, 1, w_buffer_org, w_bufferPos);
         WienerHorizontal(src, srcPos, stride, width, height, filter_horizontal, 1, w_buffer_org, w_bufferPos);
         WienerHorizontal(bottom, bottomPos, bottom_stride, width, height_extra, filter_horizontal, 1, w_buffer_org, w_bufferPos);
      } else if (number_leading_zero_coefficients[1] == 2) {
         WienerHorizontal(top, topPos + (2 - height_extra) * top_stride, top_stride, width, height_extra, filter_horizontal, 2, w_buffer_org, w_bufferPos);
         WienerHorizontal(src, srcPos, stride, width, height, filter_horizontal, 2, w_buffer_org, w_bufferPos);
         WienerHorizontal(bottom, bottomPos, bottom_stride, width, height_extra, filter_horizontal, 2, w_buffer_org, w_bufferPos);
      } else {
         WienerHorizontal(top, topPos + (2 - height_extra) * top_stride, top_stride, width, height_extra, filter_horizontal, 3, w_buffer_org, w_bufferPos);
         WienerHorizontal(src, srcPos, stride, width, height, filter_horizontal, 3, w_buffer_org, w_bufferPos);
         WienerHorizontal(bottom, bottomPos, bottom_stride, width, height_extra, filter_horizontal, 3, w_buffer_org, w_bufferPos);
      }

      int[] filter_vertical = restorationInfo.wiener_info.filter[0];
      int filter_verticalPos = 0;
      if (number_leading_zero_coefficients[0] == 0) {
         Mem.cpy(w_buffer_org, w_bufferPos, w_buffer_org, w_bufferPos - width, width);
         Mem.cpy(w_buffer_org, w_buffer_orgPos, w_buffer_org, w_buffer_orgPos + width, width);
         WienerVertical(w_buffer_org, w_bufferPos, width, height, filter_vertical, 0, dst, dstPos, stride);
      } else if (number_leading_zero_coefficients[0] == 1) {
         WienerVertical(w_buffer_org, w_buffer_orgPos, width, height, filter_vertical, 1, dst, dstPos, stride);
      } else if (number_leading_zero_coefficients[0] == 2) {
         WienerVertical(w_buffer_org, w_buffer_orgPos, width, height, filter_vertical, 2, dst, dstPos, stride);
      } else {
         assert number_leading_zero_coefficients[0] == 3;

         WienerVertical(w_buffer_org, w_buffer_orgPos, width, height, filter_vertical, 3, dst, dstPos, stride);
      }
   }

   static void BoxSum(
      int[] src, int srcPos, int src_stride, int height, int width, int[][] sums, int sumsPos, int[][] square_sums, int square_sumsPos, int size
   ) {
      int y = height;

      do {
         int sum = 0;
         int square_sum = 0;

         for (int dx = 0; dx < size; dx++) {
            int source = src[srcPos + dx];
            sum += source;
            square_sum += source * source;
         }

         sums[sumsPos][0] = sum;
         sums[sumsPos][0] = square_sum;
         int x = 1;

         do {
            int source0 = src[srcPos + x - 1];
            int source1 = src[srcPos + x - 1 + size];
            int var16 = sum - source0;
            sum = var16 + source1;
            int var17 = square_sum - source0 * source0;
            square_sum = var17 + source1 * source1;
            sums[sumsPos][x] = sum;
            square_sums[square_sumsPos][x] = square_sum;
         } while (++x != width);

         srcPos += src_stride;
         sumsPos++;
         square_sumsPos++;
      } while (--y != 0);
   }

   static void BoxSum(int[] src, int srcPos, int src_stride, int height, int width, int[][] sum3, int[][] sum5, int[][] square_sum3, int[][] square_sum5) {
      int y = height;
      int sum3Pos = 0;
      int sum5Pos = 0;
      int square_sum3Pos = 0;
      int square_sum5Pos = 0;

      do {
         int sum = 0;
         int square_sum = 0;

         for (int dx = 0; dx < 4; dx++) {
            int source = src[srcPos + dx];
            sum += source;
            square_sum += source * source;
         }

         int x = 0;

         do {
            int source0 = src[srcPos + x];
            int source1 = src[srcPos + x + 4];
            int var19 = sum - source0;
            int var20 = square_sum - source0 * source0;
            sum3[sum3Pos][x] = var19;
            square_sum3[square_sum3Pos][x] = var20;
            sum = var19 + source1;
            square_sum = var20 + source1 * source1;
            sum5[sum5Pos][x] = sum + source0;
            square_sum5[square_sum5Pos][x] = square_sum + source0 * source0;
         } while (++x != width);

         srcPos += src_stride;
         sum3Pos++;
         sum5Pos++;
         square_sum3Pos++;
         square_sum5Pos++;
      } while (--y != 0);
   }

   static void CalculateIntermediate(int s, int a, int b, int[] ma_ptr, int maPos, int[] b_ptr, int bPos, int n) {
      a = D.RightShiftWithRounding(a, 0);
      int d = D.RightShiftWithRounding(b, 0);
      int p = a * n < d * d ? 0 : a * n - d * d;
      int z = D.RightShiftWithRounding(p * s, 20);
      int ma = SGRMATRIX[Math.min(z, 255)];
      int one_over_n = (4096 + (n >> 1)) / n;
      int b2 = ma * b * one_over_n;
      ma_ptr[maPos] = ma;
      b_ptr[bPos] = D.RightShiftWithRounding(b2, 12);
   }

   static int Sum343(int[] src, int srcPos) {
      return 3 * (src[srcPos] + src[srcPos + 2]) + 4 * src[srcPos + 1];
   }

   static int Sum444(int[] src, int srcPos) {
      return 4 * (src[srcPos] + src[srcPos + 1] + src[srcPos + 2]);
   }

   static int Sum565(int[] src, int srcPos) {
      return 5 * (src[srcPos] + src[srcPos + 2]) + 6 * src[srcPos + 1];
   }

   static void BoxFilterPreProcess5(int[][] sum5, int[][] square_sum5, int width, int s, D.SgrBuffer sgr_buffer, int[] ma565, int[] b565) {
      int x = 0;

      do {
         int a = 0;
         int b = 0;

         for (int dy = 0; dy < 5; dy++) {
            a += square_sum5[dy][x];
            b += sum5[dy][x];
         }

         CalculateIntermediate(s, a, b, sgr_buffer.ma, x, sgr_buffer.b, x, 25);
      } while (++x != width + 2);

      x = 0;

      do {
         ma565[x] = Sum565(sgr_buffer.ma, x);
         b565[x] = Sum565(sgr_buffer.b, x);
      } while (++x != width);
   }

   static void BoxFilterPreProcess3(
      int[][] sum3, int[][] square_sum3, int width, int s, boolean calculate444, D.SgrBuffer sgr_buffer, int[] ma343, int[] b343, int[] ma444, int[] b444
   ) {
      int x = 0;

      do {
         int a = 0;
         int b = 0;

         for (int dy = 0; dy < 3; dy++) {
            a += square_sum3[dy][x];
            b += sum3[dy][x];
         }

         CalculateIntermediate(s, a, b, sgr_buffer.ma, x, sgr_buffer.b, x, 9);
      } while (++x != width + 2);

      x = 0;

      do {
         ma343[x] = Sum343(sgr_buffer.ma, x);
         b343[x] = Sum343(sgr_buffer.b, x);
      } while (++x != width);

      if (calculate444) {
         x = 0;

         do {
            ma444[x] = Sum444(sgr_buffer.ma, x);
            b444[x] = Sum444(sgr_buffer.b, x);
         } while (++x != width);
      }
   }

   static int CalculateFilteredOutput(int src, int ma, int b, int shift) {
      int v = b - ma * src;
      return D.RightShiftWithRounding(v, 8 + shift - 4);
   }

   static void BoxFilterPass1Kernel(int src0, int src1, int[][] ma565, int[][] b565, int x, int[] p) {
      p[0] = CalculateFilteredOutput(src0, ma565[0][x] + ma565[1][x], b565[0][x] + b565[1][x], 5);
      p[1] = CalculateFilteredOutput(src1, ma565[1][x], b565[1][x], 4);
   }

   static int BoxFilterPass2Kernel(int src, int[][] ma343, int[] ma444, int[][] b343, int[] b444, int x) {
      int ma = ma343[0][x] + ma444[x] + ma343[2][x];
      int b = b343[0][x] + b444[x] + b343[2][x];
      return CalculateFilteredOutput(src, ma, b, 5);
   }

   static int SelfGuidedFinal(int src, int v) {
      int s = src + D.RightShiftWithRounding(v, 11);
      return D.Clip3(s, 0, 255);
   }

   static int SelfGuidedDoubleMultiplier(int src, int filter0, int filter1, int w0, int w2) {
      int v = w0 * filter0 + w2 * filter1;
      return SelfGuidedFinal(src, v);
   }

   static int SelfGuidedSingleMultiplier(int src, int filter, int w0) {
      int v = w0 * filter;
      return SelfGuidedFinal(src, v);
   }

   static void BoxFilterPass1(
      int[] src,
      int srcPos,
      int stride,
      int[][] sum5,
      int[][] square_sum5,
      int width,
      int scale,
      int w0,
      D.SgrBuffer sgr_buffer,
      int[][] ma565,
      int[][] b565,
      int[] dst,
      int dstPos
   ) {
      BoxFilterPreProcess5(sum5, square_sum5, width, scale, sgr_buffer, ma565[1], b565[1]);
      int x = 0;

      do {
         int[] p = new int[2];
         BoxFilterPass1Kernel(src[srcPos + x], src[srcPos + stride + x], ma565, b565, x, p);
         dst[dstPos + x] = SelfGuidedSingleMultiplier(src[srcPos + x], p[0], w0);
         dst[dstPos + stride + x] = SelfGuidedSingleMultiplier(src[srcPos + stride + x], p[1], w0);
      } while (++x != width);
   }

   static void BoxFilterPass2(
      int[] src,
      int srcPos,
      int[] src0,
      int src0Pos,
      int width,
      int scale,
      int w0,
      int[][] sum3,
      int[][] square_sum3,
      D.SgrBuffer sgr_buffer,
      int[][] ma343,
      int[][] ma444,
      int[][] b343,
      int[][] b444,
      int[] dst,
      int dstPos
   ) {
      BoxSum(src0, src0Pos, 0, 1, width + 2, sum3, 2, square_sum3, 2, 3);
      BoxFilterPreProcess3(sum3, square_sum3, width, scale, true, sgr_buffer, ma343[2], b343[2], ma444[1], b444[1]);
      int x = 0;

      do {
         int p = BoxFilterPass2Kernel(src[srcPos + x], ma343, ma444[0], b343, b444[0], x);
         dst[dstPos + x] = SelfGuidedSingleMultiplier(src[x], p, w0);
      } while (++x != width);
   }

   static void BoxFilterPreProcess3Pos(
      int[][] sum3,
      int sum3Pos,
      int[][] square_sum3,
      int squarePos,
      int width,
      int s,
      boolean calculate444,
      D.SgrBuffer sgr_buffer,
      int[] ma343,
      int m3Pos,
      int[] b343,
      int b3Pos,
      int[] ma444,
      int m4Pos,
      int[] b444,
      int b4Pos
   ) {
      int x = 0;

      do {
         int a = 0;
         int b = 0;

         for (int dy = 0; dy < 3; dy++) {
            a += square_sum3[squarePos + dy][x];
            b += sum3[sum3Pos + dy][x];
         }

         CalculateIntermediate(s, a, b, sgr_buffer.ma, x, sgr_buffer.b, x, 9);
      } while (++x != width + 2);

      x = 0;

      do {
         ma343[m3Pos + x] = Sum343(sgr_buffer.ma, x);
         b343[b3Pos + x] = Sum343(sgr_buffer.b, x);
      } while (++x != width);

      if (calculate444) {
         x = 0;

         do {
            ma444[m4Pos + x] = Sum444(sgr_buffer.ma, x);
            b444[b4Pos + x] = Sum444(sgr_buffer.b, x);
         } while (++x != width);
      }
   }

   static void BoxFilterPreProcess5Pos(
      int[][] sum5, int sum5Pos, int[][] square_sum5, int squarePos, int width, int s, D.SgrBuffer sgr_buffer, int[] ma565, int mPos, int[] b565, int bPos
   ) {
      int x = 0;

      do {
         int a = 0;
         int b = 0;

         for (int dy = 0; dy < 5; dy++) {
            a += square_sum5[squarePos + dy][x];
            b += sum5[sum5Pos + dy][x];
         }

         CalculateIntermediate(s, a, b, sgr_buffer.ma, x, sgr_buffer.b, x, 25);
      } while (++x != width + 2);

      x = 0;

      do {
         ma565[mPos + x] = Sum565(sgr_buffer.ma, x);
         b565[bPos + x] = Sum565(sgr_buffer.b, x);
      } while (++x != width);
   }

   static boolean NeedsFilter4(int[] p, int pPos, int step, int outer_thresh, int inner_thresh) {
      int p1 = p[pPos - 2 * step];
      int p0 = p[pPos - step];
      int q0 = p[pPos + 0];
      int q1 = p[pPos + step];
      return Math.abs(p1 - p0) <= inner_thresh && Math.abs(q1 - q0) <= inner_thresh && Math.abs(p0 - q0) * 2 + Math.abs(p1 - q1) / 2 <= outer_thresh;
   }

   static boolean Hev(int[] p, int pPos, int step, int thresh) {
      int p1 = p[pPos - 2 * step];
      int p0 = p[pPos - step];
      int q0 = p[pPos + 0];
      int q1 = p[pPos + step];
      return Math.abs(p1 - p0) > thresh || Math.abs(q1 - q0) > thresh;
   }

   static void Filter2(int[] p, int pPos, int step) {
      int p1 = p[pPos - 2 * step];
      int p0 = p[pPos - step];
      int q0 = p[pPos + 0];
      int q1 = p[pPos + step];
      int min_signed_val = -128;
      int max_signed_val = 127;
      int a = 3 * (q0 - p0) + D.Clip3(p1 - q1, min_signed_val, max_signed_val);
      int a1 = D.Clip3(a + 4, min_signed_val, max_signed_val) >> 3;
      int a2 = D.Clip3(a + 3, min_signed_val, max_signed_val) >> 3;
      int max_unsigned_val = 255;
      p[pPos - step] = D.Clip3(p0 + a2, 0, max_unsigned_val);
      p[pPos + 0] = D.Clip3(q0 - a1, 0, max_unsigned_val);
   }

   static void Filter4(int[] p, int pPos, int step) {
      int p1 = p[pPos - 2 * step];
      int p0 = p[pPos - step];
      int q0 = p[pPos + 0];
      int q1 = p[pPos + step];
      int a = 3 * (q0 - p0);
      int min_signed_val = -128;
      int max_signed_val = 127;
      int a1 = D.Clip3(a + 4, min_signed_val, max_signed_val) >> 3;
      int a2 = D.Clip3(a + 3, min_signed_val, max_signed_val) >> 3;
      int a3 = a1 + 1 >> 1;
      int max_unsigned_val = 255;
      p[pPos - 2 * step] = D.Clip3(p1 + a3, 0, max_unsigned_val);
      p[pPos - 1 * step] = D.Clip3(p0 + a2, 0, max_unsigned_val);
      p[pPos + 0 * step] = D.Clip3(q0 - a1, 0, max_unsigned_val);
      p[pPos + 1 * step] = D.Clip3(q1 - a3, 0, max_unsigned_val);
   }

   static void Vertical4(int[] dst, int dstPos, int stride, int outer_thresh, int inner_thresh, int hev_thresh) {
      for (int i = 0; i < 4; i++) {
         if (NeedsFilter4(dst, dstPos, 1, outer_thresh, inner_thresh)) {
            if (Hev(dst, dstPos, 1, hev_thresh)) {
               Filter2(dst, dstPos, 1);
            } else {
               Filter4(dst, dstPos, 1);
            }
         }

         dstPos += stride;
      }
   }

   static void Horizontal4(int[] dst, int dstPos, int stride, int outer_thresh, int inner_thresh, int hev_thresh) {
      for (int i = 0; i < 4; i++) {
         if (NeedsFilter4(dst, dstPos, stride, outer_thresh, inner_thresh)) {
            if (Hev(dst, dstPos, stride, hev_thresh)) {
               Filter2(dst, dstPos, stride);
            } else {
               Filter4(dst, dstPos, stride);
            }
         }

         dstPos++;
      }
   }

   static boolean NeedsFilter6(int[] p, int pPos, int step, int outer_thresh, int inner_thresh) {
      int p2 = p[pPos - 3 * step];
      int p1 = p[pPos - 2 * step];
      int p0 = p[pPos - step];
      int q0 = p[pPos + 0];
      int q1 = p[pPos + step];
      int q2 = p[pPos + 2 * step];
      return Math.abs(p2 - p1) <= inner_thresh
         && Math.abs(p1 - p0) <= inner_thresh
         && Math.abs(q1 - q0) <= inner_thresh
         && Math.abs(q2 - q1) <= inner_thresh
         && Math.abs(p0 - q0) * 2 + Math.abs(p1 - q1) / 2 <= outer_thresh;
   }

   static boolean IsFlat3(int[] p, int pPos, int step, int flat_thresh) {
      int p2 = p[pPos - 3 * step];
      int p1 = p[pPos - 2 * step];
      int p0 = p[pPos - step];
      int q0 = p[pPos + 0];
      int q1 = p[pPos + step];
      int q2 = p[pPos + 2 * step];
      return Math.abs(p1 - p0) <= flat_thresh && Math.abs(q1 - q0) <= flat_thresh && Math.abs(p2 - p0) <= flat_thresh && Math.abs(q2 - q0) <= flat_thresh;
   }

   static int ApplyFilter6(int filter_value) {
      return D.RightShiftWithRounding(filter_value, 3);
   }

   static void Filter6(int[] p, int pPos, int step) {
      int p2 = p[pPos - 3 * step];
      int p1 = p[pPos - 2 * step];
      int p0 = p[pPos - step];
      int q0 = p[pPos + 0];
      int q1 = p[pPos + step];
      int q2 = p[pPos + 2 * step];
      int a1 = 2 * p1;
      int a0 = 2 * p0;
      int b0 = 2 * q0;
      int b1 = 2 * q1;
      p[pPos - 2 * step] = ApplyFilter6(3 * p2 + a1 + a0 + q0);
      p[pPos - 1 * step] = ApplyFilter6(p2 + a1 + a0 + b0 + q1);
      p[pPos + 0 * step] = ApplyFilter6(p1 + a0 + b0 + b1 + q2);
      p[pPos + 1 * step] = ApplyFilter6(p0 + b0 + b1 + 3 * q2);
   }

   static void Vertical6(int[] dst, int dstPos, int stride, int outer_thresh, int inner_thresh, int hev_thresh) {
      int flat_thresh = 1;

      for (int i = 0; i < 4; i++) {
         if (NeedsFilter6(dst, dstPos, 1, outer_thresh, inner_thresh)) {
            if (IsFlat3(dst, dstPos, 1, flat_thresh)) {
               Filter6(dst, dstPos, 1);
            } else if (Hev(dst, dstPos, 1, hev_thresh)) {
               Filter2(dst, dstPos, 1);
            } else {
               Filter4(dst, dstPos, 1);
            }
         }

         dstPos += stride;
      }
   }

   static void Horizontal6(int[] dst, int dstPos, int stride, int outer_thresh, int inner_thresh, int hev_thresh) {
      int flat_thresh = 1;

      for (int i = 0; i < 4; i++) {
         if (NeedsFilter6(dst, dstPos, stride, outer_thresh, inner_thresh)) {
            if (IsFlat3(dst, dstPos, stride, flat_thresh)) {
               Filter6(dst, dstPos, stride);
            } else if (Hev(dst, dstPos, stride, hev_thresh)) {
               Filter2(dst, dstPos, stride);
            } else {
               Filter4(dst, dstPos, stride);
            }
         }

         dstPos++;
      }
   }

   static boolean NeedsFilter8(int[] p, int pPos, int step, int outer_thresh, int inner_thresh) {
      int p3 = p[pPos - 4 * step];
      int p2 = p[pPos - 3 * step];
      int p1 = p[pPos - 2 * step];
      int p0 = p[pPos - step];
      int q0 = p[pPos + 0];
      int q1 = p[pPos + step];
      int q2 = p[pPos + 2 * step];
      int q3 = p[pPos + 3 * step];
      return Math.abs(p3 - p2) <= inner_thresh
         && Math.abs(p2 - p1) <= inner_thresh
         && Math.abs(p1 - p0) <= inner_thresh
         && Math.abs(q1 - q0) <= inner_thresh
         && Math.abs(q2 - q1) <= inner_thresh
         && Math.abs(q3 - q2) <= inner_thresh
         && Math.abs(p0 - q0) * 2 + Math.abs(p1 - q1) / 2 <= outer_thresh;
   }

   static boolean IsFlat4(int[] p, int pPos, int step, int flat_thresh) {
      int p3 = p[pPos - 4 * step];
      int p2 = p[pPos - 3 * step];
      int p1 = p[pPos - 2 * step];
      int p0 = p[pPos - step];
      int q0 = p[pPos + 0];
      int q1 = p[pPos + step];
      int q2 = p[pPos + 2 * step];
      int q3 = p[pPos + 3 * step];
      return Math.abs(p1 - p0) <= flat_thresh
         && Math.abs(q1 - q0) <= flat_thresh
         && Math.abs(p2 - p0) <= flat_thresh
         && Math.abs(q2 - q0) <= flat_thresh
         && Math.abs(p3 - p0) <= flat_thresh
         && Math.abs(q3 - q0) <= flat_thresh;
   }

   static int ApplyFilter8(int filter_value) {
      return D.RightShiftWithRounding(filter_value, 3);
   }

   static void Filter8(int[] p, int pPos, int step) {
      int p3 = p[pPos - 4 * step];
      int p2 = p[pPos - 3 * step];
      int p1 = p[pPos - 2 * step];
      int p0 = p[pPos - step];
      int q0 = p[pPos + 0];
      int q1 = p[pPos + step];
      int q2 = p[pPos + 2 * step];
      int q3 = p[pPos + 3 * step];
      p[pPos - 3 * step] = ApplyFilter8(3 * p3 + 2 * p2 + p1 + p0 + q0);
      p[pPos - 2 * step] = ApplyFilter8(2 * p3 + p2 + 2 * p1 + p0 + q0 + q1);
      p[pPos - 1 * step] = ApplyFilter8(p3 + p2 + p1 + 2 * p0 + q0 + q1 + q2);
      p[pPos + 0 * step] = ApplyFilter8(p2 + p1 + p0 + 2 * q0 + q1 + q2 + q3);
      p[pPos + 1 * step] = ApplyFilter8(p1 + p0 + q0 + 2 * q1 + q2 + 2 * q3);
      p[pPos + 2 * step] = ApplyFilter8(p0 + q0 + q1 + 2 * q2 + 3 * q3);
   }

   static void Vertical8(int[] dst, int dstPos, int stride, int outer_thresh, int inner_thresh, int hev_thresh) {
      int flat_thresh = 1;

      for (int i = 0; i < 4; i++) {
         if (NeedsFilter8(dst, dstPos, 1, outer_thresh, inner_thresh)) {
            if (IsFlat4(dst, dstPos, 1, flat_thresh)) {
               Filter8(dst, dstPos, 1);
            } else if (Hev(dst, dstPos, 1, hev_thresh)) {
               Filter2(dst, dstPos, 1);
            } else {
               Filter4(dst, dstPos, 1);
            }
         }

         dstPos += stride;
      }
   }

   static void Horizontal8(int[] dst, int dstPos, int stride, int outer_thresh, int inner_thresh, int hev_thresh) {
      int flat_thresh = 1;

      for (int i = 0; i < 4; i++) {
         if (NeedsFilter8(dst, dstPos, stride, outer_thresh, inner_thresh)) {
            if (IsFlat4(dst, dstPos, stride, flat_thresh)) {
               Filter8(dst, dstPos, stride);
            } else if (Hev(dst, dstPos, stride, hev_thresh)) {
               Filter2(dst, dstPos, stride);
            } else {
               Filter4(dst, dstPos, stride);
            }
         }

         dstPos++;
      }
   }

   static boolean IsFlatOuter4(int[] p, int pPos, int step, int flat_thresh) {
      int p6 = p[pPos - 7 * step];
      int p5 = p[pPos - 6 * step];
      int p4 = p[pPos - 5 * step];
      int p0 = p[pPos - step];
      int q0 = p[pPos + 0];
      int q4 = p[pPos + 4 * step];
      int q5 = p[pPos + 5 * step];
      int q6 = p[pPos + 6 * step];
      return Math.abs(p4 - p0) <= flat_thresh
         && Math.abs(q4 - q0) <= flat_thresh
         && Math.abs(p5 - p0) <= flat_thresh
         && Math.abs(q5 - q0) <= flat_thresh
         && Math.abs(p6 - p0) <= flat_thresh
         && Math.abs(q6 - q0) <= flat_thresh;
   }

   static int ApplyFilter14(int filter_value) {
      return D.RightShiftWithRounding(filter_value, 4);
   }

   static void Filter14(int[] p, int pPos, int step) {
      int p6 = p[pPos - 7 * step];
      int p5 = p[pPos - 6 * step];
      int p4 = p[pPos - 5 * step];
      int p3 = p[pPos - 4 * step];
      int p2 = p[pPos - 3 * step];
      int p1 = p[pPos - 2 * step];
      int p0 = p[pPos - step];
      int q0 = p[pPos + 0];
      int q1 = p[pPos + step];
      int q2 = p[pPos + 2 * step];
      int q3 = p[pPos + 3 * step];
      int q4 = p[pPos + 4 * step];
      int q5 = p[pPos + 5 * step];
      int q6 = p[pPos + 6 * step];
      p[pPos - 6 * step] = ApplyFilter14(p6 * 7 + p5 * 2 + p4 * 2 + p3 + p2 + p1 + p0 + q0);
      p[pPos - 5 * step] = ApplyFilter14(p6 * 5 + p5 * 2 + p4 * 2 + p3 * 2 + p2 + p1 + p0 + q0 + q1);
      p[pPos - 4 * step] = ApplyFilter14(p6 * 4 + p5 + p4 * 2 + p3 * 2 + p2 * 2 + p1 + p0 + q0 + q1 + q2);
      p[pPos - 3 * step] = ApplyFilter14(p6 * 3 + p5 + p4 + p3 * 2 + p2 * 2 + p1 * 2 + p0 + q0 + q1 + q2 + q3);
      p[pPos - 2 * step] = ApplyFilter14(p6 * 2 + p5 + p4 + p3 + p2 * 2 + p1 * 2 + p0 * 2 + q0 + q1 + q2 + q3 + q4);
      p[pPos - 1 * step] = ApplyFilter14(p6 + p5 + p4 + p3 + p2 + p1 * 2 + p0 * 2 + q0 * 2 + q1 + q2 + q3 + q4 + q5);
      p[pPos + 0 * step] = ApplyFilter14(p5 + p4 + p3 + p2 + p1 + p0 * 2 + q0 * 2 + q1 * 2 + q2 + q3 + q4 + q5 + q6);
      p[pPos + 1 * step] = ApplyFilter14(p4 + p3 + p2 + p1 + p0 + q0 * 2 + q1 * 2 + q2 * 2 + q3 + q4 + q5 + q6 * 2);
      p[pPos + 2 * step] = ApplyFilter14(p3 + p2 + p1 + p0 + q0 + q1 * 2 + q2 * 2 + q3 * 2 + q4 + q5 + q6 * 3);
      p[pPos + 3 * step] = ApplyFilter14(p2 + p1 + p0 + q0 + q1 + q2 * 2 + q3 * 2 + q4 * 2 + q5 + q6 * 4);
      p[pPos + 4 * step] = ApplyFilter14(p1 + p0 + q0 + q1 + q2 + q3 * 2 + q4 * 2 + q5 * 2 + q6 * 5);
      p[pPos + 5 * step] = ApplyFilter14(p0 + q0 + q1 + q2 + q3 + q4 * 2 + q5 * 2 + q6 * 7);
   }

   static void Vertical14(int[] dst, int dstPos, int stride, int outer_thresh, int inner_thresh, int hev_thresh) {
      int flat_thresh = 1;

      for (int i = 0; i < 4; i++) {
         if (NeedsFilter8(dst, dstPos, 1, outer_thresh, inner_thresh)) {
            if (IsFlat4(dst, dstPos, 1, 1)) {
               if (IsFlatOuter4(dst, dstPos, 1, 1)) {
                  Filter14(dst, dstPos, 1);
               } else {
                  Filter8(dst, dstPos, 1);
               }
            } else if (Hev(dst, dstPos, 1, hev_thresh)) {
               Filter2(dst, dstPos, 1);
            } else {
               Filter4(dst, dstPos, 1);
            }
         }

         dstPos += stride;
      }
   }

   static void Horizontal14(int[] dst, int dstPos, int stride, int outer_thresh, int inner_thresh, int hev_thresh) {
      int flat_thresh = 1;

      for (int i = 0; i < 4; i++) {
         if (NeedsFilter8(dst, dstPos, stride, outer_thresh, inner_thresh)) {
            if (IsFlat4(dst, dstPos, stride, flat_thresh)) {
               if (IsFlatOuter4(dst, dstPos, stride, flat_thresh)) {
                  Filter14(dst, dstPos, stride);
               } else {
                  Filter8(dst, dstPos, stride);
               }
            } else if (Hev(dst, dstPos, stride, hev_thresh)) {
               Filter2(dst, dstPos, stride);
            } else {
               Filter4(dst, dstPos, stride);
            }
         }

         dstPos++;
      }
   }

   static void doFilter(int size, int filterType, int[] src, int srcPos, int stride, int outer, int inner, int hev) {
      switch (filterType) {
         case 0:
            switch (size) {
               case 0:
                  Vertical4(src, srcPos, stride, outer, inner, hev);
                  return;
               case 1:
                  Vertical6(src, srcPos, stride, outer, inner, hev);
                  return;
               case 2:
                  Vertical8(src, srcPos, stride, outer, inner, hev);
                  return;
               case 3:
                  Vertical14(src, srcPos, stride, outer, inner, hev);
                  return;
               default:
                  return;
            }
         case 1:
            switch (size) {
               case 0:
                  Horizontal4(src, srcPos, stride, outer, inner, hev);
                  break;
               case 1:
                  Horizontal6(src, srcPos, stride, outer, inner, hev);
                  break;
               case 2:
                  Horizontal8(src, srcPos, stride, outer, inner, hev);
                  break;
               case 3:
                  Horizontal14(src, srcPos, stride, outer, inner, hev);
            }
      }
   }

   static void doFilterRestoration(
      int pos,
      D.RestorationUnitInfo restoration_info,
      int[] src,
      int srcPos,
      int stride,
      int[] top,
      int topPos,
      int top_stride,
      int[] bottom,
      int bottomPos,
      int bottom_stride,
      int width,
      int height,
      D.RestorationBuffer restoration_buffer,
      int[] dst,
      int dstPos
   ) {
      switch (pos) {
         case 0:
            LogWriter.writeLog("Wiener filter not supported yet");
            break;
         case 1:
            LogWriter.writeLog("self guided filter not supported yet");
      }
   }
}
