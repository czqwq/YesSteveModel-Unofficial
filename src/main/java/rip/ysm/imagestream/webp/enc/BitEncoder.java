package rip.ysm.imagestream.webp.enc;

class BitEncoder {
   static final short[] vp8_prob_cost = new short[]{
      2047,
      2047,
      1791,
      1641,
      1535,
      1452,
      1385,
      1328,
      1279,
      1235,
      1196,
      1161,
      1129,
      1099,
      1072,
      1046,
      1023,
      1000,
      979,
      959,
      940,
      922,
      905,
      889,
      873,
      858,
      843,
      829,
      816,
      803,
      790,
      778,
      767,
      755,
      744,
      733,
      723,
      713,
      703,
      693,
      684,
      675,
      666,
      657,
      649,
      641,
      633,
      625,
      617,
      609,
      602,
      594,
      587,
      580,
      573,
      567,
      560,
      553,
      547,
      541,
      534,
      528,
      522,
      516,
      511,
      505,
      499,
      494,
      488,
      483,
      477,
      472,
      467,
      462,
      457,
      452,
      447,
      442,
      437,
      433,
      428,
      424,
      419,
      415,
      410,
      406,
      401,
      397,
      393,
      389,
      385,
      381,
      377,
      373,
      369,
      365,
      361,
      357,
      353,
      349,
      346,
      342,
      338,
      335,
      331,
      328,
      324,
      321,
      317,
      314,
      311,
      307,
      304,
      301,
      297,
      294,
      291,
      288,
      285,
      281,
      278,
      275,
      272,
      269,
      266,
      263,
      260,
      257,
      255,
      252,
      249,
      246,
      243,
      240,
      238,
      235,
      232,
      229,
      227,
      224,
      221,
      219,
      216,
      214,
      211,
      208,
      206,
      203,
      201,
      198,
      196,
      194,
      191,
      189,
      186,
      184,
      181,
      179,
      177,
      174,
      172,
      170,
      168,
      165,
      163,
      161,
      159,
      156,
      154,
      152,
      150,
      148,
      145,
      143,
      141,
      139,
      137,
      135,
      133,
      131,
      129,
      127,
      125,
      123,
      121,
      119,
      117,
      115,
      113,
      111,
      109,
      107,
      105,
      103,
      101,
      99,
      97,
      95,
      93,
      92,
      90,
      88,
      86,
      84,
      82,
      81,
      79,
      77,
      75,
      73,
      72,
      70,
      68,
      66,
      65,
      63,
      61,
      60,
      58,
      56,
      55,
      53,
      51,
      50,
      48,
      46,
      45,
      43,
      41,
      40,
      38,
      37,
      35,
      33,
      32,
      30,
      29,
      27,
      25,
      24,
      22,
      21,
      19,
      18,
      16,
      15,
      13,
      12,
      10,
      9,
      7,
      6,
      4,
      3,
      1,
      1
   };
   private int count;
   private int range;
   private long lowvalue;
   private int pos;
   private FullGetSetPointer buffer;
   static final int VP8_PROB_HALF = 128;

   void vp8_encode_bool(boolean bit, int probability) {
      this.encodeBitInRangeLow(bit, 1 + ((this.range - 1) * probability >> 8));
      int shift = W.vp8Norm[this.range];
      this.updateRangeCount(shift);
      if (this.count >= 0) {
         int offset = shift - this.count;
         this.adjustBuffer(offset);
         shift = this.writeCurrEncodedByte(offset);
      }

      this.lowvalue <<= shift;
   }

   void vp8_encode_extra(int e) {
      this.encodeBitInRangeLow((e & 1) > 0, this.range + 1 >> 1);
      this.updateRangeCount(1);
      this.adjustBuffer(1);
      if (this.count == 0) {
         this.writeCurrEncodedByte(1);
      } else {
         this.lowvalue <<= 1;
      }
   }

   private void updateRangeCount(int shift) {
      this.range <<= shift;
      this.count += shift;
   }

   private void encodeBitInRangeLow(boolean bit, int split) {
      if (bit) {
         this.lowvalue += split;
         this.range -= split;
      } else {
         this.range = split;
      }
   }

   private int writeCurrEncodedByte(int offset) {
      int shift = this.count;
      this.buffer.incBy(this.pos);
      this.buffer.incBy(-this.pos);
      this.buffer.setRel(this.pos++, (short)(this.lowvalue >> 24 - offset & 255L));
      this.lowvalue <<= offset;
      this.lowvalue &= 16777215L;
      this.count -= 8;
      return shift;
   }

   private void adjustBuffer(int offset) {
      if ((this.lowvalue << offset - 1 & 2147483648L) != 0L) {
         int x;
         for (x = this.pos - 1; x >= 0 && this.buffer.getRel(x) == 255; x--) {
            this.buffer.setRel(x, (short)0);
         }

         this.buffer.setRel(x, (short)(this.buffer.getRel(x) + 1));
      }
   }

   void vp8_encode_value(int data, int bits) {
      for (int bit = bits - 1; bit >= 0; bit--) {
         this.vp8_encode_bool((1 & data >> bit) == 1, 128);
      }
   }

   void vp8_start_encode(FullGetSetPointer source) {
      this.lowvalue = 0L;
      this.range = 255;
      this.count = -24;
      this.buffer = source.shallowCopy();
      this.pos = 0;
   }

   void vp8_stop_encode() {
      for (int i = 0; i < 32; i++) {
         this.vp8_encode_bool(false, 128);
      }
   }

   int getPos() {
      return this.pos;
   }

   void vp8_write_bit(boolean bit) {
      this.vp8_encode_bool(bit, 128);
   }
}
