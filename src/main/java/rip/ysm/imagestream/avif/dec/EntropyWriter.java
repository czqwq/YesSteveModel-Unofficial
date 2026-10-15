package rip.ysm.imagestream.avif.dec;

import rip.ysm.imagestream.internal.LogWriter;

class EntropyWriter {
   long low = 0L;
   long range = 32768L;
   int count = -9;
   final E.DataBig data = new E.DataBig();

   void propagate_carry() {
      for (int i = this.data.len() - 1; i >= 0; i--) {
         if ((this.data.cur[i] & 255) != 255) {
            this.data.cur[i] = (byte)((this.data.cur[i] & 255) + 1);
            return;
         }

         this.data.cur[i] = 0;
      }
   }

   void write_symbol(int symbol, int[] cdf) {
      int num_symbols = cdf.length + 1;
      int inv_hi = symbol == num_symbols - 1 ? 0 : 32768 - cdf[symbol];
      if (symbol == 0) {
         this.range = this.range - (((this.range >>> 8) * (inv_hi >> 6) >> 1) + 4 * (num_symbols - 1));
      } else {
         int inv_lo = 32768 - cdf[symbol - 1];
         long u = ((this.range >>> 8) * (inv_lo >> 6) >> 1) + 4 * (num_symbols - symbol);
         long v = ((this.range >>> 8) * (inv_hi >> 6) >> 1) + 4 * (num_symbols - symbol - 1);
         this.low = this.low + (this.range - u);
         this.range = u - v;
      }

      int d = (int)(15L - E.FloorLog2(this.range));
      int s = this.count + d;
      if (s >= 16) {
         int num_bytes_ready = (s >> 3) + 1;
         int c = this.count + 24 - (num_bytes_ready << 3);
         long output = this.low >>> c;
         this.low &= (1L << c) - 1L;
         int carry = (int)(output & 1L << (num_bytes_ready << 3));
         output &= (1L << (num_bytes_ready << 3)) - 1L;
         if (carry != 0) {
            this.propagate_carry();
         }

         write_be_bytes(this.data, output, num_bytes_ready);
         s = c + d - 24;
      }

      this.low <<= d;
      this.range <<= d;
      this.count = s;
   }

   private static void write_be_bytes(E.DataBig data, long value, int numBytes) {
      for (int i = numBytes - 1; i >= 0; i--) {
         int bb = (int)(value >>> 8 * i & 255L);
         data.write_u8(bb & 0xFF);
      }
   }

   void write_bit(int value, int p_zero) {
      this.write_symbol(value, new int[]{p_zero});
   }

   void write_bool(boolean value, int[] p_false) {
      this.write_symbol(value ? 1 : 0, p_false);
   }

   void write_literal(int value, int nbits) {
      if (value < 0) {
         LogWriter.writeLog("error");
      }

      for (int shift = nbits - 1; shift >= 0; shift--) {
         int bit = value >>> shift & 1;
         this.write_bit(bit, 16384);
      }
   }

   void write_golomb(int value) {
      if (value < 0) {
         LogWriter.writeLog("error");
      }

      int length = E.FloorLog2(++value);
      this.write_literal(0, length);
      this.write_literal(value, length + 1);
   }

   byte[] finalizeBytes() {
      int s = this.count + 10;
      int m = 16383;
      long e = this.low + m & ~m | m + 1;

      for (long n = (1L << this.count + 16) - 1L; s > 0; n >>= 8) {
         long val = e >>> this.count + 16;
         if ((val & 256L) != 0L) {
            this.propagate_carry();
         }

         this.data.write_u8((int)(val & 255L));
         e &= n;
         s -= 8;
         this.count -= 8;
      }

      return this.data.finalizeBytes();
   }
}
