package rip.ysm.imagestream.webp.enc;

final class EntropyPlanes {
   final FullGetSetPointer panes;

   EntropyPlanes() {
      this.panes = new FullGetSetPointer(9);
      this.reset();
   }

   void reset() {
      CUtils.vp8_zero(this.panes);
   }

   EntropyPlanes(EntropyPlanes other) {
      this.panes = other.panes.deepCopy();
   }
}
