package rip.ysm.imagestream.webp.enc;

final class IDCTBlk {
   private IDCTBlk() {
   }

   static void vp8_dequant_idct_add(FullGetSetPointer input, GetPointer dq, FullGetSetPointer dest, int stride) {
      for (int i = 0; i < 16; i++) {
         input.setRel(i, (short)(dq.getRel(i) * input.getRel(i)));
      }

      IDCTllm.vp8_short_idct4x4llm(input, dest, stride, dest, stride);
      input.memset(0, (short)0, 16);
   }

   static void vp8_dequant_idct_add_core(int imax, int jmax, GetSetPointer eobs, FullGetSetPointer q, GetPointer dq, FullGetSetPointer dst, int stride) {
      int dstinc = stride - jmax << 2;

      for (int i = 0; i < imax; i++) {
         for (int j = 0; j < jmax; j++) {
            if (eobs.get() > 1) {
               vp8_dequant_idct_add(q, dq, dst, stride);
            } else {
               IDCTllm.vp8_dc_only_idct_add(q.get() * dq.get(), dst, stride, dst, stride);
               q.memset(0, (short)0, 2);
            }

            eobs.inc();
            q.incBy(16);
            dst.incBy(4);
         }

         dst.incBy(dstinc);
      }
   }

   static void vp8_dequant_idct_add_y_block(FullGetSetPointer q, GetPointer dq, FullGetSetPointer dst, int stride, GetSetPointer eobs) {
      int ep = eobs.getPos();
      int qp = q.getPos();
      int dp = dst.getPos();
      vp8_dequant_idct_add_core(4, 4, eobs, q, dq, dst, stride);
      eobs.setPos(ep);
      q.setPos(qp);
      dst.setPos(dp);
   }

   static void vp8_dequant_idct_add_uv_block(
      FullGetSetPointer q, GetPointer dq, FullGetSetPointer dst_u, FullGetSetPointer dst_v, int stride, GetSetPointer eobs
   ) {
      int ep = eobs.getPos();
      int qp = q.getPos();
      int dup = dst_u.getPos();
      int dvp = dst_v.getPos();
      vp8_dequant_idct_add_core(2, 2, eobs, q, dq, dst_u, stride);
      vp8_dequant_idct_add_core(2, 2, eobs, q, dq, dst_v, stride);
      eobs.setPos(ep);
      q.setPos(qp);
      dst_u.setPos(dup);
      dst_v.setPos(dvp);
   }
}
