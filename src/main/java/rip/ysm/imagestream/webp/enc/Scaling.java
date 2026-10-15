package rip.ysm.imagestream.webp.enc;

enum Scaling {
   NORMAL(1, 1);

   final int hr;
   final int hs;

   private Scaling(final int r, final int s) {
      this.hr = r;
      this.hs = s;
   }
}
