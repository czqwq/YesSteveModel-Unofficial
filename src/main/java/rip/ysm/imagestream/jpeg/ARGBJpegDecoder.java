package rip.ysm.imagestream.jpeg;

import rip.ysm.imagestream.jpeg.data.Info;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferByte;

public final class ARGBJpegDecoder {
   private ARGBJpegDecoder() {
   }

   public static BufferedImage read(byte[] data) throws Exception {
      JpegDecoder dec = new JpegDecoder();
      Info info = new Info();
      byte[] input = dec.readAsUnconvertedBytes(data, 0, info);
      if (info.nComp == 4 && info.adobe == null) {
         BufferedImage image = new BufferedImage(info.width, info.height, 5);
         byte[] pixels = ((DataBufferByte)image.getRaster().getDataBuffer()).getData();
         int p = 0;
         int ii = input.length;

         for (int i = 0; i < ii; i += 4) {
            int y = ((input[i] & 255) << 8) + 128;
            int u = (input[i + 1] & 255) - 128;
            int v = (input[i + 2] & 255) - 128;
            int r = y + 359 * v >> 8;
            int g = y - 88 * u - 183 * v >> 8;
            int b = y + 454 * u >> 8;
            pixels[p++] = b < 0 ? 0 : (b > 255 ? -1 : (byte)b);
            pixels[p++] = g < 0 ? 0 : (g > 255 ? -1 : (byte)g);
            pixels[p++] = r < 0 ? 0 : (r > 255 ? -1 : (byte)r);
         }

         return image;
      } else {
         JpegDecoder dec2 = new JpegDecoder();
         return dec2.read(data);
      }
   }
}
