package rip.ysm.imagestream.webp.enc;

class VEPredictor4x4 implements IntraPredFN {
   @Override
   public void call(FullGetSetPointer dst, int stride, GetPointer above, GetPointer left) {
      dst = dst.shallowCopy();
      GetSetPointer pAbove = GetSetPointer.makePositionable(above);
      short H = pAbove.getRel(-1);
      short I = pAbove.getAndInc();
      short J = pAbove.getAndInc();
      short K = pAbove.getAndInc();
      short L = pAbove.getAndInc();
      short M = pAbove.getAndInc();
      dst.set(W.avg3(H, I, J));
      dst.setRel(1, W.avg3(I, J, K));
      dst.setRel(2, W.avg3(J, K, L));
      dst.setRel(3, W.avg3(K, L, M));
      dst.memcopyin(stride, dst, 0, 4);
      dst.memcopyin(stride * 2, dst, 0, 4);
      dst.memcopyin(stride * 3, dst, 0, 4);
   }
}
