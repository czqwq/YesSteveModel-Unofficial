package rip.ysm.imagestream.webp.enc;

final class Variance {
   static final VarianceFNs.VF vpx_variance16x16 = (src_ptr, src_stride, ref_ptr, ref_stride, sse) -> variance(
      src_ptr, src_stride, ref_ptr, ref_stride, sse, 16, 16
   );
   static final VarianceFNs.VF vpx_variance16x8 = (src_ptr, src_stride, ref_ptr, ref_stride, sse) -> variance(
      src_ptr, src_stride, ref_ptr, ref_stride, sse, 16, 8
   );
   static final VarianceFNs.VF vpx_variance8x16 = (src_ptr, src_stride, ref_ptr, ref_stride, sse) -> variance(
      src_ptr, src_stride, ref_ptr, ref_stride, sse, 8, 16
   );
   static final VarianceFNs.VF vpx_variance8x8 = (src_ptr, src_stride, ref_ptr, ref_stride, sse) -> variance(
      src_ptr, src_stride, ref_ptr, ref_stride, sse, 8, 8
   );
   static final VarianceFNs.VF vpx_variance4x4 = (src_ptr, src_stride, ref_ptr, ref_stride, sse) -> variance(
      src_ptr, src_stride, ref_ptr, ref_stride, sse, 4, 4
   );
   static final VarianceFNs.VF vpx_mse16x16 = new VarianceFNs.VF() {
      @Override
      public void call(GetSetPointer src_ptr, int src_stride, GetSetPointer ref_ptr, int ref_stride, VarianceResults sse) {
         Variance.variance(src_ptr, src_stride, ref_ptr, ref_stride, sse, 16, 16, Variance.skipVar);
      }
   };
   private static final VarCalc calcVar = (ret, w, h, sum) -> ret.variance = ret.sse - sum * sum / ((long)w * h);
   private static final VarCalc skipVar = (ret, w, h, sum) -> ret.variance = Long.MAX_VALUE;

   private Variance() {
   }

   static int vpx_get4x4sse_cs(GetSetPointer src_ptr, int src_stride, GetSetPointer ref_ptr) {
      VarianceResults sse = new VarianceResults();
      variance(src_ptr, src_stride, ref_ptr, 16, sse, 4, 4, skipVar);
      return sse.sse;
   }

   static void variance(GetSetPointer src_ptr, int src_stride, GetSetPointer ref_ptr, int ref_stride, VarianceResults ret, int w, int h) {
      variance(src_ptr, src_stride, ref_ptr, ref_stride, ret, w, h, calcVar);
   }

   private static void variance(
      GetSetPointer src_ptr, int src_stride, GetSetPointer ref_ptr, int ref_stride, VarianceResults ret, int w, int h, VarCalc varcalc
   ) {
      long sum = 0L;
      ret.sse = 0;

      for (int i = 0; i < h; i++) {
         int baseSrc = i * src_stride;
         int baseRef = i * ref_stride;

         for (int j = 0; j < w; j++) {
            int diff = src_ptr.getRel(baseSrc + j) - ref_ptr.getRel(baseRef + j);
            sum += diff;
            ret.sse += diff * diff;
         }
      }

      varcalc.call(ret, w, h, sum);
   }

   interface VarCalc {
      void call(VarianceResults var1, int var2, int var3, long var4);
   }
}
