package rip.ysm.imagestream.webp.data;

import rip.ysm.imagestream.utility.Access;
import rip.ysm.imagestream.utility.PixGet;
import rip.ysm.imagestream.utility.WriterByteLittle;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.OutputStream;
import java.util.Arrays;

public final class EVP8 {
   private BitEncoder bitstream;
   private byte[][] leftRow;
   private byte[][] topLine;
   private final int[] tmp = new int[16];

   public static void encode(BufferedImage bImg, OutputStream out, int qp) throws IOException {
      EVP8 enc = new EVP8();
      Picture pic = getPictureFromBuffer(bImg);
      int dim = bImg.getWidth() * bImg.getHeight();
      int len = Math.max(512, dim * 3);
      LBuffer buf = new LBuffer(new byte[len]);
      int frameSize = enc.encodeFrame(pic, buf, qp);
      int frameAdd = frameSize % 2 != 0 ? frameSize + 1 : frameSize;
      byte[] temp = new byte[frameAdd];
      System.arraycopy(buf.data, 0, temp, 0, frameAdd);
      int dataLen = 20 + frameAdd;
      byte[] data = new byte[dataLen];
      WriterByteLittle endian = new WriterByteLittle(data);
      endian.write("RIFF".getBytes());
      endian.putU32(data.length - 8);
      endian.write("WEBP".getBytes());
      endian.write("VP8 ".getBytes());
      endian.putU32(frameAdd);
      endian.write(temp);
      out.write(data);
   }

   private static Picture getPictureFromBuffer(BufferedImage img) {
      int w = img.getWidth();
      int h = img.getHeight();
      int dim = w * h;
      int strideC = w / 2;
      int cw = (w + 1) / 2;
      int ch = (h + 1) / 2;
      byte[][] data = new byte[][]{new byte[dim], new byte[ch * cw], new byte[ch * cw]};
      PixGet pg = Access.getPixGet(img);

      for (int y = 0; y < h; y++) {
         for (int x = 0; x < w; x++) {
            int p = pg.getRGB(x, y);
            int r = p >> 16 & 0xFF;
            int g = p >> 8 & 0xFF;
            int b = p & 0xFF;
            data[0][y * w + x] = (byte)((128 + 66 * r + 129 * g + 25 * b >> 8) - 112);
         }
      }

      for (int var19 = 0; var19 < h; var19 += 2) {
         for (int x = 0; x < w; x += 2) {
            int p = pg.getRGB(x, var19);
            int r = p >> 16 & 0xFF;
            int g = p >> 8 & 0xFF;
            int b = p & 0xFF;
            p = var19 / 2 * strideC + x / 2;
            data[1][p] = (byte)(128 - 38 * r - 74 * g + 112 * b >> 8);
            data[2][p] = (byte)(128 + 112 * r - 94 * g - 18 * b >> 8);
         }
      }

      return new Picture(w, h, data, WebpYUV.YUV420);
   }

   private EVP8() {
   }

   private int encodeFrame(Picture pic, LBuffer out, int qp) {
      int mbWidth = pic.width + 15 >> 4;
      int mbHeight = pic.height + 15 >> 4;
      this.bitstream = new BitEncoder(mbWidth);
      this.leftRow = new byte[][]{new byte[16], new byte[8], new byte[8]};
      this.topLine = new byte[][]{new byte[mbWidth << 4], new byte[mbWidth << 3], new byte[mbWidth << 3]};
      initValue(this.leftRow, (byte)-127);
      initValue(this.topLine, (byte)127);
      Picture outMB = Picture.create();
      writeHeader1(out, pic.width, pic.height);
      int start = out.position();
      EBool boolEnc = new EBool(out);
      writeHeader2(boolEnc, qp);

      for (int mbY = 0; mbY < mbHeight; mbY++) {
         for (int mbX = 0; mbX < mbWidth; mbX++) {
            boolEnc.writeBit(145, 1);
            boolEnc.writeBit(156, 0);
            boolEnc.writeBit(163, 0);
            boolEnc.writeBit(142, 0);
         }
      }

      boolEnc.stop();
      int firstPart = out.position() - start;
      boolEnc = new EBool(out);

      for (int mbY = 0; mbY < mbHeight; mbY++) {
         initValue(this.leftRow, (byte)-127);

         for (int mbX = 0; mbX < mbWidth; mbX++) {
            this.luma(pic, mbX, mbY, boolEnc, qp, outMB);
            this.chroma(pic, mbX, mbY, boolEnc, qp, outMB);
            this.collectPredictors(outMB, mbX);
         }
      }

      boolEnc.stop();
      int length = out.position();
      out.position(0);
      writeHeader(out, firstPart);
      out.position(length);
      return length;
   }

   private static void writeHeader(LBuffer duplicate, int firstPart) {
      int showFrame = 1;
      int header = firstPart << 5 | 16;
      duplicate.put((byte)(header & 0xFF));
      duplicate.put((byte)(header >> 8 & 0xFF));
      duplicate.put((byte)(header >> 16 & 0xFF));
   }

   private static void writeHeader1(LBuffer out, int width, int height) {
      out.put(new byte[]{0, 0, 0});
      out.put((byte)-99);
      out.put((byte)1);
      out.put((byte)42);
      out.putShort((short)width);
      out.putShort((short)height);
   }

   private static void initValue(byte[][] leftRow2, byte val) {
      Arrays.fill(leftRow2[0], val);
      Arrays.fill(leftRow2[1], val);
      Arrays.fill(leftRow2[2], val);
   }

   private static void writeHeader2(EBool boolEnc, int qp) {
      boolEnc.writeBit(128, 0);
      boolEnc.writeBit(128, 0);
      boolEnc.writeBit(128, 0);
      boolEnc.writeBit(128, 0);
      writeInt(boolEnc, 1, 6);
      writeInt(boolEnc, 0, 3);
      boolEnc.writeBit(128, 0);
      writeInt(boolEnc, 0, 2);
      writeInt(boolEnc, qp, 7);
      boolEnc.writeBit(128, 0);
      boolEnc.writeBit(128, 0);
      boolEnc.writeBit(128, 0);
      boolEnc.writeBit(128, 0);
      boolEnc.writeBit(128, 0);
      boolEnc.writeBit(128, 0);
      int[][][][] probFlags = LookUp.tokenProbUpdateFlagProbs;

      for (int[][][] probFlag : probFlags) {
         for (int[][] ints : probFlag) {
            for (int[] anInt : ints) {
               for (int i : anInt) {
                  boolEnc.writeBit(i, 0);
               }
            }
         }
      }

      boolEnc.writeBit(128, 0);
   }

   private static void writeInt(EBool boolEnc, int data, int bits) {
      for (int bit = bits - 1; bit >= 0; bit--) {
         boolEnc.writeBit(128, 1 & data >> bit);
      }
   }

   private void collectPredictors(Picture outMB, int mbX) {
      System.arraycopy(outMB.getPlaneData(0), 240, this.topLine[0], mbX << 4, 16);
      System.arraycopy(outMB.getPlaneData(1), 56, this.topLine[1], mbX << 3, 8);
      System.arraycopy(outMB.getPlaneData(2), 56, this.topLine[2], mbX << 3, 8);
      copyCol(outMB.getPlaneData(0), 15, 16, this.leftRow[0]);
      copyCol(outMB.getPlaneData(1), 7, 8, this.leftRow[1]);
      copyCol(outMB.getPlaneData(2), 7, 8, this.leftRow[2]);
   }

   private static void copyCol(byte[] planeData, int off, int stride, byte[] out) {
      for (int i = 0; i < out.length; i++) {
         out[i] = planeData[off];
         off += stride;
      }
   }

   private void luma(Picture pic, int mbX, int mbY, EBool out, int qp, Picture outMB) {
      int x = mbX << 4;
      int y = mbY << 4;
      int[][] ac = this.transform(pic, x, y);
      int[] dc = extractDC(ac);
      this.writeLumaDC(mbX, out, qp, dc);
      this.writeLumaAC(mbX, out, ac, qp);
      restorePlaneLuma(dc, ac, qp);
      putLuma(outMB.getPlaneData(0), this.lumaDCPred(x, y), ac);
   }

   private void writeLumaAC(int mbX, EBool out, int[][] ac, int qp) {
      for (int i = 0; i < 16; i++) {
         EQuantizer.quantizeY(ac[i], qp);
         this.bitstream.encodeCoeffsDCT15(out, zigzag(ac[i], this.tmp), mbX, i & 3, i >> 2);
      }
   }

   private void writeLumaDC(int mbX, EBool out, int qp, int[] dc) {
      Transform.walsh4x4(dc);
      EQuantizer.quantizeY2(dc, qp);
      this.bitstream.encodeCoeffsWHT(out, zigzag(dc, this.tmp), mbX);
   }

   private void writeChroma(int comp, int mbX, EBool boolEnc, int[][] ac, int qp) {
      for (int i = 0; i < 4; i++) {
         EQuantizer.quantizeUV(ac[i], qp);
         this.bitstream.encodeCoeffsDCTUV(boolEnc, zigzag(ac[i], this.tmp), comp, mbX, i & 1, i >> 1);
      }
   }

   private static int[] zigzag(int[] zz, int[] tmp2) {
      for (int i = 0; i < 16; i++) {
         tmp2[i] = zz[LookUp.ZIGZAGS[i]];
      }

      return tmp2;
   }

   private void chroma(Picture pic, int mbX, int mbY, EBool boolEnc, int qp, Picture outMB) {
      int x = mbX << 3;
      int y = mbY << 3;
      int chromaPred1 = this.chromaPredBlk(1, x, y);
      int chromaPred2 = this.chromaPredBlk(2, x, y);
      int[][] ac1 = transformChroma(pic, 1, x, y, chromaPred1);
      int[][] ac2 = transformChroma(pic, 2, x, y, chromaPred2);
      this.writeChroma(1, mbX, boolEnc, ac1, qp);
      this.writeChroma(2, mbX, boolEnc, ac2, qp);
      restorePlaneChroma(ac1, qp);
      putChroma(outMB.data[1], ac1, chromaPred1);
      restorePlaneChroma(ac2, qp);
      putChroma(outMB.data[2], ac2, chromaPred2);
   }

   private static int[][] transformChroma(Picture pic, int comp, int x, int y, int chromaPred) {
      int[][] ac = new int[4][16];

      for (int blk = 0; blk < ac.length; blk++) {
         int blkOffX = (blk & 1) << 2;
         int blkOffY = blk >> 1 << 2;
         takeSubtract(pic.getPlaneData(comp), pic.getPlaneWidth(comp), pic.getPlaneHeight(comp), x + blkOffX, y + blkOffY, ac[blk], chromaPred);
         Transform.fdct4x4(ac[blk]);
      }

      return ac;
   }

   private static void putChroma(byte[] mb, int[][] ac, int chromaPred) {
      for (int blk = 0; blk < 4; blk++) {
         putBlk(mb, chromaPred, ac[blk], 3, (blk & 1) << 2, blk >> 1 << 2);
      }
   }

   private static byte chromaPredOne(byte[] pix, int x) {
      return (byte)(pix[x] + pix[x + 1] + pix[x + 2] + pix[x + 3] + pix[x + 4] + pix[x + 5] + pix[x + 6] + pix[x + 7] + 4 >> 3);
   }

   private static byte chromaPredTwo(byte[] pix1, byte[] pix2, int x, int y) {
      return (byte)(
         pix1[x]
               + pix1[x + 1]
               + pix1[x + 2]
               + pix1[x + 3]
               + pix1[x + 4]
               + pix1[x + 5]
               + pix1[x + 6]
               + pix1[x + 7]
               + pix2[y]
               + pix2[y + 1]
               + pix2[y + 2]
               + pix2[y + 3]
               + pix2[y + 4]
               + pix2[y + 5]
               + pix2[y + 6]
               + pix2[y + 7]
               + 8
            >> 4
      );
   }

   private byte chromaPredBlk(int comp, int x, int y) {
      int predY = y & 7;
      if (x != 0 && y != 0) {
         return chromaPredTwo(this.leftRow[comp], this.topLine[comp], predY, x);
      } else if (x != 0) {
         return chromaPredOne(this.leftRow[comp], predY);
      } else {
         return y != 0 ? chromaPredOne(this.topLine[comp], x) : 0;
      }
   }

   private static void putLuma(byte[] planeData, int pred, int[][] ac) {
      for (int blk = 0; blk < ac.length; blk++) {
         int blkOffX = (blk & 3) << 2;
         int blkOffY = blk & -4;
         putBlk(planeData, pred, ac[blk], 4, blkOffX, blkOffY);
      }
   }

   private static void putBlk(byte[] planeData, int pred, int[] block, int log2stride, int blkX, int blkY) {
      int stride = 1 << log2stride;
      int[] lastVal = new int[4];

      for (int pos = 0; pos < 4; pos++) {
         lastVal[pos] = block[pos] - 1;
      }

      byte[] vals = new byte[4];
      int line = 0;
      int srcOff = 0;

      for (int dstOff = (blkY << log2stride) + blkX; line < 4; line++) {
         for (int pos = 0; pos < 4; pos++) {
            if (lastVal[pos] != block[srcOff + pos]) {
               lastVal[pos] = block[srcOff + pos];
               vals[pos] = (byte)Util.clip(block[srcOff + pos] + pred);
            }

            planeData[dstOff + pos] = vals[pos];
         }

         srcOff += 4;
         dstOff += stride;
      }
   }

   private static void restorePlaneChroma(int[][] ac, int qp) {
      for (int i = 0; i < 4; i++) {
         EQuantizer.dequantUV(ac[i], qp);
         Transform.idct4x4(ac[i]);
      }
   }

   private static void restorePlaneLuma(int[] dc, int[][] ac, int qp) {
      EQuantizer.dequantY2(dc, qp);
      Transform.iwalsh4x4(dc);

      for (int i = 0; i < 16; i++) {
         EQuantizer.dequantY(ac[i], qp);
         ac[i][0] = dc[i];
         Transform.idct4x4(ac[i]);
      }
   }

   private static int[] extractDC(int[][] ac) {
      int[] dc = new int[ac.length];

      for (int i = 0; i < ac.length; i++) {
         dc[i] = ac[i][0];
      }

      return dc;
   }

   private byte lumaDCPred(int x, int y) {
      if (x == 0 && y == 0) {
         return 0;
      } else if (y == 0) {
         return (byte)(Util.sumByte(this.leftRow[0]) + 8 >> 4);
      } else {
         return x == 0
            ? (byte)(Util.sumByte3(this.topLine[0], 0) + 8 >> 4)
            : (byte)(Util.sumByte(this.leftRow[0]) + Util.sumByte3(this.topLine[0], x) + 16 >> 5);
      }
   }

   private int[][] transform(Picture pic, int x, int y) {
      int dcc = this.lumaDCPred(x, y);
      int[][] ac = new int[16][16];

      for (int i = 0; i < ac.length; i++) {
         int[] coeff = ac[i];
         int blkOffX = (i & 3) << 2;
         int blkOffY = i & -4;
         takeSubtract(pic.getPlaneData(0), pic.getPlaneWidth(0), pic.getPlaneHeight(0), x + blkOffX, y + blkOffY, coeff, dcc);
         Transform.fdct4x4(coeff);
      }

      return ac;
   }

   private static void takeSubtract(byte[] planeData, int planeWidth, int planeHeight, int x, int y, int[] coeff, int dc) {
      if (x + 4 < planeWidth && y + 4 < planeHeight) {
         takeSubtractSafe(planeData, planeWidth, x, y, coeff, dc);
      } else {
         takeSubtractUnsafe(planeData, planeWidth, planeHeight, x, y, coeff, dc);
      }
   }

   private static void takeSubtractSafe(byte[] planeData, int planeWidth, int x, int y, int[] coeff, int dc) {
      int i = 0;
      int srcOff = y * planeWidth + x;

      for (int dstOff = 0; i < 4; dstOff += 4) {
         coeff[dstOff] = planeData[srcOff] - dc;
         coeff[dstOff + 1] = planeData[srcOff + 1] - dc;
         coeff[dstOff + 2] = planeData[srcOff + 2] - dc;
         coeff[dstOff + 3] = planeData[srcOff + 3] - dc;
         i++;
         srcOff += planeWidth;
      }
   }

   private static void takeSubtractUnsafe(byte[] planeData, int planeWidth, int planeHeight, int x, int y, int[] coeff, int dc) {
      int outOff = 0;

      int i;
      for (i = y; i < Math.min(y + 4, planeHeight); i++) {
         int off = i * planeWidth + Math.min(x, planeWidth);

         int j;
         for (j = x; j < Math.min(x + 4, planeWidth); j++) {
            coeff[outOff++] = planeData[off++] - dc;
         }

         off--;

         while (j < x + 4) {
            coeff[outOff++] = planeData[off] - dc;
            j++;
         }
      }

      while (i < y + 4) {
         int off = planeHeight * planeWidth - planeWidth + Math.min(x, planeWidth);

         int j;
         for (j = x; j < Math.min(x + 4, planeWidth); j++) {
            coeff[outOff++] = planeData[off++] - dc;
         }

         off--;

         while (j < x + 4) {
            coeff[outOff++] = planeData[off] - dc;
            j++;
         }

         i++;
      }
   }
}
