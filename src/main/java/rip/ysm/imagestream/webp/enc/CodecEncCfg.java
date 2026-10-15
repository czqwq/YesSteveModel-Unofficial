package rip.ysm.imagestream.webp.enc;

import rip.ysm.imagestream.internal.LogWriter;

class CodecEncCfg {
   private int g_w;
   private int g_h;
   private final Rational g_timebase = Rational.R((byte)1, (byte)25);
   private static final vpx_rc_mode rc_end_usage = vpx_rc_mode.VPX_VBR;
   private short rc_max_quantizer = 63;
   private static final vpx_kf_mode kf_mode = vpx_kf_mode.VPX_KF_AUTO;
   private static final int kf_min_dist = 0;

   static void rangeCheck(int v, int min, int max) {
      if (v < min || v > max) {
         LogWriter.writeLog("range error");
      }
   }

   int getG_profile() {
      int g_profile = 0;
      return 0;
   }

   int getG_w() {
      return this.g_w;
   }

   void setG_w(int g_w) {
      rangeCheck(g_w, 1, 16383);
      this.g_w = g_w;
   }

   int getG_h() {
      return this.g_h;
   }

   void setG_h(int g_h) {
      rangeCheck(g_h, 1, 16383);
      this.g_h = g_h;
   }

   Rational getG_timebase() {
      return this.g_timebase;
   }

   boolean isG_error_resilient() {
      boolean g_error_resilient = false;
      return false;
   }

   int getRc_dropframe_thresh() {
      int rc_dropframe_thresh = 0;
      return 0;
   }

   boolean isRc_resize_allowed() {
      boolean rc_resize_allowed = false;
      return false;
   }

   int getRc_resize_up_thresh() {
      int rc_resize_up_thresh = 60;
      return 60;
   }

   int getRc_resize_down_thresh() {
      int rc_resize_down_thresh = 30;
      return 30;
   }

   vpx_rc_mode getRc_end_usage() {
      return rc_end_usage;
   }

   int getRc_target_bitrate() {
      int rc_target_bitrate = 256;
      return 256;
   }

   short getRc_min_quantizer() {
      short rc_min_quantizer = 4;
      return 4;
   }

   short getRc_max_quantizer() {
      return this.rc_max_quantizer;
   }

   void setRc_max_quantizer(short rc_max_quantizer) {
      rangeCheck(rc_max_quantizer, 0, 63);
      this.rc_max_quantizer = rc_max_quantizer;
   }

   int getRc_undershoot_pct() {
      int rc_undershoot_pct = 100;
      return 100;
   }

   int getRc_overshoot_pct() {
      int rc_overshoot_pct = 100;
      return 100;
   }

   int getRc_buf_sz() {
      int rc_buf_sz = 6000;
      return 6000;
   }

   int getRc_buf_initial_sz() {
      int rc_buf_initial_sz = 4000;
      return 4000;
   }

   int getRc_buf_optimal_sz() {
      int rc_buf_optimal_sz = 5000;
      return 5000;
   }

   vpx_kf_mode getKf_mode() {
      return kf_mode;
   }

   int getKf_min_dist() {
      return 0;
   }

   int getKf_max_dist() {
      int kf_max_dist = 128;
      return 128;
   }

   static enum vpx_kf_mode {
      VPX_KF_AUTO;
   }

   static enum vpx_rc_mode {
      VPX_VBR,
      VPX_CBR,
      VPX_CQ,
      VPX_Q;
   }
}
