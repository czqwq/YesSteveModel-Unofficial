package rip.ysm.imagestream.webp.enc;

final class Sad {
   static final VarianceFNs.SDF vpx_sad16x16 = (src_ptr, src_stride, ref_ptr, ref_stride) -> sad(src_ptr, src_stride, ref_ptr, ref_stride, 16, 16);
   static final VarianceFNs.SDF vpx_sad16x8 = (src_ptr, src_stride, ref_ptr, ref_stride) -> sad(src_ptr, src_stride, ref_ptr, ref_stride, 16, 8);
   static final VarianceFNs.SDF vpx_sad8x16 = (src_ptr, src_stride, ref_ptr, ref_stride) -> sad(src_ptr, src_stride, ref_ptr, ref_stride, 8, 16);
   static final VarianceFNs.SDF vpx_sad8x8 = (src_ptr, src_stride, ref_ptr, ref_stride) -> sad(src_ptr, src_stride, ref_ptr, ref_stride, 8, 8);
   static final VarianceFNs.SDF vpx_sad4x4 = (src_ptr, src_stride, ref_ptr, ref_stride) -> sad(src_ptr, src_stride, ref_ptr, ref_stride, 4, 4);
   static final VarianceFNs.SDXF vpx_sad16x16x3 = (src_ptr, src_stride, ref_ptr, ref_stride, sad_array) -> sadToArray(
      src_ptr, src_stride, ref_ptr, ref_stride, sad_array, 16, 16, 3
   );
   static final VarianceFNs.SDXF vpx_sad16x8x3 = (src_ptr, src_stride, ref_ptr, ref_stride, sad_array) -> sadToArray(
      src_ptr, src_stride, ref_ptr, ref_stride, sad_array, 16, 8, 3
   );
   static final VarianceFNs.SDXF vpx_sad8x16x3 = (src_ptr, src_stride, ref_ptr, ref_stride, sad_array) -> sadToArray(
      src_ptr, src_stride, ref_ptr, ref_stride, sad_array, 8, 16, 3
   );
   static final VarianceFNs.SDXF vpx_sad8x8x3 = (src_ptr, src_stride, ref_ptr, ref_stride, sad_array) -> sadToArray(
      src_ptr, src_stride, ref_ptr, ref_stride, sad_array, 8, 8, 3
   );
   static final VarianceFNs.SDXF vpx_sad4x4x3 = (src_ptr, src_stride, ref_ptr, ref_stride, sad_array) -> sadToArray(
      src_ptr, src_stride, ref_ptr, ref_stride, sad_array, 4, 4, 3
   );
   static final VarianceFNs.SDXF vpx_sad16x16x8 = (src_ptr, src_stride, ref_ptr, ref_stride, sad_array) -> sadToArray(
      src_ptr, src_stride, ref_ptr, ref_stride, sad_array, 16, 16, 8
   );
   static final VarianceFNs.SDXF vpx_sad16x8x8 = (src_ptr, src_stride, ref_ptr, ref_stride, sad_array) -> sadToArray(
      src_ptr, src_stride, ref_ptr, ref_stride, sad_array, 16, 8, 8
   );
   static final VarianceFNs.SDXF vpx_sad8x16x8 = (src_ptr, src_stride, ref_ptr, ref_stride, sad_array) -> sadToArray(
      src_ptr, src_stride, ref_ptr, ref_stride, sad_array, 8, 16, 8
   );
   static final VarianceFNs.SDXF vpx_sad8x8x8 = (src_ptr, src_stride, ref_ptr, ref_stride, sad_array) -> sadToArray(
      src_ptr, src_stride, ref_ptr, ref_stride, sad_array, 8, 8, 8
   );
   static final VarianceFNs.SDXF vpx_sad4x4x8 = (src_ptr, src_stride, ref_ptr, ref_stride, sad_array) -> sadToArray(
      src_ptr, src_stride, ref_ptr, ref_stride, sad_array, 4, 4, 8
   );
   static final VarianceFNs.SDXF vpx_sad16x16x4d = (src_ptr, src_stride, ref_ptr, ref_stride, sad_array) -> sadToArray(
      src_ptr, src_stride, ref_ptr, ref_stride, sad_array, 16, 16, 4
   );
   static final VarianceFNs.SDXF vpx_sad16x8x4d = (src_ptr, src_stride, ref_ptr, ref_stride, sad_array) -> sadToArray(
      src_ptr, src_stride, ref_ptr, ref_stride, sad_array, 16, 8, 4
   );
   static final VarianceFNs.SDXF vpx_sad8x16x4d = (src_ptr, src_stride, ref_ptr, ref_stride, sad_array) -> sadToArray(
      src_ptr, src_stride, ref_ptr, ref_stride, sad_array, 8, 16, 4
   );
   static final VarianceFNs.SDXF vpx_sad8x8x4d = (src_ptr, src_stride, ref_ptr, ref_stride, sad_array) -> sadToArray(
      src_ptr, src_stride, ref_ptr, ref_stride, sad_array, 8, 8, 4
   );
   static final VarianceFNs.SDXF vpx_sad4x4x4d = (src_ptr, src_stride, ref_ptr, ref_stride, sad_array) -> sadToArray(
      src_ptr, src_stride, ref_ptr, ref_stride, sad_array, 4, 4, 4
   );

   private Sad() {
   }

   static int sad(GetSetPointer src_ptr, int src_stride, GetSetPointer ref_ptr, int ref_stride, int width, int height) {
      int sad = 0;

      for (int y = 0; y < height; y++) {
         int basesrc = y * src_stride;
         int refsrc = y * ref_stride;

         for (int x = 0; x < width; x++) {
            sad += Math.abs(src_ptr.getRel(basesrc + x) - ref_ptr.getRel(refsrc + x));
         }
      }

      return sad;
   }

   static void sadToArray(GetSetPointer src_ptr, int src_stride, GetSetPointer ref_ptr, int ref_stride, int[] sad_array, int width, int height, int k) {
      for (int i = 0; i < k; i++) {
         sad_array[i] = sad(src_ptr, src_stride, GetSetPointer.makePositionableAndInc(ref_ptr, i), ref_stride, width, height);
      }
   }
}
