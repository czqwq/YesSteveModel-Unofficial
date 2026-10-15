package rip.ysm.imagestream.webp.enc;

import rip.ysm.imagestream.utility.Access;
import rip.ysm.imagestream.utility.PixGet;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.OutputStream;
import java.util.EnumSet;
import java.util.Iterator;

public final class VP8Encoder {
   static final short INT_TO_BYTE_OFFSET = 128;
   final CodecEncCfg cfg = new CodecEncCfg();
   final ExtraCFG vp8Cfg = new ExtraCFG();
   int pts;
   CodecPkt.FramePacket encodedPacket;

   public static void encodeWEBP(BufferedImage bImg, OutputStream out, int qp, byte[] exifBytes) throws IOException {
      Picture pic = getPictureFromBuffer(bImg);
      VP8Encoder enc = new VP8Encoder((short)qp);
      enc.encodeFrame(pic);
      int frameSize = enc.encodedPacket.sz;
      int frameAdd = frameSize % 2 != 0 ? frameSize + 1 : frameSize;
      int dataLen = 20 + frameAdd;
      if (exifBytes != null) {
         dataLen += exifBytes.length;
      }

      out.write("RIFF".getBytes());
      out.write(toLittleEndianBytes(dataLen - 8));
      out.write("WEBP".getBytes());
      out.write("VP8 ".getBytes());
      out.write(toLittleEndianBytes(frameAdd));
      FullGetSetPointer buf = enc.encodedPacket.buf;

      for (int i = 0; i < enc.encodedPacket.sz; i++) {
         out.write(buf.getRel(i));
      }

      if (frameSize % 2 != 0) {
         out.write(0);
      }

      if (exifBytes != null) {
         out.write("EXIF".getBytes());
         out.write(toLittleEndianBytes(exifBytes.length));
         out.write(exifBytes);
      }
   }

   private static byte[] toLittleEndianBytes(int v) {
      return new byte[]{(byte)(v & 0xFF), (byte)(v >> 8 & 0xFF), (byte)(v >> 16 & 0xFF), (byte)(v >> 24 & 0xFF)};
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
      byte[] yData = data[0];
      byte[] uData = data[1];
      byte[] vData = data[2];

      for (int y = 0; y < h; y++) {
         for (int x = 0; x < w; x++) {
            int p = pg.getRGB(x, y);
            int r = p >> 16 & 0xFF;
            int g = p >> 8 & 0xFF;
            int b = p & 0xFF;
            yData[y * w + x] = (byte)((128 + 66 * r + 129 * g + 25 * b >> 8) - 112);
         }
      }

      for (int var22 = 0; var22 < h; var22 += 2) {
         for (int x = 0; x < w; x += 2) {
            int p = pg.getRGB(x, var22);
            int r = p >> 16 & 0xFF;
            int g = p >> 8 & 0xFF;
            int b = p & 0xFF;
            p = var22 / 2 * strideC + x / 2;
            uData[p] = (byte)(128 - 38 * r - 74 * g + 112 * b >> 8);
            vData[p] = (byte)(128 + 112 * r - 94 * g - 18 * b >> 8);
         }
      }

      return new Picture(img.getWidth(), img.getHeight(), data);
   }

   private VP8Encoder(short qp) {
      if (qp >= 0) {
         this.cfg.setRc_max_quantizer(qp);
      }
   }

   private void encodeFrame(Picture pic) {
      this.cfg.setG_w(pic.getWidth());
      this.cfg.setG_h(pic.getHeight());
      CodecAlgPRiv ctx = new CodecAlgPRiv(this.cfg, this.vp8Cfg);
      CXInterface.vp8e_encode(ctx, pic, this.pts++, EnumSet.noneOf(AlgoFlags.class));
      CodecPkt ret = CodecAlgPRiv.vpx_codec_get_cx_data(ctx.base, new Iterator[1]);
      this.encodedPacket = (CodecPkt.FramePacket)ret.packet;
   }
}
