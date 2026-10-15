package rip.ysm.imagestream.webp.enc;

class HEPredictor4x4 implements IntraPredFN {
   @Override
   public void call(FullGetSetPointer dst, int stride, GetPointer above, GetPointer left) {
      dst = dst.shallowCopy();
      GetSetPointer pLeft = GetSetPointer.makePositionable(left);
      short H = above.getRel(-1);
      short I = pLeft.getAndInc();
      short J = pLeft.getAndInc();
      short K = pLeft.getAndInc();
      short L = pLeft.getAndInc();
      dst.memset(0, W.avg3(H, I, J), 4);
      dst.memset(stride, W.avg3(I, J, K), 4);
      dst.memset(stride * 2, W.avg3(J, K, L), 4);
      dst.memset(stride * 3, W.avg3(K, L, L), 4);
   }
}
