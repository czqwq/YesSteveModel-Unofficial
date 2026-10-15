package rip.ysm.imagestream.webp.data;

class BitEncoder {
   private final int[][][][] tokenBinProbs;
   private int whtNzLeft;
   private final int[] whtNzTop;
   private final int[][] dctNzLeft = new int[][]{new int[4], new int[2], new int[2]};
   private final int[][] dctNzTop;

   BitEncoder(int mbWidth) {
      this.tokenBinProbs = LookUp.tokenDefaultBinProbs;
      this.whtNzTop = new int[mbWidth];
      this.dctNzTop = new int[][]{new int[mbWidth << 2], new int[mbWidth << 1], new int[mbWidth << 1]};
   }

   void encodeCoeffsWHT(EBool bc, int[] coeffs, int mbX) {
      int nCoeff = fastCountCoeffWHT(coeffs);
      this.encodeCoeffs(bc, coeffs, 0, nCoeff, 1, (mbX != 0 && this.whtNzLeft > 0 ? 1 : 0) + (this.whtNzTop[mbX] > 0 ? 1 : 0));
      this.whtNzLeft = nCoeff;
      this.whtNzTop[mbX] = nCoeff;
   }

   void encodeCoeffsDCT15(EBool bc, int[] coeffs, int mbX, int blkX, int blkY) {
      int nCoeff = countCoeff(coeffs, 16);
      int blkAbsX = (mbX << 2) + blkX;
      this.encodeCoeffs(bc, coeffs, 1, nCoeff, 0, (blkAbsX != 0 && this.dctNzLeft[0][blkY] > 0 ? 1 : 0) + (this.dctNzTop[0][blkAbsX] > 0 ? 1 : 0));
      this.dctNzLeft[0][blkY] = Math.max(nCoeff - 1, 0);
      this.dctNzTop[0][blkAbsX] = Math.max(nCoeff - 1, 0);
   }

   void encodeCoeffsDCTUV(EBool bc, int[] coeffs, int comp, int mbX, int blkX, int blkY) {
      int nCoeff = countCoeff(coeffs, 16);
      int blkAbsX = (mbX << 1) + blkX;
      this.encodeCoeffs(bc, coeffs, 0, nCoeff, 2, (blkAbsX != 0 && this.dctNzLeft[comp][blkY] > 0 ? 1 : 0) + (this.dctNzTop[comp][blkAbsX] > 0 ? 1 : 0));
      this.dctNzLeft[comp][blkY] = nCoeff;
      this.dctNzTop[comp][blkAbsX] = nCoeff;
   }

   private void encodeCoeffs(EBool bc, int[] coeffs, int firstCoeff, int nCoeff, int blkType, int ctx) {
      boolean prevZero = false;

      int i;
      for (i = firstCoeff; i < nCoeff; i++) {
         int[] probs = this.tokenBinProbs[blkType][LookUp.CO_BANDS[i]][ctx];
         int coeffAbs = abs(coeffs[i]);
         if (!prevZero) {
            bc.writeBit(probs[0], 1);
         }

         if (coeffAbs == 0) {
            bc.writeBit(probs[1], 0);
            ctx = 0;
         } else {
            bc.writeBit(probs[1], 1);
            if (coeffAbs == 1) {
               bc.writeBit(probs[2], 0);
               ctx = 1;
            } else {
               ctx = 2;
               bc.writeBit(probs[2], 1);
               if (coeffAbs <= 4) {
                  bc.writeBit(probs[3], 0);
                  if (coeffAbs == 2) {
                     bc.writeBit(probs[4], 0);
                  } else {
                     bc.writeBit(probs[4], 1);
                     bc.writeBit(probs[5], coeffAbs - 3);
                  }
               } else {
                  bc.writeBit(probs[3], 1);
                  if (coeffAbs <= 10) {
                     bc.writeBit(probs[6], 0);
                     if (coeffAbs <= 6) {
                        bc.writeBit(probs[7], 0);
                        bc.writeBit(159, coeffAbs - 5);
                     } else {
                        bc.writeBit(probs[7], 1);
                        int d = coeffAbs - 7;
                        bc.writeBit(165, d >> 1);
                        bc.writeBit(145, d & 1);
                     }
                  } else {
                     bc.writeBit(probs[6], 1);
                     if (coeffAbs <= 34) {
                        bc.writeBit(probs[8], 0);
                        if (coeffAbs <= 18) {
                           bc.writeBit(probs[9], 0);
                           writeCat3Ext(bc, coeffAbs);
                        } else {
                           bc.writeBit(probs[9], 1);
                           writeCat4Ext(bc, coeffAbs);
                        }
                     } else {
                        bc.writeBit(probs[8], 1);
                        if (coeffAbs <= 66) {
                           bc.writeBit(probs[10], 0);
                           writeCatExt(bc, coeffAbs, 35, LookUp.probCoeffExtCat5);
                        } else {
                           bc.writeBit(probs[10], 1);
                           writeCatExt(bc, coeffAbs, 67, LookUp.probCoeffExtCat6);
                        }
                     }
                  }
               }
            }

            bc.writeBit(128, sign(coeffs[i]));
         }

         prevZero = coeffAbs == 0;
      }

      if (nCoeff < 16) {
         int[] probsx = this.tokenBinProbs[blkType][LookUp.CO_BANDS[i]][ctx];
         bc.writeBit(probsx[0], 0);
      }
   }

   private static void writeCat3Ext(EBool bc, int coeff) {
      int d = coeff - 11;
      bc.writeBit(173, d >> 2);
      bc.writeBit(148, d >> 1 & 1);
      bc.writeBit(140, d & 1);
   }

   private static void writeCat4Ext(EBool bc, int coeff) {
      int d = coeff - 19;
      bc.writeBit(176, d >> 3);
      bc.writeBit(155, d >> 2 & 1);
      bc.writeBit(140, d >> 1 & 1);
      bc.writeBit(135, d & 1);
   }

   private static void writeCatExt(EBool bc, int coeff, int catOff, int[] cat) {
      int d = coeff - catOff;
      int b = cat.length - 1;

      for (int i = 0; b >= 0; b--) {
         bc.writeBit(cat[i++], d >> b & 1);
      }
   }

   private static int fastCountCoeffWHT(int[] coeffs) {
      return coeffs[15] != 0 ? 16 : countCoeff(coeffs, 15);
   }

   private static int countCoeff(int[] coeffs, int nCoeff) {
      while (nCoeff > 0) {
         if (coeffs[--nCoeff] != 0) {
            return nCoeff + 1;
         }
      }

      return nCoeff;
   }

   private static int abs(int val) {
      int sign = val >> 31;
      return (val ^ sign) - sign;
   }

   private static int sign(int val) {
      return -(val >> 31);
   }
}
