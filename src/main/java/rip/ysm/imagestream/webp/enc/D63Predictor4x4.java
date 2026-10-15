package rip.ysm.imagestream.webp.enc;

class D63Predictor4x4 implements IntraPredFN {
   @Override
   public void call(FullGetSetPointer dst, int stride, GetPointer above, GetPointer left) {
      short A = above.getRel(0);
      short B = above.getRel(1);
      short C = above.getRel(2);
      short D = above.getRel(3);
      short E = above.getRel(4);
      short F = above.getRel(5);
      short G = above.getRel(6);
      vpx_d63_helper(
         dst,
         stride,
         W.avg2(A, B),
         W.avg2(B, C),
         W.avg2(C, D),
         W.avg2(D, E),
         W.avg2(E, F),
         W.avg3(A, B, C),
         W.avg3(B, C, D),
         W.avg3(C, D, E),
         W.avg3(D, E, F),
         W.avg3(E, F, G)
      );
   }

   static void vpx_d63_helper(FullGetSetPointer dst, int stride, short... vals) {
      dst = dst.shallowCopy();
      dst.set(vals[0]);
      dst.setRel(1, dst.setRel(2 * stride, vals[1]));
      dst.setRel(2, dst.setRel(1 + 2 * stride, vals[2]));
      dst.setRel(3, dst.setRel(2 + 2 * stride, vals[3]));
      dst.setRel(3 + 2 * stride, vals[4]);
      dst.setRel(stride, vals[5]);
      dst.setRel(1 + stride, dst.setRel(3 * stride, vals[6]));
      dst.setRel(2 + stride, dst.setRel(1 + 3 * stride, vals[7]));
      dst.setRel(3 + stride, dst.setRel(2 + 3 * stride, vals[8]));
      dst.setRel(3 + 3 * stride, vals[9]);
   }
}
