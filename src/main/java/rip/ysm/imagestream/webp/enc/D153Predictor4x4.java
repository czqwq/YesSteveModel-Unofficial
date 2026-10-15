package rip.ysm.imagestream.webp.enc;

class D153Predictor4x4 implements IntraPredFN {
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
      dst.set(dst.setRel(2 + stride, W.avg2(I, X)));
      dst.setRel(stride, dst.setRel(2 + 2 * stride, W.avg2(J, I)));
      dst.setRel(2 * stride, dst.setRel(2 + 3 * stride, W.avg2(K, J)));
      dst.setRel(3 * stride, W.avg2(L, K));
      dst.setRel(3, W.avg3(A, B, C));
      dst.setRel(2, W.avg3(X, A, B));
      dst.setRel(1, dst.setRel(3 + stride, W.avg3(I, X, A)));
      dst.setRel(1 + stride, dst.setRel(3 + 2 * stride, W.avg3(J, I, X)));
      dst.setRel(1 + 2 * stride, dst.setRel(3 + 3 * stride, W.avg3(K, J, I)));
      dst.setRel(1 + 3 * stride, W.avg3(L, K, J));
   }
}
