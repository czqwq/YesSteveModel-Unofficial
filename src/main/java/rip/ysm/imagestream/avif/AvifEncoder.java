package rip.ysm.imagestream.avif;

import rip.ysm.imagestream.avif.dec.EObu;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.OutputStream;

public class AvifEncoder {
   private int quality = 75;

   public AvifEncoder() {}

   public void setQuality(int quality) { this.quality = quality; }
   public int getQuality() { return quality; }

   public void write(BufferedImage image, OutputStream os) throws IOException {
      EObu.encode(image, os, quality, null);
   }
}
