package rip.ysm.imagestream.webp.enc;

class D117Predictor4x4 implements IntraPredFN {
   @Override
   public void call(FullGetSetPointer dst, int stride, GetPointer above, GetPointer left) {
      GetSetPointer pLeft = GetSetPointer.makePositionable(left);
      GetSetPointer pAbove = GetSetPointer.makePositionable(above);
      short I = pLeft.getAndInc();
      short J = pLeft.getAndInc();
      short K = pLeft.getAndInc();
      short X = pAbove.getRel(-1);
      short A = pAbove.getAndInc();
      short B = pAbove.getAndInc();
      short C = pAbove.getAndInc();
      short D = pAbove.getAndInc();
      dst.set(dst.setRel(1 + 2 * stride, W.avg2(X, A)));
      dst.setRel(1, dst.setRel(2 + 2 * stride, W.avg2(A, B)));
      dst.setRel(2, dst.setRel(3 + 2 * stride, W.avg2(B, C)));
      dst.setRel(3, W.avg2(C, D));
      dst.setRel(3 * stride, W.avg3(K, J, I));
      dst.setRel(2 * stride, W.avg3(J, I, X));
      dst.setRel(stride, dst.setRel(1 + 3 * stride, W.avg3(I, X, A)));
      dst.setRel(1 + stride, dst.setRel(2 + 3 * stride, W.avg3(X, A, B)));
      dst.setRel(2 + stride, dst.setRel(3 + 3 * stride, W.avg3(A, B, C)));
      dst.setRel(3 + stride, W.avg3(B, C, D));
   }
}
