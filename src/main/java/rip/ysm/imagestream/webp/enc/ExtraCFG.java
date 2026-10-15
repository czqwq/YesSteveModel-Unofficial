package rip.ysm.imagestream.webp.enc;

import java.util.List;

class ExtraCFG {
   private List<CodecPkt> pkt_list;
   private static final int token_partitions = 0;
   private static final int tuning = 0;

   List<CodecPkt> getPkt_list() {
      return this.pkt_list;
   }

   void setPkt_list(List<CodecPkt> pkt_list) {
      this.pkt_list = pkt_list;
   }

   short getCpu_used() {
      return 0;
   }

   boolean isEnable_auto_alt_ref() {
      return false;
   }

   int getNoise_sensitivity() {
      return 0;
   }

   int getSharpness() {
      return 0;
   }

   int getStatic_thresh() {
      return 0;
   }

   int getToken_partitions() {
      return 0;
   }

   int getArnr_max_frames() {
      return 0;
   }

   int getArnr_strength() {
      return 3;
   }

   int getArnr_type() {
      return 3;
   }

   int getTuning() {
      return 0;
   }

   short getCq_level() {
      return 10;
   }

   int getRc_max_intra_bitrate_pct() {
      return 0;
   }

   int getGf_cbr_boost_pct() {
      return 0;
   }

   int getScreen_content_mode() {
      return 0;
   }
}
