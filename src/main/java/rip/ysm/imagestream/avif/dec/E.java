package rip.ysm.imagestream.avif.dec;

class E {
   static final int PARTITION_NONE = 0;
   static final int PARTITION_HORZ = 1;
   static final int PARTITION_VERT = 2;
   static final int PARTITION_SPLIT = 3;
   static final int PARTITION_HORZ_A = 4;
   static final int PARTITION_HORZ_B = 5;
   static final int PARTITION_VERT_A = 6;
   static final int PARTITION_VERT_B = 7;
   static final int PARTITION_HORZ_4 = 8;
   static final int PARTITION_VERT_4 = 9;
   static final int PLANE_TYPES = 2;
   static final int SUPPORTED_TX_SIZES = 2;
   static final int MAX_SUPPORTED_EOB_CLASS = 6;
   static final int TOKEN_CDF_Q_CTXS = 4;
   static final int TXB_SKIP_CONTEXTS = 13;
   static final int COEFF_BASE_CONTEXTS = 26;
   static final int COEFF_BASE_EOB_CONTEXTS = 4;
   static final int COEFF_BR_CONTEXTS = 21;
   static final int DC_SIGN_CONTEXTS = 3;
   static final int[][] default_scan_4x4 = new int[][]{
      {0, 0}, {0, 1}, {1, 0}, {2, 0}, {1, 1}, {0, 2}, {0, 3}, {1, 2}, {2, 1}, {3, 0}, {3, 1}, {2, 2}, {1, 3}, {2, 3}, {3, 2}, {3, 3}
   };
   static final int[][] default_scan_8x8 = new int[][]{
      {0, 0},
      {0, 1},
      {1, 0},
      {2, 0},
      {1, 1},
      {0, 2},
      {0, 3},
      {1, 2},
      {2, 1},
      {3, 0},
      {4, 0},
      {3, 1},
      {2, 2},
      {1, 3},
      {0, 4},
      {0, 5},
      {1, 4},
      {2, 3},
      {3, 2},
      {4, 1},
      {5, 0},
      {6, 0},
      {5, 1},
      {4, 2},
      {3, 3},
      {2, 4},
      {1, 5},
      {0, 6},
      {0, 7},
      {1, 6},
      {2, 5},
      {3, 4},
      {4, 3},
      {5, 2},
      {6, 1},
      {7, 0},
      {7, 1},
      {6, 2},
      {5, 3},
      {4, 4},
      {3, 5},
      {2, 6},
      {1, 7},
      {2, 7},
      {3, 6},
      {4, 5},
      {5, 4},
      {6, 3},
      {7, 2},
      {7, 3},
      {6, 4},
      {5, 5},
      {4, 6},
      {3, 7},
      {4, 7},
      {5, 6},
      {6, 5},
      {7, 4},
      {7, 5},
      {6, 6},
      {5, 7},
      {6, 7},
      {7, 6},
      {7, 7}
   };
   static final int[][][] scan_order_2d = new int[][][]{default_scan_4x4, default_scan_8x8};
   static final int[][] Sig_Ref_Diff_Offset = new int[][]{{0, 1}, {1, 0}, {1, 1}, {0, 2}, {2, 0}};
   static final int[][] Mag_Ref_Offset = new int[][]{{0, 1}, {1, 0}, {1, 1}};
   static final int[][] Coeff_Base_Ctx_Offset_8x8 = new int[][]{
      {0, 1, 6, 6, 21}, {1, 6, 6, 21, 21}, {6, 6, 21, 21, 21}, {6, 21, 21, 21, 21}, {21, 21, 21, 21, 21}
   };
   static final int[][] av1_cospi_arr_data = new int[][]{
      {
            1024,
            1024,
            1023,
            1021,
            1019,
            1016,
            1013,
            1009,
            1004,
            999,
            993,
            987,
            980,
            972,
            964,
            955,
            946,
            936,
            926,
            915,
            903,
            891,
            878,
            865,
            851,
            837,
            822,
            807,
            792,
            775,
            759,
            742,
            724,
            706,
            688,
            669,
            650,
            630,
            610,
            590,
            569,
            548,
            526,
            505,
            483,
            460,
            438,
            415,
            392,
            369,
            345,
            321,
            297,
            273,
            249,
            224,
            200,
            175,
            150,
            125,
            100,
            75,
            50,
            25
      },
      {
            2048,
            2047,
            2046,
            2042,
            2038,
            2033,
            2026,
            2018,
            2009,
            1998,
            1987,
            1974,
            1960,
            1945,
            1928,
            1911,
            1892,
            1872,
            1851,
            1829,
            1806,
            1782,
            1757,
            1730,
            1703,
            1674,
            1645,
            1615,
            1583,
            1551,
            1517,
            1483,
            1448,
            1412,
            1375,
            1338,
            1299,
            1260,
            1220,
            1179,
            1138,
            1096,
            1053,
            1009,
            965,
            921,
            876,
            830,
            784,
            737,
            690,
            642,
            595,
            546,
            498,
            449,
            400,
            350,
            301,
            251,
            201,
            151,
            100,
            50
      },
      {
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
            101
      },
      {
            8192,
            8190,
            8182,
            8170,
            8153,
            8130,
            8103,
            8071,
            8035,
            7993,
            7946,
            7895,
            7839,
            7779,
            7713,
            7643,
            7568,
            7489,
            7405,
            7317,
            7225,
            7128,
            7027,
            6921,
            6811,
            6698,
            6580,
            6458,
            6333,
            6203,
            6070,
            5933,
            5793,
            5649,
            5501,
            5351,
            5197,
            5040,
            4880,
            4717,
            4551,
            4383,
            4212,
            4038,
            3862,
            3683,
            3503,
            3320,
            3135,
            2948,
            2760,
            2570,
            2378,
            2185,
            1990,
            1795,
            1598,
            1401,
            1202,
            1003,
            803,
            603,
            402,
            201
      }
   };
   static final int[] av1_txfm_stages = new int[]{4, 6};
   static final int[][] av1_txfm_fwd_shift = new int[][]{{2, 0, 0}, {2, -1, 0}};
   static final int[][] av1_txfm_fwd_range_mult2 = new int[][]{{0, 2, 3, 3, 0, 0}, {0, 2, 4, 5, 5, 5}};
   static final int[][] av1_txfm_inv_shift = new int[][]{{0, -4}, {-1, -4}};
   static final int[] av1_txfm_inv_start_range = new int[]{5, 6};
   static final int[] qindex_to_dc_q = new int[]{
      4,
      8,
      8,
      9,
      10,
      11,
      12,
      12,
      13,
      14,
      15,
      16,
      17,
      18,
      19,
      19,
      20,
      21,
      22,
      23,
      24,
      25,
      26,
      26,
      27,
      28,
      29,
      30,
      31,
      32,
      32,
      33,
      34,
      35,
      36,
      37,
      38,
      38,
      39,
      40,
      41,
      42,
      43,
      43,
      44,
      45,
      46,
      47,
      48,
      48,
      49,
      50,
      51,
      52,
      53,
      53,
      54,
      55,
      56,
      57,
      57,
      58,
      59,
      60,
      61,
      62,
      62,
      63,
      64,
      65,
      66,
      66,
      67,
      68,
      69,
      70,
      70,
      71,
      72,
      73,
      74,
      74,
      75,
      76,
      77,
      78,
      78,
      79,
      80,
      81,
      81,
      82,
      83,
      84,
      85,
      85,
      87,
      88,
      90,
      92,
      93,
      95,
      96,
      98,
      99,
      101,
      102,
      104,
      105,
      107,
      108,
      110,
      111,
      113,
      114,
      116,
      117,
      118,
      120,
      121,
      123,
      125,
      127,
      129,
      131,
      134,
      136,
      138,
      140,
      142,
      144,
      146,
      148,
      150,
      152,
      154,
      156,
      158,
      161,
      164,
      166,
      169,
      172,
      174,
      177,
      180,
      182,
      185,
      187,
      190,
      192,
      195,
      199,
      202,
      205,
      208,
      211,
      214,
      217,
      220,
      223,
      226,
      230,
      233,
      237,
      240,
      243,
      247,
      250,
      253,
      257,
      261,
      265,
      269,
      272,
      276,
      280,
      284,
      288,
      292,
      296,
      300,
      304,
      309,
      313,
      317,
      322,
      326,
      330,
      335,
      340,
      344,
      349,
      354,
      359,
      364,
      369,
      374,
      379,
      384,
      389,
      395,
      400,
      406,
      411,
      417,
      423,
      429,
      435,
      441,
      447,
      454,
      461,
      467,
      475,
      482,
      489,
      497,
      505,
      513,
      522,
      530,
      539,
      549,
      559,
      569,
      579,
      590,
      602,
      614,
      626,
      640,
      654,
      668,
      684,
      700,
      717,
      736,
      755,
      775,
      796,
      819,
      843,
      869,
      896,
      925,
      955,
      988,
      1022,
      1058,
      1098,
      1139,
      1184,
      1232,
      1282,
      1336
   };
   static final int[] qindex_to_ac_q = new int[]{
      4,
      8,
      9,
      10,
      11,
      12,
      13,
      14,
      15,
      16,
      17,
      18,
      19,
      20,
      21,
      22,
      23,
      24,
      25,
      26,
      27,
      28,
      29,
      30,
      31,
      32,
      33,
      34,
      35,
      36,
      37,
      38,
      39,
      40,
      41,
      42,
      43,
      44,
      45,
      46,
      47,
      48,
      49,
      50,
      51,
      52,
      53,
      54,
      55,
      56,
      57,
      58,
      59,
      60,
      61,
      62,
      63,
      64,
      65,
      66,
      67,
      68,
      69,
      70,
      71,
      72,
      73,
      74,
      75,
      76,
      77,
      78,
      79,
      80,
      81,
      82,
      83,
      84,
      85,
      86,
      87,
      88,
      89,
      90,
      91,
      92,
      93,
      94,
      95,
      96,
      97,
      98,
      99,
      100,
      101,
      102,
      104,
      106,
      108,
      110,
      112,
      114,
      116,
      118,
      120,
      122,
      124,
      126,
      128,
      130,
      132,
      134,
      136,
      138,
      140,
      142,
      144,
      146,
      148,
      150,
      152,
      155,
      158,
      161,
      164,
      167,
      170,
      173,
      176,
      179,
      182,
      185,
      188,
      191,
      194,
      197,
      200,
      203,
      207,
      211,
      215,
      219,
      223,
      227,
      231,
      235,
      239,
      243,
      247,
      251,
      255,
      260,
      265,
      270,
      275,
      280,
      285,
      290,
      295,
      300,
      305,
      311,
      317,
      323,
      329,
      335,
      341,
      347,
      353,
      359,
      366,
      373,
      380,
      387,
      394,
      401,
      408,
      416,
      424,
      432,
      440,
      448,
      456,
      465,
      474,
      483,
      492,
      501,
      510,
      520,
      530,
      540,
      550,
      560,
      571,
      582,
      593,
      604,
      615,
      627,
      639,
      651,
      663,
      676,
      689,
      702,
      715,
      729,
      743,
      757,
      771,
      786,
      801,
      816,
      832,
      848,
      864,
      881,
      898,
      915,
      933,
      951,
      969,
      988,
      1007,
      1026,
      1046,
      1066,
      1087,
      1108,
      1129,
      1151,
      1173,
      1196,
      1219,
      1243,
      1267,
      1292,
      1317,
      1343,
      1369,
      1396,
      1423,
      1451,
      1479,
      1508,
      1537,
      1567,
      1597,
      1628,
      1660,
      1692,
      1725,
      1759,
      1793,
      1828
   };

   static int clamp(int v, int low, int high) {
      if (v < low) {
         return low;
      } else {
         return v > high ? high : v;
      }
   }

   static long clamp(long v, long low, long high) {
      if (v < low) {
         return low;
      } else {
         return v > high ? high : v;
      }
   }

   static int FloorLog2Int(int x) {
      int s;
      for (s = 0; x != 0; s++) {
         x >>= 1;
      }

      return s - 1;
   }

   static int FloorLog2(int x) {
      int s;
      for (s = 0; x != 0; s++) {
         x >>>= 1;
      }

      return s - 1;
   }

   static long FloorLog2(long x) {
      int s;
      for (s = 0; x != 0L; s++) {
         x >>>= 1;
      }

      return s - 1;
   }

   static int CeilLog2(int n) {
      return n < 2 ? 0 : FloorLog2(n - 1) + 1;
   }

   static int get_prob(int symbol, int[] cdf) {
      if (symbol == 0) {
         return cdf[0];
      } else {
         return symbol == cdf.length ? 32768 - cdf[symbol - 1] : cdf[symbol] - cdf[symbol - 1];
      }
   }

   static int roundUp8(int x) {
      return x + 7 >> 3 << 3;
   }

   static int round2(int self, int n) {
      int offset = 1 << n >> 1;
      return self + offset >> n;
   }

   static long round2Long(long self, long n) {
      long offset = 1L << (int)n >> 1;
      return self + offset >> (int)n;
   }

   static class Array2dInt {
      int rows;
      int cols;
      int stride;
      int usize;
      int[][] data;

      static Array2dInt zeroed(int rows, int cols) {
         Array2dInt result = new Array2dInt();
         result.rows = rows;
         result.cols = cols;
         result.stride = cols;
         result.data = new int[rows][cols];
         return result;
      }

      void fill_with(Array2dInt f) {
         for (int i = 0; i < this.rows; i++) {
            for (int j = 0; j < this.cols; j++) {
               this.data[i][j] = f.data[i][j];
            }
         }
      }

      void fill_region(int row_start, int col_start, int rows, int cols, int value) {
         for (int r = 0; r < rows; r++) {
            for (int c = 0; c < cols; c++) {
               this.data[r + row_start][c + col_start] = value;
            }
         }
      }

      Array2dInt new_with(int rows, int cols, Array2dInt f) {
         Array2dInt result = zeroed(rows, cols);
         result.fill_with(f);
         return result;
      }

      void transpose_into(Array2dInt dst) {
         for (int i = 0; i < this.cols; i++) {
            for (int j = 0; j < this.rows; j++) {
               dst.data[i][j] = this.data[j][i];
            }
         }
      }

      Array2dInt transpose() {
         Array2dInt dst = zeroed(this.rows, this.cols);
         this.transpose_into(dst);
         return dst;
      }
   }

   static class Array2dModeInfo {
      int rows;
      int cols;
      int stride;
      int usize;
      EObu.ModeInfo[][] data;

      void fill_region(int row_start, int col_start, int rows, int cols, EObu.ModeInfo value) {
         EObu.ModeInfo cloned = new EObu.ModeInfo();

         for (int i = 0; i < 3; i++) {
            cloned.dc_sign[i] = value.dc_sign[i];
            cloned.level_ctx[i] = value.level_ctx[i];
         }

         for (int r = 0; r < rows; r++) {
            for (int c = 0; c < cols; c++) {
               this.data[r + row_start][c + col_start] = cloned;
            }
         }
      }

      static Array2dModeInfo zeroed(int rows, int cols) {
         Array2dModeInfo result = new Array2dModeInfo();
         result.rows = rows;
         result.cols = cols;
         result.stride = cols;
         result.data = new EObu.ModeInfo[rows][cols];

         for (int i = 0; i < rows; i++) {
            for (int j = 0; j < cols; j++) {
               result.data[i][j] = new EObu.ModeInfo();
            }
         }

         return result;
      }

      void transpose_into(Array2dModeInfo dst) {
         for (int i = 0; i < this.cols; i++) {
            for (int j = 0; j < this.rows; j++) {
               dst.data[i][j] = this.data[j][i];
            }
         }
      }

      void transpose() {
         Array2dModeInfo dst = zeroed(this.rows, this.cols);
         this.transpose_into(dst);
      }
   }

   static class DataBig {
      private int bp;
      byte[] cur = new byte[128];

      void check64() {
         if (this.bp + 64 > this.cur.length) {
            byte[] temp = new byte[this.bp + 128];
            System.arraycopy(this.cur, 0, temp, 0, this.cur.length);
            this.cur = temp;
         }
      }

      void write_u8(int x) {
         this.check64();
         this.cur[this.bp++] = (byte)x;
      }

      void pos_u8(int p, int value) {
         this.cur[p] = (byte)value;
      }

      void write_u16(int v) {
         this.check64();
         this.cur[this.bp++] = (byte)(v >> 8 & 0xFF);
         this.cur[this.bp++] = (byte)(v & 0xFF);
      }

      void write_U24(int v) {
         this.check64();
         this.cur[this.bp++] = (byte)(v >> 16 & 0xFF);
         this.cur[this.bp++] = (byte)(v >> 8 & 0xFF);
         this.cur[this.bp++] = (byte)(v & 0xFF);
      }

      void write_U32(int v) {
         this.check64();
         this.cur[this.bp++] = (byte)(v >> 24 & 0xFF);
         this.cur[this.bp++] = (byte)(v >> 16 & 0xFF);
         this.cur[this.bp++] = (byte)(v >> 8 & 0xFF);
         this.cur[this.bp++] = (byte)(v & 0xFF);
      }

      void write_U64(long v) {
         this.check64();
         this.cur[this.bp++] = (byte)(v >> 56 & 255L);
         this.cur[this.bp++] = (byte)(v >> 48 & 255L);
         this.cur[this.bp++] = (byte)(v >> 40 & 255L);
         this.cur[this.bp++] = (byte)(v >> 32 & 255L);
         this.cur[this.bp++] = (byte)(v >> 24 & 255L);
         this.cur[this.bp++] = (byte)(v >> 16 & 255L);
         this.cur[this.bp++] = (byte)(v >> 8 & 255L);
         this.cur[this.bp++] = (byte)(v & 255L);
      }

      void write_leb128(int value) {
         if (value == 0) {
            this.write_u8(0);
         } else {
            while (value != 0) {
               int more_flag = value >> 7 > 0 ? 128 : 0;
               this.write_u8(more_flag | value & 127);
               value >>= 7;
            }
         }
      }

      int len() {
         return this.bp;
      }

      void extend_from_slice(byte[] temp) {
         for (int i = 0; i < temp.length; i++) {
            this.write_u8(temp[i] & 255);
         }
      }

      byte[] finalizeBytes() {
         byte[] result = new byte[this.bp];
         System.arraycopy(this.cur, 0, result, 0, this.bp);
         return result;
      }
   }
}
