package rip.ysm.imagestream.avif.dec;

import java.io.ByteArrayOutputStream;

class EBit {
   ByteArrayOutputStream data = new ByteArrayOutputStream();
   int partial_byte = 0;
   int bitpos = 0;

   void write_bit(int bit) {
      this.partial_byte = (this.partial_byte << 1 | bit) & 0xFF;
      this.bitpos++;
      if (this.bitpos % 8 == 0) {
         this.data.write(this.partial_byte);
         this.partial_byte = 0;
      }
   }

   void write_bool(boolean flag) {
      this.write_bit(flag ? 1 : 0);
   }

   void write_bits(long bits, int nbits) {
      int partial_bits = this.bitpos % 8;
      long combined = this.partial_byte * 1L << nbits | bits;
      int combined_nbits = partial_bits + nbits;
      int full_bytes = combined_nbits / 8;
      int leftover_bits = combined_nbits % 8;
      this.write_be_bytes(combined >>> leftover_bits, full_bytes);
      this.partial_byte = (int)(combined & (1L << leftover_bits) - 1L);
      this.bitpos += nbits;
   }

   private void write_be_bytes(long value, int numBytes) {
      for (int i = numBytes - 1; i >= 0; i--) {
         int bb = (int)(value >>> 8 * i & 255L);
         this.data.write(bb);
      }
   }

   void byte_align() {
      int partial_bits = this.bitpos % 8;
      if (partial_bits != 0) {
         int extra_bits = 8 - partial_bits;
         this.data.write(this.partial_byte << extra_bits);
         this.partial_byte = 0;
         this.bitpos += extra_bits;
      }
   }

   byte[] finalize(boolean add_trailing_one_bit) {
      if (add_trailing_one_bit) {
         this.write_bit(1);
      }

      this.byte_align();
      return this.data.toByteArray();
   }
}
