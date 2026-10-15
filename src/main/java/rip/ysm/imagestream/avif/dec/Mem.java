package rip.ysm.imagestream.avif.dec;

import java.util.Arrays;

class Mem {
   static void cpy(int[] dst, int[] src) {
      System.arraycopy(src, 0, dst, 0, Math.min(dst.length, src.length));
   }

   static void cpy(int[] dst, int[] src, int length) {
      System.arraycopy(src, 0, dst, 0, length);
   }

   static void cpy(int[] dst, int dstPos, int[] src, int srcPos, int length) {
      System.arraycopy(src, srcPos, dst, dstPos, length);
   }

   static void set(int[] dst, int v) {
      Arrays.fill(dst, v);
   }

   static void set(int[] dst, int value, int n) {
      Arrays.fill(dst, 0, n, value);
   }

   static void set(int[] dst, int dstPos, int value, int n) {
      Arrays.fill(dst, dstPos, dstPos + n, value);
   }

   static void cpy(byte[] dst, byte[] src) {
      System.arraycopy(src, 0, dst, 0, Math.min(dst.length, src.length));
   }

   static void cpy(byte[] dst, byte[] src, int length) {
      System.arraycopy(src, 0, dst, 0, length);
   }

   static void cpy(byte[] dst, int dstPos, byte[] src, int srcPos, int length) {
      System.arraycopy(src, srcPos, dst, dstPos, length);
   }

   static void set(byte[] dst, byte v) {
      Arrays.fill(dst, v);
   }

   static void set(byte[] dst, byte value, int n) {
      Arrays.fill(dst, 0, n, value);
   }

   static void set(byte[] dst, int dstPos, byte value, int n) {
      Arrays.fill(dst, dstPos, dstPos + n, value);
   }

   static void cpy(boolean[] dst, boolean[] src) {
      System.arraycopy(src, 0, dst, 0, Math.min(dst.length, src.length));
   }

   static void cpy(boolean[] dst, boolean[] src, int length) {
      System.arraycopy(src, 0, dst, 0, length);
   }

   static void cpy(boolean[] dst, int dstPos, boolean[] src, int srcPos, int length) {
      System.arraycopy(src, srcPos, dst, dstPos, length);
   }

   static void set(boolean[] dst, boolean v) {
      Arrays.fill(dst, v);
   }

   static void set(boolean[] dst, boolean value, int n) {
      Arrays.fill(dst, 0, n, value);
   }

   static void set(boolean[] dst, int dstPos, boolean value, int n) {
      Arrays.fill(dst, dstPos, dstPos + n, value);
   }

   static void move(int[] dst, int dstPos, int[] src, int srcPos, int length) {
      System.arraycopy(src, srcPos, dst, dstPos, length);
   }

   static void cpy(float[] dst, float[] src) {
      System.arraycopy(src, 0, dst, 0, Math.min(dst.length, src.length));
   }
}
