package rip.ysm.imagestream.webp.enc;

abstract class BlockSizeSpecificPredictor implements IntraPredFN {
   final int bs;

   BlockSizeSpecificPredictor(int bs) {
      this.bs = bs;
   }
}
