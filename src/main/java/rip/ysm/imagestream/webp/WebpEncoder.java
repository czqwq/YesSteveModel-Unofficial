package rip.ysm.imagestream.webp;

import rip.ysm.imagestream.utility.ImageUtils;
import rip.ysm.imagestream.utility.Access;
import rip.ysm.imagestream.utility.PixGet;
import rip.ysm.imagestream.webp.data.EVP8;
import rip.ysm.imagestream.webp.data.EVP8L;
import rip.ysm.imagestream.webp.enc.VP8Encoder;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.OutputStream;

public class WebpEncoder {
   private int quality = 75;
   private boolean lossless = false;

   public WebpEncoder() {}

   public void setQuality(int quality) { this.quality = quality; }
   public int getQuality() { return quality; }
   public void setLossless(boolean lossless) { this.lossless = lossless; }
   public boolean isLossless() { return lossless; }

   public void write(BufferedImage inputImage, OutputStream os) throws IOException {
      if (lossless || hasAlpha(inputImage)) {
         EVP8L.encode(inputImage, os);
      } else {
         BufferedImage imageToCompress = ImageUtils.fixSubBufferedImage(inputImage);
         int qp = 100 - quality;
         if (imageToCompress.getHeight() <= 4096 && imageToCompress.getWidth() <= 4096) {
            VP8Encoder.encodeWEBP(imageToCompress, os, qp, null);
         } else {
            EVP8.encode(imageToCompress, os, qp);
         }
      }
   }

   private static boolean hasAlpha(BufferedImage image) {
      int type = image.getType();
      if (type == 2 || type == 6) {
         PixGet pg = Access.getPixGet(image);
         for (int y = 0; y < image.getHeight(); y++) {
            for (int x = 0; x < image.getWidth(); x++) {
               int v = pg.getARGB(x, y) >>> 24;
               if (v != 255) return true;
            }
         }
      }
      return false;
   }
}
