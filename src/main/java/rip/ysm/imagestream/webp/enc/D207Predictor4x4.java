package rip.ysm.imagestream.webp.enc;

class D207Predictor4x4 implements IntraPredFN {
   @Override
   public void call(FullGetSetPointer dst, int stride, GetPointer above, GetPointer left) {
      dst = dst.shallowCopy();
      GetSetPointer pLeft = GetSetPointer.makePositionable(left);
      short I = pLeft.getAndInc();
      short J = pLeft.getAndInc();
      short K = pLeft.getAndInc();
      short L = pLeft.getAndInc();
      dst.set(W.avg2(I, J));
      dst.setRel(2, dst.setRel(stride, W.avg2(J, K)));
      dst.setRel(2 + stride, dst.setRel(2 * stride, W.avg2(K, L)));
      dst.setRel(1, W.avg3(I, J, K));
      dst.setRel(3, dst.setRel(stride + 1, W.avg3(J, K, L)));
      dst.setRel(3 + stride, dst.setRel(1 + 2 * stride, W.avg3(K, L, L)));
      dst.setRel(
         3 + 2 * stride,
         dst.setRel(2 + 2 * stride, dst.setRel(3 * stride, dst.setRel(1 + 3 * stride, dst.setRel(2 + 3 * stride, dst.setRel(3 + 3 * stride, L)))))
      );
   }
}
