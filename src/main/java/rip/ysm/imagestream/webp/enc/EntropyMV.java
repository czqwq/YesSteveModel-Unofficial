package rip.ysm.imagestream.webp.enc;

final class EntropyMV {
   static final short mv_max = 1023;
   static final short MVvals = 2047;
   static final short mvfp_max = 255;
   static final short MVfpvals = 511;
   static final short mvlong_width = 10;
   static final short mvnum_short = 8;
   static final short mvpis_short = 0;
   static final short MVPsign = 1;
   static final short MVPshort = 2;
   static final short MVPbits = 9;
   static final short MVPcount = 19;

   private EntropyMV() {
   }
}
