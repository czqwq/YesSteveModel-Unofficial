package rip.ysm.imagestream.webp.enc;

class MV {
   short row;
   short col;

   MV() {
      this.row = 0;
      this.col = 0;
   }

   MV(short r, short c) {
      this.row = r;
      this.col = c;
   }

   MV(int r, int c) {
      this.row = (short)r;
      this.col = (short)c;
   }

   void set(MV other) {
      if (other == null) {
         this.setZero();
      } else {
         this.row = other.row;
         this.col = other.col;
      }
   }

   void setZero() {
      this.row = 0;
      this.col = 0;
   }

   MV mul8() {
      return new MV((short)(this.row << 3), (short)(this.col << 3));
   }
}
