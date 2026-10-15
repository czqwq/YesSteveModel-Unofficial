package rip.ysm.imagestream.avif.dec;

class ETxfm {
   private static int[] cospi_arr(int cos_bit) {
      return E.av1_cospi_arr_data[cos_bit - 10];
   }

   private static int clamp_value(int value, int range_bits) {
      return E.clamp(value, -(1 << range_bits - 1), (1 << range_bits - 1) - 1);
   }

   private static void clamp_array(int[] arr, int bits) {
      for (int i = 0; i < arr.length; i++) {
         arr[i] = clamp_value(arr[i], bits);
      }
   }

   private static void round_shift_array(int[] arr, int bits) {
      if (bits != 0) {
         if (bits < 0) {
            int shift = -bits;

            for (int i = 0; i < arr.length; i++) {
               long tmp = arr[i] * 1L << shift;
               arr[i] = (int)E.clamp(tmp, -2147483648L, 2147483647L);
            }
         } else {
            int shift = bits;

            for (int i = 0; i < arr.length; i++) {
               arr[i] = E.round2(arr[i], shift);
            }
         }
      }
   }

   private static int half_btf(int w0, int in0, int w1, int in1, int cos_bit) {
      return (int)E.round2Long(1L * w0 * in0 + 1L * w1 * in1, cos_bit);
   }

   private static void fwd_dct4(int[] arr, int cos_bit) {
      int[] cospi = cospi_arr(cos_bit);
      int c16 = cospi[16];
      int c32 = cospi[32];
      int c48 = cospi[48];
      int s0 = arr[0] + arr[3];
      int s1 = arr[1] + arr[2];
      int s2 = -arr[2] + arr[1];
      int s3 = -arr[3] + arr[0];
      arr[0] = half_btf(c32, s0, c32, s1, cos_bit);
      arr[1] = half_btf(c48, s2, c16, s3, cos_bit);
      arr[2] = half_btf(-c32, s1, c32, s0, cos_bit);
      arr[3] = half_btf(c48, s3, -c16, s2, cos_bit);
   }

   private static void fwd_dct8(int[] arr, int cos_bit) {
      int[] cospi = cospi_arr(cos_bit);
      int c8 = cospi[8];
      int c16 = cospi[16];
      int c24 = cospi[24];
      int c32 = cospi[32];
      int c40 = cospi[40];
      int c48 = cospi[48];
      int c56 = cospi[56];
      int a0 = arr[0] + arr[7];
      int a1 = arr[1] + arr[6];
      int a2 = arr[2] + arr[5];
      int a3 = arr[3] + arr[4];
      int a4 = -arr[4] + arr[3];
      int a5 = -arr[5] + arr[2];
      int a6 = -arr[6] + arr[1];
      int a7 = -arr[7] + arr[0];
      int b0 = a0 + a3;
      int b1 = a1 + a2;
      int b2 = -a2 + a1;
      int b3 = -a3 + a0;
      int b5 = half_btf(-c32, a5, c32, a6, cos_bit);
      int b6 = half_btf(c32, a6, c32, a5, cos_bit);
      int c4 = a4 + b5;
      int c5 = -b5 + a4;
      int c6 = -b6 + a7;
      int c7 = a7 + b6;
      arr[0] = half_btf(c32, b0, c32, b1, cos_bit);
      arr[1] = half_btf(c56, c4, c8, c7, cos_bit);
      arr[2] = half_btf(c48, b2, c16, b3, cos_bit);
      arr[3] = half_btf(c24, c6, -c40, c5, cos_bit);
      arr[4] = half_btf(-c32, b1, c32, b0, cos_bit);
      arr[5] = half_btf(c24, c5, c40, c6, cos_bit);
      arr[6] = half_btf(c48, b3, -c16, b2, cos_bit);
      arr[7] = half_btf(c56, c7, -c8, c4, cos_bit);
   }

   private static void inv_dct4(int[] arr, int cos_bit, int[] stage_range) {
      int[] cospi = cospi_arr(cos_bit);
      int c16 = cospi[16];
      int c32 = cospi[32];
      int c48 = cospi[48];
      int a0 = arr[0];
      int a1 = arr[2];
      int a2 = arr[1];
      int a3 = arr[3];
      int x0 = half_btf(c32, a0, c32, a1, cos_bit);
      int x1 = half_btf(c32, a0, -c32, a1, cos_bit);
      int x2 = half_btf(c48, a2, -c16, a3, cos_bit);
      int x3 = half_btf(c16, a2, c48, a3, cos_bit);
      arr[0] = clamp_value(x0 + x3, stage_range[3]);
      arr[1] = clamp_value(x1 + x2, stage_range[3]);
      arr[2] = clamp_value(x1 - x2, stage_range[3]);
      arr[3] = clamp_value(x0 - x3, stage_range[3]);
   }

   private static void inv_dct8(int[] arr, int cos_bit, int[] stage_range) {
      int[] cospi = cospi_arr(cos_bit);
      int c8 = cospi[8];
      int c16 = cospi[16];
      int c24 = cospi[24];
      int c32 = cospi[32];
      int c40 = cospi[40];
      int c48 = cospi[48];
      int c56 = cospi[56];
      int a0 = arr[0];
      int a1 = arr[4];
      int a2 = arr[2];
      int a3 = arr[6];
      int a4 = arr[1];
      int a5 = arr[5];
      int a6 = arr[3];
      int a7 = arr[7];
      int b4 = half_btf(c56, a4, -c8, a7, cos_bit);
      int b5 = half_btf(c24, a5, -c40, a6, cos_bit);
      int b6 = half_btf(c40, a5, c24, a6, cos_bit);
      int b7 = half_btf(c8, a4, c56, a7, cos_bit);
      int r3 = stage_range[3];
      int c0 = half_btf(c32, a0, c32, a1, cos_bit);
      int c1 = half_btf(c32, a0, -c32, a1, cos_bit);
      int c2 = half_btf(c48, a2, -c16, a3, cos_bit);
      int c3 = half_btf(c16, a2, c48, a3, cos_bit);
      int c4 = clamp_value(b4 + b5, r3);
      int c5 = clamp_value(b4 - b5, r3);
      int c6 = clamp_value(-b6 + b7, r3);
      int c7 = clamp_value(b6 + b7, r3);
      int r4 = stage_range[3];
      int d0 = clamp_value(c0 + c3, r4);
      int d1 = clamp_value(c1 + c2, r4);
      int d2 = clamp_value(c1 - c2, r4);
      int d3 = clamp_value(c0 - c3, r4);
      int d5 = half_btf(-c32, c5, c32, c6, cos_bit);
      int d6 = half_btf(c32, c5, c32, c6, cos_bit);
      int r5 = stage_range[5];
      arr[0] = clamp_value(d0 + c7, r5);
      arr[1] = clamp_value(d1 + d6, r5);
      arr[2] = clamp_value(d2 + d5, r5);
      arr[3] = clamp_value(d3 + c4, r5);
      arr[4] = clamp_value(d3 - c4, r5);
      arr[5] = clamp_value(d2 - d5, r5);
      arr[6] = clamp_value(d1 - d6, r5);
      arr[7] = clamp_value(d0 - c7, r5);
   }

   static void fwd_txfm2d(E.Array2dInt residual, int txh, int txw) {
      int txsz_idx = 0;
      if (txh == 8 && txw == 8) {
         txsz_idx = 1;
      } else if (txh == 4 && txw == 4) {
         txsz_idx = 0;
      }

      int cos_bit_col = 13;
      int cos_bit_row = 13;
      int bd = 8;
      int stages = E.av1_txfm_stages[txsz_idx];
      int[] shift = E.av1_txfm_fwd_shift[txsz_idx];
      int[] stage_ranges = E.av1_txfm_fwd_range_mult2[txsz_idx];
      int[] stage_range_col = new int[stages];
      int[] stage_range_row = new int[stages];

      for (int i = 0; i < stages; i++) {
         stage_range_col[i] = E.round2(stage_ranges[i], 1) + shift[0] + bd + 1;
      }

      for (int i = 0; i < stages; i++) {
         stage_range_row[i] = E.round2(stage_ranges[stages - 1] + stage_ranges[i], 1) + shift[0] + shift[1] + bd + 1;
      }

      E.Array2dInt transposed = residual.transpose();

      for (int j = 0; j < txw; j++) {
         int[] col = transposed.data[j];
         round_shift_array(col, -shift[0]);
         if (txh == 8 && txw == 8) {
            fwd_dct8(col, cos_bit_col);
         } else if (txh == 4 && txw == 4) {
            fwd_dct4(col, cos_bit_col);
         }

         round_shift_array(col, -shift[1]);
      }

      transposed.transpose_into(residual);

      for (int i = 0; i < txh; i++) {
         int[] row = residual.data[i];
         if (txh == 8 && txw == 8) {
            fwd_dct8(row, cos_bit_row);
         } else if (txh == 4 && txw == 4) {
            fwd_dct4(row, cos_bit_row);
         }

         round_shift_array(row, -shift[2]);
      }
   }

   static void inv_txfm2d(E.Array2dInt residual, int txh, int txw) {
      int txsz_idx = 0;
      if (txh == 8 && txw == 8) {
         txsz_idx = 1;
      } else if (txh == 4 && txw == 4) {
         txsz_idx = 0;
      }

      int cos_bit_col = 12;
      int cos_bit_row = 12;
      int bd = 8;
      int opt_range_row = 16;
      int opt_range_col = 16;
      int stages = E.av1_txfm_stages[txsz_idx];
      int[] shift = E.av1_txfm_inv_shift[txsz_idx];
      int[] stage_range_row = new int[stages];
      int[] stage_range_col = new int[stages];

      for (int i = 0; i < stages; i++) {
         stage_range_row[i] = E.av1_txfm_inv_start_range[txsz_idx] + bd + 1;
      }

      for (int i = 0; i < stages; i++) {
         stage_range_col[i] = E.av1_txfm_inv_start_range[txsz_idx] + shift[0] + bd + 1;
      }

      for (int i = 0; i < txh; i++) {
         int[] row = residual.data[i];
         clamp_array(row, bd + 8);
         if (txh == 8 && txw == 8) {
            inv_dct8(row, cos_bit_col, stage_range_col);
         } else if (txh == 4 && txw == 4) {
            inv_dct4(row, cos_bit_col, stage_range_col);
         }

         round_shift_array(row, -shift[0]);
      }

      E.Array2dInt transposed = residual.transpose();

      for (int j = 0; j < txw; j++) {
         int[] col = transposed.data[j];
         clamp_array(col, Math.max(bd + 6, 16));
         if (txh == 8 && txw == 8) {
            inv_dct8(col, cos_bit_row, stage_range_row);
         } else if (txh == 4 && txw == 4) {
            inv_dct4(col, cos_bit_row, stage_range_row);
         }

         round_shift_array(col, -shift[1]);
      }

      transposed.transpose_into(residual);
   }
}
