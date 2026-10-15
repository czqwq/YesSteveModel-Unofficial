package rip.ysm.imagestream.webp.enc;

class D63EPredictor4x4 implements IntraPredFN {
   @Override
   public void call(FullGetSetPointer dst, int stride, GetPointer above, GetPointer left) {
      short A = above.getRel(0);
      short B = above.getRel(1);
      short C = above.getRel(2);
      short D = above.getRel(3);
      short E = above.getRel(4);
      short F = above.getRel(5);
      short G = above.getRel(6);
      short H = above.getRel(7);
      D63Predictor4x4.vpx_d63_helper(
         dst,
         stride,
         W.avg2(A, B),
         W.avg2(B, C),
         W.avg2(C, D),
         W.avg2(D, E),
         W.avg3(E, F, G),
         W.avg3(A, B, C),
         W.avg3(B, C, D),
         W.avg3(C, D, E),
         W.avg3(D, E, F),
         W.avg3(F, G, H)
      );
   }
}
