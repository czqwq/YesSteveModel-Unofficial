package rip.ysm.imagestream.webp.enc;

abstract class AverageBasedPredictor extends SingleValPredictor {
   final int shift;
   final int adj;

   AverageBasedPredictor(int bs) {
      super(bs);
      this.adj = this.getCount(bs) >> 1;
      this.shift = Integer.numberOfTrailingZeros(this.adj) + 1;
   }

   @Override
   protected short calcSingleValue(GetPointer above, GetPointer left) {
      int sum = 0;

      for (int i = 0; i < this.bs; i++) {
         sum += this.getToSum(above, left, i);
      }

      return (short)(sum + this.adj >> this.shift);
   }

   protected abstract int getToSum(GetPointer var1, GetPointer var2, int var3);

   protected abstract int getCount(int var1);
}
