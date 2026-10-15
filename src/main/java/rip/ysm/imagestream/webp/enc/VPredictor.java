package rip.ysm.imagestream.webp.enc;

class VPredictor extends BlockSizeSpecificPredictor {
   VPredictor(int bs) {
      super(bs);
   }

   @Override
   public void call(FullGetSetPointer dst, int stride, GetPointer above, GetPointer left) {
      for (int r = 0; r < this.bs; r++) {
         dst.memcopyin(r * stride, above, 0, this.bs);
      }
   }
}
