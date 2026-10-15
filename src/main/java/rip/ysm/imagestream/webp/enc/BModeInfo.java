package rip.ysm.imagestream.webp.enc;

class BModeInfo {
   final MV mv = new MV();

   int as_mode() {
      int i = this.mv.col & 15;

      for (int b : W.validmodes) {
         if (i == b) {
            return b;
         }
      }

      return -1;
   }

   void as_mode(int b) {
      this.mv.col = (short)b;
   }
}
