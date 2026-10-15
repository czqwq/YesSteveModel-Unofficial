package rip.ysm.imagestream.jpeg;

import rip.ysm.imagestream.utility.ImageUtils;
import rip.ysm.imagestream.utility.Access;
import rip.ysm.imagestream.utility.PixGet;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferByte;
import java.io.IOException;
import java.io.OutputStream;

public class JpegEncoder {
   private static final int[] QL = new int[]{
      16,
      11,
      10,
      16,
      24,
      40,
      51,
      61,
      12,
      12,
      14,
      19,
      26,
      58,
      60,
      55,
      14,
      13,
      16,
      24,
      40,
      57,
      69,
      56,
      14,
      17,
      22,
      29,
      51,
      87,
      80,
      62,
      18,
      22,
      37,
      56,
      68,
      109,
      103,
      77,
      24,
      35,
      55,
      64,
      81,
      104,
      113,
      92,
      49,
      64,
      78,
      87,
      103,
      121,
      120,
      101,
      72,
      92,
      95,
      98,
      112,
      100,
      103,
      99
   };
   private static final int[] QC = new int[]{
      17,
      18,
      24,
      47,
      99,
      99,
      99,
      99,
      18,
      21,
      26,
      66,
      99,
      99,
      99,
      99,
      24,
      26,
      56,
      99,
      99,
      99,
      99,
      99,
      47,
      66,
      99,
      99,
      99,
      99,
      99,
      99,
      99,
      99,
      99,
      99,
      99,
      99,
      99,
      99,
      99,
      99,
      99,
      99,
      99,
      99,
      99,
      99,
      99,
      99,
      99,
      99,
      99,
      99,
      99,
      99,
      99,
      99,
      99,
      99,
      99,
      99,
      99,
      99
   };
   private static final int[] ZIGZAG = new int[]{
      0,
      1,
      5,
      6,
      14,
      15,
      27,
      28,
      2,
      4,
      7,
      13,
      16,
      26,
      29,
      42,
      3,
      8,
      12,
      17,
      25,
      30,
      41,
      43,
      9,
      11,
      18,
      24,
      31,
      40,
      44,
      53,
      10,
      19,
      23,
      32,
      39,
      45,
      52,
      54,
      20,
      22,
      33,
      38,
      46,
      51,
      55,
      60,
      21,
      34,
      37,
      47,
      50,
      56,
      59,
      61,
      35,
      36,
      48,
      49,
      57,
      58,
      62,
      63
   };
   private static final int[] CODESDCL = new int[]{0, 0, 1, 5, 1, 1, 1, 1, 1, 1, 0, 0, 0, 0, 0, 0, 0};
   private static final int[] SYMBOLSDCL = new int[]{0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11};
   private static final int[] CODESACL = new int[]{0, 0, 2, 1, 3, 3, 2, 4, 3, 5, 5, 4, 4, 0, 0, 1, 125};
   private static final int[] SYMBOLSACL = new int[]{
      1,
      2,
      3,
      0,
      4,
      17,
      5,
      18,
      33,
      49,
      65,
      6,
      19,
      81,
      97,
      7,
      34,
      113,
      20,
      50,
      129,
      145,
      161,
      8,
      35,
      66,
      177,
      193,
      21,
      82,
      209,
      240,
      36,
      51,
      98,
      114,
      130,
      9,
      10,
      22,
      23,
      24,
      25,
      26,
      37,
      38,
      39,
      40,
      41,
      42,
      52,
      53,
      54,
      55,
      56,
      57,
      58,
      67,
      68,
      69,
      70,
      71,
      72,
      73,
      74,
      83,
      84,
      85,
      86,
      87,
      88,
      89,
      90,
      99,
      100,
      101,
      102,
      103,
      104,
      105,
      106,
      115,
      116,
      117,
      118,
      119,
      120,
      121,
      122,
      131,
      132,
      133,
      134,
      135,
      136,
      137,
      138,
      146,
      147,
      148,
      149,
      150,
      151,
      152,
      153,
      154,
      162,
      163,
      164,
      165,
      166,
      167,
      168,
      169,
      170,
      178,
      179,
      180,
      181,
      182,
      183,
      184,
      185,
      186,
      194,
      195,
      196,
      197,
      198,
      199,
      200,
      201,
      202,
      210,
      211,
      212,
      213,
      214,
      215,
      216,
      217,
      218,
      225,
      226,
      227,
      228,
      229,
      230,
      231,
      232,
      233,
      234,
      241,
      242,
      243,
      244,
      245,
      246,
      247,
      248,
      249,
      250
   };
   private static final int[] CODESDCC = new int[]{0, 0, 3, 1, 1, 1, 1, 1, 1, 1, 1, 1, 0, 0, 0, 0, 0};
   private static final int[] SYMBOLSDCC = new int[]{0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11};
   private static final int[] CODESACC = new int[]{0, 0, 2, 1, 2, 4, 4, 3, 4, 7, 5, 4, 4, 0, 1, 2, 119};
   private static final int[] SYMBOLSACC = new int[]{
      0,
      1,
      2,
      3,
      17,
      4,
      5,
      33,
      49,
      6,
      18,
      65,
      81,
      7,
      97,
      113,
      19,
      34,
      50,
      129,
      8,
      20,
      66,
      145,
      161,
      177,
      193,
      9,
      35,
      51,
      82,
      240,
      21,
      98,
      114,
      209,
      10,
      22,
      36,
      52,
      225,
      37,
      241,
      23,
      24,
      25,
      26,
      38,
      39,
      40,
      41,
      42,
      53,
      54,
      55,
      56,
      57,
      58,
      67,
      68,
      69,
      70,
      71,
      72,
      73,
      74,
      83,
      84,
      85,
      86,
      87,
      88,
      89,
      90,
      99,
      100,
      101,
      102,
      103,
      104,
      105,
      106,
      115,
      116,
      117,
      118,
      119,
      120,
      121,
      122,
      130,
      131,
      132,
      133,
      134,
      135,
      136,
      137,
      138,
      146,
      147,
      148,
      149,
      150,
      151,
      152,
      153,
      154,
      162,
      163,
      164,
      165,
      166,
      167,
      168,
      169,
      170,
      178,
      179,
      180,
      181,
      182,
      183,
      184,
      185,
      186,
      194,
      195,
      196,
      197,
      198,
      199,
      200,
      201,
      202,
      210,
      211,
      212,
      213,
      214,
      215,
      216,
      217,
      218,
      226,
      227,
      228,
      229,
      230,
      231,
      232,
      233,
      234,
      242,
      243,
      244,
      245,
      246,
      247,
      248,
      249,
      250
   };
   private final int[] TL = new int[64];
   private final int[] TC = new int[64];
   private final float[] FDL = new float[64];
   private final float[] FDC = new float[64];
   private final int[] DU = new int[64];
   private final int[] DCHTL = new int[12];
   private final int[] DCHTC = new int[12];
   private final int[] ACHTL = new int[256];
   private final int[] ACHTC = new int[256];
   private final int[] codes = new int[65536];
   private int buffer;
   private int bitLen;
   private static final int bLen = 1024;
   private final byte[] bArr = new byte[1024];
   private int bp;
   private OutputStream stream;
   private PixGet pg;
   private BufferedImage img;
   private int quality = 75;

   public JpegEncoder() {
   }

   public void setQuality(int quality) { this.quality = quality; }
   public int getQuality() { return quality; }

   public void write(BufferedImage image, OutputStream out) throws IOException {
      BufferedImage imageToCompress = ImageUtils.fixSubBufferedImage(image);
      this.pg = Access.getPixGet(imageToCompress);
      this.bp = 0;
      this.img = imageToCompress;
      this.stream = out;

      this.encode(image.getWidth(), this.img.getHeight(), this.img.getType(), null);
      if (this.bp > 0) {
         this.stream.write(this.bArr, 0, this.bp);
      }
   }

   private void generateQTables(float qq) {
      for (int i = 0; i < 64; i++) {
         int j = (int)((QL[i] * qq + 50.0F) / 100.0F);
         this.TL[ZIGZAG[i]] = j < 1 ? 1 : Math.min(j, 255);
      }

      for (int i = 0; i < 64; i++) {
         int j = (int)((QC[i] * qq + 50.0F) / 100.0F);
         this.TC[ZIGZAG[i]] = j < 1 ? 1 : Math.min(j, 255);
      }

      float[] multipliyer = new float[]{1.0F, 1.3870399F, 1.306563F, 1.1758755F, 1.0F, 0.78569496F, 0.5411961F, 0.27589938F};
      int k = 0;

      for (int row = 0; row < 8; row++) {
         for (int col = 0; col < 8; col++) {
            double mrc8 = multipliyer[row] * multipliyer[col] * 8.0;
            this.FDL[k] = (float)(1.0 / (this.TL[ZIGZAG[k]] * mrc8));
            this.FDC[k] = (float)(1.0 / (this.TC[ZIGZAG[k]] * mrc8));
            k++;
         }
      }
   }

   private static void generateHuffmanMapping(int[] huffman, int[] codes, int[] lookup) {
      int value = 0;
      int index = 0;

      for (int i = 1; i <= 16; i++) {
         for (int j = 1; j <= codes[i]; j++) {
            int t = lookup[index];
            huffman[t] = value << 16 | i;
            index++;
            value++;
         }

         value *= 2;
      }
   }

   private void generateHuffmanItems() {
      int low = 1;
      int high = 2;

      for (int i = 1; i <= 15; i++) {
         for (int j = low; j < high; j++) {
            this.codes[32767 + j] = j << 16 | i;
         }

         for (int nrneg = -(high - 1); nrneg <= -low; nrneg++) {
            this.codes[32767 + nrneg] = high - 1 + nrneg << 16 | i;
         }

         low <<= 1;
         high <<= 1;
      }
   }

   private void putHuffBits(int bs) throws IOException {
      int code = bs >> 16 & 65535;
      int size = bs & 65535;
      this.buffer = this.buffer << size | code;
      this.bitLen += size;
      if (this.bitLen > 15) {
         this.bitLen -= 8;
         int c = this.buffer >> this.bitLen & 0xFF;
         this.putByte(c);
         if (c == 255) {
            this.putByte(0);
         }

         this.bitLen -= 8;
         c = this.buffer >> this.bitLen & 0xFF;
         this.putByte(c);
         if (c == 255) {
            this.putByte(0);
         }
      }
   }

   private void putByte(int v) throws IOException {
      if (this.bp == 1024) {
         this.stream.write(this.bArr);
         this.bp = 0;
      }

      this.bArr[this.bp++] = (byte)v;
   }

   private void putChar(int v) throws IOException {
      this.putByte(v >> 8 & 0xFF);
      this.putByte(v & 0xFF);
   }

   private static void fDCT(float[] inp) {
      processH(inp);
      processV(inp);
   }

   private static void processH(float[] inp) {
      for (int p = 0; p < 64; p += 8) {
         float d0 = inp[p];
         float d1 = inp[p + 1];
         float d2 = inp[p + 2];
         float d3 = inp[p + 3];
         float d4 = inp[p + 4];
         float d5 = inp[p + 5];
         float d6 = inp[p + 6];
         float d7 = inp[p + 7];
         float t0 = d0 + d7;
         float t7 = d0 - d7;
         float t1 = d1 + d6;
         float t6 = d1 - d6;
         float t2 = d2 + d5;
         float t5 = d2 - d5;
         float t3 = d3 + d4;
         float t4 = d3 - d4;
         float t10 = t0 + t3;
         float t13 = t0 - t3;
         float t11 = t1 + t2;
         float t12 = t1 - t2;
         inp[p] = t10 + t11;
         inp[p + 4] = t10 - t11;
         d1 = (t12 + t13) * 0.70710677F;
         inp[p + 2] = t13 + d1;
         inp[p + 6] = t13 - d1;
         t10 = t4 + t5;
         t12 = t6 + t7;
         d5 = (t10 - t12) * 0.38268343F;
         d2 = 0.5411961F * t10 + d5;
         d4 = 1.306563F * t12 + d5;
         d3 = (t5 + t6) * 0.70710677F;
         d6 = t7 + d3;
         d7 = t7 - d3;
         inp[p + 5] = d7 + d2;
         inp[p + 3] = d7 - d2;
         inp[p + 1] = d6 + d4;
         inp[p + 7] = d6 - d4;
      }
   }

   private static void processV(float[] inp) {
      for (int p = 0; p < 8; p++) {
         float d0 = inp[p];
         float d1 = inp[p + 8];
         float d2 = inp[p + 16];
         float d3 = inp[p + 24];
         float d4 = inp[p + 32];
         float d5 = inp[p + 40];
         float d6 = inp[p + 48];
         float d7 = inp[p + 56];
         float t0 = d0 + d7;
         float t7 = d0 - d7;
         float t1 = d1 + d6;
         float t6 = d1 - d6;
         float t2 = d2 + d5;
         float t5 = d2 - d5;
         float t3 = d3 + d4;
         float t4 = d3 - d4;
         float t10 = t0 + t3;
         float t13 = t0 - t3;
         float t11 = t1 + t2;
         float t12 = t1 - t2;
         inp[p] = t10 + t11;
         inp[p + 32] = t10 - t11;
         d1 = (t12 + t13) * 0.70710677F;
         t10 = t4 + t5;
         t12 = t6 + t7;
         d5 = (t10 - t12) * 0.38268343F;
         d2 = 0.5411961F * t10 + d5;
         d4 = 1.306563F * t12 + d5;
         d3 = (t5 + t6) * 0.70710677F;
         d6 = t7 + d3;
         d7 = t7 - d3;
         inp[p + 16] = t13 + d1;
         inp[p + 48] = t13 - d1;
         inp[p + 40] = d7 + d2;
         inp[p + 24] = d7 - d2;
         inp[p + 8] = d6 + d4;
         inp[p + 56] = d6 - d4;
      }
   }

   private void writeExif(byte[] exifBytes) throws IOException {
      int srcLen = exifBytes.length;
      int exifHeadlen = 6;
      this.putChar(65505);
      this.putChar(srcLen + 6 + 2);
      this.putByte(69);
      this.putByte(120);
      this.putByte(105);
      this.putByte(102);
      this.putByte(0);
      this.putByte(0);

      for (int i = 0; i < srcLen; i++) {
         this.putByte(exifBytes[i] & 255);
      }
   }

   private void writeAPP0() throws IOException {
      this.putChar(65504);
      this.putChar(16);
      this.putByte(74);
      this.putByte(70);
      this.putByte(73);
      this.putByte(70);
      this.putByte(0);
      this.putByte(1);
      this.putByte(1);
      this.putByte(0);
      this.putChar(1);
      this.putChar(1);
      this.putByte(0);
      this.putByte(0);
   }

   private void writeSOF0(int width, int height, boolean isGray, boolean is211) throws IOException {
      this.putChar(65472);
      if (isGray) {
         this.putChar(11);
         this.putByte(8);
         this.putChar(height);
         this.putChar(width);
         this.putByte(1);
         this.putByte(0);
         this.putByte(17);
         this.putByte(0);
      } else {
         this.putChar(17);
         this.putByte(8);
         this.putChar(height);
         this.putChar(width);
         this.putByte(3);
         if (is211) {
            this.putByte(1);
            this.putByte(34);
            this.putByte(0);
            this.putByte(2);
            this.putByte(17);
            this.putByte(1);
            this.putByte(3);
            this.putByte(17);
            this.putByte(1);
         } else {
            this.putByte(1);
            this.putByte(17);
            this.putByte(0);
            this.putByte(2);
            this.putByte(17);
            this.putByte(1);
            this.putByte(3);
            this.putByte(17);
            this.putByte(1);
         }
      }
   }

   private void writeSOS(boolean isGray) throws IOException {
      this.putChar(65498);
      if (isGray) {
         this.putChar(8);
         this.putByte(1);
         this.putByte(0);
         this.putByte(0);
      } else {
         this.putChar(12);
         this.putByte(3);
         this.putByte(1);
         this.putByte(0);
         this.putByte(2);
         this.putByte(17);
         this.putByte(3);
         this.putByte(17);
      }

      this.putByte(0);
      this.putByte(63);
      this.putByte(0);
   }

   private void writeDQT(boolean isGray) throws IOException {
      this.putChar(65499);
      if (isGray) {
         this.putChar(67);
         this.putByte(0);

         for (int i = 0; i < 64; i++) {
            this.putByte(this.TL[i]);
         }
      } else {
         this.putChar(132);
         this.putByte(0);

         for (int i = 0; i < 64; i++) {
            this.putByte(this.TL[i]);
         }

         this.putByte(1);

         for (int j = 0; j < 64; j++) {
            this.putByte(this.TC[j]);
         }
      }
   }

   private void writeDHT(boolean isGray) throws IOException {
      this.putChar(65476);
      if (isGray) {
         this.putChar(210);
         this.putByte(0);

         for (int i = 0; i < 16; i++) {
            this.putByte(CODESDCL[i + 1]);
         }

         for (int var4 = 0; var4 < 12; var4++) {
            this.putByte(SYMBOLSDCL[var4]);
         }

         this.putByte(16);

         for (int var5 = 0; var5 < 16; var5++) {
            this.putByte(CODESACL[var5 + 1]);
         }

         for (int var6 = 0; var6 <= 161; var6++) {
            this.putByte(SYMBOLSACL[var6]);
         }
      } else {
         this.putChar(418);
         this.putByte(0);

         for (int i = 0; i < 16; i++) {
            this.putByte(CODESDCL[i + 1]);
         }

         for (int var8 = 0; var8 <= 11; var8++) {
            this.putByte(SYMBOLSDCL[var8]);
         }

         this.putByte(16);

         for (int var9 = 0; var9 < 16; var9++) {
            this.putByte(CODESACL[var9 + 1]);
         }

         for (int var10 = 0; var10 <= 161; var10++) {
            this.putByte(SYMBOLSACL[var10]);
         }

         this.putByte(1);

         for (int var11 = 0; var11 < 16; var11++) {
            this.putByte(CODESDCC[var11 + 1]);
         }

         for (int var12 = 0; var12 <= 11; var12++) {
            this.putByte(SYMBOLSDCC[var12]);
         }

         this.putByte(17);

         for (int o = 0; o < 16; o++) {
            this.putByte(CODESACC[o + 1]);
         }

         for (int p = 0; p <= 161; p++) {
            this.putByte(SYMBOLSACC[p]);
         }
      }
   }

   private int compress(float[] CDU, float[] fdtbl, int DC, int[] huffDC, int[] huffAC) throws IOException {
      int EOB = huffAC[0];
      int M16zeroes = huffAC[240];
      fDCT(CDU);

      for (int j = 0; j < 64; j++) {
         float fq = CDU[j] * fdtbl[j];
         this.DU[ZIGZAG[j]] = (int)(fq > 0.0F ? fq + 0.5 : fq - 0.5);
      }

      int Diff = this.DU[0] - DC;
      DC = this.DU[0];
      if (Diff == 0) {
         this.putHuffBits(huffDC[0]);
      } else {
         int pos = 32767 + Diff;
         int ci = this.codes[pos];
         this.putHuffBits(huffDC[ci & 65535]);
         this.putHuffBits(ci);
      }

      int end0pos = 63;

      while (end0pos > 0 && this.DU[end0pos] == 0) {
         end0pos--;
      }

      if (end0pos == 0) {
         this.putHuffBits(EOB);
         return DC;
      } else {
         for (int i = 1; i <= end0pos; i++) {
            int startpos = i;

            while (this.DU[i] == 0 && i <= end0pos) {
               i++;
            }

            int nrzeroes = i - startpos;
            if (nrzeroes > 15) {
               int lng = nrzeroes >> 4;

               for (int nrmarker = 1; nrmarker <= lng; nrmarker++) {
                  this.putHuffBits(M16zeroes);
               }

               nrzeroes &= 15;
            }

            int pos = 32767 + this.DU[i];
            int ci = this.codes[pos];
            this.putHuffBits(huffAC[(nrzeroes << 4) + (ci & 65535)]);
            this.putHuffBits(ci);
         }

         if (end0pos != 63) {
            this.putHuffBits(EOB);
         }

         return DC;
      }
   }

   private void encode(int width, int height, int type, byte[] exifBytes) throws IOException {
      int quality = this.quality;
      quality = quality <= 0 ? 1 : Math.min(quality, 100);
      boolean is211 = quality < 80;
      int qq = quality < 50 ? 5000 / quality : 200 - (quality << 1);
      this.generateQTables(qq);
      generateHuffmanMapping(this.DCHTL, CODESDCL, SYMBOLSDCL);
      generateHuffmanMapping(this.DCHTC, CODESDCC, SYMBOLSDCC);
      generateHuffmanMapping(this.ACHTL, CODESACL, SYMBOLSACL);
      generateHuffmanMapping(this.ACHTC, CODESACC, SYMBOLSACC);
      this.generateHuffmanItems();
      boolean isGray = type == 10;
      this.buffer = 0;
      this.bitLen = 0;
      this.putChar(65496);
      this.writeAPP0();
      if (exifBytes != null) {
         this.writeExif(exifBytes);
      }

      this.writeDQT(isGray);
      this.writeSOF0(width, height, isGray, is211);
      this.writeDHT(isGray);
      this.writeSOS(isGray);
      this.buffer = 0;
      this.bitLen = 0;
      if (isGray) {
         this.compressGray(width, height);
      } else if (is211) {
         this.compressRGB211(width, height);
      } else {
         this.compressRGB(width, height);
      }

      if (this.bitLen > 0) {
         if (this.bitLen > 7) {
            this.bitLen -= 8;
            int c = this.buffer >> this.bitLen & 0xFF;
            this.putByte(c);
            if (c == 255) {
               this.putByte(0);
            }
         }

         if (this.bitLen > 0) {
            int c = (this.buffer & 0xFF) << 8 - this.bitLen;
            this.putByte(c);
         }
      }

      this.putChar(65497);
   }

   private void compressGray(int width, int height) throws IOException {
      int DCY = 0;
      float[] unitY = new float[64];
      int y = 0;
      int maxLen = width * height;

      for (byte[] pixBytes = ((DataBufferByte)this.img.getRaster().getDataBuffer()).getData(); y < height; y += 8) {
         for (int x = 0; x < width; x += 8) {
            int start = width * y + x;

            for (int pos = 0; pos < 64; pos++) {
               int row = pos >> 3;
               int col = pos & 7;
               int p = start + row * width + col;
               if (y + row >= height) {
                  p -= width * (y + 1 + row - height);
               }

               if (x + col >= width) {
                  p -= x + col - width + 4;
               }

               if (p <= maxLen && p >= 0) {
                  int g;
                  int b;
                  int r = g = b = pixBytes[p] & 255;
                  unitY[pos] = (128 + 76 * r + 150 * g + 29 * b >> 8) - 128;
               }
            }

            DCY = this.compress(unitY, this.FDL, DCY, this.DCHTL, this.ACHTL);
         }
      }
   }

   private void compressRGB(int width, int height) throws IOException {
      int DCY = 0;
      int DCU = 0;
      int DCV = 0;
      float[] unitY = new float[64];
      float[] unitU = new float[64];
      float[] unitV = new float[64];
      int y = 0;

      for (int maxLen = width * height; y < height; y += 8) {
         for (int x = 0; x < width; x += 8) {
            int start = width * y + x;

            for (int pos = 0; pos < 64; pos++) {
               int row = pos >> 3;
               int col = pos & 7;
               int p = start + row * width + col;
               if (y + row >= height) {
                  p -= width * (y + 1 + row - height);
               }

               if (x + col >= width) {
                  p -= x + col - width + 4;
               }

               if (p <= maxLen && p >= 0) {
                  int xx = p % width;
                  int yy = p / width;
                  updateYUV(this.pg, xx, yy, unitY, unitU, unitV, pos);
               }
            }

            DCY = this.compress(unitY, this.FDL, DCY, this.DCHTL, this.ACHTL);
            DCU = this.compress(unitU, this.FDC, DCU, this.DCHTC, this.ACHTC);
            DCV = this.compress(unitV, this.FDC, DCV, this.DCHTC, this.ACHTC);
         }
      }
   }

   private static void updateYUV(PixGet pg, int x, int y, float[] unitY, float[] unitU, float[] unitV, int pos) {
      int v = pg.getRGB(x, y);
      int r = v >> 16 & 0xFF;
      int g = v >> 8 & 0xFF;
      int b = v & 0xFF;
      unitY[pos] = (128 + 76 * r + 150 * g + 29 * b >> 8) - 128;
      unitU[pos] = 128 + 127 * b - 84 * g - 43 * r >> 8;
      unitV[pos] = 128 + 127 * r - 106 * g - 21 * b >> 8;
   }

   private void compressRGB211(int iw, int ih) throws IOException {
      int[] blockHeight = new int[3];
      int[] blockWidth = new int[3];
      int[] compHeight = new int[3];
      int[] compWidth = new int[3];
      int[] sampleFactor = new int[]{2, 1, 1};
      int[] iterFactor = new int[]{1, 2, 2};
      int maxH = sampleFactor[0];
      int maxV = sampleFactor[0];

      for (int i = 0; i < 3; i++) {
         compWidth[i] = (iw % 8 != 0 ? (int)Math.ceil(iw / 8.0) * 8 : iw) / maxH * sampleFactor[i];
         compHeight[i] = (ih % 8 != 0 ? (int)Math.ceil(ih / 8.0) * 8 : ih) / maxV * sampleFactor[i];
         blockWidth[i] = (int)Math.ceil(compWidth[i] / 8.0);
         blockHeight[i] = (int)Math.ceil(compHeight[i] / 8.0);
      }

      int minBlockWidth = iw % 8 != 0 ? (int)(Math.floor(iw / 8.0) + 1.0) * 8 : iw;
      int minBlockHeight = ih % 8 != 0 ? (int)(Math.floor(ih / 8.0) + 1.0) * 8 : ih;

      for (int comp = 0; comp < 3; comp++) {
         minBlockWidth = Math.min(minBlockWidth, blockWidth[comp]);
         minBlockHeight = Math.min(minBlockHeight, blockHeight[comp]);
      }

      float[] unitY = new float[64];
      float[] unitU = new float[64];
      float[] unitV = new float[64];
      int[] DCTBL = new int[3];

      for (int r = 0; r < minBlockHeight; r++) {
         for (int c = 0; c < minBlockWidth; c++) {
            this.updateY(iw, ih, c, r, sampleFactor[0], iterFactor[0], unitY, DCTBL);
            this.updateU(iw, ih, c, r, sampleFactor[1], iterFactor[1], unitU, DCTBL);
            this.updateV(iw, ih, c, r, sampleFactor[2], iterFactor[2], unitV, DCTBL);
         }
      }
   }

   private void updateY(int iw, int ih, int c, int r, int hv, int iter, float[] unitY, int[] DCTBL) throws IOException {
      int xpos = c * 8 * hv * iter;
      int ypos = r * 8 * hv * iter;
      int maxLen = iw * ih;

      for (int i = 0; i < hv; i++) {
         for (int j = 0; j < hv; j++) {
            int xx = xpos + (j << 3);
            int yy = ypos + (i << 3);
            int start = iw * yy + xx;

            for (int pos = 0; pos < 64; pos++) {
               int row = (pos >> 3) * iter;
               int col = (pos & 7) * iter;
               int p = start + row * iw + col;
               if (yy + row >= ih) {
                  p -= iw * (yy + 1 + row - ih);
               }

               if (xx + col >= iw) {
                  p -= xx + col - iw + 4;
               }

               if (p <= maxLen && p >= 0) {
                  int x = p % iw;
                  int y = p / iw;
                  int v = this.pg.getRGB(x, y);
                  int rr = v >> 16 & 0xFF;
                  int gg = v >> 8 & 0xFF;
                  int bb = v & 0xFF;
                  unitY[pos] = (128 + 76 * rr + 150 * gg + 29 * bb >> 8) - 128;
               }
            }

            DCTBL[0] = this.compress(unitY, this.FDL, DCTBL[0], this.DCHTL, this.ACHTL);
         }
      }
   }

   private void updateU(int iw, int ih, int c, int r, int hv, int iter, float[] unitU, int[] DCTBL) throws IOException {
      int xpos = c * 8 * hv * iter;
      int ypos = r * 8 * hv * iter;
      int maxLen = iw * ih;

      for (int i = 0; i < hv; i++) {
         for (int j = 0; j < hv; j++) {
            int xx = xpos + (j << 3);
            int yy = ypos + (i << 3);
            int start = iw * yy + xx;

            for (int pos = 0; pos < 64; pos++) {
               int row = (pos >> 3) * iter;
               int col = (pos & 7) * iter;
               int p = start + row * iw + col;
               if (yy + row >= ih) {
                  p -= iw * (yy + 1 + row - ih);
               }

               if (xx + col >= iw) {
                  p -= xx + col - iw + 4;
               }

               if (p <= maxLen && p >= 0) {
                  int x = p % iw;
                  int y = p / iw;
                  int v = this.pg.getRGB(x, y);
                  int rr = v >> 16 & 0xFF;
                  int gg = v >> 8 & 0xFF;
                  int bb = v & 0xFF;
                  unitU[pos] = 128 + 127 * bb - 84 * gg - 43 * rr >> 8;
               }
            }

            DCTBL[1] = this.compress(unitU, this.FDC, DCTBL[1], this.DCHTC, this.ACHTC);
         }
      }
   }

   private void updateV(int iw, int ih, int c, int r, int hv, int iter, float[] unitV, int[] DCTBL) throws IOException {
      int xpos = c * 8 * hv * iter;
      int ypos = r * 8 * hv * iter;
      int maxLen = iw * ih;

      for (int i = 0; i < hv; i++) {
         for (int j = 0; j < hv; j++) {
            int xx = xpos + (j << 3);
            int yy = ypos + (i << 3);
            int start = iw * yy + xx;

            for (int pos = 0; pos < 64; pos++) {
               int row = (pos >> 3) * iter;
               int col = (pos & 7) * iter;
               int p = start + row * iw + col;
               if (yy + row >= ih) {
                  p -= iw * (yy + 1 + row - ih);
               }

               if (xx + col >= iw) {
                  p -= xx + col - iw + 4;
               }

               if (p <= maxLen && p >= 0) {
                  int x = p % iw;
                  int y = p / iw;
                  int v = this.pg.getRGB(x, y);
                  int rr = v >> 16 & 0xFF;
                  int gg = v >> 8 & 0xFF;
                  int bb = v & 0xFF;
                  unitV[pos] = 128 + 127 * rr - 106 * gg - 21 * bb >> 8;
               }
            }

            DCTBL[2] = this.compress(unitV, this.FDC, DCTBL[2], this.DCHTC, this.ACHTC);
         }
      }
   }
}
