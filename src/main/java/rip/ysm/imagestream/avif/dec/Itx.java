package rip.ysm.imagestream.avif.dec;

import rip.ysm.imagestream.internal.LogWriter;

class Itx {
   static boolean debug = false;
   static boolean dofast = false;
   private static final int[] kCos128 = new int[]{
      4096,
      4095,
      4091,
      4085,
      4076,
      4065,
      4052,
      4036,
      4017,
      3996,
      3973,
      3948,
      3920,
      3889,
      3857,
      3822,
      3784,
      3745,
      3703,
      3659,
      3612,
      3564,
      3513,
      3461,
      3406,
      3349,
      3290,
      3229,
      3166,
      3102,
      3035,
      2967,
      2896,
      2824,
      2751,
      2675,
      2598,
      2520,
      2440,
      2359,
      2276,
      2191,
      2106,
      2019,
      1931,
      1842,
      1751,
      1660,
      1567,
      1474,
      1380,
      1285,
      1189,
      1092,
      995,
      897,
      799,
      700,
      601,
      501,
      401,
      301,
      201,
      101,
      0
   };
   private static final int[][] kBitReverseLookup = new int[][]{
      {
            0,
            2,
            1,
            3,
            0,
            2,
            1,
            3,
            0,
            2,
            1,
            3,
            0,
            2,
            1,
            3,
            0,
            2,
            1,
            3,
            0,
            2,
            1,
            3,
            0,
            2,
            1,
            3,
            0,
            2,
            1,
            3,
            0,
            2,
            1,
            3,
            0,
            2,
            1,
            3,
            0,
            2,
            1,
            3,
            0,
            2,
            1,
            3,
            0,
            2,
            1,
            3,
            0,
            2,
            1,
            3,
            0,
            2,
            1,
            3,
            0,
            2,
            1,
            3
      },
      {
            0,
            4,
            2,
            6,
            1,
            5,
            3,
            7,
            0,
            4,
            2,
            6,
            1,
            5,
            3,
            7,
            0,
            4,
            2,
            6,
            1,
            5,
            3,
            7,
            0,
            4,
            2,
            6,
            1,
            5,
            3,
            7,
            0,
            4,
            2,
            6,
            1,
            5,
            3,
            7,
            0,
            4,
            2,
            6,
            1,
            5,
            3,
            7,
            0,
            4,
            2,
            6,
            1,
            5,
            3,
            7,
            0,
            4,
            2,
            6,
            1,
            5,
            3,
            7
      },
      {
            0,
            8,
            4,
            12,
            2,
            10,
            6,
            14,
            1,
            9,
            5,
            13,
            3,
            11,
            7,
            15,
            0,
            8,
            4,
            12,
            2,
            10,
            6,
            14,
            1,
            9,
            5,
            13,
            3,
            11,
            7,
            15,
            0,
            8,
            4,
            12,
            2,
            10,
            6,
            14,
            1,
            9,
            5,
            13,
            3,
            11,
            7,
            15,
            0,
            8,
            4,
            12,
            2,
            10,
            6,
            14,
            1,
            9,
            5,
            13,
            3,
            11,
            7,
            15
      },
      {
            0,
            16,
            8,
            24,
            4,
            20,
            12,
            28,
            2,
            18,
            10,
            26,
            6,
            22,
            14,
            30,
            1,
            17,
            9,
            25,
            5,
            21,
            13,
            29,
            3,
            19,
            11,
            27,
            7,
            23,
            15,
            31,
            0,
            16,
            8,
            24,
            4,
            20,
            12,
            28,
            2,
            18,
            10,
            26,
            6,
            22,
            14,
            30,
            1,
            17,
            9,
            25,
            5,
            21,
            13,
            29,
            3,
            19,
            11,
            27,
            7,
            23,
            15,
            31
      },
      {
            0,
            32,
            16,
            48,
            8,
            40,
            24,
            56,
            4,
            36,
            20,
            52,
            12,
            44,
            28,
            60,
            2,
            34,
            18,
            50,
            10,
            42,
            26,
            58,
            6,
            38,
            22,
            54,
            14,
            46,
            30,
            62,
            1,
            33,
            17,
            49,
            9,
            41,
            25,
            57,
            5,
            37,
            21,
            53,
            13,
            45,
            29,
            61,
            3,
            35,
            19,
            51,
            11,
            43,
            27,
            59,
            7,
            39,
            23,
            55,
            15,
            47,
            31,
            63
      }
   };
   private static final int[] kAdstOutputPermutationLookup = new int[]{0, 8, 12, 4, 6, 14, 10, 2, 3, 11, 15, 7, 5, 13, 9, 1};
   private static final short kTransformColumnShift = 4;
   private static int[] kAdst4Multiplier = new int[]{1321, 2482, 3344, 3803};
   private static int[] kTransformRowShift = new int[]{0, 0, 1, 0, 1, 1, 2, 1, 1, 2, 1, 2, 2, 1, 2, 1, 2, 1, 2};
   private static boolean[] kShouldRound = new boolean[]{
      false, true, false, true, false, true, false, false, true, false, true, false, false, true, false, true, false, true, false
   };
   private static int kIdentity4Multiplier = 5793;
   private static int kIdentity4MultiplierFraction = 1697;
   private static int kIdentity16Multiplier = 11586;
   private static int kTransformRowMultiplier = 2896;

   private static int Cos128(int angle) {
      angle &= 255;
      if (angle <= 64) {
         return kCos128[angle];
      } else if (angle <= 128) {
         return -kCos128[128 - angle];
      } else {
         return angle <= 192 ? -kCos128[angle - 128] : kCos128[256 - angle];
      }
   }

   private static int Sin128(int angle) {
      return Cos128(angle - 64);
   }

   private static void ButterflyRotation(int[] dst, int dstPos, int a, int b, int angle, boolean flip, int range) {
      long x = dst[dstPos + a] * 1L * Cos128(angle) - dst[dstPos + b] * 1L * Sin128(angle);
      long y = dst[dstPos + a] * 1L * Sin128(angle) + dst[dstPos + b] * 1L * Cos128(angle);
      dst[dstPos + a] = D.RightShiftWithRounding(flip ? y : x, 12);
      dst[dstPos + b] = D.RightShiftWithRounding(flip ? x : y, 12);
   }

   private static void ButterflyRotationFirstIsZero(int[] dst, int dstPos, int a, int b, int angle, boolean flip, int range) {
      long x = dst[dstPos + b] * 1L * -Sin128(angle);
      long y = dst[dstPos + b] * 1L * Cos128(angle);
      dst[dstPos + a] = D.RightShiftWithRounding(flip ? y : x, 12);
      dst[dstPos + b] = D.RightShiftWithRounding(flip ? x : y, 12);
   }

   private static void ButterflyRotationSecondIsZero(int[] dst, int dstPos, int a, int b, int angle, boolean flip, int range) {
      long x = dst[dstPos + a] * 1L * Cos128(angle);
      long y = dst[dstPos + a] * 1L * Sin128(angle);
      dst[dstPos + a] = D.RightShiftWithRounding(flip ? y : x, 12);
      dst[dstPos + b] = D.RightShiftWithRounding(flip ? x : y, 12);
   }

   private static void HadamardRotation(int[] dst, int dstPos, int a, int b, boolean flip, int range) {
      if (flip) {
         int temp = a;
         a = b;
         b = temp;
      }

      int min = -(1 << --range);
      int max = (1 << range) - 1;
      int x = dst[dstPos + a] + dst[dstPos + b];
      int y = dst[dstPos + a] - dst[dstPos + b];
      dst[dstPos + a] = D.Clip3(x, min, max);
      dst[dstPos + b] = D.Clip3(y, min, max);
   }

   static void Dct(int[] dst, int dstPos, int size_log2, int range) {
      if (debug) {
         LogWriter.writeLog("DCT ");
      }

      int size = 1 << size_log2;
      int[] temp = new int[size];
      Mem.cpy(temp, 0, dst, dstPos, temp.length);

      for (int i = 0; i < size; i++) {
         dst[dstPos + i] = temp[kBitReverseLookup[size_log2 - 2][i]];
      }

      if (size_log2 == 6) {
         for (int i = 0; i < 16; i++) {
            ButterflyRotation(dst, dstPos, i + 32, 63 - i, 63 - kBitReverseLookup[2][i] * 4, false, range);
         }
      }

      if (size_log2 >= 5) {
         for (int i = 0; i < 8; i++) {
            ButterflyRotation(dst, dstPos, i + 16, 31 - i, 6 + kBitReverseLookup[1][7 - i] * 8, false, range);
         }
      }

      if (size_log2 == 6) {
         for (int i = 0; i < 16; i++) {
            HadamardRotation(dst, dstPos, i * 2 + 32, i * 2 + 33, (i & 1) != 0, range);
         }
      }

      if (size_log2 >= 4) {
         for (int i = 0; i < 4; i++) {
            ButterflyRotation(dst, dstPos, i + 8, 15 - i, 12 + kBitReverseLookup[0][3 - i] * 16, false, range);
         }
      }

      if (size_log2 >= 5) {
         for (int i = 0; i < 8; i++) {
            HadamardRotation(dst, dstPos, i * 2 + 16, i * 2 + 17, (i & 1) != 0, range);
         }
      }

      if (size_log2 == 6) {
         for (int i = 0; i < 4; i++) {
            for (int j = 0; j < 2; j++) {
               ButterflyRotation(dst, dstPos, 62 - i * 4 - j, i * 4 + j + 33, 60 - kBitReverseLookup[0][i] * 16 + j * 64, true, range);
            }
         }
      }

      if (size_log2 >= 3) {
         for (int i = 0; i < 2; i++) {
            ButterflyRotation(dst, dstPos, i + 4, 7 - i, 56 - 32 * i, false, range);
         }
      }

      if (size_log2 >= 4) {
         for (int i = 0; i < 4; i++) {
            HadamardRotation(dst, dstPos, i * 2 + 8, i * 2 + 9, (i & 1) != 0, range);
         }
      }

      if (size_log2 >= 5) {
         for (int i = 0; i < 2; i++) {
            for (int j = 0; j < 2; j++) {
               ButterflyRotation(dst, dstPos, 30 - i * 4 - j, i * 4 + j + 17, 24 + j * 64 + (1 - i) * 32, true, range);
            }
         }
      }

      if (size_log2 == 6) {
         for (int i = 0; i < 8; i++) {
            for (int j = 0; j < 2; j++) {
               HadamardRotation(dst, dstPos, i * 4 + j + 32, i * 4 - j + 35, (i & 1) != 0, range);
            }
         }
      }

      for (int i = 0; i < 2; i++) {
         ButterflyRotation(dst, dstPos, i * 2, i * 2 + 1, 32 + 16 * i, i == 0, range);
      }

      if (size_log2 >= 3) {
         for (int i = 0; i < 2; i++) {
            HadamardRotation(dst, dstPos, i * 2 + 4, i * 2 + 5, i != 0, range);
         }
      }

      if (size_log2 >= 4) {
         for (int i = 0; i < 2; i++) {
            ButterflyRotation(dst, dstPos, 14 - i, i + 9, 48 + 64 * i, true, range);
         }
      }

      if (size_log2 >= 5) {
         for (int i = 0; i < 4; i++) {
            for (int j = 0; j < 2; j++) {
               HadamardRotation(dst, dstPos, i * 4 + j + 16, i * 4 - j + 19, (i & 1) != 0, range);
            }
         }
      }

      if (size_log2 == 6) {
         for (int i = 0; i < 2; i++) {
            for (int j = 0; j < 4; j++) {
               ButterflyRotation(dst, dstPos, 61 - i * 8 - j, i * 8 + j + 34, 56 - i * 32 + j / 2 * 64, true, range);
            }
         }
      }

      for (int i = 0; i < 2; i++) {
         HadamardRotation(dst, dstPos, i, 3 - i, false, range);
      }

      if (size_log2 >= 3) {
         ButterflyRotation(dst, dstPos, 6, 5, 32, true, range);
      }

      if (size_log2 >= 4) {
         for (int i = 0; i < 2; i++) {
            for (int j = 0; j < 2; j++) {
               HadamardRotation(dst, dstPos, i * 4 + j + 8, i * 4 - j + 11, i != 0, range);
            }
         }
      }

      if (size_log2 >= 5) {
         for (int i = 0; i < 4; i++) {
            ButterflyRotation(dst, dstPos, 29 - i, i + 18, 48 + 64 * (i / 2), true, range);
         }
      }

      if (size_log2 == 6) {
         for (int i = 0; i < 4; i++) {
            for (int j = 0; j < 4; j++) {
               HadamardRotation(dst, dstPos, i * 8 + j + 32, i * 8 - j + 39, (i & 1) != 0, range);
            }
         }
      }

      if (size_log2 >= 3) {
         for (int i = 0; i < 4; i++) {
            HadamardRotation(dst, dstPos, i, 7 - i, false, range);
         }
      }

      if (size_log2 >= 4) {
         for (int i = 0; i < 2; i++) {
            ButterflyRotation(dst, dstPos, 13 - i, i + 10, 32, true, range);
         }
      }

      if (size_log2 >= 5) {
         for (int i = 0; i < 2; i++) {
            for (int j = 0; j < 4; j++) {
               HadamardRotation(dst, dstPos, i * 8 + j + 16, i * 8 - j + 23, i == 1, range);
            }
         }
      }

      if (size_log2 == 6) {
         for (int i = 0; i < 8; i++) {
            ButterflyRotation(dst, dstPos, 59 - i, i + 36, i < 4 ? 48 : 112, true, range);
         }
      }

      if (size_log2 >= 4) {
         for (int i = 0; i < 8; i++) {
            HadamardRotation(dst, dstPos, i, 15 - i, false, range);
         }
      }

      if (size_log2 >= 5) {
         for (int i = 0; i < 4; i++) {
            ButterflyRotation(dst, dstPos, 27 - i, i + 20, 32, true, range);
         }
      }

      if (size_log2 == 6) {
         for (int i = 0; i < 8; i++) {
            HadamardRotation(dst, dstPos, i + 32, 47 - i, false, range);
            HadamardRotation(dst, dstPos, i + 48, 63 - i, true, range);
         }
      }

      if (size_log2 >= 5) {
         for (int i = 0; i < 16; i++) {
            HadamardRotation(dst, dstPos, i, 31 - i, false, range);
         }
      }

      if (size_log2 == 6) {
         for (int i = 0; i < 8; i++) {
            ButterflyRotation(dst, dstPos, 55 - i, i + 40, 32, true, range);
         }
      }

      if (size_log2 == 6) {
         for (int i = 0; i < 32; i++) {
            HadamardRotation(dst, dstPos, i, 63 - i, false, range);
         }
      }
   }

   private static void DctDcOnly(int[] dst, int dstPos, int range, boolean should_round, int row_shift, boolean is_row, int size_log2) {
      if (debug) {
         LogWriter.writeLog("DCT only");
      }

      if (is_row && should_round) {
         dst[dstPos] = D.RightShiftWithRounding(dst[dstPos] * kTransformRowMultiplier, 12);
      }

      ButterflyRotationSecondIsZero(dst, dstPos, 0, 1, 32, true, range);
      if (is_row && row_shift > 0) {
         dst[dstPos] = D.RightShiftWithRounding(dst[dstPos], row_shift);
      }

      int size = 1 << size_log2;

      for (int i = 1; i < size; i++) {
         dst[dstPos + i] = dst[dstPos];
      }
   }

   private static void Adst4(int[] dst, int dstPos, int range) {
      if (debug) {
         LogWriter.writeLog("ADST4");
      }

      if ((dst[dstPos] | dst[dstPos + 1] | dst[dstPos + 2] | dst[dstPos + 3]) != 0) {
         int[] s = new int[]{
            kAdst4Multiplier[0] * dst[dstPos],
            kAdst4Multiplier[1] * dst[dstPos],
            kAdst4Multiplier[2] * dst[dstPos + 1],
            kAdst4Multiplier[3] * dst[dstPos + 2],
            kAdst4Multiplier[0] * dst[dstPos + 2],
            kAdst4Multiplier[1] * dst[dstPos + 3],
            kAdst4Multiplier[3] * dst[dstPos + 3]
         };
         int a7 = dst[dstPos] - dst[dstPos + 2];
         int b7 = a7 + dst[dstPos + 3];
         s[0] += s[3];
         s[1] -= s[4];
         s[3] = s[2];
         int adst2_b7 = kAdst4Multiplier[2] * b7;
         s[2] = adst2_b7;
         s[0] += s[5];
         s[1] -= s[6];
         int x0 = s[0] + s[3];
         int x1 = s[1] + s[3];
         int x3 = s[0] + s[1];
         x3 -= s[3];
         int dst_0 = D.RightShiftWithRounding(x0, 12);
         int dst_1 = D.RightShiftWithRounding(x1, 12);
         int dst_2 = D.RightShiftWithRounding(s[2], 12);
         int dst_3 = D.RightShiftWithRounding(x3, 12);
         dst_0 -= dst_0 == 32768 ? 1 : 0;
         dst_1 -= dst_1 == 32768 ? 1 : 0;
         dst_3 -= dst_3 == 32768 ? 1 : 0;
         dst[dstPos] = dst_0;
         dst[dstPos + 1] = dst_1;
         dst[dstPos + 2] = dst_2;
         dst[dstPos + 3] = dst_3;
      }
   }

   private static void Adst4DcOnly(int[] dst, int dstPos, int range, boolean should_round, int row_shift, boolean is_row) {
      if (debug) {
         LogWriter.writeLog("ADST4 only");
      }

      if (is_row && should_round) {
         dst[dstPos] = D.RightShiftWithRounding(dst[dstPos] * kTransformRowMultiplier, 12);
      }

      int[] s = new int[]{kAdst4Multiplier[0] * dst[dstPos], kAdst4Multiplier[1] * dst[dstPos], kAdst4Multiplier[2] * dst[dstPos]};
      int dst_0 = D.RightShiftWithRounding(s[0], 12);
      int dst_1 = D.RightShiftWithRounding(s[1], 12);
      int dst_2 = D.RightShiftWithRounding(s[2], 12);
      int dst_3 = D.RightShiftWithRounding(s[0] + s[1], 12);
      dst_0 -= dst_0 == 32768 ? 1 : 0;
      dst_1 -= dst_1 == 32768 ? 1 : 0;
      dst_3 -= dst_3 == 32768 ? 1 : 0;
      dst[dstPos] = dst_0;
      dst[dstPos + 1] = dst_1;
      dst[dstPos + 2] = dst_2;
      dst[dstPos + 3] = dst_3;
      int size = 4;
      if (is_row && row_shift > 0) {
         for (int j = 0; j < size; j++) {
            dst[dstPos + j] = D.RightShiftWithRounding(dst[dstPos + j], row_shift);
         }
      }
   }

   private static void AdstInputPermutation(int[] dst, int dstPos, int[] src, int srcPos, int n) {
      for (int i = 0; i < n; i++) {
         dst[dstPos + i] = src[srcPos + ((i & 1) == 0 ? n - i - 1 : i - 1)];
      }
   }

   private static void AdstOutputPermutation(int[] dst, int dstPos, int[] src, int srcPos, int n) {
      int shift = n == 8 ? 1 : 0;

      for (int i = 0; i < n; i++) {
         int index = kAdstOutputPermutationLookup[i] >> shift;
         int dst_i = (i & 1) == 0 ? src[srcPos + index] : -src[srcPos + index];
         dst_i -= dst_i == 32768 ? 1 : 0;
         dst[dstPos + i] = dst_i;
      }
   }

   private static void Adst8(int[] dst, int dstPos, int range) {
      if (debug) {
         LogWriter.writeLog("ADST8");
      }

      if (dofast) {
         int min = -32768;
         int max = 32767;
         int in0 = dst[dstPos];
         int in1 = dst[dstPos + 1];
         int in2 = dst[dstPos + 2];
         int in3 = dst[dstPos + 3];
         int in4 = dst[dstPos + 4];
         int in5 = dst[dstPos + 5];
         int in6 = dst[dstPos + 6];
         int in7 = dst[dstPos + 7];
         int t0a = (-20 * in7 + 401 * in0 + 2048 >> 12) + in7;
         int t1a = (401 * in7 - -20 * in0 + 2048 >> 12) - in0;
         int t2a = (-484 * in5 + 1931 * in2 + 2048 >> 12) + in5;
         int t3a = (1931 * in5 - -484 * in2 + 2048 >> 12) - in2;
         int t4a = 1299 * in3 + 1583 * in4 + 1024 >> 11;
         int t5a = 1583 * in3 - 1299 * in4 + 1024 >> 11;
         int t6a = (1189 * in1 + -176 * in6 + 2048 >> 12) + in6;
         int t7a = (-176 * in1 - 1189 * in6 + 2048 >> 12) + in1;
         int t0 = D.Clip3(t0a + t4a, min, max);
         int t1 = D.Clip3(t1a + t5a, min, max);
         int t2 = D.Clip3(t2a + t6a, min, max);
         int t3 = D.Clip3(t3a + t7a, min, max);
         int t4 = D.Clip3(t0a - t4a, min, max);
         int t5 = D.Clip3(t1a - t5a, min, max);
         int t6 = D.Clip3(t2a - t6a, min, max);
         int t7 = D.Clip3(t3a - t7a, min, max);
         t4a = (-312 * t4 + 1567 * t5 + 2048 >> 12) + t4;
         t5a = (1567 * t4 - -312 * t5 + 2048 >> 12) - t5;
         t6a = (-312 * t7 - 1567 * t6 + 2048 >> 12) + t7;
         t7a = (1567 * t7 + -312 * t6 + 2048 >> 12) + t6;
         dst[dstPos] = D.Clip3(t0 + t2, min, max);
         dst[dstPos + 7] = -D.Clip3(t1 + t3, min, max);
         t2 = D.Clip3(t0 - t2, min, max);
         t3 = D.Clip3(t1 - t3, min, max);
         dst[dstPos + 1] = -D.Clip3(t4a + t6a, min, max);
         dst[dstPos + 6] = D.Clip3(t5a + t7a, min, max);
         t6 = D.Clip3(t4a - t6a, min, max);
         t7 = D.Clip3(t5a - t7a, min, max);
         dst[dstPos + 3] = -((t2 + t3) * 181 + 128 >> 8);
         dst[dstPos + 4] = (t2 - t3) * 181 + 128 >> 8;
         dst[dstPos + 2] = (t6 + t7) * 181 + 128 >> 8;
         dst[dstPos + 5] = -((t6 - t7) * 181 + 128 >> 8);
      } else {
         int[] temp = new int[8];
         AdstInputPermutation(temp, 0, dst, dstPos, 8);

         for (int i = 0; i < 4; i++) {
            ButterflyRotation(temp, 0, i * 2, i * 2 + 1, 60 - 16 * i, true, range);
         }

         for (int i = 0; i < 4; i++) {
            HadamardRotation(temp, 0, i, i + 4, false, range);
         }

         for (int i = 0; i < 2; i++) {
            ButterflyRotation(temp, 0, i * 3 + 4, i + 5, 48 - 32 * i, true, range);
         }

         for (int i = 0; i < 2; i++) {
            for (int j = 0; j < 2; j++) {
               HadamardRotation(temp, 0, i + j * 4, i + j * 4 + 2, false, range);
            }
         }

         for (int i = 0; i < 2; i++) {
            ButterflyRotation(temp, 0, i * 4 + 2, i * 4 + 3, 32, true, range);
         }

         AdstOutputPermutation(dst, dstPos, temp, 0, 8);
      }
   }

   private static void Adst8DcOnly(int[] dst, int dstPos, int range, boolean should_round, int row_shift, boolean is_row) {
      if (debug) {
         LogWriter.writeLog("ADST8 only");
      }

      int[] temp = new int[8];
      AdstInputPermutation(temp, 0, dst, dstPos, 8);
      if (is_row && should_round) {
         temp[1] = D.RightShiftWithRounding(temp[1] * kTransformRowMultiplier, 12);
      }

      ButterflyRotationFirstIsZero(temp, 0, 0, 1, 60, true, range);
      temp[4] = temp[0];
      temp[5] = temp[1];
      ButterflyRotation(temp, 0, 4, 5, 48, true, range);
      temp[2] = temp[0];
      temp[3] = temp[1];
      temp[6] = temp[4];
      temp[7] = temp[5];
      ButterflyRotation(temp, 0, 2, 3, 32, true, range);
      ButterflyRotation(temp, 0, 6, 7, 32, true, range);
      AdstOutputPermutation(dst, dstPos, temp, 0, 8);
      int size = 8;
      if (is_row && row_shift > 0) {
         for (int j = 0; j < size; j++) {
            dst[dstPos + j] = D.RightShiftWithRounding(dst[dstPos + j], row_shift);
         }
      }
   }

   private static void Adst16(int[] dst, int dstPos, int range) {
      if (debug) {
         LogWriter.writeLog("ADST16");
      }

      if (dofast) {
         int min = -32768;
         int max = 32767;
         int in0 = dst[dstPos];
         int in1 = dst[dstPos + 1];
         int in2 = dst[dstPos + 2];
         int in3 = dst[dstPos + 3];
         int in4 = dst[dstPos + 4];
         int in5 = dst[dstPos + 5];
         int in6 = dst[dstPos + 6];
         int in7 = dst[dstPos + 7];
         int in8 = dst[dstPos + 8];
         int in9 = dst[dstPos + 9];
         int in10 = dst[dstPos + 10];
         int in11 = dst[dstPos + 11];
         int in12 = dst[dstPos + 12];
         int in13 = dst[dstPos + 13];
         int in14 = dst[dstPos + 14];
         int in15 = dst[dstPos + 15];
         int t0 = (in15 * -5 + in0 * 201 + 2048 >> 12) + in15;
         int t1 = (in15 * 201 - in0 * -5 + 2048 >> 12) - in0;
         int t2 = (in13 * -123 + in2 * 995 + 2048 >> 12) + in13;
         int t3 = (in13 * 995 - in2 * -123 + 2048 >> 12) - in2;
         int t4 = (in11 * -393 + in4 * 1751 + 2048 >> 12) + in11;
         int t5 = (in11 * 1751 - in4 * -393 + 2048 >> 12) - in4;
         int t6 = in9 * 1645 + in6 * 1220 + 1024 >> 11;
         int t7 = in9 * 1220 - in6 * 1645 + 1024 >> 11;
         int t8 = (in7 * 2751 + in8 * -1061 + 2048 >> 12) + in8;
         int t9 = (in7 * -1061 - in8 * 2751 + 2048 >> 12) + in7;
         int t10 = (in5 * 2106 + in10 * -583 + 2048 >> 12) + in10;
         int t11 = (in5 * -583 - in10 * 2106 + 2048 >> 12) + in5;
         int t12 = (in3 * 1380 + in12 * -239 + 2048 >> 12) + in12;
         int t13 = (in3 * -239 - in12 * 1380 + 2048 >> 12) + in3;
         int t14 = (in1 * 601 + in14 * -44 + 2048 >> 12) + in14;
         int t15 = (in1 * -44 - in14 * 601 + 2048 >> 12) + in1;
         int t0a = D.Clip3(t0 + t8, min, max);
         int t1a = D.Clip3(t1 + t9, min, max);
         int t2a = D.Clip3(t2 + t10, min, max);
         int t3a = D.Clip3(t3 + t11, min, max);
         int t4a = D.Clip3(t4 + t12, min, max);
         int t5a = D.Clip3(t5 + t13, min, max);
         int t6a = D.Clip3(t6 + t14, min, max);
         int t7a = D.Clip3(t7 + t15, min, max);
         int t8a = D.Clip3(t0 - t8, min, max);
         int t9a = D.Clip3(t1 - t9, min, max);
         int t10a = D.Clip3(t2 - t10, min, max);
         int t11a = D.Clip3(t3 - t11, min, max);
         int t12a = D.Clip3(t4 - t12, min, max);
         int t13a = D.Clip3(t5 - t13, min, max);
         int t14a = D.Clip3(t6 - t14, min, max);
         int t15a = D.Clip3(t7 - t15, min, max);
         t8 = (t8a * -79 + t9a * 799 + 2048 >> 12) + t8a;
         t9 = (t8a * 799 - t9a * -79 + 2048 >> 12) - t9a;
         t10 = (t10a * 2276 + t11a * -690 + 2048 >> 12) + t11a;
         t11 = (t10a * -690 - t11a * 2276 + 2048 >> 12) + t10a;
         t12 = (t13a * -79 - t12a * 799 + 2048 >> 12) + t13a;
         t13 = (t13a * 799 + t12a * -79 + 2048 >> 12) + t12a;
         t14 = (t15a * 2276 - t14a * -690 + 2048 >> 12) - t14a;
         t15 = (t15a * -690 + t14a * 2276 + 2048 >> 12) + t15a;
         t0 = D.Clip3(t0a + t4a, min, max);
         t1 = D.Clip3(t1a + t5a, min, max);
         t2 = D.Clip3(t2a + t6a, min, max);
         t3 = D.Clip3(t3a + t7a, min, max);
         t4 = D.Clip3(t0a - t4a, min, max);
         t5 = D.Clip3(t1a - t5a, min, max);
         t6 = D.Clip3(t2a - t6a, min, max);
         t7 = D.Clip3(t3a - t7a, min, max);
         t8a = D.Clip3(t8 + t12, min, max);
         t9a = D.Clip3(t9 + t13, min, max);
         t10a = D.Clip3(t10 + t14, min, max);
         t11a = D.Clip3(t11 + t15, min, max);
         t12a = D.Clip3(t8 - t12, min, max);
         t13a = D.Clip3(t9 - t13, min, max);
         t14a = D.Clip3(t10 - t14, min, max);
         t15a = D.Clip3(t11 - t15, min, max);
         t4a = (t4 * -312 + t5 * 1567 + 2048 >> 12) + t4;
         t5a = (t4 * 1567 - t5 * -312 + 2048 >> 12) - t5;
         t6a = (t7 * -312 - t6 * 1567 + 2048 >> 12) + t7;
         t7a = (t7 * 1567 + t6 * -312 + 2048 >> 12) + t6;
         t12 = (t12a * -312 + t13a * 1567 + 2048 >> 12) + t12a;
         t13 = (t12a * 1567 - t13a * -312 + 2048 >> 12) - t13a;
         t14 = (t15a * -312 - t14a * 1567 + 2048 >> 12) + t15a;
         t15 = (t15a * 1567 + t14a * -312 + 2048 >> 12) + t14a;
         dst[dstPos] = D.Clip3(t0 + t2, min, max);
         dst[dstPos + 15] = -D.Clip3(t1 + t3, min, max);
         t2a = D.Clip3(t0 - t2, min, max);
         t3a = D.Clip3(t1 - t3, min, max);
         dst[dstPos + 3] = -D.Clip3(t4a + t6a, min, max);
         dst[dstPos + 12] = D.Clip3(t5a + t7a, min, max);
         t6 = D.Clip3(t4a - t6a, min, max);
         t7 = D.Clip3(t5a - t7a, min, max);
         dst[dstPos + 1] = -D.Clip3(t8a + t10a, min, max);
         dst[dstPos + 14] = D.Clip3(t9a + t11a, min, max);
         t10 = D.Clip3(t8a - t10a, min, max);
         t11 = D.Clip3(t9a - t11a, min, max);
         dst[dstPos + 2] = D.Clip3(t12 + t14, min, max);
         dst[dstPos + 13] = -D.Clip3(t13 + t15, min, max);
         t14a = D.Clip3(t12 - t14, min, max);
         t15a = D.Clip3(t13 - t15, min, max);
         dst[dstPos + 7] = -((t2a + t3a) * 181 + 128 >> 8);
         dst[dstPos + 8] = (t2a - t3a) * 181 + 128 >> 8;
         dst[dstPos + 4] = (t6 + t7) * 181 + 128 >> 8;
         dst[dstPos + 11] = -((t6 - t7) * 181 + 128 >> 8);
         dst[dstPos + 6] = (t10 + t11) * 181 + 128 >> 8;
         dst[dstPos + 9] = -((t10 - t11) * 181 + 128 >> 8);
         dst[dstPos + 5] = -((t14a + t15a) * 181 + 128 >> 8);
         dst[dstPos + 10] = (t14a - t15a) * 181 + 128 >> 8;
      } else {
         int[] temp = new int[16];
         AdstInputPermutation(temp, 0, dst, dstPos, 16);

         for (int i = 0; i < 8; i++) {
            ButterflyRotation(temp, 0, i * 2, i * 2 + 1, 62 - 8 * i, true, range);
         }

         for (int i = 0; i < 8; i++) {
            HadamardRotation(temp, 0, i, i + 8, false, range);
         }

         for (int i = 0; i < 2; i++) {
            ButterflyRotation(temp, 0, i * 2 + 8, i * 2 + 9, 56 - 32 * i, true, range);
            ButterflyRotation(temp, 0, i * 2 + 13, i * 2 + 12, 8 + 32 * i, true, range);
         }

         for (int i = 0; i < 4; i++) {
            for (int j = 0; j < 2; j++) {
               HadamardRotation(temp, 0, i + j * 8, i + j * 8 + 4, false, range);
            }
         }

         for (int i = 0; i < 2; i++) {
            for (int j = 0; j < 2; j++) {
               ButterflyRotation(temp, 0, i * 3 + j * 8 + 4, i + j * 8 + 5, 48 - 32 * i, true, range);
            }
         }

         for (int i = 0; i < 2; i++) {
            for (int j = 0; j < 4; j++) {
               HadamardRotation(temp, 0, i + j * 4, i + j * 4 + 2, false, range);
            }
         }

         for (int i = 0; i < 4; i++) {
            ButterflyRotation(temp, 0, i * 4 + 2, i * 4 + 3, 32, true, range);
         }

         AdstOutputPermutation(dst, dstPos, temp, 0, 16);
      }
   }

   private static void Adst16DcOnly(int[] dst, int dstPos, int range, boolean should_round, int row_shift, boolean is_row) {
      if (debug) {
         LogWriter.writeLog("ADST16 only");
      }

      int[] temp = new int[16];
      AdstInputPermutation(temp, 0, dst, dstPos, 16);
      if (is_row && should_round) {
         temp[1] = D.RightShiftWithRounding(temp[1] * kTransformRowMultiplier, 12);
      }

      ButterflyRotationFirstIsZero(temp, 0, 0, 1, 62, true, range);
      temp[8] = temp[0];
      temp[9] = temp[1];
      ButterflyRotation(temp, 0, 8, 9, 56, true, range);
      temp[4] = temp[0];
      temp[5] = temp[1];
      temp[12] = temp[8];
      temp[13] = temp[9];
      ButterflyRotation(temp, 0, 4, 5, 48, true, range);
      ButterflyRotation(temp, 0, 12, 13, 48, true, range);
      temp[2] = temp[0];
      temp[3] = temp[1];
      temp[10] = temp[8];
      temp[11] = temp[9];
      temp[6] = temp[4];
      temp[7] = temp[5];
      temp[14] = temp[12];
      temp[15] = temp[13];

      for (int i = 0; i < 4; i++) {
         ButterflyRotation(temp, 0, i * 4 + 2, i * 4 + 3, 32, true, range);
      }

      AdstOutputPermutation(dst, dstPos, temp, 0, 16);
      int size = 16;
      if (is_row && row_shift > 0) {
         for (int j = 0; j < size; j++) {
            dst[dstPos + j] = D.RightShiftWithRounding(dst[dstPos + j], row_shift);
         }
      }
   }

   private static void Identity4Row(int[] dst, int dstPos, int shift) {
      if (debug) {
         LogWriter.writeLog("Identity4Row");
      }

      int rounding = 1 + (shift << 1) << 11;

      for (int i = 0; i < 4; i++) {
         int intermediate = dst[dstPos + i] * kIdentity4Multiplier;
         int dst_i = intermediate + rounding >> 12 + shift;
         dst_i = D.Clip3(dst_i, -32768, 32767);
         dst[dstPos + i] = dst_i;
      }
   }

   private static void Identity4Column(int[] dst, int dstPos) {
      if (debug) {
         LogWriter.writeLog("Identity4Column");
      }

      int rounding = 34816;

      for (int i = 0; i < 4; i++) {
         dst[dstPos + i] = dst[dstPos + i] * kIdentity4Multiplier + rounding >> 16;
      }
   }

   private static void Identity4DcOnly(int[] dst, int dstPos, boolean shouldRound, int rowShift, boolean isRow) {
      if (debug) {
         LogWriter.writeLog("Identity4DC only");
      }

      if (isRow) {
         if (shouldRound) {
            int intermediate = dst[dstPos] * kTransformRowMultiplier;
            dst[dstPos] = D.RightShiftWithRounding(intermediate, 12);
         }

         int rounding = 1 + (rowShift << 1) << 11;
         int intermediate = dst[dstPos] * kIdentity4Multiplier;
         int dst_i = intermediate + rounding >> 12 + rowShift;
         dst_i = D.Clip3(dst_i, -32768, 32767);
         dst[dstPos] = dst_i;
      } else {
         int rounding = 34816;
         dst[dstPos] = dst[dstPos] * kIdentity4Multiplier + rounding >> 16;
      }
   }

   private static void Identity8Row(int[] dst, int dstPos, int shift) {
      if (debug) {
         LogWriter.writeLog("Identity8Row");
      }

      for (int i = 0; i < 8; i++) {
         int dst_i = D.RightShiftWithRounding(dst[dstPos + i] * 2, shift);
         dst_i = D.Clip3(dst_i, -32768, 32767);
         dst[dstPos + i] = dst_i;
      }
   }

   private static void Identity8Column(int[] dst, int dstPos) {
      if (debug) {
         LogWriter.writeLog("Identity8Column");
      }

      for (int i = 0; i < 8; i++) {
         dst[dstPos + i] = D.RightShiftWithRounding(dst[dstPos + i], 3);
      }
   }

   private static void Identity8DcOnly(int[] dst, int dstPos, boolean shouldRound, int rowShift, boolean isRow) {
      if (debug) {
         LogWriter.writeLog("Identity8Dc only");
      }

      if (isRow) {
         if (shouldRound) {
            int intermediate = dst[dstPos] * kTransformRowMultiplier;
            dst[dstPos] = D.RightShiftWithRounding(intermediate, 12);
         }

         int dst_i = D.RightShiftWithRounding(dst[dstPos] * 2, rowShift);
         dst_i = D.Clip3(dst_i, -32768, 32767);
         dst[dstPos] = dst_i;
      } else {
         dst[dstPos] = D.RightShiftWithRounding(dst[dstPos], 3);
      }
   }

   private static void Identity16Row(int[] dst, int dstPos, int shift) {
      if (debug) {
         LogWriter.writeLog("Identity16Row");
      }

      int rounding = 1 + (1 << shift) << 11;

      for (int i = 0; i < 16; i++) {
         int intermediate = dst[dstPos + i] * kIdentity16Multiplier;
         int dst_i = intermediate + rounding >> 12 + shift;
         dst_i = D.Clip3(dst_i, -32768, 32767);
         dst[dstPos + i] = dst_i;
      }
   }

   private static void Identity16Column(int[] dst, int dstPos) {
      if (debug) {
         LogWriter.writeLog("Identity16Column");
      }

      int rounding = 34816;

      for (int i = 0; i < 16; i++) {
         dst[dstPos + i] = dst[dstPos + i] * kIdentity16Multiplier + 34816 >> 16;
      }
   }

   private static void Identity16DcOnly(int[] dst, int dstPos, boolean shouldRound, int rowShift, boolean isRow) {
      if (debug) {
         LogWriter.writeLog("Identity16Dc only");
      }

      if (isRow) {
         if (shouldRound) {
            int intermediate = dst[dstPos] * kTransformRowMultiplier;
            dst[dstPos] = D.RightShiftWithRounding(intermediate, 12);
         }

         int rounding = 1 + (1 << rowShift) << 11;
         int intermediate = dst[dstPos] * kIdentity16Multiplier;
         int dst_i = intermediate + rounding >> 12 + rowShift;
         dst_i = D.Clip3(dst_i, -32768, 32767);
         dst[dstPos] = dst_i;
      } else {
         int rounding = 34816;
         dst[dstPos] = dst[dstPos] * kIdentity16Multiplier + rounding >> 16;
      }
   }

   private static void Identity32Row(int[] dst, int dstPos, int shift) {
      if (debug) {
         LogWriter.writeLog("Identity32Row");
      }

      for (int i = 0; i < 32; i++) {
         int dst_i = D.RightShiftWithRounding(dst[dstPos + i] * 4, shift);
         dst_i = D.Clip3(dst_i, -32768, 32767);
         dst[dstPos + i] = dst_i;
      }
   }

   private static void Identity32Column(int[] dst, int dstPos) {
      if (debug) {
         LogWriter.writeLog("Identity32Column");
      }

      for (int i = 0; i < 32; i++) {
         dst[dstPos + i] = D.RightShiftWithRounding(dst[dstPos + i], 2);
      }
   }

   private static void Identity32DcOnly(int[] dst, int dstPos, boolean shouldRound, int row_shift, boolean is_row) {
      if (debug) {
         LogWriter.writeLog("Identity32Dc only");
      }

      if (is_row) {
         if (shouldRound) {
            int intermediate = dst[dstPos] * kTransformRowMultiplier;
            dst[dstPos] = D.RightShiftWithRounding(intermediate, 12);
         }

         int dst_i = D.RightShiftWithRounding(dst[dstPos] * 4, row_shift);
         dst_i = D.Clip3(dst_i, -32768, 32767);
         dst[dstPos] = dst_i;
      } else {
         dst[dstPos] = D.RightShiftWithRounding(dst[dstPos], 2);
      }
   }

   private static void Wht4(int[] dst, int dstPos, int shift) {
      if (debug) {
         LogWriter.writeLog("WHT4");
      }

      int[] temp = new int[]{dst[dstPos] >> shift, 0, dst[dstPos + 1] >> shift, dst[dstPos + 2] >> shift};
      temp[1] = dst[dstPos + 3] >> shift;
      temp[0] += temp[2];
      temp[3] -= temp[1];
      int e = temp[0] - temp[3] >> 1;
      dst[dstPos + 1] = e - temp[1];
      dst[dstPos + 2] = e - temp[2];
      dst[dstPos] = temp[0] - dst[dstPos + 1];
      dst[dstPos + 3] = temp[3] + dst[dstPos + 2];
   }

   private static void Wht4DcOnly(int[] dst, int dstPos, int range) {
      if (debug) {
         LogWriter.writeLog("WHT4 only");
      }

      int temp = dst[dstPos] >> range;
      int e = temp >> 1;
      dst[dstPos] = temp - e;
      dst[dstPos + 1] = e;
      dst[dstPos + 2] = e;
      dst[dstPos + 3] = e;
   }

   static void TransformLoop(
      int txType,
      int txSize,
      int adjustedTxHeight,
      int[] srcBuffer,
      int srcBufferPos,
      int startX,
      int startY,
      D.Array2DView frame,
      int transform1DType,
      DcOnlyFunc onlyFunc,
      TransferFunc1dFunc func1d,
      int size_log2,
      boolean isRow
   ) {
      int bitdepth = 8;
      boolean lossless = transform1DType == 3;
      boolean is_identity = transform1DType == 2;
      int tx_width = lossless ? 4 : D.kTransformWidth[txSize];
      int tx_height = lossless ? 4 : D.kTransformHeight[txSize];
      int tx_width_log2 = D.kTransformWidthLog2[txSize];
      int tx_height_log2 = D.kTransformHeightLog2[txSize];
      int[] residual = srcBuffer;
      int residualPos = srcBufferPos;
      if (isRow) {
         int row_shift = lossless ? 0 : kTransformRowShift[txSize];
         int row_clamp_range = lossless ? 2 : bitdepth + 8;
         boolean should_round = Math.abs(tx_width_log2 - tx_height_log2) == 1;
         if (adjustedTxHeight == 1) {
            onlyFunc.doTransform(srcBuffer, srcBufferPos, size_log2, row_clamp_range, should_round, row_shift, true);
         } else {
            for (int i = 0; i < adjustedTxHeight; i++) {
               if (!lossless && should_round) {
                  for (int j = 0; j < Math.min(tx_width, 32); j++) {
                     residual[residualPos + i * tx_width + j] = D.RightShiftWithRounding(residual[residualPos + i * tx_width + j] * kTransformRowMultiplier, 12);
                  }
               }

               func1d.doTransform(residual, residualPos + i * tx_width, size_log2, is_identity ? row_shift : row_clamp_range, isRow);
               if (!lossless && !is_identity && row_shift > 0) {
                  for (int j = 0; j < tx_width; j++) {
                     residual[residualPos + i * tx_width + j] = D.RightShiftWithRounding(residual[residualPos + i * tx_width + j], row_shift);
                  }
               }
            }
         }
      } else {
         int column_shift = lossless ? 0 : 4;
         int column_clamp_range = lossless ? 0 : Math.max(bitdepth + 6, 16);
         boolean flip_rows = transform1DType == 1 && D.kTransformFlipRowsMask.Contains(txType);
         boolean flip_columns = !lossless && D.kTransformFlipColumnsMask.Contains(txType);
         int min_value = 0;
         int max_value = (1 << bitdepth) - 1;
         int[] tx_buffer = new int[64];

         for (int j = 0; j < tx_width; j++) {
            int flipped_j = flip_columns ? tx_width - j - 1 : j;
            int i = 0;

            do {
               tx_buffer[i] = residual[residualPos + i * tx_width + flipped_j];
            } while (++i != tx_height);

            if (adjustedTxHeight == 1) {
               onlyFunc.doTransform(tx_buffer, 0, size_log2, column_clamp_range, false, 0, false);
            } else {
               func1d.doTransform(tx_buffer, 0, size_log2, is_identity ? column_shift : column_clamp_range, isRow);
            }

            int x = startX + j;

            for (int xx = 0; xx < tx_height; xx++) {
               int y = startY + xx;
               int index = flip_rows ? tx_height - xx - 1 : xx;
               int residual_value = tx_buffer[index];
               if (!lossless && !is_identity) {
                  residual_value = D.RightShiftWithRounding(residual_value, column_shift);
               }

               int temp = frame.get(y, x);
               temp = D.Clip3(temp + residual_value, min_value, max_value);
               frame.set(y, x, temp);
            }
         }
      }
   }

   static void doTransform(
      int givenID,
      int givenSize,
      int txRC,
      int tx_type,
      int tx_size,
      int adjusted_tx_height,
      int[] src_buffer,
      int src_bufferPos,
      int start_x,
      int start_y,
      D.Array2DView frame
   ) {
      DcOnlyFunc dcOnlyFunc = null;
      TransferFunc1dFunc func1D = null;
      boolean isRow = txRC == 0;
      switch (givenID) {
         case 0:
            dcOnlyFunc = new DCT_ONLY();
            func1D = new DCT_1D();
            break;
         case 1:
            dcOnlyFunc = new ADST_ONLY();
            func1D = new ADST_1D();
            break;
         case 2:
            dcOnlyFunc = new IDENTITY_ONLY();
            func1D = new IDENTITY_1D();
            break;
         case 3:
            dcOnlyFunc = new WHT_ONLY();
            func1D = new WHT_1D();
            break;
         default:
            LogWriter.writeLog("tranform type not found " + givenID);
      }

      int size_log2 = 2;
      switch (givenSize) {
         case 0:
            size_log2 = 2;
            break;
         case 1:
            size_log2 = 3;
            break;
         case 2:
            size_log2 = 4;
            break;
         case 3:
            size_log2 = 5;
            break;
         case 4:
            size_log2 = 6;
            break;
         default:
            LogWriter.writeLog("itx: unsupported transform size " + givenSize);
      }

      TransformLoop(tx_type, tx_size, adjusted_tx_height, src_buffer, src_bufferPos, start_x, start_y, frame, givenID, dcOnlyFunc, func1D, size_log2, isRow);
   }

   static class ADST_1D implements TransferFunc1dFunc {
      @Override
      public void doTransform(int[] dst, int dstPos, int sizeLog2, int range, boolean isRow) {
         switch (sizeLog2) {
            case 2:
               Itx.Adst4(dst, dstPos, range);
               break;
            case 3:
               Itx.Adst8(dst, dstPos, range);
               break;
            case 4:
               Itx.Adst16(dst, dstPos, range);
               break;
            default:
               LogWriter.writeLog("invalid adst found: " + sizeLog2);
         }
      }
   }

   static class ADST_ONLY implements DcOnlyFunc {
      @Override
      public void doTransform(int[] dst, int dstPos, int sizeLog2, int range, boolean shouldRound, int rowShift, boolean isRow) {
         switch (sizeLog2) {
            case 2:
               Itx.Adst4DcOnly(dst, dstPos, range, shouldRound, rowShift, isRow);
               break;
            case 3:
               Itx.Adst8DcOnly(dst, dstPos, range, shouldRound, rowShift, isRow);
               break;
            case 4:
               Itx.Adst16DcOnly(dst, dstPos, range, shouldRound, rowShift, isRow);
               break;
            default:
               LogWriter.writeLog("invalid adst only found: " + sizeLog2);
         }
      }
   }

   static class DCT_1D implements TransferFunc1dFunc {
      @Override
      public void doTransform(int[] dst, int dstPos, int sizeLog2, int range, boolean isRow) {
         Itx.Dct(dst, dstPos, sizeLog2, range);
      }
   }

   static class DCT_ONLY implements DcOnlyFunc {
      @Override
      public void doTransform(int[] dst, int dstPos, int sizeLog2, int range, boolean shouldRound, int rowShift, boolean isRow) {
         Itx.DctDcOnly(dst, dstPos, range, shouldRound, rowShift, isRow, sizeLog2);
      }
   }

   interface DcOnlyFunc {
      void doTransform(int[] var1, int var2, int var3, int var4, boolean var5, int var6, boolean var7);
   }

   static class IDENTITY_1D implements TransferFunc1dFunc {
      @Override
      public void doTransform(int[] dst, int dstPos, int sizeLog2, int range, boolean isRow) {
         switch (sizeLog2) {
            case 2:
               if (isRow) {
                  Itx.Identity4Row(dst, dstPos, range);
               } else {
                  Itx.Identity4Column(dst, dstPos);
               }
               break;
            case 3:
               if (isRow) {
                  Itx.Identity8Row(dst, dstPos, range);
               } else {
                  Itx.Identity8Column(dst, dstPos);
               }
               break;
            case 4:
               if (isRow) {
                  Itx.Identity16Row(dst, dstPos, range);
               } else {
                  Itx.Identity16Column(dst, dstPos);
               }
               break;
            case 5:
               if (isRow) {
                  Itx.Identity32Row(dst, dstPos, range);
               } else {
                  Itx.Identity32Column(dst, dstPos);
               }
               break;
            default:
               LogWriter.writeLog("invalid identity found: " + sizeLog2);
         }
      }
   }

   static class IDENTITY_ONLY implements DcOnlyFunc {
      @Override
      public void doTransform(int[] dst, int dstPos, int sizeLog2, int range, boolean shouldRound, int rowShift, boolean isRow) {
         switch (sizeLog2) {
            case 2:
               Itx.Identity4DcOnly(dst, dstPos, shouldRound, rowShift, isRow);
               break;
            case 3:
               Itx.Identity8DcOnly(dst, dstPos, shouldRound, rowShift, isRow);
               break;
            case 4:
               Itx.Identity16DcOnly(dst, dstPos, shouldRound, rowShift, isRow);
               break;
            case 5:
               Itx.Identity32DcOnly(dst, dstPos, shouldRound, rowShift, isRow);
               break;
            default:
               LogWriter.writeLog("invalid identity only found: " + sizeLog2);
         }
      }
   }

   interface TransferFunc1dFunc {
      void doTransform(int[] var1, int var2, int var3, int var4, boolean var5);
   }

   static class WHT_1D implements TransferFunc1dFunc {
      @Override
      public void doTransform(int[] dst, int dstPos, int sizeLog2, int range, boolean isRow) {
         Itx.Wht4(dst, dstPos, range);
      }
   }

   static class WHT_ONLY implements DcOnlyFunc {
      @Override
      public void doTransform(int[] dst, int dstPos, int sizeLog2, int range, boolean shouldRound, int rowShift, boolean isRow) {
         Itx.Wht4DcOnly(dst, dstPos, range);
      }
   }
}
