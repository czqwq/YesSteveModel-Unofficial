package rip.ysm.imagestream.avif.dec;

class Convolve {
   static final int kVerticalOffset = 3;
   static final int kHorizontalOffset = 3;
   static final int bitdepth = 8;

   static int GetNumTapsInFilter(int filter_index) {
      if (filter_index < 2) {
         return 6;
      } else if (filter_index == 2) {
         return 8;
      } else {
         return filter_index == 3 ? 2 : 4;
      }
   }

   static void ConvolveScale2D(
      int[] src,
      int srcPos,
      int src_stride,
      int horizontal_filter_index,
      int vertical_filter_index,
      int subpixel_x,
      int subpixel_y,
      int step_x,
      int step_y,
      int width,
      int height,
      int[] dest,
      int destPos,
      int dest_stride
   ) {
      int kRoundBitsHorizontal = 3;
      int kRoundBitsVertical = 11;
      int intermediate_height = ((height - 1) * step_y + 1024 - 1 >> 10) + 8;
      int[] intermediate = new int[33792];
      int intermediatePos = 0;
      int intermediate_stride = 128;
      int max_pixel_value = 255;
      int filter_index = D.GetFilterIndex(horizontal_filter_index, width);
      int ref_x = subpixel_x >> 10;
      int y = 0;

      do {
         int p = subpixel_x;
         int x = 0;

         do {
            int sum = 0;
            int src_xPos = srcPos + (p >> 10) - ref_x;
            int filter_id = p >> 6 & 15;

            for (int k = 0; k < 8; k++) {
               sum += D.kHalfSubPixelFilters[filter_index][filter_id][k] * src[srcPos + src_xPos + k];
            }

            intermediate[intermediatePos + x] = D.RightShiftWithRounding(sum, 2);
            p += step_x;
         } while (++x < width);

         srcPos += src_stride;
         intermediatePos += intermediate_stride;
      } while (++y < intermediate_height);

      filter_index = D.GetFilterIndex(vertical_filter_index, height);
      int var30 = 0;
      int p = subpixel_y & 1023;
      y = 0;

      do {
         int filter_id = p >> 6 & 15;
         int x = 0;

         do {
            int sum = 0;

            for (int k = 0; k < 8; k++) {
               sum += D.kHalfSubPixelFilters[filter_index][filter_id][k] * intermediate[var30 + ((p >> 10) + k) * intermediate_stride + x];
            }

            dest[destPos + x] = D.Clip3(D.RightShiftWithRounding(sum, 10), 0, max_pixel_value);
         } while (++x < width);

         destPos += dest_stride;
         p += step_y;
      } while (++y < height);
   }

   static void ConvolveCompoundScale2D(
      int[] src,
      int srcPos,
      int src_stride,
      int horizontal_filter_index,
      int vertical_filter_index,
      int subpixel_x,
      int subpixel_y,
      int step_x,
      int step_y,
      int width,
      int height,
      int[] dest,
      int destPos,
      int dest_stride
   ) {
      int kRoundBitsHorizontal = 3;
      int kRoundBitsVertical = 7;
      int intermediate_height = ((height - 1) * step_y + 1024 - 1 >> 10) + 8;
      int[] intermediate = new int[33792];
      int intermediatePos = 0;
      int intermediate_stride = 128;
      int filter_index = D.GetFilterIndex(horizontal_filter_index, width);
      int ref_x = subpixel_x >> 10;
      int y = 0;

      do {
         int p = subpixel_x;
         int x = 0;

         do {
            int sum = 0;
            int src_xPos = srcPos + (p >> 10) - ref_x;
            int filter_id = p >> 6 & 15;

            for (int k = 0; k < 8; k++) {
               sum += D.kHalfSubPixelFilters[filter_index][filter_id][k] * src[srcPos + src_xPos + k];
            }

            intermediate[intermediatePos + x] = D.RightShiftWithRounding(sum, 2);
            p += step_x;
         } while (++x < width);

         srcPos += src_stride;
         intermediatePos += intermediate_stride;
      } while (++y < intermediate_height);

      filter_index = D.GetFilterIndex(vertical_filter_index, height);
      int var29 = 0;
      int p = subpixel_y & 1023;
      y = 0;

      do {
         int filter_id = p >> 6 & 15;
         int x = 0;

         do {
            int sum = 0;

            for (int k = 0; k < 8; k++) {
               sum += D.kHalfSubPixelFilters[filter_index][filter_id][k] * intermediate[var29 + ((p >> 10) + k) * intermediate_stride + x];
            }

            sum = D.RightShiftWithRounding(sum, 6);
            sum += 0;
            dest[destPos + x] = sum;
         } while (++x < width);

         destPos += dest_stride;
         p += step_y;
      } while (++y < height);
   }

   static void ConvolveCompound2D(
      int[] src,
      int srcPos,
      int src_stride,
      int horizontal_filter_index,
      int horizontal_filter_id,
      int vertical_filter_index,
      int vertical_filter_id,
      int width,
      int height,
      int[] dest,
      int destPos,
      int dest_stride
   ) {
      int kRoundBitsHorizontal = 3;
      int kRoundBitsVertical = 7;
      int intermediate_height = height + 8 - 1;
      int[] intermediate = new int[17280];
      int intermediatePos = 0;
      int intermediate_stride = 128;
      int filter_index = D.GetFilterIndex(horizontal_filter_index, width);
      srcPos = srcPos - 3 * src_stride - 3;
      int y = 0;

      do {
         int x = 0;

         do {
            int sum = 0;

            for (int k = 0; k < 8; k++) {
               sum += D.kHalfSubPixelFilters[filter_index][horizontal_filter_id][k] * src[srcPos + x + k];
            }

            intermediate[intermediatePos + x] = D.RightShiftWithRounding(sum, 2);
         } while (++x < width);

         srcPos += src_stride;
         intermediatePos += intermediate_stride;
      } while (++y < intermediate_height);

      filter_index = D.GetFilterIndex(vertical_filter_index, height);
      intermediatePos = 0;
      y = 0;

      do {
         int x = 0;

         do {
            int sum = 0;

            for (int k = 0; k < 8; k++) {
               sum += D.kHalfSubPixelFilters[filter_index][vertical_filter_id][k] * intermediate[intermediatePos + k * intermediate_stride + x];
            }

            sum = D.RightShiftWithRounding(sum, 6);
            sum += 0;
            dest[destPos + x] = sum;
         } while (++x < width);

         destPos += dest_stride;
         intermediatePos += intermediate_stride;
      } while (++y < height);
   }

   static void Convolve2D(
      int[] src,
      int srcPos,
      int src_stride,
      int horizontal_filter_index,
      int horizontal_filter_id,
      int vertical_filter_index,
      int vertical_filter_id,
      int width,
      int height,
      int[] dest,
      int destPos,
      int dest_stride
   ) {
      int kRoundBitsHorizontal = 3;
      int kRoundBitsVertical = 11;
      int intermediate_height = height + 8 - 1;
      int[] intermediate = new int[17280];
      int intermediatePos = 0;
      int intermediate_stride = 128;
      int max_pixel_value = 255;
      int filter_index = D.GetFilterIndex(horizontal_filter_index, width);
      srcPos = srcPos - 3 * src_stride - 3;
      int y = 0;

      do {
         int x = 0;

         do {
            int sum = 0;

            for (int k = 0; k < 8; k++) {
               sum += D.kHalfSubPixelFilters[filter_index][horizontal_filter_id][k] * src[srcPos + x + k];
            }

            intermediate[intermediatePos + x] = D.RightShiftWithRounding(sum, kRoundBitsHorizontal - 1);
         } while (++x < width);

         srcPos += src_stride;
         intermediatePos += intermediate_stride;
      } while (++y < intermediate_height);

      filter_index = D.GetFilterIndex(vertical_filter_index, height);
      intermediatePos = 0;
      y = 0;

      do {
         int x = 0;

         do {
            int sum = 0;

            for (int k = 0; k < 8; k++) {
               sum += D.kHalfSubPixelFilters[filter_index][vertical_filter_id][k] * intermediate[intermediatePos + k * intermediate_stride + x];
            }

            dest[destPos + x] = D.Clip3(D.RightShiftWithRounding(sum, kRoundBitsVertical - 1), 0, max_pixel_value);
         } while (++x < width);

         destPos += dest_stride;
         intermediatePos += intermediate_stride;
      } while (++y < height);
   }

   static void ConvolveHorizontal(
      int[] src,
      int srcPos,
      int src_stride,
      int horizontal_filter_index,
      int horizontal_filter_id,
      int width,
      int height,
      int[] dest,
      int destPos,
      int dest_stride
   ) {
      int kRoundBitsHorizontal = 3;
      int filter_index = D.GetFilterIndex(horizontal_filter_index, width);
      int bits = 4;
      srcPos -= 3;
      int max_pixel_value = 255;
      int y = 0;

      do {
         int x = 0;

         do {
            int sum = 0;

            for (int k = 0; k < 8; k++) {
               sum += D.kHalfSubPixelFilters[filter_index][horizontal_filter_id][k] * src[srcPos + x + k];
            }

            sum = D.RightShiftWithRounding(sum, 2);
            dest[destPos + x] = D.Clip3(D.RightShiftWithRounding(sum, bits), 0, max_pixel_value);
         } while (++x < width);

         srcPos += src_stride;
         destPos += dest_stride;
      } while (++y < height);
   }

   static void ConvolveVertical(
      int[] src, int srcPos, int src_stride, int vertical_filter_index, int vertical_filter_id, int width, int height, int[] dest, int destPos, int dest_stride
   ) {
      int filter_index = D.GetFilterIndex(vertical_filter_index, height);
      srcPos -= 3 * src_stride;
      int max_pixel_value = 255;
      int y = 0;

      do {
         int x = 0;

         do {
            int sum = 0;

            for (int k = 0; k < 8; k++) {
               sum += D.kHalfSubPixelFilters[filter_index][vertical_filter_id][k] * src[srcPos + k * src_stride + x];
            }

            dest[destPos + x] = D.Clip3(D.RightShiftWithRounding(sum, 6), 0, max_pixel_value);
         } while (++x < width);

         srcPos += src_stride;
         destPos += dest_stride;
      } while (++y < height);
   }

   static void ConvolveCopy(
      int[] src,
      int srcPos,
      int src_stride,
      int horizontal_filter_index,
      int horizontal_filter_id,
      int vertical_filter_index,
      int vertical_filter_id,
      int width,
      int height,
      int[] dest,
      int destPos,
      int dest_stride
   ) {
      int y = 0;

      do {
         Mem.cpy(dest, destPos, src, srcPos, width);
         srcPos += src_stride;
         destPos += dest_stride;
      } while (++y < height);
   }

   static void ConvolveCompoundCopy(
      int[] src,
      int srcPos,
      int src_stride,
      int horizontal_filter_index,
      int horizontal_filter_id,
      int vertical_filter_index,
      int vertical_filter_id,
      int width,
      int height,
      int[] dest,
      int destPos,
      int dest_stride
   ) {
      int kRoundBitsVertical = 4;
      int y = 0;

      do {
         int x = 0;

         do {
            int sum = 0;
            sum += src[srcPos + x];
            dest[destPos + x] = sum << 4;
         } while (++x < width);

         srcPos += src_stride;
         destPos += dest_stride;
      } while (++y < height);
   }

   static void ConvolveCompoundHorizontal(
      int[] src,
      int srcPos,
      int src_stride,
      int horizontal_filter_index,
      int horizontal_filter_id,
      int vertical_filter_index,
      int vertical_filter_id,
      int width,
      int height,
      int[] dest,
      int destPos,
      int dest_stride
   ) {
      int kRoundBitsHorizontal = 3;
      int filter_index = D.GetFilterIndex(horizontal_filter_index, width);
      srcPos -= 3;
      int y = 0;

      do {
         int x = 0;

         do {
            int sum = 0;

            for (int k = 0; k < 8; k++) {
               sum += D.kHalfSubPixelFilters[filter_index][horizontal_filter_id][k] * src[srcPos + x + k];
            }

            sum = D.RightShiftWithRounding(sum, 2);
            sum += 0;
            dest[destPos + x] = sum;
         } while (++x < width);

         srcPos += src_stride;
         destPos += dest_stride;
      } while (++y < height);
   }

   static void ConvolveCompoundVertical(
      int[] src,
      int srcPos,
      int src_stride,
      int horizontal_filter_index,
      int horizontal_filter_id,
      int vertical_filter_index,
      int vertical_filter_id,
      int width,
      int height,
      int[] dest,
      int destPos,
      int dest_stride
   ) {
      int kRoundBitsHorizontal = 3;
      int filter_index = D.GetFilterIndex(vertical_filter_index, height);
      srcPos -= 3 * src_stride;
      int y = 0;

      do {
         int x = 0;

         do {
            int sum = 0;

            for (int k = 0; k < 8; k++) {
               sum += D.kHalfSubPixelFilters[filter_index][vertical_filter_id][k] * src[srcPos + k * src_stride + x];
            }

            sum = D.RightShiftWithRounding(sum, 2);
            sum += 0;
            dest[destPos + x] = sum;
         } while (++x < width);

         srcPos += src_stride;
         destPos += dest_stride;
      } while (++y < height);
   }

   static void ConvolveIntraBlockCopy2D(int[] src, int srcPos, int src_stride, int width, int height, int[] dest, int destPos, int dest_stride) {
      int intermediate_height = height + 1;
      int[] intermediate = new int[16512];
      int intermediatePos = 0;
      int y = 0;

      do {
         int x = 0;

         do {
            intermediate[intermediatePos + x] = src[srcPos + x] + src[srcPos + x + 1];
         } while (++x < width);

         srcPos += src_stride;
         intermediatePos += width;
      } while (++y < intermediate_height);

      intermediatePos = 0;
      y = 0;

      do {
         int x = 0;

         do {
            dest[destPos + x] = D.RightShiftWithRounding(intermediate[intermediatePos + x] + intermediate[intermediatePos + x + width], 2);
         } while (++x < width);

         intermediatePos += width;
         destPos += dest_stride;
      } while (++y < height);
   }

   static void ConvolveIntraBlockCopy1D(
      int[] src, int srcPos, int src_stride, int width, int height, int[] dest, int destPos, int dest_stride, boolean is_horizontal
   ) {
      int offset = is_horizontal ? 1 : src_stride;
      int y = 0;

      do {
         int x = 0;

         do {
            dest[destPos + x] = D.RightShiftWithRounding(src[srcPos + x] + src[srcPos + x + offset], 1);
         } while (++x < width);

         srcPos += src_stride;
         destPos += dest_stride;
      } while (++y < height);
   }
}
