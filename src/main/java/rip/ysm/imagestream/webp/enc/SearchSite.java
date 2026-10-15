package rip.ysm.imagestream.webp.enc;

class SearchSite {
   final MV mv;
   final int offset;

   SearchSite(int r, int c, int off) {
      this.mv = new MV(r, c);
      this.offset = off;
   }
}
