package rip.ysm.imagestream.avif.dec;

class Cdef {
   static void doCdef(
      boolean isDirection,
      int pos0,
      int pos1,
      int[] src,
      int srcPos,
      int srcStride,
      int[] dst,
      int dstPos,
      int dstStride,
      int primaryStrength,
      int secondaryStrength,
      int damping,
      int[] direction,
      int directionPos,
      int[] variance,
      int blockHeight
   ) {
      if (isDirection) {
         CdefDirection(src, srcPos, srcStride, direction, directionPos, variance);
      } else {
         switch (pos1) {
            case 0:
               if (pos0 == 0) {
                  CdefFilter(src, srcPos, srcStride, dst, dstPos, dstStride, primaryStrength, secondaryStrength, damping, direction, 4, blockHeight, true, true);
               } else if (pos0 == 1) {
                  CdefFilter(
                     src, srcPos, srcStride, dst, dstPos, dstStride, primaryStrength, secondaryStrength, damping, direction, 4, blockHeight, true, false
                  );
               } else {
                  CdefFilter(
                     src, srcPos, srcStride, dst, dstPos, dstStride, primaryStrength, secondaryStrength, damping, direction, 4, blockHeight, false, true
                  );
               }
               break;
            case 1:
               if (pos0 == 0) {
                  CdefFilter(src, srcPos, srcStride, dst, dstPos, dstStride, primaryStrength, secondaryStrength, damping, direction, 8, blockHeight, true, true);
               } else if (pos0 == 1) {
                  CdefFilter(
                     src, srcPos, srcStride, dst, dstPos, dstStride, primaryStrength, secondaryStrength, damping, direction, 8, blockHeight, true, false
                  );
               } else {
                  CdefFilter(
                     src, srcPos, srcStride, dst, dstPos, dstStride, primaryStrength, secondaryStrength, damping, direction, 8, blockHeight, false, true
                  );
               }
         }
      }
   }

   static void CdefDirection(int[] src, int srcPos, int stride, int[] direction, int dirPos, int[] variance) {
      int bitdepth = 8;
      int[] cost = new int[8];
      int[][] partial = new int[8][15];

      for (int i = 0; i < 8; i++) {
         for (int j = 0; j < 8; j++) {
            int x = (src[srcPos + j] >> 0) - 128;
            partial[0][i + j] = partial[0][i + j] + x;
            partial[1][i + j / 2] = partial[1][i + j / 2] + x;
            partial[2][i] = partial[2][i] + x;
            partial[3][3 + i - j / 2] = partial[3][3 + i - j / 2] + x;
            partial[4][7 + i - j] = partial[4][7 + i - j] + x;
            partial[5][3 - i / 2 + j] = partial[5][3 - i / 2 + j] + x;
            partial[6][j] = partial[6][j] + x;
            partial[7][i / 2 + j] = partial[7][i / 2 + j] + x;
         }

         srcPos += stride;
      }

      for (int i = 0; i < 8; i++) {
         cost[2] += D.Square(partial[2][i]);
         cost[6] += D.Square(partial[6][i]);
      }

      cost[2] *= D.kDivisionTable[7];
      cost[6] *= D.kDivisionTable[7];

      for (int i = 0; i < 7; i++) {
         cost[0] += (D.Square(partial[0][i]) + D.Square(partial[0][14 - i])) * D.kDivisionTable[i];
         cost[4] += (D.Square(partial[4][i]) + D.Square(partial[4][14 - i])) * D.kDivisionTable[i];
      }

      cost[0] += D.Square(partial[0][7]) * D.kDivisionTable[7];
      cost[4] += D.Square(partial[4][7]) * D.kDivisionTable[7];

      for (int i = 1; i < 8; i += 2) {
         for (int j = 0; j < 5; j++) {
            cost[i] += D.Square(partial[i][3 + j]);
         }

         cost[i] *= D.kDivisionTable[7];

         for (int j = 0; j < 3; j++) {
            cost[i] += (D.Square(partial[i][j]) + D.Square(partial[i][10 - j])) * D.kDivisionTable[2 * j + 1];
         }
      }

      int best_cost = 0;
      direction[dirPos] = 0;

      for (int i = 0; i < 8; i++) {
         if (cost[i] > best_cost) {
            best_cost = cost[i];
            direction[dirPos] = i;
         }
      }

      variance[0] = best_cost - cost[direction[dirPos] + 4 & 7] >> 10;
   }

   static void CdefFilter(
      int[] src,
      int srcPos,
      int srcStride,
      int[] dst,
      int dstPos,
      int dstStride,
      int primaryStrength,
      int secondaryStrength,
      int damping,
      int[] direction,
      int blockWidth,
      int blockHeight,
      boolean enablePrimary,
      boolean enableSecondary
   ) {
      int coeff_shift = 0;
      boolean clipping_required = enablePrimary && enableSecondary;
      int[] kCdefSecondaryTaps = new int[]{2, 1};
      int y = blockHeight;

      do {
         int x = 0;

         do {
            int sum = 0;
            int pixel_value = src[srcPos + x];
            int max_value = pixel_value;
            int min_value = pixel_value;

            for (int k = 0; k < 2; k++) {
               int[] signs = new int[]{-1, 1};

               for (int sign : signs) {
                  if (enablePrimary) {
                     int dy = sign * D.kCdefDirections[direction[0]][k][0];
                     int dx = sign * D.kCdefDirections[direction[0]][k][1];
                     int value = src[srcPos + dy * srcStride + dx + x];
                     if (value != 16384) {
                        sum += D.Constrain(value - pixel_value, primaryStrength, damping) * D.kCdefPrimaryTaps[primaryStrength >> 0 & 1][k];
                        if (clipping_required) {
                           max_value = Math.max(value, max_value);
                           min_value = Math.min(value, min_value);
                        }
                     }
                  }

                  if (enableSecondary) {
                     int[] offsets = new int[]{-2, 2};

                     for (int offset : offsets) {
                        int dy = sign * D.kCdefDirections[direction[0] + offset][k][0];
                        int dx = sign * D.kCdefDirections[direction[0] + offset][k][1];
                        int value = src[srcPos + dy * srcStride + dx + x];
                        if (value != 16384) {
                           sum += D.Constrain(value - pixel_value, secondaryStrength, damping) * kCdefSecondaryTaps[k];
                           if (clipping_required) {
                              max_value = Math.max(value, max_value);
                              min_value = Math.min(value, min_value);
                           }
                        }
                     }
                  }
               }
            }

            int offsetx = 8 + sum - (sum < 0 ? 1 : 0) >> 4;
            if (clipping_required) {
               dst[dstPos + x] = D.Clip3(pixel_value + offsetx, min_value, max_value);
            } else {
               dst[dstPos + x] = pixel_value + offsetx;
            }
         } while (++x < blockWidth);

         srcPos += srcStride;
         dstPos += dstStride;
      } while (--y != 0);
   }

   static void CopyRowForCdef(
      int[] src, int srcPos, int blockWidth, int unitWidth, boolean isFrameLeft, boolean isFrameRight, int[] dst, int dstPos, int[] leftBorder, int leftPos
   ) {
      if (isFrameLeft) {
         Mem.set(dst, dstPos - 2, 16384, 2);
      } else if (leftBorder == null) {
         Mem.cpy(dst, dstPos - 2, src, srcPos - 2, 2);
      } else {
         Mem.cpy(dst, dstPos - 2, leftBorder, leftPos, 2);
      }

      Mem.cpy(dst, dstPos, src, srcPos, blockWidth);
      if (isFrameRight) {
         Mem.set(dst, dstPos + blockWidth, 16384, unitWidth + 2 - blockWidth);
      } else {
         Mem.cpy(dst, dstPos + blockWidth, src, srcPos + blockWidth, unitWidth + 2 - blockWidth);
      }
   }
}
