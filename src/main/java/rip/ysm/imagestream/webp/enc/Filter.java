package rip.ysm.imagestream.webp.enc;

abstract class Filter implements SubpixFN {
   static final int VP8_FILTER_WEIGHT = 128;
   static final int VP8_FILTER_SHIFT = 7;

   void filter_block2d_single_pass(
      GetPointer srcp,
      FullGetSetPointer output_ptr,
      int output_pitch,
      int src_pixels_per_line,
      int pixel_step,
      int output_height,
      int output_width,
      int[] vp8_filter
   ) {
      GetSetPointer src_ptr = GetSetPointer.makePositionable(srcp);

      for (int i = 0; i < output_height; i++) {
         int outbase = i * output_pitch;

         for (int j = 0; j < output_width; j++) {
            output_ptr.setRel(outbase + j, this.applyFilterCore(src_ptr, pixel_step, vp8_filter));
            src_ptr.inc();
         }

         src_ptr.incBy(src_pixels_per_line - output_width);
      }
   }

   protected abstract short applyFilterCore(GetPointer var1, int var2, int[] var3);
}
