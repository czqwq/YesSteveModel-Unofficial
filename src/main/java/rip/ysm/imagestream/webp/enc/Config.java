package rip.ysm.imagestream.webp.enc;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

final class Config {
   int Version;
   int Width;
   int Height;
   Rational timebase = Rational.ONE;
   int target_bandwidth;
   int noise_sensitivity;
   int Sharpness;
   private int cpu_used;
   int rc_max_intra_bitrate_pct;
   int gf_cbr_boost_pct;
   int screen_content_mode;
   CompressMode Mode = CompressMode.BESTQUALITY;
   boolean auto_key;
   int key_freq;
   int allow_lag;
   int lag_in_frames;
   int end_usage;
   int under_shoot_pct;
   int over_shoot_pct;
   long starting_buffer_level;
   long optimal_buffer_level;
   long maximum_buffer_size;
   long starting_buffer_level_in_ms;
   long optimal_buffer_level_in_ms;
   long maximum_buffer_size_in_ms;
   short fixed_q;
   short worst_allowed_q;
   short best_allowed_q;
   short cq_level;
   boolean allow_spatial_resampling;
   int resample_down_water_mark;
   int resample_up_water_mark;
   boolean allow_df;
   int drop_frames_water_mark;
   boolean play_alternate;
   int alt_freq;
   short alt_q;
   short key_q;
   short gold_q;
   int token_partitions;
   int encode_breakout;
   boolean error_resilient_mode;
   int arnr_max_frames;
   int arnr_strength;
   int arnr_type;
   List<CodecPkt> output_pkt_list = new ArrayList<>();
   int tuning;
   int number_of_layers;
   int[] target_bitrate = new int[16];
   int[] rate_decimator = new int[16];
   int periodicity;
   int[] layer_id = new int[16];

   private Config() {
   }

   Config(CodecEncCfg cfg, ExtraCFG vp8_cfg) {
      this.Version = cfg.getG_profile();
      this.Width = cfg.getG_w();
      this.Height = cfg.getG_h();
      this.timebase = cfg.getG_timebase();
      this.error_resilient_mode = cfg.isG_error_resilient();
      this.Mode = CompressMode.BESTQUALITY;
      this.allow_lag = 0;
      this.lag_in_frames = 0;
      this.allow_df = cfg.getRc_dropframe_thresh() > 0;
      this.drop_frames_water_mark = cfg.getRc_dropframe_thresh();
      this.allow_spatial_resampling = cfg.isRc_resize_allowed();
      this.resample_up_water_mark = cfg.getRc_resize_up_thresh();
      this.resample_down_water_mark = cfg.getRc_resize_down_thresh();
      switch (cfg.getRc_end_usage()) {
         case VPX_VBR:
            this.end_usage = 0;
            break;
         case VPX_CBR:
            this.end_usage = 1;
            break;
         case VPX_CQ:
            this.end_usage = 2;
            break;
         case VPX_Q:
            this.end_usage = 4;
      }

      this.target_bandwidth = cfg.getRc_target_bitrate();
      this.rc_max_intra_bitrate_pct = vp8_cfg.getRc_max_intra_bitrate_pct();
      this.gf_cbr_boost_pct = vp8_cfg.getGf_cbr_boost_pct();
      this.best_allowed_q = cfg.getRc_min_quantizer();
      this.worst_allowed_q = cfg.getRc_max_quantizer();
      this.cq_level = vp8_cfg.getCq_level();
      this.fixed_q = -1;
      this.under_shoot_pct = cfg.getRc_undershoot_pct();
      this.over_shoot_pct = cfg.getRc_overshoot_pct();
      this.maximum_buffer_size_in_ms = cfg.getRc_buf_sz();
      this.starting_buffer_level_in_ms = cfg.getRc_buf_initial_sz();
      this.optimal_buffer_level_in_ms = cfg.getRc_buf_optimal_sz();
      this.maximum_buffer_size = cfg.getRc_buf_sz();
      this.starting_buffer_level = cfg.getRc_buf_initial_sz();
      this.optimal_buffer_level = cfg.getRc_buf_optimal_sz();
      this.auto_key = cfg.getKf_mode() == CodecEncCfg.vpx_kf_mode.VPX_KF_AUTO && cfg.getKf_min_dist() != cfg.getKf_max_dist();
      this.key_freq = cfg.getKf_max_dist();
      this.number_of_layers = 1;
      this.periodicity = 0;
      this.encode_breakout = vp8_cfg.getStatic_thresh();
      this.play_alternate = vp8_cfg.isEnable_auto_alt_ref();
      this.noise_sensitivity = vp8_cfg.getNoise_sensitivity();
      this.Sharpness = vp8_cfg.getSharpness();
      this.token_partitions = vp8_cfg.getToken_partitions();
      this.output_pkt_list = vp8_cfg.getPkt_list();
      this.arnr_max_frames = vp8_cfg.getArnr_max_frames();
      this.arnr_strength = vp8_cfg.getArnr_strength();
      this.arnr_type = vp8_cfg.getArnr_type();
      this.tuning = vp8_cfg.getTuning();
      this.screen_content_mode = vp8_cfg.getScreen_content_mode();
      this.setCpu_used(vp8_cfg.getCpu_used());
   }

   Config copy() {
      Config n = new Config();
      n.allow_df = this.allow_df;
      n.allow_lag = this.allow_lag;
      n.allow_spatial_resampling = this.allow_spatial_resampling;
      n.alt_freq = this.alt_freq;
      n.alt_q = this.alt_q;
      n.arnr_max_frames = this.arnr_max_frames;
      n.arnr_strength = this.arnr_strength;
      n.arnr_type = this.arnr_type;
      n.auto_key = this.auto_key;
      n.best_allowed_q = this.best_allowed_q;
      n.cpu_used = this.cpu_used;
      n.cq_level = this.cq_level;
      n.drop_frames_water_mark = this.drop_frames_water_mark;
      n.encode_breakout = this.encode_breakout;
      n.end_usage = this.end_usage;
      n.error_resilient_mode = this.error_resilient_mode;
      n.fixed_q = this.fixed_q;
      n.gf_cbr_boost_pct = this.gf_cbr_boost_pct;
      n.gold_q = this.gold_q;
      n.Height = this.Height;
      n.key_freq = this.key_freq;
      n.key_q = this.key_q;
      n.lag_in_frames = this.lag_in_frames;
      n.layer_id = Arrays.copyOf(this.layer_id, this.layer_id.length);
      n.maximum_buffer_size = this.maximum_buffer_size;
      n.maximum_buffer_size_in_ms = this.maximum_buffer_size_in_ms;
      n.Mode = this.Mode;
      n.noise_sensitivity = this.noise_sensitivity;
      n.number_of_layers = this.number_of_layers;
      n.optimal_buffer_level = this.optimal_buffer_level;
      n.optimal_buffer_level_in_ms = this.optimal_buffer_level_in_ms;
      n.output_pkt_list = new ArrayList<>(this.output_pkt_list);
      n.over_shoot_pct = this.over_shoot_pct;
      n.periodicity = this.periodicity;
      n.play_alternate = this.play_alternate;
      n.rate_decimator = Arrays.copyOf(this.rate_decimator, this.rate_decimator.length);
      n.rc_max_intra_bitrate_pct = this.rc_max_intra_bitrate_pct;
      n.resample_down_water_mark = this.resample_down_water_mark;
      n.resample_up_water_mark = this.resample_up_water_mark;
      n.screen_content_mode = this.screen_content_mode;
      n.Sharpness = this.Sharpness;
      n.starting_buffer_level = this.starting_buffer_level;
      n.starting_buffer_level_in_ms = this.starting_buffer_level_in_ms;
      n.target_bandwidth = this.target_bandwidth;
      n.target_bitrate = Arrays.copyOf(this.target_bitrate, this.target_bitrate.length);
      n.timebase = this.timebase;
      n.token_partitions = this.token_partitions;
      n.tuning = this.tuning;
      n.under_shoot_pct = this.under_shoot_pct;
      n.Version = this.Version;
      n.Width = this.Width;
      n.worst_allowed_q = this.worst_allowed_q;
      return n;
   }

   int getCpu_used() {
      return this.cpu_used;
   }

   void setCpu_used(short cpu_used) {
      short low = -16;
      short up = 16;
      if (this.Mode == CompressMode.GOODQUALITY) {
         low = -5;
         up = 5;
      }

      this.cpu_used = CUtils.clamp(cpu_used, low, up);
   }
}
