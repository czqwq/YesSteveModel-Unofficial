package rip.ysm.imagestream.avif.dec;

class RawBit {
   static final int kMaximumLeb128Size = 8;
   static final int kLeb128ValueByteMask = 127;
   static final int kLeb128TerminationByteMask = 128;
   final byte[] data;
   int bitOffset;
   final int size;
   int startByteOffset = 0;
   int startBitOffset = 0;

   RawBit(byte[] data, int offset, int size) {
      this.data = data;
      this.startByteOffset = offset;
      this.bitOffset = offset * 8;
      this.startBitOffset = this.startByteOffset * 8;
      this.size = size;
   }

   int DivideBy8(int n, boolean ceil) {
      return n + (ceil ? 7 : 0) >> 3;
   }

   int byte_offset() {
      return (this.bitOffset + 7 >> 3) - this.startByteOffset;
   }

   int bit_offset() {
      return this.bitOffset - this.startBitOffset;
   }

   int ReadBitImpl() {
      int byteOffset = this.DivideBy8(this.bitOffset, false);
      int b = this.data[byteOffset] & 255;
      int shift = 7 - (this.bitOffset & 7);
      this.bitOffset++;
      return b >> shift & 1;
   }

   boolean finished() {
      return this.DivideBy8(this.bitOffset, false) >= this.size;
   }

   int readBit() {
      return this.finished() ? -1 : this.ReadBitImpl();
   }

   boolean canReadLiteral(int num_bits) {
      if (this.finished()) {
         return false;
      } else {
         int bit_offset = this.bitOffset + num_bits - 1;
         return this.DivideBy8(bit_offset, false) < this.size;
      }
   }

   long readLiteral(int num_bits) {
      if (!this.canReadLiteral(num_bits)) {
         return -1L;
      } else {
         long literal = 0L;
         int bit = num_bits - 1;

         do {
            literal <<= 1;
            literal |= this.ReadBitImpl();
         } while (--bit >= 0);

         return literal;
      }
   }

   int readInverseSignedLiteral(int num_bits) {
      int value = (int)this.readLiteral(num_bits + 1);
      int signBit = 1 << num_bits;
      if ((value & signBit) != 0) {
         value -= 2 * signBit;
      }

      return value;
   }

   int readLittleEndian(int num_bytes) {
      int byte_offset = this.DivideBy8(this.bitOffset, false);
      int value = 0;

      for (int i = 0; i < num_bytes; i++) {
         int bb = this.data[byte_offset] & 255;
         value |= bb << i * 8;
         byte_offset++;
      }

      this.bitOffset = byte_offset * 8;
      return value;
   }

   long readUnsignedLeb128() {
      long value64 = 0L;

      for (int i = 0; i < 8; i++) {
         int byteOffset = this.DivideBy8(this.bitOffset, false);
         int bb = this.data[byteOffset] & 255;
         this.bitOffset += 8;
         value64 |= (bb & 127) << i * 7;
         if ((bb & 128) == 0) {
            return value64;
         }
      }

      return value64;
   }

   int readUvlc() {
      int leading_zeros = 0;

      while (true) {
         int bit = this.readBit();
         if (bit == 1) {
            if (leading_zeros != 0) {
               bit = (int)this.readLiteral(leading_zeros);
               bit += (1 << leading_zeros) - 1;
            } else {
               bit = 0;
            }

            return bit;
         }

         leading_zeros++;
      }
   }

   boolean alignToNextByte() {
      while ((this.bitOffset & 7) != 0) {
         if (this.readBit() != 0) {
            return false;
         }
      }

      return true;
   }

   boolean skipTrailingBits(int num_bits) {
      if (this.readBit() != 1) {
         return false;
      } else {
         for (int i = 0; i < num_bits - 1; i++) {
            if (this.readBit() != 0) {
               return false;
            }
         }

         return true;
      }
   }

   boolean skipBytes(int num_bytes) {
      return (this.bitOffset & 7) != 0 ? false : this.skipBits(num_bytes * 8);
   }

   boolean skipBits(int num_bits) {
      if (this.finished()) {
         return false;
      } else {
         int bit_offset = this.bitOffset + num_bits - 1;
         if (this.DivideBy8(bit_offset, false) >= this.size) {
            return false;
         } else {
            this.bitOffset += num_bits;
            return true;
         }
      }
   }

   static int inverseRecenter(int r, int v) {
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

   int decodeSubexp(int num_symbols, int control) {
      int i = 0;
      int mk = 0;
      int value = 0;

      while (true) {
         int b = i != 0 ? control + i - 1 : control;
         if (b >= 32) {
            return 0;
         }

         int a = 1 << b;
         if (num_symbols <= mk + 3 * a) {
            value = this.decodeUniform(num_symbols - mk);
            return value + mk;
         }

         int subexp_more_bits = this.readBit();
         if (subexp_more_bits == -1) {
            return 0;
         }

         if (subexp_more_bits == 0) {
            int subexp_bits = (int)this.readLiteral(b);
            if (subexp_bits == -1) {
               return 0;
            }

            return subexp_bits + mk;
         }

         i++;
         mk += a;
      }
   }
}
