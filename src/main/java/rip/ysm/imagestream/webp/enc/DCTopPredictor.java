package rip.ysm.imagestream.webp.enc;

class DCTopPredictor extends AverageBasedPredictor {
   DCTopPredictor(int bs) {
      super(bs);
   }

   @Override
   protected int getToSum(GetPointer above, GetPointer left, int idx) {
      return above.getRel(idx);
   }

   @Override
   protected int getCount(int bs) {
      return bs;
   }
}
