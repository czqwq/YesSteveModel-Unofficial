package rip.ysm.imagestream.webp.enc;

class D135Predictor4x4 implements IntraPredFN {
   @Override
   public void call(FullGetSetPointer dst, int stride, GetPointer above, GetPointer left) {
      GetSetPointer pLeft = GetSetPointer.makePositionable(left);
      GetSetPointer pAbove = GetSetPointer.makePositionable(above);
      short I = pLeft.getAndInc();
      short J = pLeft.getAndInc();
      short K = pLeft.getAndInc();
      short L = pLeft.getAndInc();
      short X = pAbove.getRel(-1);
      short A = pAbove.getAndInc();
      short B = pAbove.getAndInc();
      short C = pAbove.getAndInc();
      short D = pAbove.getAndInc();
      dst.setRel(3 * stride, W.avg3(J, K, L));
      dst.setRel(1 + 3 * stride, dst.setRel(2 * stride, W.avg3(I, J, K)));
      dst.setRel(2 + 3 * stride, dst.setRel(1 + 2 * stride, dst.setRel(stride, W.avg3(X, I, J))));
      dst.setRel(3 + 3 * stride, dst.setRel(2 + 2 * stride, dst.setRel(1 + stride, dst.set(W.avg3(A, X, I)))));
      dst.setRel(3 + 2 * stride, dst.setRel(2 + stride, dst.setRel(1, W.avg3(B, A, X))));
      dst.setRel(3 + stride, dst.setRel(2, W.avg3(C, B, A)));
      dst.setRel(3, W.avg3(D, C, B));
   }
}
