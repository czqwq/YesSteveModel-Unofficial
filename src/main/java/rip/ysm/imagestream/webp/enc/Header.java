package rip.ysm.imagestream.webp.enc;

class Header {
   static final int VP8_HEADER_SIZE = 3;
   int type;
   byte version;
   boolean show_frame;
   int first_partition_length_in_bytes;

   short[] asThreeBytes() {
      int header = this.first_partition_length_in_bytes << 5 | (this.show_frame ? 1 : 0) << 4 | this.version << 1 | (this.type == 0 ? 0 : 1);
      return new short[]{(short)(header & 0xFF), (short)(header >> 8 & 0xFF), (short)(header >> 16 & 0xFF)};
   }
}
