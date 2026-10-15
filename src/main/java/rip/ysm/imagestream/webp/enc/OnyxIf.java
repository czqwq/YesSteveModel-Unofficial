package rip.ysm.imagestream.webp.enc;

import java.util.Arrays;
import java.util.EnumSet;
import java.util.Objects;

final class OnyxIf {
   static final int[] thresh_mult_map_znn = new int[]{0, GOOD(2), 1500, GOOD(3), 2000, RT(0), 1000, RT(2), 2000, Integer.MAX_VALUE};
   static final int[] thresh_mult_map_vhpred = new int[]{
      1000, GOOD(2), 1500, GOOD(3), 2000, RT(0), 1000, RT(1), 2000, RT(7), Integer.MAX_VALUE, Integer.MAX_VALUE
   };
   static final int[] thresh_mult_map_bpred = new int[]{
      2000, GOOD(0), 2500, GOOD(2), 5000, GOOD(3), 7500, RT(0), 2500, RT(1), 5000, RT(6), Integer.MAX_VALUE, Integer.MAX_VALUE
   };
   static final int[] thresh_mult_map_tm = new int[]{
      1000, GOOD(2), 1500, GOOD(3), 2000, RT(0), 0, RT(1), 1000, RT(2), 2000, RT(7), Integer.MAX_VALUE, Integer.MAX_VALUE
   };
   static final int[] thresh_mult_map_new1 = new int[]{1000, GOOD(2), 2000, RT(0), 2000, Integer.MAX_VALUE};
   static final int[] thresh_mult_map_new2 = new int[]{
      1000, GOOD(2), 2000, GOOD(3), 2500, GOOD(5), 4000, RT(0), 2000, RT(2), 2500, RT(5), 4000, Integer.MAX_VALUE
   };
   static final int[] thresh_mult_map_split1 = new int[]{
      2500,
      GOOD(0),
      1700,
      GOOD(2),
      10000,
      GOOD(3),
      25000,
      GOOD(4),
      Integer.MAX_VALUE,
      RT(0),
      5000,
      RT(1),
      10000,
      RT(2),
      25000,
      RT(3),
      Integer.MAX_VALUE,
      Integer.MAX_VALUE
   };
   static final int[] thresh_mult_map_split2 = new int[]{
      5000,
      GOOD(0),
      4500,
      GOOD(2),
      20000,
      GOOD(3),
      50000,
      GOOD(4),
      Integer.MAX_VALUE,
      RT(0),
      10000,
      RT(1),
      20000,
      RT(2),
      50000,
      RT(3),
      Integer.MAX_VALUE,
      Integer.MAX_VALUE
   };
   static final int[] mode_check_freq_map_zn2 = new int[]{0, RT(10), 2, RT(11), 4, RT(12), 8, Integer.MAX_VALUE};
   static final int[] mode_check_freq_map_vhbpred = new int[]{0, GOOD(5), 2, RT(0), 0, RT(3), 2, RT(5), 4, Integer.MAX_VALUE};
   static final int[] mode_check_freq_map_near2 = new int[]{0, GOOD(5), 2, RT(0), 0, RT(3), 2, RT(10), 4, RT(11), 8, RT(12), 16, Integer.MAX_VALUE};
   static final int[] mode_check_freq_map_new1 = new int[]{0, RT(10), 2, RT(11), 4, RT(12), 8, Integer.MAX_VALUE};
   static final int[] mode_check_freq_map_new2 = new int[]{0, GOOD(5), 4, RT(0), 0, RT(3), 4, RT(10), 8, RT(11), 16, RT(12), 32, Integer.MAX_VALUE};
   static final int[] mode_check_freq_map_split1 = new int[]{0, GOOD(2), 2, GOOD(3), 7, RT(1), 2, RT(2), 7, Integer.MAX_VALUE};
   static final int[] mode_check_freq_map_split2 = new int[]{0, GOOD(1), 2, GOOD(2), 4, GOOD(3), 15, RT(1), 4, RT(2), 15, Integer.MAX_VALUE};
   static final short[] q_trans = new short[]{
      0,
      1,
      2,
      3,
      4,
      5,
      7,
      8,
      9,
      10,
      12,
      13,
      15,
      17,
      18,
      19,
      20,
      21,
      23,
      24,
      25,
      26,
      27,
      28,
      29,
      30,
      31,
      33,
      35,
      37,
      39,
      41,
      43,
      45,
      47,
      49,
      51,
      53,
      55,
      57,
      59,
      61,
      64,
      67,
      70,
      73,
      76,
      79,
      82,
      85,
      88,
      91,
      94,
      97,
      100,
      103,
      106,
      109,
      112,
      115,
      118,
      121,
      124,
      127
   };
   private static final double MAX_PSNR = 100.0;
   static final int VPX_TS_MAX_LAYERS = 5;
   static final int VPX_TS_MAX_PERIODICITY = 16;

   private OnyxIf() {
   }

   static int GOOD(int x) {
      return x + 1;
   }

   static int RT(int x) {
      return x + 7;
   }

   static int speed_map(int speed, int[] map) {
      int idx = 0;

      int res;
      do {
         res = map[idx++];
      } while (speed >= map[idx++]);

      return res;
   }

   static void vp8_set_speed_features(Compressor cpi) {
      SpeedFeatures sf = cpi.sf;
      int Mode = cpi.compressor_speed;
      int Speed = cpi.Speed;
      CommonData cm = cpi.common;

      for (int i = 0; i < 20; i++) {
         cpi.mode_check_freq[i] = 0;
      }

      sf.RD = true;
      sf.search_method = SearchMethods.NSTEP;
      sf.improved_quant = true;
      sf.auto_filter = true;
      sf.recode_loop = 1;
      sf.quarter_pixel_search = true;
      sf.half_pixel_search = true;
      sf.iterative_sub_pixel = true;
      sf.optimize_coefficients = true;
      sf.use_fastquant_for_pick = false;
      sf.no_skip_block4x4_search = true;
      sf.first_step = 0;
      sf.max_step_search_steps = 8;
      sf.improved_mv_pred = true;

      for (int var8 = 0; var8 < 20; var8++) {
         sf.thresh_mult[var8] = 0;
      }

      int ref_frames = 1;
      if (cpi.ref_frame_flags.contains(MVReferenceFrame.LAST_FRAME)) {
         ref_frames++;
      }

      if (cpi.ref_frame_flags.contains(MVReferenceFrame.GOLDEN_FRAME)) {
         ref_frames++;
      }

      if (cpi.ref_frame_flags.contains(MVReferenceFrame.ALTREF_FRAME)) {
         ref_frames++;
      }

      if (Mode == 0) {
         Speed = 0;
      } else if (Mode == 2) {
         Speed = RT(Speed);
      } else {
         if (Speed > 5) {
            Speed = 5;
         }

         Speed = GOOD(Speed);
      }

      sf.thresh_mult[ThrModes.THR_ZERO1.ordinal()] = sf.thresh_mult[ThrModes.THR_NEAREST1.ordinal()] = sf.thresh_mult[ThrModes.THR_NEAR1.ordinal()] = sf.thresh_mult[ThrModes.THR_DC
         .ordinal()] = 0;
      sf.thresh_mult[ThrModes.THR_ZERO2.ordinal()] = sf.thresh_mult[ThrModes.THR_ZERO3.ordinal()] = sf.thresh_mult[ThrModes.THR_NEAREST2.ordinal()] = sf.thresh_mult[ThrModes.THR_NEAREST3
         .ordinal()] = sf.thresh_mult[ThrModes.THR_NEAR2.ordinal()] = sf.thresh_mult[ThrModes.THR_NEAR3.ordinal()] = speed_map(Speed, thresh_mult_map_znn);
      sf.thresh_mult[ThrModes.THR_V_PRED.ordinal()] = sf.thresh_mult[ThrModes.THR_H_PRED.ordinal()] = speed_map(Speed, thresh_mult_map_vhpred);
      sf.thresh_mult[ThrModes.THR_B_PRED.ordinal()] = speed_map(Speed, thresh_mult_map_bpred);
      sf.thresh_mult[ThrModes.THR_TM.ordinal()] = speed_map(Speed, thresh_mult_map_tm);
      sf.thresh_mult[ThrModes.THR_NEW1.ordinal()] = speed_map(Speed, thresh_mult_map_new1);
      sf.thresh_mult[ThrModes.THR_NEW2.ordinal()] = sf.thresh_mult[ThrModes.THR_NEW3.ordinal()] = speed_map(Speed, thresh_mult_map_new2);
      sf.thresh_mult[ThrModes.THR_SPLIT1.ordinal()] = speed_map(Speed, thresh_mult_map_split1);
      sf.thresh_mult[ThrModes.THR_SPLIT2.ordinal()] = sf.thresh_mult[ThrModes.THR_SPLIT3.ordinal()] = speed_map(Speed, thresh_mult_map_split2);
      if (cpi.Speed <= 6
         && cpi.oxcf.number_of_layers > 1
         && cpi.ref_frame_flags.contains(MVReferenceFrame.LAST_FRAME)
         && cpi.ref_frame_flags.contains(MVReferenceFrame.GOLDEN_FRAME)) {
         if (cpi.closest_reference_frame == MVReferenceFrame.GOLDEN_FRAME) {
            sf.thresh_mult[ThrModes.THR_ZERO2.ordinal()] >>= 3;
            sf.thresh_mult[ThrModes.THR_NEAREST2.ordinal()] >>= 3;
            sf.thresh_mult[ThrModes.THR_NEAR2.ordinal()] >>= 3;
         } else {
            sf.thresh_mult[ThrModes.THR_ZERO2.ordinal()] >>= 1;
            sf.thresh_mult[ThrModes.THR_NEAREST2.ordinal()] >>= 1;
            sf.thresh_mult[ThrModes.THR_NEAR2.ordinal()] >>= 1;
         }
      }

      speedFeatureRest(cpi, sf, Mode, Speed, cm, ref_frames);
   }

   private static void speedFeatureRest(Compressor cpi, SpeedFeatures sf, int Mode, int Speed, CommonData cm, int ref_frames) {
      cpi.mode_check_freq[ThrModes.THR_ZERO1.ordinal()] = cpi.mode_check_freq[ThrModes.THR_NEAREST1.ordinal()] = cpi.mode_check_freq[ThrModes.THR_NEAR1
         .ordinal()] = cpi.mode_check_freq[ThrModes.THR_TM.ordinal()] = cpi.mode_check_freq[ThrModes.THR_DC.ordinal()] = 0;
      cpi.mode_check_freq[ThrModes.THR_ZERO2.ordinal()] = cpi.mode_check_freq[ThrModes.THR_ZERO3.ordinal()] = cpi.mode_check_freq[ThrModes.THR_NEAREST2
         .ordinal()] = cpi.mode_check_freq[ThrModes.THR_NEAREST3.ordinal()] = speed_map(Speed, mode_check_freq_map_zn2);
      cpi.mode_check_freq[ThrModes.THR_NEAR2.ordinal()] = cpi.mode_check_freq[ThrModes.THR_NEAR3.ordinal()] = speed_map(Speed, mode_check_freq_map_near2);
      cpi.mode_check_freq[ThrModes.THR_V_PRED.ordinal()] = cpi.mode_check_freq[ThrModes.THR_H_PRED.ordinal()] = cpi.mode_check_freq[ThrModes.THR_B_PRED
         .ordinal()] = speed_map(Speed, mode_check_freq_map_vhbpred);
      int Speed2 = Speed;
      if (cpi.Speed == 10 && Mode == 2) {
         Speed2 = RT(9);
      }

      cpi.mode_check_freq[ThrModes.THR_NEW1.ordinal()] = speed_map(Speed2, mode_check_freq_map_new1);
      cpi.mode_check_freq[ThrModes.THR_NEW2.ordinal()] = cpi.mode_check_freq[ThrModes.THR_NEW3.ordinal()] = speed_map(Speed, mode_check_freq_map_new2);
      cpi.mode_check_freq[ThrModes.THR_SPLIT1.ordinal()] = speed_map(Speed, mode_check_freq_map_split1);
      cpi.mode_check_freq[ThrModes.THR_SPLIT2.ordinal()] = cpi.mode_check_freq[ThrModes.THR_SPLIT3.ordinal()] = speed_map(Speed, mode_check_freq_map_split2);
      Speed = cpi.Speed;
      extractMode(cpi, sf, Mode, Speed, cm, ref_frames);
      cpi.mb.changeFNs(cpi);
      if (cpi.sf.iterative_sub_pixel) {
         cpi.find_fractional_mv_step = MComp.vp8_find_best_sub_pixel_step_iteratively;
      } else if (cpi.sf.quarter_pixel_search) {
         cpi.find_fractional_mv_step = MComp.vp8_find_best_sub_pixel_step;
      } else if (cpi.sf.half_pixel_search) {
         cpi.find_fractional_mv_step = MComp.vp8_find_best_half_pixel_step;
      } else {
         cpi.find_fractional_mv_step = MComp.vp8_skip_fractional_mv_step;
      }

      if (cpi.common.full_pixel) {
         cpi.find_fractional_mv_step = MComp.vp8_skip_fractional_mv_step;
      }
   }

   private static void extractMode(Compressor cpi, SpeedFeatures sf, int Mode, int Speed, CommonData cm, int ref_frames) {
      switch (Mode) {
         case 0:
            sf.first_step = 0;
            sf.max_step_search_steps = 8;
            break;
         case 1:
         case 3:
            if (Speed > 0) {
               sf.optimize_coefficients = false;
               sf.use_fastquant_for_pick = true;
               sf.no_skip_block4x4_search = false;
               sf.first_step = 1;
            }

            if (Speed > 2) {
               sf.improved_quant = false;
               sf.recode_loop = 2;
            }

            if (Speed > 3) {
               sf.auto_filter = true;
               sf.recode_loop = 0;
               sf.RD = false;
            }

            if (Speed > 4) {
               sf.auto_filter = false;
            }
            break;
         case 2:
            sf.optimize_coefficients = false;
            sf.recode_loop = 0;
            sf.auto_filter = true;
            sf.iterative_sub_pixel = true;
            sf.search_method = SearchMethods.NSTEP;
            if (Speed > 0) {
               sf.improved_quant = false;
               sf.use_fastquant_for_pick = true;
               sf.no_skip_block4x4_search = false;
               sf.first_step = 1;
            }

            if (Speed > 2) {
               sf.auto_filter = false;
            }

            if (Speed > 3) {
               sf.RD = false;
               sf.auto_filter = true;
            }

            if (Speed > 4) {
               sf.auto_filter = false;
               sf.search_method = SearchMethods.HEX;
               sf.iterative_sub_pixel = false;
            }

            if (Speed > 6) {
               int sum = 0;
               int total_mbs = cm.MBs;
               int min = Math.max(cpi.oxcf.encode_breakout, 2000);
               min >>= 7;

               int i;
               for (i = 0; i < min; i++) {
                  sum += cpi.mb.error_bins[i];
               }

               int total_skip = sum;

               for (int var13 = 0; i < 1024; i++) {
                  var13 += cpi.mb.error_bins[i];
                  if (10 * var13 >= (cpi.Speed - 6) * (total_mbs - total_skip)) {
                     break;
                  }
               }

               int thresh = --i << 7;
               if (thresh < 2000) {
                  thresh = 2000;
               }

               if (ref_frames > 1) {
                  sf.thresh_mult[ThrModes.THR_NEW1.ordinal()] = thresh;
                  sf.thresh_mult[ThrModes.THR_NEAREST1.ordinal()] = thresh >> 1;
                  sf.thresh_mult[ThrModes.THR_NEAR1.ordinal()] = thresh >> 1;
               }

               if (ref_frames > 2) {
                  sf.thresh_mult[ThrModes.THR_NEW2.ordinal()] = thresh << 1;
                  sf.thresh_mult[ThrModes.THR_NEAREST2.ordinal()] = thresh;
                  sf.thresh_mult[ThrModes.THR_NEAR2.ordinal()] = thresh;
               }

               if (ref_frames > 3) {
                  sf.thresh_mult[ThrModes.THR_NEW3.ordinal()] = thresh << 1;
                  sf.thresh_mult[ThrModes.THR_NEAREST3.ordinal()] = thresh;
                  sf.thresh_mult[ThrModes.THR_NEAR3.ordinal()] = thresh;
               }

               sf.improved_mv_pred = false;
            }

            if (Speed > 8) {
               sf.quarter_pixel_search = false;
            }

            if (cm.getVersion() == 0) {
               cm.filter_type = 0;
               if (Speed >= 14) {
                  cm.filter_type = 1;
               }
            } else {
               cm.filter_type = 1;
            }

            if (Speed >= 15) {
               sf.half_pixel_search = false;
            }

            CUtils.vp8_zero(cpi.mb.error_bins);
      }
   }

   static void save_layer_context(Compressor cpi) {
      LayerContext lc = cpi.layer_context[cpi.current_layer];
      lc.target_bandwidth = cpi.target_bandwidth;
      lc.starting_buffer_level = cpi.oxcf.starting_buffer_level;
      lc.optimal_buffer_level = cpi.oxcf.optimal_buffer_level;
      lc.maximum_buffer_size = cpi.oxcf.maximum_buffer_size;
      lc.starting_buffer_level_in_ms = cpi.oxcf.starting_buffer_level_in_ms;
      lc.optimal_buffer_level_in_ms = cpi.oxcf.optimal_buffer_level_in_ms;
      lc.maximum_buffer_size_in_ms = cpi.oxcf.maximum_buffer_size_in_ms;
      lc.buffer_level = cpi.buffer_level;
      lc.bits_off_target = cpi.bits_off_target;
      lc.total_actual_bits = cpi.total_actual_bits;
      lc.active_worst_quality = cpi.active_worst_quality;
      lc.active_best_quality = cpi.active_best_quality;
      lc.ni_av_qi = cpi.ni_av_qi;
      lc.ni_tot_qi = cpi.ni_tot_qi;
      lc.ni_frames = cpi.ni_frames;
      lc.avg_frame_qindex = cpi.avg_frame_qindex;
      lc.rate_correction_factor = cpi.rate_correction_factor;
      lc.key_frame_rate_correction_factor = cpi.key_frame_rate_correction_factor;
      lc.gf_rate_correction_factor = cpi.gf_rate_correction_factor;
      lc.zbin_over_quant = cpi.mb.zbin_over_quant;
      lc.inter_frame_target = cpi.inter_frame_target;
      lc.total_byte_count = cpi.total_byte_count;
      lc.filter_level = cpi.common.filter_level;
      lc.frames_since_last_drop_overshoot = cpi.frames_since_last_drop_overshoot;
      lc.force_maxqp = cpi.force_maxqp;
      lc.last_frame_percent_intra = cpi.last_frame_percent_intra;
      lc.last_q[0] = cpi.last_q[0];
      lc.last_q[1] = cpi.last_q[1];
      lc.count_mb_ref_frame_usage.clear();
      lc.count_mb_ref_frame_usage.putAll(cpi.mb.count_mb_ref_frame_usage);
   }

   static void restore_layer_context(Compressor cpi, int layer) {
      LayerContext lc = cpi.layer_context[layer];
      cpi.current_layer = layer;
      cpi.target_bandwidth = lc.target_bandwidth;
      cpi.oxcf.target_bandwidth = lc.target_bandwidth;
      cpi.oxcf.starting_buffer_level = lc.starting_buffer_level;
      cpi.oxcf.optimal_buffer_level = lc.optimal_buffer_level;
      cpi.oxcf.maximum_buffer_size = lc.maximum_buffer_size;
      cpi.oxcf.starting_buffer_level_in_ms = lc.starting_buffer_level_in_ms;
      cpi.oxcf.optimal_buffer_level_in_ms = lc.optimal_buffer_level_in_ms;
      cpi.oxcf.maximum_buffer_size_in_ms = lc.maximum_buffer_size_in_ms;
      cpi.buffer_level = lc.buffer_level;
      cpi.bits_off_target = lc.bits_off_target;
      cpi.total_actual_bits = lc.total_actual_bits;
      cpi.active_worst_quality = lc.active_worst_quality;
      cpi.active_best_quality = lc.active_best_quality;
      cpi.ni_av_qi = lc.ni_av_qi;
      cpi.ni_tot_qi = lc.ni_tot_qi;
      cpi.ni_frames = lc.ni_frames;
      cpi.avg_frame_qindex = lc.avg_frame_qindex;
      cpi.rate_correction_factor = lc.rate_correction_factor;
      cpi.key_frame_rate_correction_factor = lc.key_frame_rate_correction_factor;
      cpi.gf_rate_correction_factor = lc.gf_rate_correction_factor;
      cpi.mb.zbin_over_quant = lc.zbin_over_quant;
      cpi.inter_frame_target = lc.inter_frame_target;
      cpi.total_byte_count = lc.total_byte_count;
      cpi.common.filter_level = lc.filter_level;
      cpi.frames_since_last_drop_overshoot = lc.frames_since_last_drop_overshoot;
      cpi.force_maxqp = lc.force_maxqp;
      cpi.last_frame_percent_intra = lc.last_frame_percent_intra;
      cpi.last_q[0] = lc.last_q[0];
      cpi.last_q[1] = lc.last_q[1];
      cpi.mb.count_mb_ref_frame_usage.clear();
      cpi.mb.count_mb_ref_frame_usage.putAll(lc.count_mb_ref_frame_usage);
   }

   static int rescale(int val, int num) {
      return val * num / 1000;
   }

   static void reset_temporal_layer_change(Compressor cpi, int prev_num_layers) {
      double prev_layer_framerate = 0.0;
      int curr_num_layers = cpi.oxcf.number_of_layers;
      if (prev_num_layers == 1) {
         cpi.current_layer = 0;
         save_layer_context(cpi);
      }

      for (int i = 0; i < curr_num_layers; i++) {
         LayerContext lc = cpi.layer_context[i];
         if (i >= prev_num_layers) {
            cpi.init_temporal_layer_context(i, prev_layer_framerate);
         }

         lc.buffer_level = cpi.oxcf.starting_buffer_level_in_ms * cpi.oxcf.target_bitrate[i];
         lc.bits_off_target = lc.buffer_level;
         if (curr_num_layers == 1) {
            lc.target_bandwidth = cpi.oxcf.target_bandwidth;
            lc.buffer_level = cpi.oxcf.starting_buffer_level_in_ms * lc.target_bandwidth / 1000L;
            lc.bits_off_target = lc.buffer_level;
            restore_layer_context(cpi, 0);
         }

         prev_layer_framerate = cpi.output_framerate / cpi.oxcf.rate_decimator[i];
      }
   }

   static void enable_segmentation(Compressor cpi) {
      cpi.mb.e_mbd.segmentation_enabled = 1;
      cpi.mb.e_mbd.update_mb_segmentation_map = true;
      cpi.mb.e_mbd.update_mb_segmentation_data = true;
   }

   static void disable_segmentation(Compressor cpi) {
      cpi.mb.e_mbd.segmentation_enabled = 0;
   }

   static void set_segment_data(Compressor cpi, short[][] feature_data) {
      cpi.mb.e_mbd.mb_segement_abs_delta = false;
      CUtils.vp8_copy(feature_data, cpi.segment_feature_data);
   }

   static void dealloc_raw_frame_buffers(Compressor cpi) {
      cpi.alt_ref_buffer = null;
      if (cpi.lookahead != null) {
         cpi.lookahead.vp8_lookahead_destroy();
      }
   }

   static void cyclic_background_refresh(Compressor cpi, short Q) {
      int[] seg_map = cpi.segmentation_map;
      short[][] feature_data = new short[MBLvlFeatures.featureCount][4];
      int block_count = cpi.cyclic_refresh_mode_max_mbs_perframe;
      int mbs_in_frame = cpi.common.mb_rows * cpi.common.mb_cols;
      cpi.cyclic_refresh_q = Q / 2;
      if (cpi.oxcf.screen_content_mode != 0) {
         int qp_thresh = cpi.oxcf.screen_content_mode == 2 ? 80 : 100;
         if (Q >= qp_thresh) {
            cpi.cyclic_refresh_mode_max_mbs_perframe = cpi.common.mb_rows * cpi.common.mb_cols / 10;
         } else if (cpi.frames_since_key > 250 && Q < 20 && cpi.mb.skip_true_count > (int)(0.95 * mbs_in_frame)) {
            cpi.cyclic_refresh_mode_max_mbs_perframe = 0;
         } else {
            cpi.cyclic_refresh_mode_max_mbs_perframe = cpi.common.mb_rows * cpi.common.mb_cols / 20;
         }

         block_count = cpi.cyclic_refresh_mode_max_mbs_perframe;
      }

      Arrays.fill(cpi.segmentation_map, 0, 0, mbs_in_frame);
      if (cpi.common.frame_type != 0 && block_count > 0) {
         int i = cpi.cyclic_refresh_mode_index;

         do {
            if (cpi.cyclic_refresh_map[i] == 0) {
               seg_map[i] = 1;
               block_count--;
            } else if (cpi.cyclic_refresh_map[i] < 0) {
               cpi.cyclic_refresh_map[i]++;
            }

            if (++i == mbs_in_frame) {
               i = 0;
            }
         } while (block_count != 0 && i != cpi.cyclic_refresh_mode_index);

         cpi.cyclic_refresh_mode_index = i;
      }

      cpi.mb.e_mbd.update_mb_segmentation_map = true;
      cpi.mb.e_mbd.update_mb_segmentation_data = true;
      enable_segmentation(cpi);
      feature_data[MBLvlFeatures.ALT_Q.ordinal()][0] = 0;
      feature_data[MBLvlFeatures.ALT_Q.ordinal()][1] = (short)(cpi.cyclic_refresh_q - Q);
      feature_data[MBLvlFeatures.ALT_Q.ordinal()][2] = 0;
      feature_data[MBLvlFeatures.ALT_Q.ordinal()][3] = 0;
      feature_data[MBLvlFeatures.ALT_LF.ordinal()][0] = 0;
      feature_data[MBLvlFeatures.ALT_LF.ordinal()][1] = 0;
      feature_data[MBLvlFeatures.ALT_LF.ordinal()][2] = 0;
      feature_data[MBLvlFeatures.ALT_LF.ordinal()][3] = 0;
      set_segment_data(cpi, feature_data);
   }

   static void compute_skin_map(Compressor cpi) {
      CommonData cm = cpi.common;
      FullGetSetPointer src_y = cpi.sourceYV12.y_buffer.shallowCopy();
      FullGetSetPointer src_u = cpi.sourceYV12.u_buffer.shallowCopy();
      FullGetSetPointer src_v = cpi.sourceYV12.v_buffer.shallowCopy();
      int src_ystride = cpi.sourceYV12.y_stride;
      int src_uvstride = cpi.sourceYV12.uv_stride;
      SkinDetectionBlockSize bsize = cm.Width * cm.Height <= 101376 ? SkinDetectionBlockSize.SKIN_8x8 : SkinDetectionBlockSize.SKIN_16x16;

      for (int mb_row = 0; mb_row < cm.mb_rows; mb_row++) {
         int num_bl = 0;

         for (int mb_col = 0; mb_col < cm.mb_cols; mb_col++) {
            int bl_index = mb_row * cm.mb_cols + mb_col;
            cpi.skin_map[bl_index] = SkinDetect.vp8_compute_skin_block(src_y, src_u, src_v, src_ystride, src_uvstride, bsize, cpi.consec_zero_last[bl_index], 0);
            num_bl++;
            src_y.incBy(16);
            src_u.incBy(8);
            src_v.incBy(8);
         }

         src_y.incBy((src_ystride << 4) - (num_bl << 4));
         src_u.incBy((src_uvstride << 3) - (num_bl << 3));
         src_v.incBy((src_uvstride << 3) - (num_bl << 3));
      }

      for (int var17 = 1; var17 < cm.mb_rows - 1; var17++) {
         for (int mb_col = 1; mb_col < cm.mb_cols - 1; mb_col++) {
            int bl_index = var17 * cm.mb_cols + mb_col;
            int num_neighbor = 0;
            int non_skin_threshold = 8;

            for (int mi = -1; mi <= 1; mi++) {
               for (int mj = -1; mj <= 1; mj++) {
                  int bl_neighbor_index = (var17 + mi) * cm.mb_cols + mb_col + mj;
                  if (cpi.skin_map[bl_neighbor_index]) {
                     num_neighbor++;
                  }
               }
            }

            if (cpi.skin_map[bl_index] && num_neighbor < 2) {
               cpi.skin_map[bl_index] = false;
            }

            if (!cpi.skin_map[bl_index] && num_neighbor == 8) {
               cpi.skin_map[bl_index] = true;
            }
         }
      }
   }

   static void alloc_raw_frame_buffers(Compressor cpi) {
      int width = cpi.oxcf.Width + 15 & -16;
      int height = cpi.oxcf.Height + 15 & -16;
      cpi.lookahead = new Lookahead(cpi.oxcf.Width, cpi.oxcf.Height, cpi.oxcf.lag_in_frames);
      cpi.alt_ref_buffer = new YV12buffer(width, height);
   }

   static void vp8_alloc_compressor_data(Compressor cpi) {
      CommonData cm = cpi.common;
      int width = cm.Width;
      int height = cm.Height;
      cm.vp8_alloc_frame_buffers(width, height);
      if ((width & 15) != 0) {
         width += 16 - (width & 15);
      }

      if ((height & 15) != 0) {
         height += 16 - (height & 15);
      }

      cpi.pick_lf_lvl_frame = new YV12buffer(width, height);
      int tokens = cm.mb_rows * cm.mb_cols * 24 * 16;
      cpi.tok = new FullGenArrPointer<>(tokens);

      for (int i = 0; i < tokens; i++) {
         cpi.tok.setRel(i, null);
      }

      cpi.zeromv_count = 0;
      cpi.gf_active_flags = new FullGetSetPointer(cm.mb_rows * cm.mb_cols);
      cpi.gf_active_count = cm.mb_rows * cm.mb_cols;
      cpi.mb_activity_map = new FullGetSetPointer(cpi.gf_active_count);
      cpi.lfmv = new MV[(cm.mb_rows + 2) * (cm.mb_cols + 2)];

      for (int i = 0; i < cpi.lfmv.length; i++) {
         cpi.lfmv[i] = new MV();
      }

      cpi.lf_ref_frame_sign_bias = new boolean[cpi.lfmv.length];
      cpi.lf_ref_frame = new MVReferenceFrame[cpi.lfmv.length];
      cpi.segmentation_map = new int[cpi.gf_active_count];
      cpi.cyclic_refresh_mode_index = 0;
      cpi.active_map = new FullGetSetPointer(cpi.gf_active_count);
      cpi.active_map.memset(0, (short)1, cpi.gf_active_count);
      cpi.tplist = new TokenList[cm.mb_rows];

      for (int i = 0; i < cm.mb_rows; i++) {
         cpi.tplist[i] = new TokenList();
      }
   }

   static void update_layer_contexts(Compressor cpi) {
      Config oxcf = cpi.oxcf;
      if (oxcf.number_of_layers > 1) {
         double prev_layer_framerate = 0.0;

         for (int i = 0; i < oxcf.number_of_layers; i++) {
            LayerContext lc = cpi.layer_context[i];
            lc.framerate = cpi.ref_framerate / oxcf.rate_decimator[i];
            lc.target_bandwidth = oxcf.target_bitrate[i] * 1000;
            lc.starting_buffer_level = rescale((int)oxcf.starting_buffer_level_in_ms, lc.target_bandwidth);
            if (oxcf.optimal_buffer_level == 0L) {
               lc.optimal_buffer_level = lc.target_bandwidth / 8;
            } else {
               lc.optimal_buffer_level = rescale((int)oxcf.optimal_buffer_level_in_ms, lc.target_bandwidth);
            }

            if (oxcf.maximum_buffer_size == 0L) {
               lc.maximum_buffer_size = lc.target_bandwidth / 8;
            } else {
               lc.maximum_buffer_size = rescale((int)oxcf.maximum_buffer_size_in_ms, lc.target_bandwidth);
            }

            if (i > 0) {
               lc.avg_frame_size_for_layer = (int)((oxcf.target_bitrate[i] - oxcf.target_bitrate[i - 1]) * 1000 / (lc.framerate - prev_layer_framerate));
            }

            prev_layer_framerate = lc.framerate;
         }
      }
   }

   static double log2f(double x) {
      double log2e = 0.6931471805599453;
      return Math.log(x) / 0.6931471805599453;
   }

   static long calc_plane_error(FullGetSetPointer orig, int orig_stride, FullGetSetPointer recon, int recon_stride, int cols, int rows) {
      long total_sse = 0L;
      orig = orig.shallowCopy();
      recon = recon.shallowCopy();
      VarianceResults sse = new VarianceResults();

      int row;
      for (row = 0; row + 16 <= rows; row += 16) {
         int col;
         for (col = 0; col + 16 <= cols; col += 16) {
            orig.incBy(col);
            recon.incBy(col);
            Variance.vpx_mse16x16.call(orig, orig_stride, recon, recon_stride, sse);
            orig.incBy(-col);
            recon.incBy(-col);
            total_sse += sse.sse;
         }

         if (col < cols) {
            int bordOrigPos = orig.getPos();
            int bordRecPos = recon.getPos();

            for (int border_row = 0; border_row < 16; border_row++) {
               for (int border_col = col; border_col < cols; border_col++) {
                  int diff = orig.getRel(border_col) - recon.getRel(border_col);
                  total_sse += (long)diff * diff;
               }

               orig.incBy(orig_stride);
               recon.incBy(recon_stride);
            }

            orig.setPos(bordOrigPos);
            recon.setPos(bordRecPos);
         }

         orig.incBy(orig_stride * 16);
         recon.incBy(recon_stride * 16);
      }

      while (row < rows) {
         for (int colx = 0; colx < cols; colx++) {
            int diff = orig.getRel(colx) - recon.getRel(colx);
            total_sse += (long)diff * diff;
         }

         orig.incBy(orig_stride);
         recon.incBy(recon_stride);
         row++;
      }

      return total_sse;
   }

   private static double vpx_sse_to_psnr(double samples, double sse) {
      if (sse > 0.0) {
         double psnr = 10.0 * Math.log10(samples * 255.0 * 255.0 / sse);
         return Math.min(psnr, 100.0);
      } else {
         return 100.0;
      }
   }

   static void generate_psnr_packet(Compressor cpi) {
      YV12buffer orig = cpi.sourceYV12;
      YV12buffer recon = cpi.common.frame_to_show;
      CodecPkt pkt = new CodecPkt();
      int width = cpi.common.Width;
      int height = cpi.common.Height;
      pkt.kind = 1;
      long sse = calc_plane_error(orig.y_buffer, orig.y_stride, recon.y_buffer, recon.y_stride, width, height);
      CodecPkt.PSNRPacket psnrp = new CodecPkt.PSNRPacket();
      psnrp.sse[0] = sse;
      psnrp.sse[1] = sse;
      psnrp.samples[0] = width * height;
      psnrp.samples[1] = width * height;
      width = (width + 1) / 2;
      height = (height + 1) / 2;
      sse = calc_plane_error(orig.u_buffer, orig.uv_stride, recon.u_buffer, recon.uv_stride, width, height);
      psnrp.sse[0] = psnrp.sse[0] + sse;
      psnrp.sse[2] = sse;
      psnrp.samples[0] = psnrp.samples[0] + width * height;
      psnrp.samples[2] = width * height;
      sse = calc_plane_error(orig.v_buffer, orig.uv_stride, recon.v_buffer, recon.uv_stride, width, height);
      psnrp.sse[0] = psnrp.sse[0] + sse;
      psnrp.sse[3] = sse;
      psnrp.samples[0] = psnrp.samples[0] + width * height;
      psnrp.samples[3] = width * height;

      for (int i = 0; i < 4; i++) {
         psnrp.psnr[i] = vpx_sse_to_psnr(psnrp.samples[i], psnrp.sse[i]);
      }

      pkt.packet = psnrp;
      cpi.output_pkt_list.add(pkt);
   }

   static void vp8_use_as_reference(Compressor cpi, EnumSet<MVReferenceFrame> ref_frame_flags) {
      cpi.ref_frame_flags = EnumSet.copyOf(ref_frame_flags);
   }

   static void vp8_update_reference(Compressor cpi, EnumSet<MVReferenceFrame> ref_frame_flags) {
      cpi.common.refresh_golden_frame = false;
      cpi.common.refresh_alt_ref_frame = false;
      cpi.common.refresh_last_frame = ref_frame_flags.contains(MVReferenceFrame.LAST_FRAME);
      if (ref_frame_flags.contains(MVReferenceFrame.GOLDEN_FRAME)) {
         cpi.common.refresh_golden_frame = true;
      }

      if (ref_frame_flags.contains(MVReferenceFrame.ALTREF_FRAME)) {
         cpi.common.refresh_alt_ref_frame = true;
      }

      cpi.ext_refresh_frame_flags_pending = true;
   }

   static void vp8_update_entropy(Compressor cpi) {
      cpi.common.refresh_entropy_probs = false;
   }

   static void scale_and_extend_source(YV12buffer sd, Compressor cpi) {
      if (CommonData.horiz_scale == Scaling.NORMAL && CommonData.vert_scale == Scaling.NORMAL) {
         cpi.sourceYV12 = sd;
      }
   }

   static void update_rd_ref_frame_probs(Compressor cpi) {
      CommonData cm = cpi.common;
      ReferenceCounts rf = cpi.mb.sumReferenceCounts();
      if (cm.frame_type == 0) {
         cpi.prob_intra_coded = 255;
         cpi.prob_last_coded = 128;
         cpi.prob_gf_coded = 128;
      } else if (rf.total == 0) {
         cpi.prob_intra_coded = 63;
         cpi.prob_last_coded = 128;
         cpi.prob_gf_coded = 128;
      }

      if (cpi.oxcf.number_of_layers == 1) {
         if (cpi.common.refresh_alt_ref_frame) {
            cpi.prob_intra_coded += 40;
            if (cpi.prob_intra_coded > 255) {
               cpi.prob_intra_coded = 255;
            }

            cpi.prob_last_coded = 200;
            cpi.prob_gf_coded = 1;
         } else if (cpi.frames_since_golden == 0) {
            cpi.prob_last_coded = 214;
         } else if (cpi.frames_since_golden == 1) {
            cpi.prob_last_coded = 192;
            cpi.prob_gf_coded = 220;
         } else if (cpi.source_alt_ref_active) {
            cpi.prob_gf_coded -= 20;
            if (cpi.prob_gf_coded < 10) {
               cpi.prob_gf_coded = 10;
            }
         }

         if (!cpi.source_alt_ref_active) {
            cpi.prob_gf_coded = 255;
         }
      }
   }

   static boolean decide_key_frame(Compressor cpi) {
      CommonData cm = cpi.common;
      if (cpi.Speed > 11) {
         return false;
      } else if (cpi.compressor_speed == 2 && cpi.Speed >= 5 && !cpi.sf.RD) {
         double change = 1.0 * Math.abs((int)(cpi.mb.intra_error - cpi.last_intra_error)) / (1L + cpi.last_intra_error);
         double change2 = 1.0 * Math.abs((int)(cpi.mb.prediction_error - cpi.last_prediction_error)) / (1L + cpi.last_prediction_error);
         double minerror = cm.MBs * 256;
         cpi.last_intra_error = cpi.mb.intra_error;
         cpi.last_prediction_error = cpi.mb.prediction_error;
         return 10L * cpi.mb.intra_error / (1L + cpi.mb.prediction_error) < 15L && cpi.mb.prediction_error > minerror && (change > 0.25 || change2 > 0.25);
      } else if ((cpi.this_frame_percent_intra != 100 || cpi.this_frame_percent_intra <= cpi.last_frame_percent_intra + 2)
         && (cpi.this_frame_percent_intra <= 95 || cpi.this_frame_percent_intra < cpi.last_frame_percent_intra + 5)) {
         return cpi.this_frame_percent_intra > 60 && cpi.this_frame_percent_intra > cpi.last_frame_percent_intra * 2
               || cpi.this_frame_percent_intra > 75 && cpi.this_frame_percent_intra > cpi.last_frame_percent_intra * 3 / 2
               || cpi.this_frame_percent_intra > 90 && cpi.this_frame_percent_intra > cpi.last_frame_percent_intra + 10
            ? !cm.refresh_golden_frame
            : false;
      } else {
         return true;
      }
   }

   static long vp8_calc_ss_err(FullGetSetPointer src, int systride, FullGetSetPointer dst, int dystride, int yheight, int ywidth) {
      long Total = 0L;
      VarianceResults sse = new VarianceResults();
      src = src.shallowCopy();
      dst = dst.shallowCopy();

      for (int i = 0; i < yheight; i += 16) {
         for (int j = 0; j < ywidth; j += 16) {
            src.incBy(j);
            dst.incBy(j);
            Variance.vpx_mse16x16.call(src, systride, dst, dystride, sse);
            Total += sse.sse;
            src.incBy(-j);
            dst.incBy(-j);
         }

         src.incBy(16 * systride);
         dst.incBy(16 * dystride);
      }

      return Total;
   }

   static long vp8_calc_ss_err(YV12buffer source, YV12buffer dest) {
      return vp8_calc_ss_err(source.y_buffer, source.y_stride, dest.y_buffer, dest.y_stride, source.y_height, source.y_width);
   }

   static boolean recode_loop_test(Compressor cpi, int high_limit, int low_limit, int q, int maxq, int minq) {
      boolean force_recode = false;
      CommonData cm = cpi.common;
      if (cpi.sf.recode_loop == 1 || cpi.sf.recode_loop == 2 && (cm.frame_type == 0 || cm.refresh_golden_frame || cm.refresh_alt_ref_frame)) {
         if ((cpi.projected_frame_size <= high_limit || q >= maxq) && (cpi.projected_frame_size >= low_limit || q <= minq)) {
            if (cpi.oxcf.end_usage == 2) {
               if (q > cpi.cq_target_quality && cpi.projected_frame_size < cpi.this_frame_target * 7 >> 3) {
                  force_recode = true;
               } else if (q > cpi.oxcf.cq_level && cpi.projected_frame_size < cpi.min_frame_bandwidth && cpi.active_best_quality > cpi.oxcf.cq_level) {
                  force_recode = true;
                  cpi.active_best_quality = cpi.oxcf.cq_level;
               }
            }
         } else {
            force_recode = true;
         }
      }

      return force_recode;
   }

   static int encode_frame_to_data_rate(Compressor cpi, FullGetSetPointer dest, EnumSet<FrameFlags> frame_flags) {
      RateCtrl.FrameLimits limits = new RateCtrl.FrameLimits();
      boolean active_worst_qchanged = false;
      CommonData cm = cpi.common;
      int zbin_oq_high = 0;
      int zbin_oq_low = 0;
      boolean overshoot_seen = false;
      boolean undershoot_seen = false;
      if (cpi.force_next_frame_intra) {
         cm.frame_type = 0;
         cpi.force_next_frame_intra = false;
      }

      cpi.per_frame_bandwidth = (int)(cpi.target_bandwidth / cpi.output_framerate);
      cm.copy_buffer_to_gf = 0;
      cm.copy_buffer_to_arf = 0;
      cpi.mb.zbin_over_quant = 0;
      cpi.mb.zbin_mode_boost = 0;
      cpi.common.ref_frame_sign_bias.put(MVReferenceFrame.ALTREF_FRAME, cpi.source_alt_ref_active);
      if (cm.current_video_frame == 0 || cm.frame_flags.contains(FrameFlags.Key) || cpi.oxcf.auto_key && cpi.frames_since_key % cpi.key_frame_frequency == 0) {
         cm.frame_type = 0;
      }

      cpi.closest_reference_frame = MVReferenceFrame.LAST_FRAME;
      if (cm.frame_type == 0) {
         cpi.mb.e_mbd.setup_features(cpi);
         cpi.source_alt_ref_active = false;

         for (int i = 0; i < 20; i++) {
            cpi.mb.rd_thresh_mult[i] = 128;
         }

         Arrays.fill(cpi.consec_zero_last, 0, cm.mb_rows * cm.mb_cols, 0);
         Arrays.fill(cpi.consec_zero_last_mvbias, 0, cpi.common.mb_rows * cpi.common.mb_cols, 0);
      }

      update_rd_ref_frame_probs(cpi);
      if (cpi.decimation_factor > 0) {
         switch (cpi.decimation_factor) {
            case 1:
               cpi.per_frame_bandwidth = cpi.per_frame_bandwidth * 3 / 2;
               break;
            case 2:
            case 3:
               cpi.per_frame_bandwidth = cpi.per_frame_bandwidth * 5 / 4;
         }

         if (cm.frame_type == 0) {
            cpi.decimation_count = cpi.decimation_factor;
         }
      } else {
         cpi.decimation_count = 0;
      }

      if (!RateCtrl.vp8_pick_frame_size(cpi)) {
         cm.current_video_frame++;
         cpi.frames_since_key++;
         cpi.ext_refresh_frame_flags_pending = false;
         cpi.temporal_pattern_counter++;
         return 0;
      } else {
         if (cpi.oxcf.end_usage == 1 && cpi.buffer_level >= cpi.oxcf.optimal_buffer_level && cpi.buffered_mode) {
            int Adjustment = cpi.active_worst_quality / 4;
            if (Adjustment != 0) {
               if (cpi.buffer_level < cpi.oxcf.maximum_buffer_size) {
                  int buff_lvl_step = (int)((cpi.oxcf.maximum_buffer_size - cpi.oxcf.optimal_buffer_level) / Adjustment);
                  if (buff_lvl_step != 0) {
                     Adjustment = (int)((cpi.buffer_level - cpi.oxcf.optimal_buffer_level) / buff_lvl_step);
                  } else {
                     Adjustment = 0;
                  }
               }

               cpi.active_worst_quality = (short)(cpi.active_worst_quality - Adjustment);
               if (cpi.active_worst_quality < cpi.active_best_quality) {
                  cpi.active_worst_quality = cpi.active_best_quality;
               }
            }
         }

         if (cpi.oxcf.end_usage == 2) {
            if (cm.frame_type == 0 || cm.refresh_golden_frame || cpi.common.refresh_alt_ref_frame) {
               cpi.active_best_quality = cpi.best_quality;
            } else if (cpi.active_best_quality < cpi.cq_target_quality) {
               cpi.active_best_quality = cpi.cq_target_quality;
            }
         }

         if (cpi.active_worst_quality > cpi.worst_quality) {
            cpi.active_worst_quality = cpi.worst_quality;
         }

         if (cpi.active_best_quality < cpi.best_quality) {
            cpi.active_best_quality = cpi.best_quality;
         }

         if (cpi.active_worst_quality < cpi.active_best_quality) {
            cpi.active_worst_quality = cpi.active_best_quality;
         }

         short Q = RateCtrl.vp8_regulate_q(cpi, cpi.this_frame_target);
         if (!cpi.repeatFrameDetected) {
            compute_skin_map(cpi);
         }

         if (cpi.cyclic_refresh_mode_enabled) {
            boolean disable_cr_gf = cpi.oxcf.screen_content_mode == 2 && cm.refresh_golden_frame;
            if (cpi.current_layer == 0 && cpi.force_maxqp == 0 && !disable_cr_gf) {
               cyclic_background_refresh(cpi, Q);
            } else {
               disable_segmentation(cpi);
            }
         }

         RateCtrl.vp8_compute_frame_size_bounds(cpi, limits);
         int bottom_index = cpi.active_best_quality;
         int top_index = cpi.active_worst_quality;
         short q_low = cpi.active_best_quality;
         short q_high = cpi.active_worst_quality;
         cpi.coding_context.vp8_save_coding_context(cpi);
         scale_and_extend_source(cpi.un_scaled_source, cpi);

         boolean Loop;
         do {
            Quantize.vp8_set_quantizer(cpi, Q);
            if (cpi.common.mb_no_coeff_skip) {
               cpi.prob_skip_false = cpi.base_skip_false_prob[Q];
            }

            if (cm.frame_type == 0) {
               RateCtrl.vp8_setup_key_frame(cpi);
            }

            EncodeFrame.vp8_encode_frame(cpi);
            if (cpi.oxcf.end_usage == 1) {
               if (RateCtrl.vp8_drop_encodedframe_overshoot(cpi, Q)) {
                  return 0;
               }

               if (cm.frame_type != 0) {
                  cpi.last_pred_err_mb = (int)(cpi.mb.prediction_error / cpi.common.MBs);
               }
            }

            cpi.projected_frame_size = cpi.projected_frame_size - BitStream.vp8_estimate_entropy_savings(cpi);
            cpi.projected_frame_size = Math.max(cpi.projected_frame_size, 0);
            if (cpi.oxcf.auto_key && cm.frame_type != 0 && cpi.compressor_speed != 2 && decide_key_frame(cpi)) {
               cm.frame_type = 0;
               RateCtrl.vp8_pick_frame_size(cpi);
               cpi.source_alt_ref_active = false;
               cpi.mb.e_mbd.setup_features(cpi);
               cpi.coding_context.vp8_restore_coding_context(cpi);
               Q = RateCtrl.vp8_regulate_q(cpi, cpi.this_frame_target);
               RateCtrl.vp8_compute_frame_size_bounds(cpi, limits);
               bottom_index = cpi.active_best_quality;
               top_index = cpi.active_worst_quality;
               q_low = cpi.active_best_quality;
               q_high = cpi.active_worst_quality;
               Loop = true;
            } else {
               if (limits.frame_over_shoot_limit == 0) {
                  limits.frame_over_shoot_limit = 1;
               }

               if (cpi.oxcf.end_usage == 1
                  && Q == cpi.active_worst_quality
                  && cpi.active_worst_quality < cpi.worst_quality
                  && cpi.projected_frame_size > limits.frame_over_shoot_limit) {
                  for (int over_size_percent = (cpi.projected_frame_size - limits.frame_over_shoot_limit) * 100 / limits.frame_over_shoot_limit;
                     cpi.active_worst_quality < cpi.worst_quality && over_size_percent > 0;
                     over_size_percent = (int)(over_size_percent * 0.96)
                  ) {
                     cpi.active_worst_quality++;
                  }

                  top_index = cpi.active_worst_quality;
                  active_worst_qchanged = true;
               } else {
                  active_worst_qchanged = false;
               }

               if (cm.frame_type == 0 && cpi.this_key_frame_forced) {
                  int last_q = Q;
                  long kf_err = vp8_calc_ss_err(cpi.sourceYV12, cm.yv12_fb[cm.new_fb_idx]);
                  if (kf_err > 0L) {
                     q_high = (short)(Q > q_low ? Q - 1 : q_low);
                     Q = (short)(q_high + q_low >> 1);
                  } else if (kf_err < 0L) {
                     q_low = (short)(Q < q_high ? Q + 1 : q_high);
                     Q = (short)(q_high + q_low + 1 >> 1);
                  }

                  if (Q > q_high) {
                     Q = q_high;
                  } else if (Q < q_low) {
                     Q = q_low;
                  }

                  Loop = Q != last_q;
               } else if (recode_loop_test(cpi, limits.frame_over_shoot_limit, limits.frame_under_shoot_limit, Q, top_index, bottom_index)) {
                  int last_qx = Q;
                  int Retries = 0;
                  if (cpi.projected_frame_size > cpi.this_frame_target) {
                     q_low = (short)(Q < q_high ? Q + 1 : q_high);
                     if (cpi.mb.zbin_over_quant > 0) {
                        zbin_oq_low = cpi.mb.zbin_over_quant < zbin_oq_high ? cpi.mb.zbin_over_quant + 1 : zbin_oq_high;
                     }

                     if (undershoot_seen) {
                        if (!active_worst_qchanged) {
                           RateCtrl.vp8_update_rate_correction_factors(cpi, 1);
                        }

                        Q = (short)(q_high + q_low + 1 >> 1);
                        if (Q < 127) {
                           cpi.mb.zbin_over_quant = 0;
                        } else {
                           zbin_oq_low = cpi.mb.zbin_over_quant < zbin_oq_high ? cpi.mb.zbin_over_quant + 1 : zbin_oq_high;
                           cpi.mb.zbin_over_quant = (zbin_oq_high + zbin_oq_low) / 2;
                        }
                     } else {
                        if (!active_worst_qchanged) {
                           RateCtrl.vp8_update_rate_correction_factors(cpi, 0);
                        }

                        for (Q = RateCtrl.vp8_regulate_q(cpi, cpi.this_frame_target);
                           (Q < q_low || cpi.mb.zbin_over_quant < zbin_oq_low) && Retries < 10;
                           Retries++
                        ) {
                           RateCtrl.vp8_update_rate_correction_factors(cpi, 0);
                           Q = RateCtrl.vp8_regulate_q(cpi, cpi.this_frame_target);
                        }
                     }

                     overshoot_seen = true;
                  } else {
                     if (cpi.mb.zbin_over_quant == 0) {
                        q_high = (short)(Q > q_low ? Q - 1 : q_low);
                     } else {
                        zbin_oq_high = cpi.mb.zbin_over_quant > zbin_oq_low ? cpi.mb.zbin_over_quant - 1 : zbin_oq_low;
                     }

                     if (overshoot_seen) {
                        if (!active_worst_qchanged) {
                           RateCtrl.vp8_update_rate_correction_factors(cpi, 1);
                        }

                        Q = (short)(q_high + q_low >> 1);
                        if (Q < 127) {
                           cpi.mb.zbin_over_quant = 0;
                        } else {
                           cpi.mb.zbin_over_quant = (zbin_oq_high + zbin_oq_low) / 2;
                        }
                     } else {
                        if (!active_worst_qchanged) {
                           RateCtrl.vp8_update_rate_correction_factors(cpi, 0);
                        }

                        Q = RateCtrl.vp8_regulate_q(cpi, cpi.this_frame_target);
                        if (cpi.oxcf.end_usage == 2 && Q < q_low) {
                           q_low = Q;
                        }

                        while ((Q > q_high || cpi.mb.zbin_over_quant > zbin_oq_high) && Retries < 10) {
                           RateCtrl.vp8_update_rate_correction_factors(cpi, 0);
                           Q = RateCtrl.vp8_regulate_q(cpi, cpi.this_frame_target);
                           Retries++;
                        }
                     }

                     undershoot_seen = true;
                  }

                  if (Q > q_high) {
                     Q = q_high;
                  } else if (Q < q_low) {
                     Q = q_low;
                  }

                  cpi.mb.zbin_over_quant = cpi.mb.zbin_over_quant < zbin_oq_low ? zbin_oq_low : Math.min(cpi.mb.zbin_over_quant, zbin_oq_high);
                  Loop = Q != last_qx;
               } else {
                  Loop = false;
               }

               if (cpi.is_src_frame_alt_ref) {
                  Loop = false;
               }

               if (Loop) {
                  cpi.coding_context.vp8_restore_coding_context(cpi);
               }
            }
         } while (Loop);

         cpi.zeromv_count = 0;
         if (cpi.oxcf.number_of_layers == 1) {
            vp8_update_gf_useage_maps(cpi, cm, cpi.mb);
         }

         if (cm.frame_type == 0) {
            cm.refresh_last_frame = true;
         }

         if (!cpi.oxcf.error_resilient_mode && cm.refresh_golden_frame && !cpi.ext_refresh_frame_flags_pending) {
            cm.copy_buffer_to_arf = 2;
         } else {
            cm.copy_buffer_to_arf = 0;
         }

         cm.frame_to_show = cm.yv12_fb[cm.new_fb_idx];
         vp8_loopfilter_frame(cpi, cm);
         update_reference_frames(cpi);
         if (cpi.oxcf.error_resilient_mode) {
            cm.refresh_entropy_probs = false;
         }

         int size = BitStream.vp8_pack_bitstream(cpi, dest);
         cpi.total_byte_count += size;
         cpi.projected_frame_size = size << 3;
         if (cpi.oxcf.number_of_layers > 1) {
            for (int i = cpi.current_layer + 1; i < cpi.oxcf.number_of_layers; i++) {
               cpi.layer_context[i].total_byte_count += size;
            }
         }

         if (!active_worst_qchanged) {
            RateCtrl.vp8_update_rate_correction_factors(cpi, 2);
         }

         cpi.last_q[cm.frame_type] = cm.base_qindex;
         if (cm.frame_type == 0) {
            RateCtrl.vp8_adjust_key_frame_context(cpi);
         }

         if (!cm.show_frame) {
            cpi.bits_off_target = cpi.bits_off_target - cpi.projected_frame_size;
         } else {
            cpi.bits_off_target = cpi.bits_off_target + (cpi.av_per_frame_bandwidth - cpi.projected_frame_size);
         }

         if (cpi.bits_off_target > cpi.oxcf.maximum_buffer_size) {
            cpi.bits_off_target = cpi.oxcf.maximum_buffer_size;
         }

         if (!cpi.drop_frames_allowed && cpi.oxcf.screen_content_mode != 0 && cpi.bits_off_target < -cpi.oxcf.maximum_buffer_size) {
            cpi.bits_off_target = -cpi.oxcf.maximum_buffer_size;
         }

         cpi.rolling_target_bits = (int)CUtils.roundPowerOfTwo(cpi.rolling_target_bits * 3L + cpi.this_frame_target, 2);
         cpi.rolling_actual_bits = (int)CUtils.roundPowerOfTwo(cpi.rolling_actual_bits * 3L + cpi.projected_frame_size, 2);
         cpi.long_rolling_target_bits = (int)CUtils.roundPowerOfTwo(cpi.long_rolling_target_bits * 31L + cpi.this_frame_target, 5);
         cpi.long_rolling_actual_bits = (int)CUtils.roundPowerOfTwo(cpi.long_rolling_actual_bits * 31L + cpi.projected_frame_size, 5);
         cpi.total_actual_bits = cpi.total_actual_bits + cpi.projected_frame_size;
         cpi.buffer_level = cpi.bits_off_target;
         if (cpi.oxcf.number_of_layers > 1) {
            for (int i = cpi.current_layer + 1; i < cpi.oxcf.number_of_layers; i++) {
               LayerContext lc = cpi.layer_context[i];
               int bits_off_for_this_layer = (int)(lc.target_bandwidth / lc.framerate - cpi.projected_frame_size);
               lc.bits_off_target += bits_off_for_this_layer;
               if (lc.bits_off_target > lc.maximum_buffer_size) {
                  lc.bits_off_target = lc.maximum_buffer_size;
               }

               lc.total_actual_bits = lc.total_actual_bits + cpi.projected_frame_size;
               lc.buffer_level = lc.bits_off_target;
            }
         }

         cpi.ext_refresh_frame_flags_pending = false;
         if (cm.refresh_golden_frame) {
            cm.frame_flags.add(FrameFlags.Golden);
         } else {
            cm.frame_flags.remove(FrameFlags.Golden);
         }

         if (cm.refresh_alt_ref_frame) {
            cm.frame_flags.add(FrameFlags.AltRef);
         } else {
            cm.frame_flags.remove(FrameFlags.AltRef);
         }

         if (cm.refresh_last_frame & cm.refresh_golden_frame) {
            cpi.gold_is_last = true;
         } else if (cm.refresh_last_frame ^ cm.refresh_golden_frame) {
            cpi.gold_is_last = false;
         }

         if (cm.refresh_last_frame & cm.refresh_alt_ref_frame) {
            cpi.alt_is_last = true;
         } else if (cm.refresh_last_frame ^ cm.refresh_alt_ref_frame) {
            cpi.alt_is_last = false;
         }

         if (cm.refresh_alt_ref_frame & cm.refresh_golden_frame) {
            cpi.gold_is_alt = true;
         } else if (cm.refresh_alt_ref_frame ^ cm.refresh_golden_frame) {
            cpi.gold_is_alt = false;
         }

         cpi.ref_frame_flags = EnumSet.copyOf(MVReferenceFrame.interFrames);
         if (cpi.gold_is_last) {
            cpi.ref_frame_flags.remove(MVReferenceFrame.GOLDEN_FRAME);
         }

         if (cpi.alt_is_last) {
            cpi.ref_frame_flags.remove(MVReferenceFrame.ALTREF_FRAME);
         }

         if (cpi.gold_is_alt) {
            cpi.ref_frame_flags.remove(MVReferenceFrame.ALTREF_FRAME);
         }

         if (!cpi.oxcf.error_resilient_mode) {
            if (cpi.oxcf.play_alternate && cm.refresh_alt_ref_frame && cm.frame_type != 0) {
               update_alt_ref_frame_stats(cpi);
            } else {
               update_golden_frame_stats(cpi);
            }
         }

         if (cm.frame_type == 0) {
            frame_flags.clear();
            frame_flags.addAll(cm.frame_flags);
            frame_flags.add(FrameFlags.Key);
            cm.frame_type = 1;
            cpi.last_frame_percent_intra = 100;
         }

         cpi.mb.e_mbd.update_mb_segmentation_map = false;
         cpi.mb.e_mbd.update_mb_segmentation_data = false;
         cpi.mb.e_mbd.mode_ref_lf_delta_update = false;
         if (cm.show_frame) {
            cm.current_video_frame++;
            cpi.frames_since_key++;
            cpi.temporal_pattern_counter++;
         }

         return size;
      }
   }

   private static void vp8_update_gf_useage_maps(Compressor cpi, CommonData cm, Macroblock x) {
      FullGenArrPointer<ModeInfo> this_mb_mode_info = cm.mi.shallowCopy();
      x.gf_active_ptr = cpi.gf_active_flags.shallowCopy();
      if (cm.frame_type != 0 && !cm.refresh_golden_frame) {
         for (int mb_row = 0; mb_row < cm.mb_rows; mb_row++) {
            for (int mb_col = 0; mb_col < cm.mb_cols; mb_col++) {
               if (this_mb_mode_info.get().mbmi.ref_frame != MVReferenceFrame.GOLDEN_FRAME
                  && this_mb_mode_info.get().mbmi.ref_frame != MVReferenceFrame.ALTREF_FRAME) {
                  if (this_mb_mode_info.get().mbmi.mode != MBPredictionMode.ZEROMV && x.gf_active_ptr.get() != 0) {
                     x.gf_active_ptr.set((short)0);
                     cpi.gf_active_count--;
                  }
               } else if (x.gf_active_ptr.get() == 0) {
                  x.gf_active_ptr.set((short)1);
                  cpi.gf_active_count++;
               }

               x.gf_active_ptr.inc();
               this_mb_mode_info.inc();
            }

            this_mb_mode_info.inc();
         }
      } else {
         cpi.gf_active_flags.memset(0, (short)1, cm.mb_rows * cm.mb_cols);
         cpi.gf_active_count = cm.mb_rows * cm.mb_cols;
      }
   }

   static int vp8_get_compressed_data(Compressor cpi, EnumSet<FrameFlags> frame_flags, FullGetSetPointer dest, TimeStampRange time, boolean flush) {
      YV12buffer force_src_buffer = null;
      if (cpi == null) {
         return -1;
      } else {
         CommonData cm = cpi.common;
         cpi.sourceLAE = null;
         if (!cpi.oxcf.error_resilient_mode
            && cpi.oxcf.play_alternate
            && cpi.source_alt_ref_pending
            && (cpi.sourceLAE = cpi.lookahead.vp8_lookahead_peek(cpi.frames_till_gf_update_due, 1)) != null) {
            cpi.alt_ref_source = cpi.sourceLAE;
            if (cpi.oxcf.arnr_max_frames > 0) {
               TemporalFilter.vp8_temporal_filter_prepare(cpi, cpi.frames_till_gf_update_due);
               force_src_buffer = cpi.alt_ref_buffer;
            }

            cpi.frames_till_alt_ref_frame = cpi.frames_till_gf_update_due;
            cm.refresh_alt_ref_frame = true;
            cm.refresh_golden_frame = false;
            cm.refresh_last_frame = false;
            cm.show_frame = false;
            cpi.source_alt_ref_pending = false;
            cpi.is_src_frame_alt_ref = false;
         }

         if (cpi.sourceLAE == null && (cpi.sourceLAE = cpi.lookahead.vp8_lookahead_pop(flush)) != null) {
            cm.show_frame = true;
            cpi.is_src_frame_alt_ref = cpi.alt_ref_source != null && cpi.sourceLAE == cpi.alt_ref_source;
            if (cpi.is_src_frame_alt_ref) {
               cpi.alt_ref_source = null;
            }
         }

         if (cpi.sourceLAE != null) {
            cpi.sourceYV12 = force_src_buffer != null ? force_src_buffer : cpi.sourceLAE.img;
            cpi.un_scaled_source = cpi.sourceYV12;
            time.time_stamp = cpi.sourceLAE.ts_start;
            time.time_end = cpi.sourceLAE.ts_end;
            frame_flags.addAll(cpi.sourceLAE.flags);
            if (cpi.sourceLAE.ts_start < cpi.first_time_stamp_ever) {
               cpi.first_time_stamp_ever = cpi.sourceLAE.ts_start;
               cpi.last_end_time_stamp_seen = cpi.sourceLAE.ts_start;
            }

            if (cm.show_frame) {
               int step = 0;
               long this_duration;
               if (cpi.sourceLAE.ts_start == cpi.first_time_stamp_ever) {
                  this_duration = cpi.sourceLAE.ts_end - cpi.sourceLAE.ts_start;
                  step = 1;
               } else {
                  this_duration = cpi.sourceLAE.ts_end - cpi.last_end_time_stamp_seen;
                  long last_duration = cpi.last_end_time_stamp_seen - cpi.last_time_stamp_seen;
                  if (last_duration != 0L) {
                     step = (int)((this_duration - last_duration) * 10L / last_duration);
                  }
               }

               if (this_duration != 0L) {
                  if (step != 0) {
                     cpi.ref_framerate = 1.0E7 / this_duration;
                  } else {
                     double interval = cpi.sourceLAE.ts_end - cpi.first_time_stamp_ever;
                     if (interval > 1.0E7) {
                        interval = 1.0E7;
                     }

                     double avg_duration = 1.0E7 / cpi.ref_framerate;
                     avg_duration *= interval - avg_duration + this_duration;
                     avg_duration /= interval;
                     cpi.ref_framerate = 1.0E7 / avg_duration;
                  }

                  if (cpi.oxcf.number_of_layers > 1) {
                     for (int i = 0; i < cpi.oxcf.number_of_layers && i < 5; i++) {
                        LayerContext lc = cpi.layer_context[i];
                        lc.framerate = cpi.ref_framerate / cpi.oxcf.rate_decimator[i];
                     }
                  } else {
                     cpi.vp8_new_framerate(cpi.ref_framerate);
                  }
               }

               cpi.last_time_stamp_seen = cpi.sourceLAE.ts_start;
               cpi.last_end_time_stamp_seen = cpi.sourceLAE.ts_end;
            }

            if (cpi.oxcf.number_of_layers > 1) {
               update_layer_contexts(cpi);
               int layer;
               if (cpi.temporal_layer_id >= 0) {
                  layer = cpi.temporal_layer_id;
               } else {
                  layer = cpi.oxcf.layer_id[cpi.temporal_pattern_counter % cpi.oxcf.periodicity];
               }

               restore_layer_context(cpi, layer);
               cpi.vp8_new_framerate(cpi.layer_context[layer].framerate);
            }

            cpi.lf_zeromv_pct = cpi.zeromv_count * 100 / cm.MBs;
            cm.frame_type = 1;
            cm.frame_flags = EnumSet.copyOf(frame_flags);

            for (int i = 0; i < 4; i++) {
               if (cm.yv12_fb[i].flags.isEmpty()) {
                  cm.new_fb_idx = i;
                  break;
               }
            }

            int size = encode_frame_to_data_rate(cpi, dest, frame_flags);
            if (!cm.refresh_entropy_probs) {
               cm.fc = new FrameContext(cm.lfc);
            }

            if (cm.refresh_alt_ref_frame) {
               cpi.lfc_a = new FrameContext(cm.fc);
            }

            if (cm.refresh_golden_frame) {
               cpi.lfc_g = new FrameContext(cm.fc);
            }

            if (cm.refresh_last_frame) {
               cpi.lfc_n = new FrameContext(cm.fc);
            }

            if (size > 0) {
               cpi.droppable = !frame_is_reference(cpi);
               cm.refresh_entropy_probs = true;
               cm.refresh_alt_ref_frame = false;
               cm.refresh_golden_frame = false;
               cm.refresh_last_frame = true;
               cm.frame_type = 1;
            }

            if (cpi.oxcf.number_of_layers > 1) {
               save_layer_context(cpi);
            }

            if (cpi.b_calculate_psnr && cm.show_frame) {
               generate_psnr_packet(cpi);
            }

            return size;
         } else {
            return -1;
         }
      }
   }

   static boolean frame_is_reference(Compressor cpi) {
      CommonData cm = cpi.common;
      MacroblockD xd = cpi.mb.e_mbd;
      return cm.frame_type == 0
         || cm.refresh_last_frame
         || cm.refresh_golden_frame
         || cm.refresh_alt_ref_frame
         || cm.copy_buffer_to_gf != 0
         || cm.copy_buffer_to_arf != 0
         || cm.refresh_entropy_probs
         || xd.mode_ref_lf_delta_update
         || xd.update_mb_segmentation_map
         || xd.update_mb_segmentation_data;
   }

   static void vp8_loopfilter_frame(Compressor cpi, CommonData cm) {
      int frame_type = cm.frame_type;
      boolean update_any_ref_buffers = cpi.common.refresh_last_frame || cpi.common.refresh_golden_frame || cpi.common.refresh_alt_ref_frame;
      if (cm.no_lpf) {
         cm.filter_level = 0;
      } else {
         if (!cpi.sf.auto_filter) {
            PickLpf.vp8cx_pick_filter_level_fast(cpi.sourceYV12, cpi);
         } else {
            PickLpf.vp8cx_pick_filter_level(cpi.sourceYV12, cpi);
         }

         if (cm.filter_level > 0) {
            PickLpf.vp8cx_set_alt_lf_level(cpi);
         }
      }

      if (cm.filter_level > 0 && update_any_ref_buffers) {
         LoopFilter.vp8_loop_filter_frame(cm, cpi.mb.e_mbd, frame_type);
      }

      cm.frame_to_show.extend_frame_borders();
   }

   static void update_reference_frames(Compressor cpi) {
      CommonData cm = cpi.common;
      if (cm.frame_type == 0) {
         refreshFBWithNewFrame(cpi, MVReferenceFrame.GOLDEN_FRAME);
         refreshFBWithNewFrame(cpi, MVReferenceFrame.ALTREF_FRAME);
      } else {
         if (cm.refresh_alt_ref_frame) {
            refreshFBWithNewFrame(cpi, MVReferenceFrame.ALTREF_FRAME);
         } else if (cm.copy_buffer_to_arf != 0) {
            if (cm.copy_buffer_to_arf == 1) {
               if (!Objects.equals(cm.frameIdxs.get(MVReferenceFrame.ALTREF_FRAME), cm.frameIdxs.get(MVReferenceFrame.LAST_FRAME))) {
                  refreshFBWithOtherFB(cpi, MVReferenceFrame.ALTREF_FRAME, MVReferenceFrame.LAST_FRAME);
               }
            } else if (!Objects.equals(cm.frameIdxs.get(MVReferenceFrame.ALTREF_FRAME), cm.frameIdxs.get(MVReferenceFrame.GOLDEN_FRAME))) {
               refreshFBWithOtherFB(cpi, MVReferenceFrame.ALTREF_FRAME, MVReferenceFrame.GOLDEN_FRAME);
            }
         }

         if (cm.refresh_golden_frame) {
            refreshFBWithNewFrame(cpi, MVReferenceFrame.GOLDEN_FRAME);
         } else if (cm.copy_buffer_to_gf != 0) {
            if (cm.copy_buffer_to_gf == 1) {
               if (!Objects.equals(cm.frameIdxs.get(MVReferenceFrame.GOLDEN_FRAME), cm.frameIdxs.get(MVReferenceFrame.LAST_FRAME))) {
                  refreshFBWithOtherFB(cpi, MVReferenceFrame.GOLDEN_FRAME, MVReferenceFrame.LAST_FRAME);
               }
            } else if (!Objects.equals(cm.frameIdxs.get(MVReferenceFrame.ALTREF_FRAME), cm.frameIdxs.get(MVReferenceFrame.GOLDEN_FRAME))) {
               refreshFBWithOtherFB(cpi, MVReferenceFrame.GOLDEN_FRAME, MVReferenceFrame.ALTREF_FRAME);
            }
         }
      }

      if (cm.refresh_last_frame) {
         refreshFBWithNewFrame(cpi, MVReferenceFrame.LAST_FRAME);
      }
   }

   private static void refreshFBWithNewFrame(Compressor cpi, MVReferenceFrame whichFb) {
      CommonData cm = cpi.common;
      cm.yv12_fb[cm.new_fb_idx].flags.add(whichFb);
      cm.yv12_fb[cm.frameIdxs.get(whichFb)].flags.remove(whichFb);
      cm.frameIdxs.put(whichFb, cm.new_fb_idx);
      cpi.current_ref_frames.put(whichFb, cm.current_video_frame);
   }

   private static void refreshFBWithOtherFB(Compressor cpi, MVReferenceFrame whichFb, MVReferenceFrame otherFb) {
      CommonData cm = cpi.common;
      YV12buffer[] yv12_fb = cm.yv12_fb;
      yv12_fb[cm.frameIdxs.get(otherFb)].flags.add(whichFb);
      yv12_fb[cm.frameIdxs.get(whichFb)].flags.remove(whichFb);
      cm.frameIdxs.put(whichFb, cm.frameIdxs.get(otherFb));
      cpi.current_ref_frames.put(whichFb, cpi.current_ref_frames.get(otherFb));
   }

   static void update_alt_ref_frame_stats(Compressor cpi) {
      CommonData cm = cpi.common;
      if (!cpi.auto_gold) {
         cpi.frames_till_gf_update_due = 7;
      }

      if (cpi.frames_till_gf_update_due != 0) {
         cpi.current_gf_interval = cpi.frames_till_gf_update_due;
         cpi.gf_overspend_bits = cpi.gf_overspend_bits + cpi.projected_frame_size;
         cpi.non_gf_bitrate_adjustment = cpi.gf_overspend_bits / cpi.frames_till_gf_update_due;
      }

      cpi.gf_active_flags.memset(0, (short)1, cm.mb_rows * cm.mb_cols);
      cpi.gf_active_count = cm.mb_rows * cm.mb_cols;
      cpi.frames_since_golden = 0;
      cpi.source_alt_ref_pending = false;
      cpi.source_alt_ref_active = true;
   }

   static void update_golden_frame_stats(Compressor cpi) {
      CommonData cm = cpi.common;
      if (cm.refresh_golden_frame) {
         if (!cpi.auto_gold) {
            cpi.frames_till_gf_update_due = 7;
         }

         if (cpi.frames_till_gf_update_due > 0) {
            cpi.current_gf_interval = cpi.frames_till_gf_update_due;
            if (cm.frame_type != 0 && !cpi.source_alt_ref_active) {
               cpi.gf_overspend_bits = cpi.gf_overspend_bits + (cpi.projected_frame_size - cpi.inter_frame_target);
            }

            cpi.non_gf_bitrate_adjustment = cpi.gf_overspend_bits / cpi.frames_till_gf_update_due;
         }

         cpi.gf_active_flags.memset(0, (short)1, cm.mb_rows * cm.mb_cols);
         cpi.gf_active_count = cm.mb_rows * cm.mb_cols;
         cm.refresh_golden_frame = false;
         cpi.frames_since_golden = 0;

         for (MVReferenceFrame rf : MVReferenceFrame.validFrames) {
            cpi.recent_ref_frame_usage.put(rf, 1);
         }

         if (cpi.oxcf.fixed_q >= 0 && cpi.oxcf.play_alternate && !cpi.common.refresh_alt_ref_frame) {
            cpi.source_alt_ref_pending = true;
            cpi.frames_till_gf_update_due = cpi.baseline_gf_interval;
         }

         if (!cpi.source_alt_ref_pending) {
            cpi.source_alt_ref_active = false;
         }

         if (cpi.frames_till_gf_update_due > 0) {
            cpi.frames_till_gf_update_due--;
         }
      } else if (!cpi.common.refresh_alt_ref_frame) {
         if (cpi.frames_till_gf_update_due > 0) {
            cpi.frames_till_gf_update_due--;
         }

         if (cpi.frames_till_alt_ref_frame != 0) {
            cpi.frames_till_alt_ref_frame--;
         }

         cpi.frames_since_golden++;
         if (cpi.frames_since_golden > 1) {
            for (MVReferenceFrame rf : MVReferenceFrame.validFrames) {
               cpi.recent_ref_frame_usage.put(rf, cpi.recent_ref_frame_usage.get(rf) + cpi.mb.count_mb_ref_frame_usage.get(rf));
            }
         }
      }
   }

   static void vp8_receive_raw_frame(Compressor cpi, EnumSet<FrameFlags> frame_flags, YV12buffer sd, long time_stamp, long end_time) {
      if (sd.y_width != cpi.oxcf.Width || sd.y_height != cpi.oxcf.Height) {
         dealloc_raw_frame_buffers(cpi);
         alloc_raw_frame_buffers(cpi);
      }

      cpi.lookahead.vp8_lookahead_push(sd, time_stamp, end_time, frame_flags, cpi.active_map_enabled ? cpi.active_map : null);
      if (cpi.oxcf.screen_content_mode > 0 && cpi.sourceYV12 != null) {
         LookaheadEntry lent = cpi.lookahead.vp8_lookahead_peek(0, -1);
         cpi.repeatFrameDetected = cpi.sourceYV12.y_buffer.compareTo(lent.img.y_buffer) == 0;
      }
   }

   static class TimeStampRange {
      long time_stamp;
      long time_end;
   }
}
