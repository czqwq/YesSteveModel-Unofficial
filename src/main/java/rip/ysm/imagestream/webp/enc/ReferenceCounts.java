package rip.ysm.imagestream.webp.enc;

class ReferenceCounts {
   final int intra;
   final int inter;
   final int total;

   ReferenceCounts(int intra, int inter) {
      this.intra = intra;
      this.inter = inter;
      this.total = inter + intra;
   }
}
