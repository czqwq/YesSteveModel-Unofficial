package rip.ysm.imagestream.webp.enc;

final class SixtapPredict extends Filter {
   public static final SubPixFnCollector sixtap = new SubPixFnCollector() {
      @Override
      public SubpixFN get8x8() {
         return new SixtapPredict(208, 13, 8, 8);
      }

      @Override
      public SubpixFN get16x16() {
         return new SixtapPredict(504, 21, 16, 16);
      }
   };
   private static final int[][] vp8_sub_pel_filters = new int[][]{
      {0, 0, 128, 0, 0, 0},
      {0, -6, 123, 12, -1, 0},
      {2, -11, 108, 36, -8, 1},
      {0, -9, 93, 50, -6, 0},
      {3, -16, 77, 77, -16, 3},
      {0, -6, 50, 93, -9, 0},
      {1, -8, 36, 108, -11, 2},
      {0, -1, 12, 123, -6, 0}
   };
   private static final int sixtapWeight = 64;
   private final int width;
   private final int height;
   private final int vfOnlyHeight;
   private final int vfFdataShift;
   private int[] HFilter;
   private int[] VFilter;
   private final FullGetSetPointer FData;

   private SixtapPredict(int fdatasize, int height, int width, int vfH) {
      this.width = width;
      this.height = height;
      this.vfOnlyHeight = vfH;
      this.vfFdataShift = width << 1;
      this.FData = new FullGetSetPointer(fdatasize);
   }

   @Override
   protected short applyFilterCore(GetPointer src_ptr, int pixel_step, int[] vp8_filter) {
      int Temp = 0;
      int k = -2;

      for (int fi = 0; k < 4; fi++) {
         Temp += src_ptr.getRel(k * pixel_step) * vp8_filter[fi];
         k++;
      }

      Temp += 64;
      return CUtils.clipPixel((short)(Temp >> 7));
   }

   private void getFilters(int xoff, int yoff) {
      this.HFilter = vp8_sub_pel_filters[xoff];
      this.VFilter = vp8_sub_pel_filters[yoff];
   }

   void filter_block2d(GetPointer src_ptr, FullGetSetPointer output_ptr, int src_pixels_per_line, int output_pitch) {
      this.FData.rewind();
      this.filter_block2d_single_pass(
         GetSetPointer.makePositionableAndInc(src_ptr, -(2 * src_pixels_per_line)),
         this.FData,
         this.width,
         src_pixels_per_line,
         1,
         this.height,
         this.width,
         this.HFilter
      );
      this.FData.incBy(this.vfFdataShift);
      this.filter_block2d_single_pass(this.FData, output_ptr, output_pitch, this.width, this.width, this.vfOnlyHeight, this.width, this.VFilter);
   }

   @Override
   public void call(GetPointer src, int src_pixels_per_line, int xoffset, int yoffset, FullGetSetPointer dst_ptr, int dst_pitch) {
      this.getFilters(xoffset, yoffset);
      this.filter_block2d(src, dst_ptr, src_pixels_per_line, dst_pitch);
   }
}
