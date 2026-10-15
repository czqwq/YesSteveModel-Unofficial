package rip.ysm.imagestream.webp.enc;

import java.util.Arrays;

final class CUtils {
   static final VarianceFNs.COPY vp8_copy32xn = (src_ptr, src_stride, ref_ptr, ref_stride, n) -> genericCopy(src_ptr, src_stride, ref_ptr, ref_stride, n, 32);

   private CUtils() {
   }

   static void vp8_zero(int[] dataToZero) {
      Arrays.fill(dataToZero, 0);
   }

   static void vp8_zero(int[][] dataToZero) {
      for (int[] subArr : dataToZero) {
         vp8_zero(subArr);
      }
   }

   static void vp8_zero(int[][][] dataToZero) {
      for (int[][] sub2DArr : dataToZero) {
         vp8_zero(sub2DArr);
      }
   }

   static void vp8_zero(int[][][][] dataToZero) {
      for (int[][][] sub3DArr : dataToZero) {
         vp8_zero(sub3DArr);
      }
   }

   static void vp8_zero(FullGetSetPointer dataToZero) {
      dataToZero.memset(0, (short)0, dataToZero.getRemaining());
   }

   static void vp8_copy(int[] source, int[] target) {
      System.arraycopy(source, 0, target, 0, source.length);
   }

   static void vp8_copy(short[] source, short[] target) {
      System.arraycopy(source, 0, target, 0, source.length);
   }

   static void vp8_copy(short[][] source, short[][] target) {
      for (int i = 0; i < source.length; i++) {
         vp8_copy(source[i], target[i]);
      }
   }

   static void vp8_copy(short[][][] source, short[][][] target) {
      for (int i = 0; i < source.length; i++) {
         vp8_copy(source[i], target[i]);
      }
   }

   static void vp8_copy(short[][][][] source, short[][][][] target) {
      for (int i = 0; i < source.length; i++) {
         vp8_copy(source[i], target[i]);
      }
   }

   static void vp8_copy(GetPointer source, FullGetSetPointer target) {
      target.memcopyin(0, source, 0, source.getRemaining());
   }

   static short clipPixel(short val) {
      return clamp(val, (short)0, (short)255);
   }

   static short byteClamp(short val) {
      return clamp(val, (short)-128, (short)127);
   }

   static short clamp(short value, short low, short high) {
      return value < low ? low : (value > high ? high : value);
   }

   static void genericCopy(GetSetPointer src_ptr, int src_stride, FullGetSetPointer dst_ptr, int dst_stride, int height, int width) {
      for (int r = 0; r < height; r++) {
         dst_ptr.memcopyin(r * dst_stride, src_ptr, r * src_stride, width);
      }
   }

   static void vp8_copy_mem8x8(GetSetPointer src, int src_stride, FullGetSetPointer dst) {
      genericCopy(src, src_stride, dst, 8, 8, 8);
   }

   static void vp8_copy_mem16x16(GetSetPointer src, int src_stride, FullGetSetPointer dst, int dst_stride) {
      genericCopy(src, src_stride, dst, dst_stride, 16, 16);
   }

   static int roundPowerOfTwo(int value, int n) {
      return value + (1 << n - 1) >> n;
   }

   static long roundPowerOfTwo(long value, int n) {
      return value + (1L << n - 1) >> n;
   }
}
