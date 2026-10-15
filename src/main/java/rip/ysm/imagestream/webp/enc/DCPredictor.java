package rip.ysm.imagestream.webp.enc;

class DCPredictor extends AverageBasedPredictor {
   DCPredictor(int bs) {
      super(bs);
   }

   @Override
   protected int getToSum(GetPointer above, GetPointer left, int idx) {
      return above.getRel(idx) + left.getRel(idx);
   }

   @Override
   protected int getCount(int bs) {
      return bs << 1;
   }
}
