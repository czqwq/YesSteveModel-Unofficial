package rip.ysm.imagestream.webp.enc;

final class FrameContext {
   final FullGetSetPointer bmode_prob = new FullGetSetPointer(EntropyMode.vp8_bmode_prob.size());
   final FullGetSetPointer ymode_prob = new FullGetSetPointer(EntropyMode.vp8_ymode_prob.size());
   final FullGetSetPointer uv_mode_prob = new FullGetSetPointer(EntropyMode.vp8_uv_mode_prob.size());
   final FullGetSetPointer sub_mv_ref_prob = new FullGetSetPointer(EntropyMode.sub_mv_ref_prob.size());
   final short[][][][] coef_probs = new short[4][8][3][11];
   final MVContext[] mvc = new MVContext[2];

   FrameContext(FrameContext other) {
      CUtils.vp8_copy(other.bmode_prob, this.bmode_prob);
      CUtils.vp8_copy(other.ymode_prob, this.ymode_prob);
      CUtils.vp8_copy(other.uv_mode_prob, this.uv_mode_prob);
      CUtils.vp8_copy(other.sub_mv_ref_prob, this.sub_mv_ref_prob);
      CUtils.vp8_copy(other.coef_probs, this.coef_probs);

      for (int i = 0; i < this.mvc.length; i++) {
         this.mvc[i] = new MVContext(other.mvc[i]);
      }
   }

   FrameContext() {
      this.vp8_init_mbmode_probs();
      this.toDefault();
   }

   public void vp8_init_mbmode_probs() {
      CUtils.vp8_copy(EntropyMode.vp8_bmode_prob, this.bmode_prob);
      CUtils.vp8_copy(EntropyMode.vp8_ymode_prob, this.ymode_prob);
      CUtils.vp8_copy(EntropyMode.vp8_uv_mode_prob, this.uv_mode_prob);
      CUtils.vp8_copy(EntropyMode.sub_mv_ref_prob, this.sub_mv_ref_prob);
   }

   public void vp8_default_coef_probs() {
      CUtils.vp8_copy(W.tokenDefaultBinProbs, this.coef_probs);
   }

   public void vp8_default_mvctxt() {
      this.mvc[0] = new MVContext(MVContext.vp8_default_mv_context[0]);
      this.mvc[1] = new MVContext(MVContext.vp8_default_mv_context[1]);
   }

   public void toDefault() {
      this.vp8_default_coef_probs();
      this.vp8_default_mvctxt();
   }
}
