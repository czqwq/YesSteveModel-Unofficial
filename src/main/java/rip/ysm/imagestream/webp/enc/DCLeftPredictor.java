package rip.ysm.imagestream.webp.enc;

class DCLeftPredictor extends AverageBasedPredictor {
   DCLeftPredictor(int bs) {
      super(bs);
   }

   @Override
   protected int getToSum(GetPointer above, GetPointer left, int idx) {
      return left.getRel(idx);
   }

   @Override
   protected int getCount(int bs) {
      return bs;
   }
}
