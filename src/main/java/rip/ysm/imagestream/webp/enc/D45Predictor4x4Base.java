package rip.ysm.imagestream.webp.enc;

class D45Predictor4x4Base implements IntraPredFN {
   private final boolean avgCalc;

   D45Predictor4x4Base(boolean avgCalc) {
      this.avgCalc = avgCalc;
   }

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
      dst.set(W.avg3(A, B, C));
      dst.setRel(1, dst.setRel(stride, W.avg3(B, C, D)));
      dst.setRel(2, dst.setRel(1 + stride, dst.setRel(2 * stride, W.avg3(C, D, E))));
      dst.setRel(3, dst.setRel(2 + stride, dst.setRel(1 + 2 * stride, dst.setRel(3 * stride, W.avg3(D, E, F)))));
      dst.setRel(3 + stride, dst.setRel(2 + 2 * stride, dst.setRel(1 + 3 * stride, W.avg3(E, F, G))));
      dst.setRel(3 + 2 * stride, dst.setRel(2 + 3 * stride, W.avg3(F, G, H)));
      dst.setRel(3 + 3 * stride, this.avgCalc ? W.avg3(G, H, H) : H);
   }
}
