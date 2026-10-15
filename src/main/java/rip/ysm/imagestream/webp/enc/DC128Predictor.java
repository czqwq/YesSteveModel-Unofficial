package rip.ysm.imagestream.webp.enc;

class DC128Predictor extends SingleValPredictor {
   DC128Predictor(int bs) {
      super(bs);
   }

   @Override
   protected short calcSingleValue(GetPointer above, GetPointer left) {
      return 128;
   }
}
