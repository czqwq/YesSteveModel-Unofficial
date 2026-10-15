package rip.ysm.imagestream.webp.enc;

final class BilinearPredict extends Filter {
   static final int[][] vp8_bilinear_filters = new int[][]{{128, 0}, {112, 16}, {96, 32}, {80, 48}, {64, 64}, {48, 80}, {32, 96}, {16, 112}};
   static final SubPixFnCollector bilinear = new SubPixFnCollector() {
      @Override
      public SubpixFN get8x8() {
         return new BilinearPredict(8, 8);
      }

      @Override
      public SubpixFN get16x16() {
         return new BilinearPredict(16, 16);
      }
   };
   private static final int BILINEARWEIGHT = 64;
   private int[] VFilter;
   private int[] HFilter;
   private final int width;
   private final int height;
   private final FullGetSetPointer FData = new FullGetSetPointer(272);

   private BilinearPredict(int width, int height) {
      this.width = width;
      this.height = height;
   }

   @Override
   protected short applyFilterCore(GetPointer src_ptr, int pixel_step, int[] vp8_filter) {
      return (short)(src_ptr.get() * vp8_filter[0] + src_ptr.getRel(1) * vp8_filter[1] + 64 >> 7);
   }

   @Override
   public void call(GetPointer src, int src_pixels_per_line, int xoffset, int yoffset, FullGetSetPointer dst_ptr, int dst_pitch) {
      this.getFilters(xoffset, yoffset);
      this.filter_block2d_bil(src, dst_ptr, src_pixels_per_line, dst_pitch);
   }

   private void getFilters(int xoff, int yoff) {
      this.HFilter = vp8_bilinear_filters[xoff];
      this.VFilter = vp8_bilinear_filters[yoff];
   }

   private void filter_block2d_bil(GetPointer src_ptr, FullGetSetPointer dst_ptr, int src_pitch, int dst_pitch) {
      this.FData.rewind();
      this.filter_block2d_single_pass(src_ptr, this.FData, this.width, this.width, src_pitch, this.height + 1, this.width, this.HFilter);
      this.filter_block2d_single_pass(this.FData, dst_ptr, this.width, this.width, dst_pitch, this.height, this.width, this.VFilter);
   }
}
