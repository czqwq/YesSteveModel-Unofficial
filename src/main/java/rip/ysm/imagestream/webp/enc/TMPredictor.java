package rip.ysm.imagestream.webp.enc;

public class TMPredictor extends BlockSizeSpecificPredictor {
   public TMPredictor(int bs) {
      super(bs);
   }

   @Override
   public void call(FullGetSetPointer dst, int stride, GetPointer above, GetPointer left) {
      int ytop_left = above.getRel(-1);

      for (int r = 0; r < this.bs; r++) {
         int base = r * stride;

         for (int c = 0; c < this.bs; c++) {
            dst.setRel(base + c, CUtils.clipPixel((short)(left.getRel(r) + above.getRel(c) - ytop_left)));
         }
      }
   }
}
