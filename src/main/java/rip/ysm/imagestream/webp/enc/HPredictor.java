package rip.ysm.imagestream.webp.enc;

class HPredictor extends BlockSizeSpecificPredictor {
   HPredictor(int bs) {
      super(bs);
   }

   @Override
   public void call(FullGetSetPointer dst, int stride, GetPointer above, GetPointer left) {
      for (int r = 0; r < this.bs; r++) {
         dst.memset(r * stride, left.getRel(r), this.bs);
      }
   }
}
