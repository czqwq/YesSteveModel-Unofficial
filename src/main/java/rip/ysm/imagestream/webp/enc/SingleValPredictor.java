package rip.ysm.imagestream.webp.enc;

public abstract class SingleValPredictor extends BlockSizeSpecificPredictor {
   public SingleValPredictor(int bs) {
      super(bs);
   }

   @Override
   public void call(FullGetSetPointer dst, int stride, GetPointer above, GetPointer left) {
      short expectedVal = this.calcSingleValue(above, left);

      for (int r = 0; r < this.bs; r++) {
         dst.memset(r * stride, expectedVal, this.bs);
      }
   }

   protected abstract short calcSingleValue(GetPointer var1, GetPointer var2);
}
