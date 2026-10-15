package rip.ysm.imagestream.webp.enc;

import java.util.ArrayList;

class FullGenArrPointer<T> {
   final ArrayList<T> arr;
   int pos;
   private int savedPos;

   public FullGenArrPointer(int size) {
      this.arr = new ArrayList<>(size);

      for (int i = 0; i < size; i++) {
         this.arr.add(null);
      }
   }

   public FullGenArrPointer(FullGenArrPointer<T> other) {
      this.arr = other.arr;
      this.pos = other.pos;
      this.savedPos = other.savedPos;
   }

   public T get() {
      return this.arr.get(this.pos);
   }

   public T getRel(int r) {
      return this.arr.get(this.pos + r);
   }

   public int size() {
      return this.arr.size();
   }

   public T getAndInc() {
      return this.arr.get(this.pos++);
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

   public int getPos() {
      return this.pos;
   }

   public void setPos(int p) {
      this.pos = p;
   }

   public void set(T v) {
      this.arr.set(this.pos, v);
   }

   public void setAndInc(T v) {
      this.arr.set(this.pos++, v);
   }

   public void setRel(int r, T v) {
      this.arr.set(this.pos + r, v);
   }

   public FullGenArrPointer<T> shallowCopy() {
      return new FullGenArrPointer<>(this);
   }

   public FullGenArrPointer<T> shallowCopyWithPosInc(int by) {
      FullGenArrPointer<T> ret = this.shallowCopy();
      ret.incBy(by);
      return ret;
   }

   public int pointerDiff(FullGenArrPointer<T> other) {
      return other.pos - this.pos;
   }

   @Override
   public boolean equals(Object obj) {
      return !(obj instanceof FullGenArrPointer<?> other) ? false : other.arr == this.arr && other.pos == this.pos;
   }

   @Override
   public int hashCode() {
      return 0;
   }
}
