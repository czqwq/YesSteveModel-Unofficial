package rip.ysm.imagestream.avif.dec;

import rip.ysm.imagestream.internal.LogWriter;

class Reconstruct {
   static int getTransformClass(int tx_type) {
      D.BitMaskSet kTransformClassVerticalMask = new D.BitMaskSet(10, 12, 14);
      if (kTransformClassVerticalMask.Contains(tx_type)) {
         return 2;
      } else {
         D.BitMaskSet kTransformClassHorizontalMask = new D.BitMaskSet(11, 13, 15);
         return kTransformClassHorizontalMask.Contains(tx_type) ? 1 : 0;
      }
   }

   private static int getNumRows(int tx_width, int tx_type, int tx_height, int non_zero_coeff_count) {
      int txClass = getTransformClass(tx_type);
      switch (txClass) {
         case 0:
            if (tx_width == 4) {
               if (non_zero_coeff_count <= 13) {
                  return 4;
               }

               if (non_zero_coeff_count <= 29) {
                  return 8;
               }
            }

            if (tx_width == 8) {
               if (non_zero_coeff_count <= 10) {
                  return 4;
               }

               if (non_zero_coeff_count <= 14 & tx_height > 8) {
                  return 4;
               }

               if (non_zero_coeff_count <= 43) {
                  return 8;
               }

               if (non_zero_coeff_count <= 107 & tx_height > 16) {
                  return 16;
               }

               if (non_zero_coeff_count <= 171 & tx_height > 16) {
                  return 24;
               }
            }

            if (tx_width == 16) {
               if (non_zero_coeff_count <= 10) {
                  return 4;
               }

               if (non_zero_coeff_count <= 14 & tx_height > 16) {
                  return 4;
               }

               if (non_zero_coeff_count <= 36) {
                  return 8;
               }

               if (non_zero_coeff_count <= 44 & tx_height > 16) {
                  return 8;
               }

               if (non_zero_coeff_count <= 151 & tx_height > 16) {
                  return 16;
               }

               if (non_zero_coeff_count <= 279 & tx_height > 16) {
                  return 24;
               }
            }

            if (tx_width == 32) {
               if (non_zero_coeff_count <= 10) {
                  return 4;
               }

               if (non_zero_coeff_count <= 36) {
                  return 8;
               }

               if (non_zero_coeff_count <= 136 & tx_height > 16) {
                  return 16;
               }

               if (non_zero_coeff_count <= 300 & tx_height > 16) {
                  return 24;
               }
            }
            break;
         case 1:
            if (non_zero_coeff_count <= 4) {
               return 4;
            }

            if (non_zero_coeff_count <= 8) {
               return 8;
            }

            if (non_zero_coeff_count <= 16 & tx_height > 16) {
               return 16;
            }

            if (non_zero_coeff_count <= 24 & tx_height > 16) {
               return 24;
            }
            break;
         default:
            if (tx_width == 4) {
               if (non_zero_coeff_count <= 16) {
                  return 4;
               }

               if (non_zero_coeff_count <= 32) {
                  return 8;
               }
            }

            if (tx_width == 8) {
               if (non_zero_coeff_count <= 32) {
                  return 4;
               }

               if (non_zero_coeff_count <= 64) {
                  return 8;
               }

               if (non_zero_coeff_count <= 128) {
                  return 16;
               }

               if (non_zero_coeff_count <= 192) {
                  return 24;
               }
            }

            if (tx_width == 16) {
               if (non_zero_coeff_count <= 64) {
                  return 4;
               }

               if (non_zero_coeff_count <= 128) {
                  return 8;
               }

               if (non_zero_coeff_count <= 256) {
                  return 16;
               }

               if (non_zero_coeff_count <= 384) {
                  return 24;
               }
            }

            if (tx_width == 32) {
               if (non_zero_coeff_count <= 128) {
                  return 4;
               }

               if (non_zero_coeff_count <= 256) {
                  return 8;
               }

               if (non_zero_coeff_count <= 512) {
                  return 16;
               }

               if (non_zero_coeff_count <= 768) {
                  return 24;
               }
            }
      }

      return tx_width >= 16 ? Math.min(tx_height, 32) : tx_height;
   }

   private static int getSize1D(int size_log2) {
      return size_log2 - 2;
   }

   static void reconsruct(
      int tx_type, int tx_size, boolean lossless, int[] buffer, int bufferPos, int startX, int startY, D.Array2DView frame, int non_zero_coeff_count
   ) {
      int txWidthLog2 = D.kTransformWidthLog2[tx_size];
      int txHeightLog2 = D.kTransformHeightLog2[tx_size];
      int txHeight = non_zero_coeff_count == 1 ? 1 : D.kTransformHeight[tx_size];
      if (txHeight > 4) {
         int rowSelect = txWidthLog2 - 2;
         switch (rowSelect) {
            case 0:
               txHeight = getNumRows(4, tx_type, txHeight, non_zero_coeff_count);
               break;
            case 1:
               txHeight = getNumRows(8, tx_type, txHeight, non_zero_coeff_count);
               break;
            case 2:
               txHeight = getNumRows(16, tx_type, txHeight, non_zero_coeff_count);
               break;
            case 3:
               txHeight = getNumRows(32, tx_type, txHeight, non_zero_coeff_count);
               break;
            case 4:
               txHeight = getNumRows(32, tx_type, txHeight, non_zero_coeff_count);
               break;
            default:
               LogWriter.writeLog("invalid num of rows found in reconstruct");
         }
      }

      int rowTransformSize = getSize1D(txWidthLog2);
      int rowTransform = lossless ? 3 : D.kRowTransform[tx_type];
      Itx.doTransform(rowTransform, rowTransformSize, 0, tx_type, tx_size, txHeight, buffer, bufferPos, startX, startY, frame);
      int columnTransformSize = getSize1D(txHeightLog2);
      int columnTransform = lossless ? 3 : D.kColumnTransform[tx_type];
      Itx.doTransform(columnTransform, columnTransformSize, 1, tx_type, tx_size, txHeight, buffer, bufferPos, startX, startY, frame);
   }
}
