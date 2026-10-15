package rip.ysm.imagestream.webp.enc;

import java.util.EnumMap;

class LayerContext {
   double framerate;
   int target_bandwidth;
   long starting_buffer_level;
   long optimal_buffer_level;
   long maximum_buffer_size;
   long starting_buffer_level_in_ms;
   long optimal_buffer_level_in_ms;
   long maximum_buffer_size_in_ms;
   int avg_frame_size_for_layer;
   long buffer_level;
   long bits_off_target;
   long total_actual_bits;
   short active_worst_quality;
   short active_best_quality;
   short ni_av_qi;
   int ni_tot_qi;
   int ni_frames;
   short avg_frame_qindex;
   double rate_correction_factor;
   double key_frame_rate_correction_factor;
   double gf_rate_correction_factor;
   int zbin_over_quant;
   int inter_frame_target;
   long total_byte_count;
   short filter_level;
   int frames_since_last_drop_overshoot;
   int force_maxqp;
   int last_frame_percent_intra;
   final EnumMap<MVReferenceFrame, Integer> count_mb_ref_frame_usage = new EnumMap<>(MVReferenceFrame.class);
   final short[] last_q = new short[2];

   LayerContext(Compressor cpi, Config oxcf, int layer, double prev_layer_framerate) {
      this.framerate = cpi.output_framerate / cpi.oxcf.rate_decimator[layer];
      this.target_bandwidth = cpi.oxcf.target_bitrate[layer] * 1000;
      this.starting_buffer_level_in_ms = oxcf.starting_buffer_level;
      this.optimal_buffer_level_in_ms = oxcf.optimal_buffer_level;
      this.maximum_buffer_size_in_ms = oxcf.maximum_buffer_size;
      this.starting_buffer_level = OnyxIf.rescale((int)oxcf.starting_buffer_level, this.target_bandwidth);
      if (oxcf.optimal_buffer_level == 0L) {
         this.optimal_buffer_level = this.target_bandwidth / 8;
      } else {
         this.optimal_buffer_level = OnyxIf.rescale((int)oxcf.optimal_buffer_level, this.target_bandwidth);
      }

      if (oxcf.maximum_buffer_size == 0L) {
         this.maximum_buffer_size = this.target_bandwidth / 8;
      } else {
         this.maximum_buffer_size = OnyxIf.rescale((int)oxcf.maximum_buffer_size, this.target_bandwidth);
      }

      if (layer > 0) {
         this.avg_frame_size_for_layer = (int)(
            (cpi.oxcf.target_bitrate[layer] - cpi.oxcf.target_bitrate[layer - 1]) * 1000 / (this.framerate - prev_layer_framerate)
         );
      }

      this.active_worst_quality = cpi.oxcf.worst_allowed_q;
      this.active_best_quality = cpi.oxcf.best_allowed_q;
      this.avg_frame_qindex = cpi.oxcf.worst_allowed_q;
      this.buffer_level = this.starting_buffer_level;
      this.bits_off_target = this.starting_buffer_level;
      this.total_actual_bits = 0L;
      this.ni_av_qi = 0;
      this.ni_tot_qi = 0;
      this.ni_frames = 0;
      this.rate_correction_factor = 1.0;
      this.key_frame_rate_correction_factor = 1.0;
      this.gf_rate_correction_factor = 1.0;
      this.inter_frame_target = 0;
   }
}
