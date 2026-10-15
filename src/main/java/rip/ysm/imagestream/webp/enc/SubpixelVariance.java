package rip.ysm.imagestream.webp.enc;

class SubpixelVariance implements VarianceFNs.SVF {
   static final int FILTER_BITS = 7;
   private final int w;
   private final int h;
   private final FullGetSetPointer biliX;
   private final FullGetSetPointer biliY;

   static void var_filter_block2d_bil(
      GetSetPointer src_ptr, FullGetSetPointer ref_ptr, int src_pixels_per_line, int pixel_step, int output_height, int output_width, int[] filter
   ) {
      int baseSrc = 0;
      int baseRef = 0;

      for (int i = 0; i < output_height; i++) {
         int basePS = baseSrc + pixel_step;

         for (int j = 0; j < output_width; j++) {
            ref_ptr.setRel(baseRef + j, (short)CUtils.roundPowerOfTwo(src_ptr.getRel(baseSrc + j) * filter[0] + src_ptr.getRel(basePS + j) * filter[1], 7));
         }

         baseSrc += src_pixels_per_line;
         baseRef += output_width;
      }
   }

   public SubpixelVariance(int w, int h) {
      this.w = w;
      this.h = h;
      this.biliX = new FullGetSetPointer((h + 1) * w);
      this.biliY = new FullGetSetPointer(h * w);
   }

   @Override
   public void call(GetSetPointer src_ptr, int src_stride, int xoff, int yoff, GetSetPointer ref_ptr, int ref_stride, VarianceResults sse) {
      var_filter_block2d_bil(src_ptr, this.biliX, src_stride, 1, this.h + 1, this.w, BilinearPredict.vp8_bilinear_filters[xoff]);
      var_filter_block2d_bil(this.biliX, this.biliY, this.w, this.w, this.h, this.w, BilinearPredict.vp8_bilinear_filters[yoff]);
      Variance.variance(this.biliY, this.w, ref_ptr, ref_stride, sse, this.w, this.h);
   }
}
