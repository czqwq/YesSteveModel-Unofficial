package rip.ysm.imagestream.webp.enc;

import java.util.TreeMap;

class DefaultVarianceFNs {
   public final TreeMap<Integer, VarianceFNs> default_fn_ptr = new TreeMap<>();

   public DefaultVarianceFNs() {
      VarianceFNs tfn = new VarianceFNs();
      tfn.sdf = Sad.vpx_sad16x16;
      tfn.vf = Variance.vpx_variance16x16;
      tfn.svf = new SubpixelVariance(16, 16);
      tfn.sdx3f = Sad.vpx_sad16x16x3;
      tfn.sdx8f = Sad.vpx_sad16x16x8;
      tfn.sdx4df = Sad.vpx_sad16x16x4d;
      tfn.copymem = CUtils.vp8_copy32xn;
      this.default_fn_ptr.put(4, tfn);
      tfn = new VarianceFNs();
      tfn.sdf = Sad.vpx_sad16x8;
      tfn.vf = Variance.vpx_variance16x8;
      tfn.svf = new SubpixelVariance(16, 8);
      tfn.sdx3f = Sad.vpx_sad16x8x3;
      tfn.sdx8f = Sad.vpx_sad16x8x8;
      tfn.sdx4df = Sad.vpx_sad16x8x4d;
      tfn.copymem = CUtils.vp8_copy32xn;
      this.default_fn_ptr.put(0, tfn);
      tfn = new VarianceFNs();
      tfn.sdf = Sad.vpx_sad8x16;
      tfn.vf = Variance.vpx_variance8x16;
      tfn.svf = new SubpixelVariance(8, 16);
      tfn.sdx3f = Sad.vpx_sad8x16x3;
      tfn.sdx8f = Sad.vpx_sad8x16x8;
      tfn.sdx4df = Sad.vpx_sad8x16x4d;
      tfn.copymem = CUtils.vp8_copy32xn;
      this.default_fn_ptr.put(1, tfn);
      tfn = new VarianceFNs();
      tfn.sdf = Sad.vpx_sad8x8;
      tfn.vf = Variance.vpx_variance8x8;
      tfn.svf = new SubpixelVariance(8, 8);
      tfn.sdx3f = Sad.vpx_sad8x8x3;
      tfn.sdx8f = Sad.vpx_sad8x8x8;
      tfn.sdx4df = Sad.vpx_sad8x8x4d;
      tfn.copymem = CUtils.vp8_copy32xn;
      this.default_fn_ptr.put(2, tfn);
      tfn = new VarianceFNs();
      tfn.sdf = Sad.vpx_sad4x4;
      tfn.vf = Variance.vpx_variance4x4;
      tfn.svf = new SubpixelVariance(4, 4);
      tfn.sdx3f = Sad.vpx_sad4x4x3;
      tfn.sdx8f = Sad.vpx_sad4x4x8;
      tfn.sdx4df = Sad.vpx_sad4x4x4d;
      tfn.copymem = CUtils.vp8_copy32xn;
      this.default_fn_ptr.put(3, tfn);
   }
}
