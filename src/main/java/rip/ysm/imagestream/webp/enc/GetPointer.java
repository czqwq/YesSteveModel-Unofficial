package rip.ysm.imagestream.webp.enc;

import java.util.Arrays;

class GetPointer implements Comparable<GetPointer> {
   protected final short[] arr;
   protected int pos;

   GetPointer(short[] data, int pos) {
      this.arr = data;
      this.pos = pos;
   }

   GetPointer(GetPointer other) {
      this.arr = other.arr;
      this.pos = other.pos;
   }

   public short get() {
      return this.arr[this.pos];
   }

   public short getRel(int r) {
      return this.arr[this.pos + r];
   }

   int size() {
      return this.arr.length;
   }

   int getRemaining() {
      return this.arr.length - this.pos;
   }

   int getPos() {
      return this.pos;
   }

   @Override
   public boolean equals(Object obj) {
      return !(obj instanceof GetPointer other) ? false : Arrays.equals(other.arr, this.arr) && other.pos == this.pos;
   }

   @Override
   public int hashCode() {
      return 0;
   }

   int pointerDiff(GetPointer other) {
      return other.pos - this.pos;
   }

   public int compareTo(GetPointer arg0) {
      boolean sameArray = Arrays.equals(arg0.arr, this.arr);
      int compResult = sameArray ? 0 : Integer.compare(arg0.arr.length, this.arr.length);
      if (compResult == 0 && !sameArray) {
         for (int i = 0; i < this.arr.length && compResult == 0; i++) {
            compResult = Short.compare(this.arr[i], arg0.arr[i]);
         }
      }

      return compResult;
   }
}
