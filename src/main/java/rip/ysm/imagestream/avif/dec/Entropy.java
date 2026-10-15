package rip.ysm.imagestream.avif.dec;

class Entropy {
   byte[] data;
   int byteP = 0;
   int dataEnd;
   int bits;
   boolean allowUpdateCdf;
   long valuesInRange;
   long windowDiff;
   static final int kReadBitMask = -256;
   static final int kCdfPrecision = 6;
   static final int kMinimumProbabilityPerSymbol = 4;
   static final int kWindowSize = 32;

   Entropy(byte[] data, int offset, int size, boolean allow_update_cdf) {
      this.data = data;
      this.dataEnd = Math.min(offset + size, data.length);
      this.allowUpdateCdf = allow_update_cdf;
      this.byteP = offset;
      if (size >= 4) {
         int var10000 = offset + size - 4 + 1;
      }

      this.valuesInRange = 32768L;
      this.windowDiff = 0L;
      this.bits = -15;
      this.populate();
   }

   static int scaleCdf(int valuesInRangeShifted, int[] cdf, int index, int symbolCount) {
      return (valuesInRangeShifted * (cdf[index] >> 6) >> 1) + 4 * (symbolCount - index);
   }

   static void updateCdf(int[] cdf, int symbolCount, int symbol) {
      int count = cdf[symbolCount];
      int rate = (count >> 4) + 4 + (symbolCount > 3 ? 1 : 0);
      int i = 0;

      do {
         if (i < symbol) {
            cdf[i] += 32768 - cdf[i] >> rate;
         } else {
            cdf[i] -= cdf[i] >> rate;
         }
      } while (++i < symbolCount - 1);

      cdf[symbolCount] += count < 32 ? 1 : 0;
   }

   int readBit() {
      long curr = ((this.valuesInRange & -256L) >> 1) + 4L;
      int symbol_value = (int)(this.windowDiff >> this.bits & 65535L);
      int bit = 1;
      if (symbol_value >= curr) {
         this.valuesInRange -= curr;
         this.windowDiff = this.windowDiff - (curr << this.bits);
         bit = 0;
      } else {
         this.valuesInRange = curr;
      }

      this.normalize();
      return bit;
   }

   long readLiteral(int n) {
      long literal = 0L;
      int bit = n - 1;

      do {
         literal <<= 1;
         literal |= this.readBit();
      } while (--bit >= 0);

      return literal;
   }

   int readSymbol(int[] cdf, int symbolCount) {
      int symbol = this.readSymbolImpl(cdf, symbolCount);
      if (this.allowUpdateCdf) {
         updateCdf(cdf, symbolCount, symbol);
      }

      return symbol;
   }

   boolean readSymbol(int[] cdf) {
      boolean symbol = this.readSymbolImpl(cdf[0]) != 0;
      if (this.allowUpdateCdf) {
         int count = cdf[2] & 65535;
         int rate = 4 | count >> 4;
         if (symbol) {
            cdf[0] += 32768 - cdf[0] >> rate;
         } else {
            cdf[0] -= cdf[0] >> rate;
         }

         cdf[2] += count < 32 ? 1 : 0;
      }

      return symbol;
   }

   boolean readSymbolWithoutCdfUpdate(int cdf) {
      return this.readSymbolImpl(cdf) != 0;
   }

   private int readSymbolImpl(int cdf) {
      int symbolValue = (int)(this.windowDiff >> this.bits & 65535L);
      long curr = ((this.valuesInRange >> 8) * (cdf >> 6) >> 1) + 4L;
      int symbol = symbolValue < curr ? 1 : 0;
      if (symbol == 1) {
         this.valuesInRange = curr;
      } else {
         this.valuesInRange -= curr;
         this.windowDiff = this.windowDiff - (curr << this.bits);
      }

      this.normalize();
      return symbol;
   }

   int readSymbolImpl(int[] cdf, int symbolCount) {
      symbolCount--;
      long curr = this.valuesInRange;
      int symbol = -1;
      int symbolValue = (int)(this.windowDiff >> this.bits & 65535L);
      int delta = 4 * symbolCount;

      long prev;
      do {
         prev = curr;
         curr = ((this.valuesInRange >> 8) * (cdf[++symbol] >> 6) >> 1) + delta;
         delta -= 4;
      } while (symbolValue < curr);

      this.valuesInRange = prev - curr;
      this.windowDiff = this.windowDiff - (curr << this.bits);
      this.normalize();
      return symbol;
   }

   void normalize() {
      int bits_used = (int)(15L ^ D.FloorLog2(this.valuesInRange));
      this.bits -= bits_used;
      this.valuesInRange <<= bits_used;
      if (this.bits < 0) {
         this.populate();
      }
   }

   void populate() {
      int kMaxCachedBits = 16;

      for (int count = 23 - (this.bits + 15); count >= 0 && this.byteP < this.dataEnd; count -= 8) {
         int value = ~(this.data[this.byteP++] & 255);
         value &= 255;
         this.windowDiff = value | this.windowDiff << 8;
         this.bits += 8;
      }

      if (this.byteP == this.dataEnd) {
         this.windowDiff = (this.windowDiff + 1L << kMaxCachedBits - this.bits) - 1L;
         this.bits = kMaxCachedBits;
      }
   }

   private static int inverseRecenter(int r, int v) {
      if (v > r << 1) {
         return v;
      } else {
         return (v & 1) != 0 ? r - (v + 1 >> 1) : r + (v >> 1);
      }
   }

   int decodeUniform(int max) {
      int l = D.FloorLog2(max) + 1;
      int m = (1 << l) - max;
      int v = (int)this.readLiteral(l - 1);
      return v < m ? v : (v << 1) - m + this.readBit();
   }

   int decodeSignedSubexpWithReference(int low, int high, int reference, int control) {
      int value = this.decodeUnsignedSubexpWithReference(high - low, reference - low, control);
      return value + low;
   }

   int decodeUnsignedSubexpWithReference(int mx, int reference, int control) {
      int v = this.decodeSubexp(mx, control);
      int value;
      if (reference << 1 <= mx) {
         value = inverseRecenter(reference, v);
      } else {
         value = mx - 1 - inverseRecenter(mx - 1 - reference, v);
      }

      return value;
   }

   int decodeSubexp(int numSymbols, int control) {
      int i = 0;
      int mk = 0;
      int value = 0;

      while (true) {
         int b = i != 0 ? control + i - 1 : control;
         if (b >= 32) {
            return 0;
         }

         int a = 1 << b;
         if (numSymbols <= mk + 3 * a) {
            value = this.decodeUniform(numSymbols - mk);
            return value + mk;
         }

         int subexpMoreBits = this.readBit();
         if (subexpMoreBits == -1) {
            return 0;
         }

         if (subexpMoreBits == 0) {
            int subexpBits = (int)this.readLiteral(b);
            if (subexpBits == -1) {
               return 0;
            }

            return subexpBits + mk;
         }

         i++;
         mk += a;
      }
   }
}
