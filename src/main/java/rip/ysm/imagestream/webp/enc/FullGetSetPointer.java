package rip.ysm.imagestream.webp.enc;

import java.util.Arrays;

public class FullGetSetPointer extends GetSetPointer {
   public FullGetSetPointer(int size) {
      super(new short[size], 0);
   }

   private FullGetSetPointer(short[] a, int p) {
      super(a, p);
   }

   public short set(short v) {
      this.arr[this.pos] = v;
      return v;
   }

   public void setAndInc(short v) {
      this.arr[this.pos++] = v;
   }

   public short setRel(int r, short v) {
      this.arr[this.pos + r] = v;
      return v;
   }

   public void setAbs(int l, short v) {
      this.arr[l] = v;
   }

   public void memcopyin(int rel, GetPointer other, int otRel, int len) {
      System.arraycopy(other.arr, other.pos + otRel, this.arr, this.pos + rel, len);
   }

   public void memcopyin(int rel, short[] other, int otRel, int len) {
      System.arraycopy(other, otRel, this.arr, this.pos + rel, len);
   }

   public void memset(int rel, short v, int len) {
      int start = this.pos + rel;
      Arrays.fill(this.arr, start, start + len, v);
   }

   public FullGetSetPointer shallowCopy() {
      return new FullGetSetPointer(this.arr, this.pos);
   }

   public FullGetSetPointer shallowCopyWithPosInc(int by) {
      return new FullGetSetPointer(this.arr, this.pos + by);
   }

   public FullGetSetPointer deepCopy() {
      return new FullGetSetPointer(Arrays.copyOf(this.arr, this.arr.length), this.pos);
   }

   public GetPointer readOnly() {
      return new GetPointer(this);
   }

   public GetSetPointer positionableOnly() {
      return new GetSetPointer(this);
   }

   public static FullGetSetPointer toPointer(short[] data) {
      return new FullGetSetPointer(data, 0);
   }
}
