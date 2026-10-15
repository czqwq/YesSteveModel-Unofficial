package rip.ysm.imagestream.webp.enc;

enum PlaneType {
   Y_NO_DC(4, 1),
   Y2(16, 0),
   UV(2, 0),
   Y_WITH_DC(4, 0);

   final byte rd_mult;
   final byte start_coeff;

   private PlaneType(final int rdm, final int sc) {
      this.rd_mult = (byte)rdm;
      this.start_coeff = (byte)sc;
   }
}
