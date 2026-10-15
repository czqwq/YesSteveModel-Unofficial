package rip.ysm.imagestream.webp.enc;

class CodingContext {
   int frames_since_key;
   int frames_since_golden;
   short filter_level;
   int frames_till_gf_update_due;
   final MVContext[] mvc = new MVContext[2];
   final FullGetSetPointer[] mvcosts = new FullGetSetPointer[2];
   final FullGetSetPointer ymode_prob = new FullGetSetPointer(EntropyMode.vp8_ymode_prob.size());
   final FullGetSetPointer uv_mode_prob = new FullGetSetPointer(EntropyMode.vp8_uv_mode_prob.size());
   final int[] ymode_count = new int[5];
   final int[] uv_mode_count = new int[4];
   int this_frame_percent_intra;

   void vp8_save_coding_context(Compressor cpi) {
      this.frames_since_key = cpi.frames_since_key;
      this.filter_level = cpi.common.filter_level;
      this.frames_till_gf_update_due = cpi.frames_till_gf_update_due;
      this.frames_since_golden = cpi.frames_since_golden;
      this.mvc[0] = new MVContext(cpi.common.fc.mvc[0]);
      this.mvc[1] = new MVContext(cpi.common.fc.mvc[1]);
      this.mvcosts[0] = cpi.rd_costs.mvcosts[0].deepCopy();
      this.mvcosts[1] = cpi.rd_costs.mvcosts[1].deepCopy();
      CUtils.vp8_copy(cpi.common.fc.ymode_prob, this.ymode_prob);
      CUtils.vp8_copy(cpi.common.fc.uv_mode_prob, this.uv_mode_prob);
      CUtils.vp8_copy(cpi.mb.ymode_count, this.ymode_count);
      CUtils.vp8_copy(cpi.mb.uv_mode_count, this.uv_mode_count);
      this.this_frame_percent_intra = cpi.this_frame_percent_intra;
   }

   public void vp8_restore_coding_context(Compressor cpi) {
      cpi.frames_since_key = this.frames_since_key;
      cpi.common.filter_level = this.filter_level;
      cpi.frames_till_gf_update_due = this.frames_till_gf_update_due;
      cpi.frames_since_golden = this.frames_since_golden;
      cpi.common.fc.mvc[0] = new MVContext(this.mvc[0]);
      cpi.common.fc.mvc[1] = new MVContext(this.mvc[1]);
      cpi.rd_costs.mvcosts[0] = this.mvcosts[0].deepCopy();
      cpi.rd_costs.mvcosts[1] = this.mvcosts[1].deepCopy();
      CUtils.vp8_copy(this.ymode_prob, cpi.common.fc.ymode_prob);
      CUtils.vp8_copy(this.uv_mode_prob, cpi.common.fc.uv_mode_prob);
      CUtils.vp8_copy(this.ymode_count, cpi.mb.ymode_count);
      CUtils.vp8_copy(this.uv_mode_count, cpi.mb.uv_mode_count);
      cpi.this_frame_percent_intra = this.this_frame_percent_intra;
   }
}
