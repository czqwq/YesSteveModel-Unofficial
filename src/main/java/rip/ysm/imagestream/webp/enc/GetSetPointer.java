package rip.ysm.imagestream.webp.enc;

public class GetSetPointer extends GetPointer {
   private int savedPos;

   protected GetSetPointer(short[] data, int pos) {
      super(data, pos);
      this.savedPos = pos;
   }

   public GetSetPointer(GetSetPointer other) {
      super(other);
      this.savedPos = other.pos;
   }

   public void dec() {
      this.pos--;
   }

   public short getAndInc() {
      return this.arr[this.pos++];
   }

   public void inc() {
      this.pos++;
   }

   public void incBy(int r) {
      this.pos += r;
   }

   public void rewind() {
      this.pos = 0;
   }

   public void rewindToSaved() {
      this.pos = this.savedPos;
   }

   public void savePos() {
      this.savedPos = this.pos;
   }

   public void setPos(int p) {
      this.pos = p;
   }

   public static GetSetPointer makePositionable(GetPointer other) {
      return new GetSetPointer(other.arr, other.pos);
   }

   public static GetSetPointer makePositionableAndInc(GetPointer other, int incby) {
      return new GetSetPointer(other.arr, other.pos + incby);
   }
}
