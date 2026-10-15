package rip.ysm.imagestream.webp.enc;

final class RateCtrl {
   static final int[][] vp8_bits_per_mb = new int[][]{
      {
            1125000,
            900000,
            750000,
            642857,
            562500,
            500000,
            450000,
            450000,
            409090,
            375000,
            346153,
            321428,
            300000,
            281250,
            264705,
            264705,
            250000,
            236842,
            225000,
            225000,
            214285,
            214285,
            204545,
            204545,
            195652,
            195652,
            187500,
            180000,
            180000,
            173076,
            166666,
            160714,
            155172,
            150000,
            145161,
            140625,
            136363,
            132352,
            128571,
            125000,
            121621,
            121621,
            118421,
            115384,
            112500,
            109756,
            107142,
            104651,
            102272,
            100000,
            97826,
            97826,
            95744,
            93750,
            91836,
            90000,
            88235,
            86538,
            84905,
            83333,
            81818,
            80357,
            78947,
            77586,
            76271,
            75000,
            73770,
            72580,
            71428,
            70312,
            69230,
            68181,
            67164,
            66176,
            65217,
            64285,
            63380,
            62500,
            61643,
            60810,
            60000,
            59210,
            59210,
            58441,
            57692,
            56962,
            56250,
            55555,
            54878,
            54216,
            53571,
            52941,
            52325,
            51724,
            51136,
            50561,
            49450,
            48387,
            47368,
            46875,
            45918,
            45000,
            44554,
            44117,
            43269,
            42452,
            41666,
            40909,
            40178,
            39473,
            38793,
            38135,
            36885,
            36290,
            35714,
            35156,
            34615,
            34090,
            33582,
            33088,
            32608,
            32142,
            31468,
            31034,
            30405,
            29801,
            29220,
            28662
      },
      {
            712500,
            570000,
            475000,
            407142,
            356250,
            316666,
            285000,
            259090,
            237500,
            219230,
            203571,
            190000,
            178125,
            167647,
            158333,
            150000,
            142500,
            135714,
            129545,
            123913,
            118750,
            114000,
            109615,
            105555,
            101785,
            98275,
            95000,
            91935,
            89062,
            86363,
            83823,
            81428,
            79166,
            77027,
            75000,
            73076,
            71250,
            69512,
            67857,
            66279,
            64772,
            63333,
            61956,
            60638,
            59375,
            58163,
            57000,
            55882,
            54807,
            53773,
            52777,
            51818,
            50892,
            50000,
            49137,
            47500,
            45967,
            44531,
            43181,
            41911,
            40714,
            39583,
            38513,
            37500,
            36538,
            35625,
            34756,
            33928,
            33139,
            32386,
            31666,
            30978,
            30319,
            29687,
            29081,
            28500,
            27941,
            27403,
            26886,
            26388,
            25909,
            25446,
            25000,
            24568,
            23949,
            23360,
            22800,
            22265,
            21755,
            21268,
            20802,
            20357,
            19930,
            19520,
            19127,
            18750,
            18387,
            18037,
            17701,
            17378,
            17065,
            16764,
            16473,
            16101,
            15745,
            15405,
            15079,
            14766,
            14467,
            14179,
            13902,
            13636,
            13380,
            13133,
            12895,
            12666,
            12445,
            12179,
            11924,
            11632,
            11445,
            11220,
            11003,
            10795,
            10594,
            10401,
            10215,
            10035
      }
   };
   static final int[] kf_boost_qadjustment = new int[]{
      128,
      129,
      130,
      131,
      132,
      133,
      134,
      135,
      136,
      137,
      138,
      139,
      140,
      141,
      142,
      143,
      144,
      145,
      146,
      147,
      148,
      149,
      150,
      151,
      152,
      153,
      154,
      155,
      156,
      157,
      158,
      159,
      160,
      161,
      162,
      163,
      164,
      165,
      166,
      167,
      168,
      169,
      170,
      171,
      172,
      173,
      174,
      175,
      176,
      177,
      178,
      179,
      180,
      181,
      182,
      183,
      184,
      185,
      186,
      187,
      188,
      189,
      190,
      191,
      192,
      193,
      194,
      195,
      196,
      197,
      198,
      199,
      200,
      200,
      201,
      201,
      202,
      203,
      203,
      203,
      204,
      204,
      205,
      205,
      206,
      206,
      207,
      207,
      208,
      208,
      209,
      209,
      210,
      210,
      211,
      211,
      212,
      212,
      213,
      213,
      214,
      214,
      215,
      215,
      216,
      216,
      217,
      217,
      218,
      218,
      219,
      219,
      220,
      220,
      220,
      220,
      220,
      220,
      220,
      220,
      220,
      220,
      220,
      220,
      220,
      220,
      220,
      220
   };
   static final int[] vp8_gf_boost_qadjustment = new int[]{
      80,
      82,
      84,
      86,
      88,
      90,
      92,
      94,
      96,
      97,
      98,
      99,
      100,
      101,
      102,
      103,
      104,
      105,
      106,
      107,
      108,
      109,
      110,
      111,
      112,
      113,
      114,
      115,
      116,
      117,
      118,
      119,
      120,
      121,
      122,
      123,
      124,
      125,
      126,
      127,
      128,
      129,
      130,
      131,
      132,
      133,
      134,
      135,
      136,
      137,
      138,
      139,
      140,
      141,
      142,
      143,
      144,
      145,
      146,
      147,
      148,
      149,
      150,
      151,
      152,
      153,
      154,
      155,
      156,
      157,
      158,
      159,
      160,
      161,
      162,
      163,
      164,
      165,
      166,
      167,
      168,
      169,
      170,
      171,
      172,
      173,
      174,
      175,
      176,
      177,
      178,
      179,
      180,
      181,
      182,
      183,
      184,
      184,
      185,
      185,
      186,
      186,
      187,
      187,
      188,
      188,
      189,
      189,
      190,
      190,
      191,
      191,
      192,
      192,
      193,
      193,
      194,
      194,
      194,
      194,
      195,
      195,
      196,
      196,
      197,
      197,
      198,
      198
   };
   static final int[] gf_intra_usage_adjustment = new int[]{125, 120, 115, 110, 105, 100, 95, 85, 80, 75, 70, 65, 60, 55, 50, 50, 50, 50, 50, 50};
   static final int[] gf_adjust_table = new int[]{
      100,
      115,
      130,
      145,
      160,
      175,
      190,
      200,
      210,
      220,
      230,
      240,
      260,
      270,
      280,
      290,
      300,
      310,
      320,
      330,
      340,
      350,
      360,
      370,
      380,
      390,
      400,
      400,
      400,
      400,
      400,
      400,
      400,
      400,
      400,
      400,
      400,
      400,
      400,
      400,
      400,
      400,
      400,
      400,
      400,
      400,
      400,
      400,
      400,
      400,
      400,
      400,
      400,
      400,
      400,
      400,
      400,
      400,
      400,
      400,
      400,
      400,
      400,
      400,
      400,
      400,
      400,
      400,
      400,
      400,
      400,
      400,
      400,
      400,
      400,
      400,
      400,
      400,
      400,
      400,
      400,
      400,
      400,
      400,
      400,
      400,
      400,
      400,
      400,
      400,
      400,
      400,
      400,
      400,
      400,
      400,
      400,
      400,
      400,
      400,
      400
   };
   static final int[] kf_gf_boost_qlimits = new int[]{
      150,
      155,
      160,
      165,
      170,
      175,
      180,
      185,
      190,
      195,
      200,
      205,
      210,
      215,
      220,
      225,
      230,
      235,
      240,
      245,
      250,
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
      310,
      320,
      330,
      340,
      350,
      360,
      370,
      380,
      390,
      400,
      410,
      420,
      430,
      440,
      450,
      460,
      470,
      480,
      490,
      500,
      510,
      520,
      530,
      540,
      550,
      560,
      570,
      580,
      590,
      600,
      600,
      600,
      600,
      600,
      600,
      600,
      600,
      600,
      600,
      600,
      600,
      600,
      600,
      600,
      600,
      600,
      600,
      600,
      600,
      600,
      600,
      600,
      600,
      600,
      600,
      600,
      600,
      600,
      600,
      600,
      600,
      600,
      600,
      600,
      600,
      600,
      600,
      600,
      600,
      600,
      600,
      600,
      600,
      600,
      600,
      600,
      600,
      600,
      600,
      600,
      600,
      600,
      600,
      600,
      600,
      600,
      600,
      600,
      600,
      600,
      600,
      600,
      600,
      600,
      600,
      600
   };
   static final int[] gf_interval_table = new int[]{
      7,
      7,
      7,
      7,
      7,
      7,
      7,
      7,
      7,
      7,
      7,
      7,
      7,
      7,
      7,
      7,
      7,
      7,
      7,
      7,
      7,
      7,
      7,
      7,
      7,
      7,
      7,
      7,
      7,
      7,
      7,
      8,
      8,
      8,
      8,
      8,
      8,
      8,
      8,
      8,
      8,
      8,
      8,
      8,
      8,
      8,
      8,
      8,
      8,
      8,
      8,
      9,
      9,
      9,
      9,
      9,
      9,
      9,
      9,
      9,
      9,
      9,
      9,
      9,
      9,
      9,
      9,
      9,
      9,
      9,
      9,
      10,
      10,
      10,
      10,
      10,
      10,
      10,
      10,
      10,
      10,
      10,
      10,
      10,
      10,
      10,
      10,
      10,
      10,
      10,
      10,
      11,
      11,
      11,
      11,
      11,
      11,
      11,
      11,
      11,
      11
   };
   static final int[] prior_key_frame_weight = new int[]{1, 2, 3, 4, 5};
   static final int BPER_MB_NORMBITS = 9;
   static final double MIN_BPB_FACTOR = 0.01;
   static final double MAX_BPB_FACTOR = 50.0;

   private RateCtrl() {
   }

   static void vp8_setup_key_frame(Compressor cpi) {
      cpi.common.fc.toDefault();
      EncodeMV.vp8_build_component_cost_table(cpi.mb.mvcost, cpi.common.fc.mvc, new boolean[]{true, true});
      cpi.lfc_a = new FrameContext(cpi.common.fc);
      cpi.lfc_g = new FrameContext(cpi.common.fc);
      cpi.lfc_n = new FrameContext(cpi.common.fc);
      cpi.common.filter_level = (short)(cpi.common.base_qindex * 3 / 8);
      if (cpi.auto_gold) {
         cpi.frames_till_gf_update_due = cpi.baseline_gf_interval;
      } else {
         cpi.frames_till_gf_update_due = 7;
      }

      cpi.common.refresh_golden_frame = true;
      cpi.common.refresh_alt_ref_frame = true;
   }

   static int estimate_bits_at_q(MVReferenceFrame frame_kind, int Q, int MBs, double correction_factor) {
      int Bpm = (int)(0.5 + correction_factor * vp8_bits_per_mb[frame_kind == MVReferenceFrame.INTRA_FRAME ? 0 : 1][Q]);
      return MBs > 2048 ? (Bpm >> 9) * MBs : Bpm * MBs >> 9;
   }

   static void calc_iframe_target_size(Compressor cpi) {
      long target;
      if (cpi.oxcf.fixed_q >= 0) {
         int Q = cpi.oxcf.key_q;
         target = estimate_bits_at_q(MVReferenceFrame.INTRA_FRAME, Q, cpi.common.MBs, cpi.key_frame_rate_correction_factor);
      } else if (cpi.common.current_video_frame == 0) {
         target = cpi.oxcf.starting_buffer_level / 2L;
         if (target > cpi.oxcf.target_bandwidth * 3L / 2L) {
            target = cpi.oxcf.target_bandwidth * 3L / 2L;
         }
      } else {
         int Q = cpi.common.frame_flags.contains(FrameFlags.Key) ? cpi.avg_frame_qindex : cpi.ni_av_qi;
         int initial_boost = 32;
         int kf_boost;
         if (cpi.oxcf.number_of_layers == 1) {
            kf_boost = Math.max(32, (int)(2.0 * cpi.output_framerate - 16.0));
         } else {
            kf_boost = 32;
         }

         kf_boost = kf_boost * kf_boost_qadjustment[Q] / 100;
         if (cpi.frames_since_key < cpi.output_framerate / 2.0) {
            kf_boost = (int)(kf_boost * cpi.frames_since_key / (cpi.output_framerate / 2.0));
         }

         if (kf_boost < 16) {
            kf_boost = 16;
         }

         target = (long)(16 + kf_boost) * cpi.per_frame_bandwidth >> 4;
      }

      if (cpi.oxcf.rc_max_intra_bitrate_pct != 0) {
         int max_rate = cpi.per_frame_bandwidth * cpi.oxcf.rc_max_intra_bitrate_pct / 100;
         if (target > max_rate) {
            target = max_rate;
         }
      }

      cpi.this_frame_target = (int)target;
      cpi.active_worst_quality = cpi.worst_quality;
   }

   private static int getGoldenFrameUsage(Compressor cpi) {
      int gf_frame_useage = 0;
      int pct_gf_active = 100 * cpi.gf_active_count / (cpi.common.mb_rows * cpi.common.mb_cols);
      if (pct_gf_active > gf_frame_useage) {
         gf_frame_useage = pct_gf_active;
      }

      return gf_frame_useage;
   }

   static void calc_gf_params(Compressor cpi) {
      int Q = cpi.oxcf.fixed_q < 0 ? cpi.last_q[1] : cpi.oxcf.fixed_q;
      int gf_frame_useage = getGoldenFrameUsage(cpi);
      int Boost = vp8_gf_boost_qadjustment[Q];
      Boost = Boost * gf_intra_usage_adjustment[cpi.this_frame_percent_intra < 15 ? cpi.this_frame_percent_intra : 14] / 100;
      Boost = Boost * gf_adjust_table[gf_frame_useage] / 100;
      if (cpi.sf.recode_loop == 0 && cpi.compressor_speed == 2) {
         Boost /= 2;
      }

      if (Boost > kf_gf_boost_qlimits[Q]) {
         Boost = kf_gf_boost_qlimits[Q];
      } else if (Boost < 110) {
         Boost = 110;
      }

      cpi.last_boost = Boost;
      if (cpi.oxcf.fixed_q == -1) {
         cpi.frames_till_gf_update_due = cpi.baseline_gf_interval;
         if (cpi.last_boost > 750) {
            cpi.frames_till_gf_update_due++;
         }

         if (cpi.last_boost > 1000) {
            cpi.frames_till_gf_update_due++;
         }

         if (cpi.last_boost > 1250) {
            cpi.frames_till_gf_update_due++;
         }

         if (cpi.last_boost >= 1500) {
            cpi.frames_till_gf_update_due++;
         }

         if (gf_interval_table[gf_frame_useage] > cpi.frames_till_gf_update_due) {
            cpi.frames_till_gf_update_due = gf_interval_table[gf_frame_useage];
         }

         if (cpi.frames_till_gf_update_due > cpi.max_gf_interval) {
            cpi.frames_till_gf_update_due = cpi.max_gf_interval;
         }
      } else {
         cpi.frames_till_gf_update_due = cpi.baseline_gf_interval;
      }

      cpi.source_alt_ref_pending = false;
   }

   static void calc_pframe_target_size(Compressor cpi) {
      int old_per_frame_bandwidth = cpi.per_frame_bandwidth;
      if (cpi.current_layer > 0) {
         cpi.per_frame_bandwidth = cpi.layer_context[cpi.current_layer].avg_frame_size_for_layer;
      }

      int min_frame_target = 0;
      if (min_frame_target < cpi.per_frame_bandwidth / 4) {
         min_frame_target = cpi.per_frame_bandwidth / 4;
      }

      if (!cpi.common.refresh_alt_ref_frame || cpi.oxcf.number_of_layers != 1) {
         defineAdjustment(cpi, min_frame_target);
      }

      if (cpi.this_frame_target < min_frame_target) {
         cpi.this_frame_target = min_frame_target;
      }

      if (!cpi.common.refresh_alt_ref_frame) {
         cpi.inter_frame_target = cpi.this_frame_target;
      }

      if (cpi.buffered_mode) {
         defineBufferMode(cpi);
      } else {
         cpi.active_worst_quality = cpi.worst_quality;
      }

      if (cpi.oxcf.end_usage == 2 && cpi.active_worst_quality < cpi.cq_target_quality) {
         cpi.active_worst_quality = cpi.cq_target_quality;
      }

      if (cpi.drop_frames_allowed && cpi.oxcf.end_usage == 1 && cpi.common.frame_type != 0 && cpi.buffer_level < 0L) {
         cpi.drop_frame = true;
         cpi.bits_off_target = cpi.bits_off_target + cpi.av_per_frame_bandwidth;
         if (cpi.bits_off_target > cpi.oxcf.maximum_buffer_size) {
            cpi.bits_off_target = (int)cpi.oxcf.maximum_buffer_size;
         }

         cpi.buffer_level = cpi.bits_off_target;
         if (cpi.oxcf.number_of_layers > 1) {
            for (int i = cpi.current_layer + 1; i < cpi.oxcf.number_of_layers; i++) {
               LayerContext lc = cpi.layer_context[i];
               lc.bits_off_target = lc.bits_off_target + (int)(lc.target_bandwidth / lc.framerate);
               if (lc.bits_off_target > lc.maximum_buffer_size) {
                  lc.bits_off_target = lc.maximum_buffer_size;
               }

               lc.buffer_level = lc.bits_off_target;
            }
         }
      }

      if (!cpi.oxcf.error_resilient_mode && cpi.frames_till_gf_update_due == 0 && !cpi.drop_frame) {
         if (!cpi.gf_update_onepass_cbr) {
            defineOnePassCbr(cpi);
         } else {
            cpi.gf_noboost_onepass_cbr = cpi.oxcf.gf_cbr_boost_pct <= 100;
            cpi.baseline_gf_interval = cpi.gf_interval_onepass_cbr;
            if (cpi.zeromv_count > cpi.common.MBs >> 1) {
               cpi.common.refresh_golden_frame = true;
               cpi.this_frame_target = cpi.this_frame_target * (100 + cpi.oxcf.gf_cbr_boost_pct) / 100;
            }

            cpi.frames_till_gf_update_due = cpi.baseline_gf_interval;
            cpi.current_gf_interval = cpi.frames_till_gf_update_due;
         }
      }

      cpi.per_frame_bandwidth = old_per_frame_bandwidth;
   }

   private static void defineBufferMode(Compressor cpi) {
      int one_percent_bits = (int)(1L + cpi.oxcf.optimal_buffer_level / 100L);
      if (cpi.buffer_level >= cpi.oxcf.optimal_buffer_level && cpi.bits_off_target >= cpi.oxcf.optimal_buffer_level) {
         int percent_high = 0;
         if (cpi.oxcf.end_usage == 1 && cpi.buffer_level > cpi.oxcf.optimal_buffer_level) {
            percent_high = (int)((cpi.buffer_level - cpi.oxcf.optimal_buffer_level) / one_percent_bits);
         } else if (cpi.bits_off_target > cpi.oxcf.optimal_buffer_level) {
            percent_high = (int)(100L * cpi.bits_off_target / (cpi.total_byte_count * 8L));
         }

         if (percent_high > cpi.oxcf.over_shoot_pct) {
            percent_high = cpi.oxcf.over_shoot_pct;
         } else if (percent_high < 0) {
            percent_high = 0;
         }

         cpi.this_frame_target = cpi.this_frame_target + cpi.this_frame_target * percent_high / 200;
         if (cpi.auto_worst_q && cpi.ni_frames > 150) {
            cpi.active_worst_quality = cpi.ni_av_qi;
         } else {
            cpi.active_worst_quality = cpi.worst_quality;
         }
      } else {
         int percent_low = 0;
         if (cpi.oxcf.end_usage == 1 && cpi.buffer_level < cpi.oxcf.optimal_buffer_level) {
            percent_low = (int)((cpi.oxcf.optimal_buffer_level - cpi.buffer_level) / one_percent_bits);
         } else if (cpi.bits_off_target < 0L) {
            percent_low = (int)(100L * -cpi.bits_off_target / (cpi.total_byte_count * 8L));
         }

         if (percent_low > cpi.oxcf.under_shoot_pct) {
            percent_low = cpi.oxcf.under_shoot_pct;
         } else if (percent_low < 0) {
            percent_low = 0;
         }

         cpi.this_frame_target = cpi.this_frame_target - cpi.this_frame_target * percent_low / 200;
         if (cpi.auto_worst_q && cpi.ni_frames > 150) {
            long critical_buffer_level;
            if (cpi.oxcf.end_usage == 1) {
               critical_buffer_level = Math.min(cpi.buffer_level, cpi.bits_off_target);
            } else {
               critical_buffer_level = cpi.bits_off_target;
            }

            if (critical_buffer_level < cpi.oxcf.optimal_buffer_level) {
               if (critical_buffer_level > cpi.oxcf.optimal_buffer_level >> 2) {
                  long qadjustment_range = cpi.worst_quality - cpi.ni_av_qi;
                  long above_base = critical_buffer_level - (cpi.oxcf.optimal_buffer_level >> 2);
                  cpi.active_worst_quality = (short)(cpi.worst_quality - (int)(qadjustment_range * above_base / (cpi.oxcf.optimal_buffer_level * 3L >> 2)));
               } else {
                  cpi.active_worst_quality = cpi.worst_quality;
               }
            } else {
               cpi.active_worst_quality = cpi.ni_av_qi;
            }
         } else {
            cpi.active_worst_quality = cpi.worst_quality;
         }
      }

      cpi.active_best_quality = cpi.best_quality;
      if (cpi.active_worst_quality <= cpi.active_best_quality) {
         cpi.active_worst_quality = (short)(cpi.active_best_quality + 1);
      }

      if (cpi.active_worst_quality > 127) {
         cpi.active_worst_quality = 127;
      }
   }

   private static void defineOnePassCbr(Compressor cpi) {
      int Q = cpi.oxcf.fixed_q < 0 ? cpi.last_q[1] : cpi.oxcf.fixed_q;
      int gf_frame_useage = getGoldenFrameUsage(cpi);
      if (cpi.auto_gold && (cpi.this_frame_percent_intra < 15 || gf_frame_useage >= 5)) {
         cpi.common.refresh_golden_frame = true;
      }

      if (cpi.common.refresh_golden_frame) {
         if (cpi.auto_adjust_gold_quantizer) {
            calc_gf_params(cpi);
         }

         if (cpi.source_alt_ref_active) {
            cpi.this_frame_target = 0;
         } else if (cpi.oxcf.fixed_q >= 0) {
            cpi.this_frame_target = estimate_bits_at_q(MVReferenceFrame.GOLDEN_FRAME, Q, cpi.common.MBs, 1.0) * cpi.last_boost / 100;
         } else {
            int Boost = cpi.last_boost;
            int frames_in_section = cpi.frames_till_gf_update_due + 1;
            int allocation_chunks = frames_in_section * 100 + (Boost - 100);

            int bits_in_section;
            for (bits_in_section = cpi.inter_frame_target * frames_in_section; Boost > 1000; allocation_chunks /= 2) {
               Boost /= 2;
            }

            if (bits_in_section >> 7 > allocation_chunks) {
               cpi.this_frame_target = Boost * (bits_in_section / allocation_chunks);
            } else {
               cpi.this_frame_target = Boost * bits_in_section / allocation_chunks;
            }
         }

         cpi.current_gf_interval = cpi.frames_till_gf_update_due;
      }
   }

   private static void defineAdjustment(Compressor cpi, int min_frame_target) {
      if (cpi.kf_overspend_bits > 0) {
         int Adjustment = Math.min(cpi.kf_bitrate_adjustment, cpi.kf_overspend_bits);
         if (Adjustment > cpi.per_frame_bandwidth - min_frame_target) {
            Adjustment = cpi.per_frame_bandwidth - min_frame_target;
         }

         cpi.kf_overspend_bits -= Adjustment;
         cpi.this_frame_target = cpi.per_frame_bandwidth - Adjustment;
         if (cpi.this_frame_target < min_frame_target) {
            cpi.this_frame_target = min_frame_target;
         }
      } else {
         cpi.this_frame_target = cpi.per_frame_bandwidth;
      }

      if (cpi.gf_overspend_bits > 0 && cpi.this_frame_target > min_frame_target) {
         int Adjustmentx = Math.min(cpi.non_gf_bitrate_adjustment, cpi.gf_overspend_bits);
         if (Adjustmentx > cpi.this_frame_target - min_frame_target) {
            Adjustmentx = cpi.this_frame_target - min_frame_target;
         }

         cpi.gf_overspend_bits -= Adjustmentx;
         cpi.this_frame_target -= Adjustmentx;
      }

      if (cpi.last_boost > 150 && cpi.frames_till_gf_update_due > 0 && cpi.current_gf_interval >= 8) {
         int Adjustmentx = cpi.last_boost - 100 >> 5;
         if (Adjustmentx > 10) {
            Adjustmentx = 10;
         }

         Adjustmentx = cpi.this_frame_target * Adjustmentx / 100;
         if (Adjustmentx > cpi.this_frame_target - min_frame_target) {
            Adjustmentx = cpi.this_frame_target - min_frame_target;
         }

         if (cpi.frames_since_golden == cpi.current_gf_interval >> 1) {
            Adjustmentx = (cpi.current_gf_interval - 1) * Adjustmentx;
            if (Adjustmentx > 10 * cpi.this_frame_target / 100) {
               Adjustmentx = 10 * cpi.this_frame_target / 100;
            }

            cpi.this_frame_target += Adjustmentx;
         } else {
            cpi.this_frame_target -= Adjustmentx;
         }
      }
   }

   static void vp8_compute_frame_size_bounds(Compressor cpi, FrameLimits limits) {
      if (cpi.oxcf.fixed_q >= 0) {
         limits.frame_under_shoot_limit = 0;
         limits.frame_over_shoot_limit = Integer.MAX_VALUE;
      } else {
         long this_frame_target = cpi.this_frame_target;
         long over_shoot_limit;
         long under_shoot_limit;
         if (cpi.common.frame_type == 0) {
            over_shoot_limit = this_frame_target * 9L / 8L;
            under_shoot_limit = this_frame_target * 7L / 8L;
         } else if (cpi.oxcf.number_of_layers > 1 || cpi.common.refresh_alt_ref_frame || cpi.common.refresh_golden_frame) {
            over_shoot_limit = this_frame_target * 9L / 8L;
            under_shoot_limit = this_frame_target * 7L / 8L;
         } else if (cpi.oxcf.end_usage == 1) {
            if (cpi.buffer_level >= cpi.oxcf.optimal_buffer_level + cpi.oxcf.maximum_buffer_size >> 1) {
               over_shoot_limit = this_frame_target * 12L / 8L;
               under_shoot_limit = this_frame_target * 6L / 8L;
            } else if (cpi.buffer_level <= cpi.oxcf.optimal_buffer_level >> 1) {
               over_shoot_limit = this_frame_target * 10L / 8L;
               under_shoot_limit = this_frame_target * 4L / 8L;
            } else {
               over_shoot_limit = this_frame_target * 11L / 8L;
               under_shoot_limit = this_frame_target * 5L / 8L;
            }
         } else if (cpi.oxcf.end_usage == 2) {
            over_shoot_limit = this_frame_target * 11L / 8L;
            under_shoot_limit = this_frame_target * 2L / 8L;
         } else {
            over_shoot_limit = this_frame_target * 11L / 8L;
            under_shoot_limit = this_frame_target * 5L / 8L;
         }

         over_shoot_limit += 200L;
         under_shoot_limit -= 200L;
         if (under_shoot_limit < 0L) {
            under_shoot_limit = 0L;
         }

         if (over_shoot_limit > 2147483647L) {
            over_shoot_limit = 2147483647L;
         }

         limits.frame_under_shoot_limit = (int)under_shoot_limit;
         limits.frame_over_shoot_limit = (int)over_shoot_limit;
      }
   }

   static boolean vp8_pick_frame_size(Compressor cpi) {
      CommonData cm = cpi.common;
      if (cm.frame_type == 0) {
         calc_iframe_target_size(cpi);
      } else {
         calc_pframe_target_size(cpi);
         if (cpi.drop_frame) {
            cpi.drop_frame = false;
            return false;
         }
      }

      return true;
   }

   static short limit_q_cbr_inter(short last_q, short current_q) {
      short limit_down = 12;
      return last_q - current_q > 12 ? (short)(last_q - 12) : current_q;
   }

   static short vp8_regulate_q(Compressor cpi, int target_bits_per_frame) {
      short Q = cpi.active_worst_quality;
      if (cpi.force_maxqp == 1) {
         cpi.active_worst_quality = cpi.worst_quality;
         return cpi.worst_quality;
      } else {
         cpi.mb.zbin_over_quant = 0;
         if (cpi.oxcf.fixed_q >= 0) {
            Q = cpi.oxcf.fixed_q;
            if (cpi.common.frame_type == 0) {
               Q = cpi.oxcf.key_q;
            } else if (cpi.oxcf.number_of_layers == 1 && cpi.common.refresh_alt_ref_frame && !cpi.gf_noboost_onepass_cbr) {
               Q = cpi.oxcf.alt_q;
            } else if (cpi.oxcf.number_of_layers == 1 && cpi.common.refresh_golden_frame && !cpi.gf_noboost_onepass_cbr) {
               Q = cpi.oxcf.gold_q;
            }
         } else {
            int last_error = Integer.MAX_VALUE;
            double correction_factor;
            if (cpi.common.frame_type == 0) {
               correction_factor = cpi.key_frame_rate_correction_factor;
            } else if (cpi.oxcf.number_of_layers != 1 || cpi.gf_noboost_onepass_cbr || !cpi.common.refresh_alt_ref_frame && !cpi.common.refresh_golden_frame) {
               correction_factor = cpi.rate_correction_factor;
            } else {
               correction_factor = cpi.gf_rate_correction_factor;
            }

            int target_bits_per_mb;
            if (target_bits_per_frame >= 4194303) {
               target_bits_per_mb = target_bits_per_frame / cpi.common.MBs << 9;
            } else {
               target_bits_per_mb = (target_bits_per_frame << 9) / cpi.common.MBs;
            }

            short i = cpi.active_best_quality;

            int bits_per_mb_at_this_q;
            do {
               bits_per_mb_at_this_q = (int)(0.5 + correction_factor * vp8_bits_per_mb[cpi.common.frame_type][i]);
               if (bits_per_mb_at_this_q <= target_bits_per_mb) {
                  if (target_bits_per_mb - bits_per_mb_at_this_q <= last_error) {
                     Q = i;
                  } else {
                     Q = (short)(i - 1);
                  }
                  break;
               }

               last_error = bits_per_mb_at_this_q - target_bits_per_mb;
            } while (++i <= cpi.active_worst_quality);

            if (Q >= 127) {
               double Factor = 0.99;
               double factor_adjustment = 3.90625E-5;
               int zbin_oqmax;
               if (cpi.common.frame_type == 0) {
                  zbin_oqmax = 0;
               } else if (cpi.oxcf.number_of_layers != 1
                  || cpi.gf_noboost_onepass_cbr
                  || !cpi.common.refresh_alt_ref_frame && (!cpi.common.refresh_golden_frame || cpi.source_alt_ref_active)) {
                  zbin_oqmax = 192;
               } else {
                  zbin_oqmax = 16;
               }

               while (cpi.mb.zbin_over_quant < zbin_oqmax) {
                  cpi.mb.zbin_over_quant++;
                  if (cpi.mb.zbin_over_quant > zbin_oqmax) {
                     cpi.mb.zbin_over_quant = zbin_oqmax;
                  }

                  bits_per_mb_at_this_q = (int)(Factor * bits_per_mb_at_this_q);
                  Factor += 3.90625E-5;
                  if (Factor >= 0.999) {
                     Factor = 0.999;
                  }

                  if (bits_per_mb_at_this_q <= target_bits_per_mb) {
                     break;
                  }
               }
            }
         }

         if (cpi.common.frame_type != 0 && cpi.oxcf.end_usage == 1 && cpi.oxcf.screen_content_mode != 0) {
            Q = limit_q_cbr_inter(cpi.last_q[1], Q);
         }

         return Q;
      }
   }

   static boolean vp8_drop_encodedframe_overshoot(Compressor cpi, int Q) {
      if (cpi.common.frame_type != 0
         && (
            cpi.oxcf.screen_content_mode == 2
               || cpi.drop_frames_allowed && cpi.rate_correction_factor < 0.08 && cpi.frames_since_last_drop_overshoot > (int)cpi.framerate
         )) {
         int thresh_qp = 3 * cpi.worst_quality >> 2;
         int thresh_rate = 2 * (cpi.av_per_frame_bandwidth >> 3);
         int thresh_pred_err_mb = 3200;
         int pred_err_mb = (int)(cpi.mb.prediction_error / cpi.common.MBs);
         if (cpi.drop_frames_allowed && pred_err_mb > 51200) {
            thresh_rate >>= 3;
         }

         if (Q < thresh_qp && cpi.projected_frame_size > thresh_rate && pred_err_mb > 3200 && pred_err_mb > 2 * cpi.last_pred_err_mb) {
            int target_size = cpi.av_per_frame_bandwidth;
            cpi.force_maxqp = 1;
            cpi.buffer_level = cpi.oxcf.optimal_buffer_level;
            cpi.bits_off_target = cpi.oxcf.optimal_buffer_level;
            int target_bits_per_mb;
            if (target_size >= 4194303) {
               target_bits_per_mb = target_size / cpi.common.MBs << 9;
            } else {
               target_bits_per_mb = (target_size << 9) / cpi.common.MBs;
            }

            double new_correction_factor = (double)target_bits_per_mb / vp8_bits_per_mb[1][cpi.worst_quality];
            if (new_correction_factor > cpi.rate_correction_factor) {
               cpi.rate_correction_factor = Math.min(2.0 * cpi.rate_correction_factor, new_correction_factor);
            }

            if (cpi.rate_correction_factor > 50.0) {
               cpi.rate_correction_factor = 50.0;
            }

            cpi.common.current_video_frame++;
            cpi.frames_since_key++;
            cpi.temporal_pattern_counter++;
            cpi.frames_since_last_drop_overshoot = 0;
            if (cpi.oxcf.number_of_layers > 1) {
               for (int i = 0; i < cpi.oxcf.number_of_layers; i++) {
                  LayerContext lc = cpi.layer_context[i];
                  lc.force_maxqp = 1;
                  lc.frames_since_last_drop_overshoot = 0;
                  lc.rate_correction_factor = cpi.rate_correction_factor;
               }
            }

            return true;
         } else {
            cpi.force_maxqp = 0;
            cpi.frames_since_last_drop_overshoot++;
            return false;
         }
      } else {
         cpi.force_maxqp = 0;
         cpi.frames_since_last_drop_overshoot++;
         return false;
      }
   }

   static void vp8_update_rate_correction_factors(Compressor cpi, int damp_var) {
      int Q = cpi.common.base_qindex;
      int correction_factor = 100;
      double rate_correction_factor;
      if (cpi.common.frame_type == 0) {
         rate_correction_factor = cpi.key_frame_rate_correction_factor;
      } else if (cpi.oxcf.number_of_layers != 1 || cpi.gf_noboost_onepass_cbr || !cpi.common.refresh_alt_ref_frame && !cpi.common.refresh_golden_frame) {
         rate_correction_factor = cpi.rate_correction_factor;
      } else {
         rate_correction_factor = cpi.gf_rate_correction_factor;
      }

      int projected_size_based_on_q = (int)((0.5 + rate_correction_factor * vp8_bits_per_mb[cpi.common.frame_type][Q]) * cpi.common.MBs / 512.0);
      if (cpi.mb.zbin_over_quant > 0) {
         int Z = cpi.mb.zbin_over_quant;
         double Factor = 0.99;
         double factor_adjustment = 3.90625E-5;

         while (Z > 0) {
            Z--;
            projected_size_based_on_q = (int)(Factor * projected_size_based_on_q);
            Factor += 3.90625E-5;
            if (Factor >= 0.999) {
               Factor = 0.999;
            }
         }
      }

      if (projected_size_based_on_q > 0) {
         correction_factor = 100 * cpi.projected_frame_size / projected_size_based_on_q;
      }
      double adjustment_limit = switch (damp_var) {
         case 0 -> 0.75;
         case 1 -> 0.375;
         default -> 0.25;
      };
      if (correction_factor > 102) {
         correction_factor = (int)(100.5 + (correction_factor - 100) * adjustment_limit);
         rate_correction_factor = rate_correction_factor * correction_factor / 100.0;
         if (rate_correction_factor > 50.0) {
            rate_correction_factor = 50.0;
         }
      } else if (correction_factor < 99) {
         correction_factor = (int)(100.5 - (100 - correction_factor) * adjustment_limit);
         rate_correction_factor = rate_correction_factor * correction_factor / 100.0;
         if (rate_correction_factor < 0.01) {
            rate_correction_factor = 0.01;
         }
      }

      if (cpi.common.frame_type == 0) {
         cpi.key_frame_rate_correction_factor = rate_correction_factor;
      } else if (cpi.oxcf.number_of_layers != 1 || cpi.gf_noboost_onepass_cbr || !cpi.common.refresh_alt_ref_frame && !cpi.common.refresh_golden_frame) {
         cpi.rate_correction_factor = rate_correction_factor;
      } else {
         cpi.gf_rate_correction_factor = rate_correction_factor;
      }
   }

   static int estimate_keyframe_frequency(Compressor cpi) {
      int av_key_frame_frequency = 0;
      if (cpi.key_frame_count == 1L) {
         int key_freq = cpi.oxcf.key_freq > 0 ? cpi.oxcf.key_freq : 1;
         av_key_frame_frequency = 1 + (int)cpi.output_framerate * 2;
         if (cpi.oxcf.auto_key && av_key_frame_frequency > key_freq) {
            av_key_frame_frequency = key_freq;
         }

         cpi.prior_key_frame_distance[4] = av_key_frame_frequency;
      } else {
         int total_weight = 0;
         int last_kf_interval = cpi.frames_since_key > 0 ? cpi.frames_since_key : 1;

         for (int i = 0; i < 5; i++) {
            if (i < 4) {
               cpi.prior_key_frame_distance[i] = cpi.prior_key_frame_distance[i + 1];
            } else {
               cpi.prior_key_frame_distance[i] = last_kf_interval;
            }

            av_key_frame_frequency += prior_key_frame_weight[i] * cpi.prior_key_frame_distance[i];
            total_weight += prior_key_frame_weight[i];
         }

         av_key_frame_frequency /= total_weight;
      }

      if (av_key_frame_frequency == 0) {
         av_key_frame_frequency = 1;
      }

      return av_key_frame_frequency;
   }

   static void vp8_adjust_key_frame_context(Compressor cpi) {
      if (cpi.projected_frame_size > cpi.per_frame_bandwidth) {
         int overspend = cpi.projected_frame_size - cpi.per_frame_bandwidth;
         if (cpi.oxcf.number_of_layers > 1) {
            cpi.kf_overspend_bits += overspend;
         } else {
            cpi.kf_overspend_bits += overspend * 7 / 8;
            cpi.gf_overspend_bits += overspend / 8;
         }

         cpi.kf_bitrate_adjustment = cpi.kf_overspend_bits / estimate_keyframe_frequency(cpi);
      }

      cpi.frames_since_key = 0;
      cpi.key_frame_count++;
   }

   static class FrameLimits {
      int frame_under_shoot_limit;
      int frame_over_shoot_limit;
   }
}
