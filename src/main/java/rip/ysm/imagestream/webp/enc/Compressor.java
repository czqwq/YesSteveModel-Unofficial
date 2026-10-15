package rip.ysm.imagestream.webp.enc;

import java.util.Arrays;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.TreeMap;

class Compressor {
   private static final int MAX_PARTITIONS = 9;
   final EnumMap<CommonData.Quant, QuantDetails> q = new EnumMap<>(CommonData.Quant.class);
   final Macroblock mb;
   final CommonData common = new CommonData();
   final BitEncoder[] bc = new BitEncoder[9];
   Config oxcf;
   Lookahead lookahead;
   LookaheadEntry sourceLAE;
   LookaheadEntry alt_ref_source;
   YV12buffer sourceYV12;
   YV12buffer un_scaled_source;
   int frames_till_alt_ref_frame;
   boolean source_alt_ref_pending;
   boolean source_alt_ref_active;
   boolean is_src_frame_alt_ref;
   boolean gold_is_last;
   boolean alt_is_last;
   boolean gold_is_alt;
   YV12buffer pick_lf_lvl_frame;
   FullGenArrPointer<TokenExtra> tok;
   int tok_count;
   int frames_since_key;
   final int key_frame_frequency;
   final boolean this_key_frame_forced;
   final int[] mode_check_freq = new int[20];
   final int[] rd_baseline_thresh = new int[20];
   int RDMULT;
   int RDDIV;
   final CodingContext coding_context = new CodingContext();
   long last_prediction_error;
   long last_intra_error;
   int this_frame_target;
   int projected_frame_size;
   final short[] last_q = new short[2];
   double rate_correction_factor;
   double key_frame_rate_correction_factor;
   double gf_rate_correction_factor;
   int frames_since_golden;
   int frames_till_gf_update_due;
   int current_gf_interval;
   int gf_overspend_bits;
   int non_gf_bitrate_adjustment;
   int kf_overspend_bits;
   int kf_bitrate_adjustment;
   int max_gf_interval;
   int baseline_gf_interval;
   static final int active_arnr_frames = 0;
   long key_frame_count;
   final int[] prior_key_frame_distance = new int[5];
   int per_frame_bandwidth;
   int av_per_frame_bandwidth;
   int min_frame_bandwidth;
   int inter_frame_target;
   double output_framerate;
   long last_time_stamp_seen;
   long last_end_time_stamp_seen;
   long first_time_stamp_ever;
   short ni_av_qi;
   int ni_tot_qi;
   int ni_frames;
   short avg_frame_qindex;
   long total_byte_count;
   boolean buffered_mode;
   double framerate;
   double ref_framerate;
   long buffer_level;
   long bits_off_target;
   int rolling_target_bits;
   int rolling_actual_bits;
   int long_rolling_target_bits;
   int long_rolling_actual_bits;
   long total_actual_bits;
   short worst_quality;
   short active_worst_quality;
   short best_quality;
   short active_best_quality;
   short cq_target_quality;
   boolean drop_frames_allowed;
   boolean drop_frame;
   final short[][][][] frame_coef_probs = new short[4][8][3][11];
   final int[][][][][] frame_branch_ct = new int[4][8][3][11][2];
   int last_boost;
   int target_bandwidth;
   final List<CodecPkt> output_pkt_list;
   int decimation_factor;
   int decimation_count;
   int avg_encode_time;
   int avg_pick_mode_time;
   int Speed;
   int compressor_speed;
   final boolean auto_gold;
   final boolean auto_adjust_gold_quantizer;
   boolean auto_worst_q;
   int prob_intra_coded;
   int prob_last_coded;
   int prob_gf_coded;
   int prob_skip_false;
   final EnumMap<MVReferenceFrame, Integer> recent_ref_frame_usage = new EnumMap<>(MVReferenceFrame.class);
   int this_frame_percent_intra;
   int last_frame_percent_intra;
   EnumSet<MVReferenceFrame> ref_frame_flags;
   final SpeedFeatures sf = new SpeedFeatures();
   int zeromv_count;
   int lf_zeromv_pct;
   final boolean[] skin_map;
   int[] segmentation_map;
   final short[][] segment_feature_data = new short[MBLvlFeatures.featureCount][4];
   final int[] segment_encode_breakout = new int[4];
   FullGetSetPointer active_map;
   final boolean active_map_enabled;
   final boolean cyclic_refresh_mode_enabled;
   int cyclic_refresh_mode_max_mbs_perframe;
   int cyclic_refresh_mode_index;
   int cyclic_refresh_q;
   final byte[] cyclic_refresh_map;
   final int[] consec_zero_last;
   final int[] consec_zero_last_mvbias;
   int temporal_pattern_counter;
   int temporal_layer_id;
   int force_maxqp;
   int frames_since_last_drop_overshoot;
   int last_pred_err_mb;
   boolean gf_update_onepass_cbr;
   int gf_interval_onepass_cbr;
   boolean gf_noboost_onepass_cbr;
   TokenList[] tplist;
   final int[] partition_sz = new int[9];
   FractionalMVStepIF find_fractional_mv_step;
   final TreeMap<Integer, VarianceFNs> fn_ptr = new TreeMap<>();
   final int[] base_skip_false_prob;
   FrameContext lfc_n;
   FrameContext lfc_a;
   FrameContext lfc_g;
   YV12buffer alt_ref_buffer;
   final YV12buffer[] frames = new YV12buffer[25];
   final int[] fixed_divide = new int[512];
   boolean b_calculate_psnr;
   final int activity_avg;
   FullGetSetPointer mb_activity_map;
   FullGetSetPointer gf_active_flags;
   int gf_active_count;
   boolean output_partition;
   MV[] lfmv;
   boolean[] lf_ref_frame_sign_bias;
   MVReferenceFrame[] lf_ref_frame;
   boolean force_next_frame_intra;
   boolean droppable;
   int initial_width;
   int current_layer;
   final LayerContext[] layer_context = new LayerContext[5];
   final EnumMap<MVReferenceFrame, Integer> current_ref_frames = new EnumMap<>(MVReferenceFrame.class);
   MVReferenceFrame closest_reference_frame;
   final RDCosts rd_costs = new RDCosts();
   final boolean use_roi_static_threshold;
   boolean ext_refresh_frame_flags_pending;
   boolean repeatFrameDetected;
   final DefaultVarianceFNs varFns = new DefaultVarianceFNs();

   Compressor(Config oxcfNew) {
      for (CommonData.Quant quant : CommonData.Quant.values()) {
         this.q.put(quant, new QuantDetails());
      }

      if (oxcfNew.timebase.num > 0) {
         this.framerate = oxcfNew.timebase.flip().toDouble();
      } else {
         this.framerate = 30.0;
      }

      if (this.framerate > 180.0) {
         this.framerate = 30.0;
      }

      this.vp8_change_config(oxcfNew);
      this.auto_gold = true;
      this.auto_adjust_gold_quantizer = true;
      this.ref_framerate = this.framerate;
      this.ref_frame_flags = EnumSet.copyOf(MVReferenceFrame.interFrames);
      this.active_worst_quality = this.oxcf.worst_allowed_q;
      this.active_best_quality = this.oxcf.best_allowed_q;
      this.avg_frame_qindex = this.oxcf.worst_allowed_q;
      this.buffer_level = this.oxcf.starting_buffer_level;
      this.bits_off_target = this.oxcf.starting_buffer_level;
      this.rolling_target_bits = this.av_per_frame_bandwidth;
      this.rolling_actual_bits = this.av_per_frame_bandwidth;
      this.long_rolling_target_bits = this.av_per_frame_bandwidth;
      this.long_rolling_actual_bits = this.av_per_frame_bandwidth;
      this.total_actual_bits = 0L;
      if (this.oxcf.number_of_layers > 1) {
         double prev_layer_framerate = 0.0;

         for (int i = 0; i < this.oxcf.number_of_layers; i++) {
            this.init_temporal_layer_context(i, prev_layer_framerate);
            prev_layer_framerate = this.output_framerate / this.oxcf.rate_decimator[i];
         }
      }

      this.fixed_divide[0] = 0;

      for (int i = 1; i < 512; i++) {
         this.fixed_divide[i] = 524288 / i;
      }

      this.base_skip_false_prob = Arrays.copyOf(BitStream.VP_8_CX_BASE_SKIP_FALSE_PROB, BitStream.VP_8_CX_BASE_SKIP_FALSE_PROB.length);
      this.common.current_video_frame = 0;
      this.temporal_pattern_counter = 0;
      this.temporal_layer_id = -1;
      this.kf_overspend_bits = 0;
      this.kf_bitrate_adjustment = 0;
      this.frames_till_gf_update_due = 0;
      this.gf_overspend_bits = 0;
      this.non_gf_bitrate_adjustment = 0;
      this.prob_last_coded = 128;
      this.prob_gf_coded = 128;
      this.prob_intra_coded = 63;

      for (MVReferenceFrame mvrf : MVReferenceFrame.validFrames) {
         this.recent_ref_frame_usage.put(mvrf, 1);
      }

      this.common.ref_frame_sign_bias.put(MVReferenceFrame.ALTREF_FRAME, true);
      this.baseline_gf_interval = 7;
      this.gold_is_last = false;
      this.alt_is_last = false;
      this.gold_is_alt = false;
      this.active_map_enabled = false;
      this.use_roi_static_threshold = false;
      this.cyclic_refresh_mode_enabled = this.oxcf.error_resilient_mode || this.oxcf.end_usage == 1;
      this.cyclic_refresh_mode_max_mbs_perframe = this.common.mb_rows * this.common.mb_cols / 7;
      if (this.oxcf.number_of_layers == 1) {
         this.cyclic_refresh_mode_max_mbs_perframe = this.common.mb_rows * this.common.mb_cols / 20;
      } else if (this.oxcf.number_of_layers == 2) {
         this.cyclic_refresh_mode_max_mbs_perframe = this.common.mb_rows * this.common.mb_cols / 10;
      }

      this.cyclic_refresh_mode_index = 0;
      this.cyclic_refresh_q = 32;
      this.gf_update_onepass_cbr = false;
      this.gf_noboost_onepass_cbr = false;
      if (!this.oxcf.error_resilient_mode && this.oxcf.end_usage == 1) {
         this.gf_update_onepass_cbr = true;
         this.gf_noboost_onepass_cbr = true;
         this.gf_interval_onepass_cbr = this.cyclic_refresh_mode_max_mbs_perframe > 0
            ? 2 * this.common.mb_rows * this.common.mb_cols / this.cyclic_refresh_mode_max_mbs_perframe
            : 10;
         this.gf_interval_onepass_cbr = Math.min(40, Math.max(6, this.gf_interval_onepass_cbr));
         this.baseline_gf_interval = this.gf_interval_onepass_cbr;
      }

      if (this.cyclic_refresh_mode_enabled) {
         this.cyclic_refresh_map = new byte[this.common.mb_rows * this.common.mb_cols];
      } else {
         this.cyclic_refresh_map = null;
      }

      this.skin_map = new boolean[this.common.mb_rows * this.common.mb_cols];
      this.consec_zero_last = new int[this.common.mb_rows * this.common.mb_cols];
      this.consec_zero_last_mvbias = new int[this.common.mb_rows * this.common.mb_cols];
      this.activity_avg = 368640;
      this.frames_since_key = 8;
      this.key_frame_frequency = this.oxcf.key_freq;
      this.this_key_frame_forced = false;
      this.source_alt_ref_pending = false;
      this.source_alt_ref_active = false;
      this.common.refresh_alt_ref_frame = false;
      this.force_maxqp = 0;
      this.frames_since_last_drop_overshoot = 0;
      this.b_calculate_psnr = false;
      this.first_time_stamp_ever = 2147483647L;
      this.frames_till_gf_update_due = 0;
      this.key_frame_count = 1L;
      this.ni_av_qi = this.oxcf.worst_allowed_q;
      this.ni_tot_qi = 0;
      this.ni_frames = 0;
      this.total_byte_count = 0L;
      this.drop_frame = false;
      this.rate_correction_factor = 1.0;
      this.key_frame_rate_correction_factor = 1.0;
      this.gf_rate_correction_factor = 1.0;
      Arrays.fill(this.prior_key_frame_distance, (int)this.output_framerate);
      this.output_pkt_list = this.oxcf.output_pkt_list;
      if (this.compressor_speed == 2) {
         this.avg_encode_time = 0;
         this.avg_pick_mode_time = 0;
      }

      for (int i = 0; i < 5; i++) {
         this.fn_ptr.put(i, this.varFns.default_fn_ptr.get(i).copy());
      }

      for (int i = 0; i < this.bc.length; i++) {
         this.bc[i] = new BitEncoder();
      }

      Quantize.vp8cx_init_quantizer(this);
      this.mb = new Macroblock(this);
      OnyxIf.vp8_set_speed_features(this);
      this.common.setVersion((byte)this.oxcf.Version);
   }

   void vp8_convert_rfct_to_prob() {
      ReferenceCounts rf = this.mb.sumReferenceCounts();
      EnumMap<MVReferenceFrame, Integer> rfct = this.mb.count_mb_ref_frame_usage;
      if ((this.prob_intra_coded = rf.intra * 255 / rf.total) == 0) {
         this.prob_intra_coded = 1;
      }

      this.prob_last_coded = rf.inter != 0 ? rfct.get(MVReferenceFrame.LAST_FRAME) * 255 / rf.inter : 128;
      if (this.prob_last_coded == 0) {
         this.prob_last_coded = 1;
      }

      this.prob_gf_coded = rfct.get(MVReferenceFrame.GOLDEN_FRAME) + rfct.get(MVReferenceFrame.ALTREF_FRAME) != 0
         ? rfct.get(MVReferenceFrame.GOLDEN_FRAME) * 255 / (rfct.get(MVReferenceFrame.GOLDEN_FRAME) + rfct.get(MVReferenceFrame.ALTREF_FRAME))
         : 128;
      if (this.prob_gf_coded == 0) {
         this.prob_gf_coded = 1;
      }
   }

   void init_temporal_layer_context(int layer, double prev_layer_framerate) {
      this.layer_context[layer] = new LayerContext(this, this.oxcf, layer, prev_layer_framerate);
   }

   void vp8_change_config(Config oxcfNew) {
      CommonData cm = this.common;
      if (cm.getVersion() != oxcfNew.Version) {
         cm.setVersion((byte)oxcfNew.Version);
      }

      Config tempCfg = this.oxcf == null ? oxcfNew : this.oxcf;
      int last_w = tempCfg.Width;
      int last_h = tempCfg.Height;
      int prev_number_of_layers = tempCfg.number_of_layers;
      this.oxcf = oxcfNew.copy();
      this.defineCompressorSpeed();
      this.auto_worst_q = true;
      this.oxcf.worst_allowed_q = OnyxIf.q_trans[oxcfNew.worst_allowed_q];
      this.oxcf.best_allowed_q = OnyxIf.q_trans[oxcfNew.best_allowed_q];
      this.oxcf.cq_level = OnyxIf.q_trans[this.oxcf.cq_level];
      if (oxcfNew.fixed_q >= 0) {
         this.oxcf.fixed_q = OnyxIf.q_trans[oxcfNew.worst_allowed_q];
         this.oxcf.alt_q = oxcfNew.alt_q < 0 ? OnyxIf.q_trans[0] : OnyxIf.q_trans[oxcfNew.alt_q];
         this.oxcf.key_q = oxcfNew.key_q < 0 ? OnyxIf.q_trans[0] : OnyxIf.q_trans[oxcfNew.key_q];
         this.oxcf.gold_q = oxcfNew.gold_q < 0 ? OnyxIf.q_trans[0] : OnyxIf.q_trans[oxcfNew.gold_q];
      }

      this.ext_refresh_frame_flags_pending = false;
      this.baseline_gf_interval = this.oxcf.alt_freq != 0 ? this.oxcf.alt_freq : 7;
      if (!this.oxcf.error_resilient_mode && this.oxcf.end_usage == 1 && this.oxcf.Mode == CompressMode.REALTIME) {
         this.baseline_gf_interval = this.gf_interval_onepass_cbr;
      }

      this.oxcf.token_partitions = 1;
      cm.multi_token_partition = this.oxcf.token_partitions;
      if (!this.use_roi_static_threshold) {
         for (int i = 0; i < 4; i++) {
            this.segment_encode_breakout[i] = this.oxcf.encode_breakout;
         }
      }

      if (this.oxcf.fixed_q > 127) {
         this.oxcf.fixed_q = 127;
      }

      if (this.oxcf.end_usage == 0) {
         this.definePlay();
      }

      int raw_target_rate = (int)((long)this.oxcf.Width * this.oxcf.Height * 8L * 3L * this.framerate / 1000.0);
      if (this.oxcf.target_bandwidth > raw_target_rate) {
         this.oxcf.target_bandwidth = raw_target_rate;
      }

      this.oxcf.target_bandwidth *= 1000;
      this.defineBuffers();
      this.vp8_new_framerate(this.framerate);
      this.defineQuality();
      this.buffered_mode = this.oxcf.optimal_buffer_level > 0L;
      this.cq_target_quality = this.oxcf.cq_level;
      this.drop_frames_allowed = this.oxcf.allow_df && this.buffered_mode;
      this.target_bandwidth = this.oxcf.target_bandwidth;
      if (this.oxcf.number_of_layers != prev_number_of_layers) {
         if (this.temporal_layer_id > 0) {
            this.temporal_layer_id = 0;
         }

         this.temporal_pattern_counter = 0;
         OnyxIf.reset_temporal_layer_change(this, prev_number_of_layers);
      }

      if (this.initial_width == 0) {
         this.initial_width = this.oxcf.Width;
      }

      cm.Width = this.oxcf.Width;
      cm.Height = this.oxcf.Height;
      if (this.oxcf.Sharpness > 7) {
         this.oxcf.Sharpness = 7;
      }

      cm.sharpness_level = this.oxcf.Sharpness;
      if (CommonData.horiz_scale != Scaling.NORMAL || CommonData.vert_scale != Scaling.NORMAL) {
         int hr = CommonData.horiz_scale.hr;
         int hs = CommonData.horiz_scale.hs;
         int vr = CommonData.vert_scale.hr;
         int vs = CommonData.vert_scale.hs;
         cm.Width = (hs - 1 + this.oxcf.Width * hr) / hs;
         cm.Height = (vs - 1 + this.oxcf.Height * vr) / vs;
      }

      if (last_w != this.oxcf.Width || last_h != this.oxcf.Height) {
         this.force_next_frame_intra = true;
      }

      if (cm.yv12_fb[0] == null
         || (cm.Width + 15 & -16) != cm.yv12_fb[cm.frameIdxs.get(MVReferenceFrame.LAST_FRAME)].y_width
         || (cm.Height + 15 & -16) != cm.yv12_fb[cm.frameIdxs.get(MVReferenceFrame.LAST_FRAME)].y_height
         || cm.yv12_fb[cm.frameIdxs.get(MVReferenceFrame.LAST_FRAME)].y_width == 0) {
         OnyxIf.dealloc_raw_frame_buffers(this);
         OnyxIf.alloc_raw_frame_buffers(this);
         OnyxIf.vp8_alloc_compressor_data(this);
      }

      if (this.oxcf.fixed_q >= 0) {
         this.last_q[0] = this.oxcf.fixed_q;
         this.last_q[1] = this.oxcf.fixed_q;
      }

      this.Speed = this.oxcf.getCpu_used();
      if (this.oxcf.lag_in_frames == 0) {
         this.oxcf.allow_lag = 0;
      } else if (this.oxcf.lag_in_frames > 25) {
         this.oxcf.lag_in_frames = 25;
      }

      this.alt_ref_source = null;
      this.is_src_frame_alt_ref = false;
   }

   private void defineQuality() {
      this.worst_quality = this.oxcf.worst_allowed_q;
      this.best_quality = this.oxcf.best_allowed_q;
      if (this.active_worst_quality > this.oxcf.worst_allowed_q) {
         this.active_worst_quality = this.oxcf.worst_allowed_q;
      } else if (this.active_worst_quality < this.oxcf.best_allowed_q) {
         this.active_worst_quality = this.oxcf.best_allowed_q;
      }

      if (this.active_best_quality < this.oxcf.best_allowed_q) {
         this.active_best_quality = this.oxcf.best_allowed_q;
      } else if (this.active_best_quality > this.oxcf.worst_allowed_q) {
         this.active_best_quality = this.oxcf.worst_allowed_q;
      }
   }

   private void defineBuffers() {
      this.oxcf.starting_buffer_level = OnyxIf.rescale((int)this.oxcf.starting_buffer_level, this.oxcf.target_bandwidth);
      if (this.oxcf.optimal_buffer_level == 0L) {
         this.oxcf.optimal_buffer_level = this.oxcf.target_bandwidth / 8;
      } else {
         this.oxcf.optimal_buffer_level = OnyxIf.rescale((int)this.oxcf.optimal_buffer_level, this.oxcf.target_bandwidth);
      }

      if (this.oxcf.maximum_buffer_size == 0L) {
         this.oxcf.maximum_buffer_size = this.oxcf.target_bandwidth / 8;
      } else {
         this.oxcf.maximum_buffer_size = OnyxIf.rescale((int)this.oxcf.maximum_buffer_size, this.oxcf.target_bandwidth);
      }

      if (this.bits_off_target > this.oxcf.maximum_buffer_size) {
         this.bits_off_target = this.oxcf.maximum_buffer_size;
         this.buffer_level = this.bits_off_target;
      }
   }

   private void defineCompressorSpeed() {
      switch (this.oxcf.Mode) {
         case REALTIME:
            this.compressor_speed = 2;
            break;
         case GOODQUALITY:
            this.compressor_speed = 1;
            break;
         case BESTQUALITY:
            this.compressor_speed = 0;
      }
   }

   private void definePlay() {
      this.oxcf.starting_buffer_level = 60000L;
      this.oxcf.optimal_buffer_level = 60000L;
      this.oxcf.maximum_buffer_size = 240000L;
      this.oxcf.starting_buffer_level_in_ms = 60000L;
      this.oxcf.optimal_buffer_level_in_ms = 60000L;
      this.oxcf.maximum_buffer_size_in_ms = 240000L;
   }

   void vp8_new_framerate(double framerate) {
      if (framerate < 0.1) {
         framerate = 30.0;
      }

      this.framerate = framerate;
      this.output_framerate = framerate;
      this.per_frame_bandwidth = (int)(this.oxcf.target_bandwidth / this.output_framerate);
      this.av_per_frame_bandwidth = this.per_frame_bandwidth;
      this.min_frame_bandwidth = this.av_per_frame_bandwidth / 100;
      this.max_gf_interval = (int)(this.output_framerate / 2.0) + 2;
      if (this.max_gf_interval < 12) {
         this.max_gf_interval = 12;
      }

      if (this.oxcf.play_alternate && this.oxcf.lag_in_frames != 0 && this.max_gf_interval > this.oxcf.lag_in_frames - 1) {
         this.max_gf_interval = this.oxcf.lag_in_frames - 1;
      }
   }

   interface FractionalMVStepIF {
      long call(Macroblock var1, Block var2, BlockD var3, MV var4, MV var5, int var6, VarianceFNs var7, GetPointer[] var8, VarianceResults var9);
   }
}
