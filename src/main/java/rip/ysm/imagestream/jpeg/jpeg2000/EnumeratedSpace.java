package rip.ysm.imagestream.jpeg.jpeg2000;

public class EnumeratedSpace {
   public static int getRGB(int c, int m, int y, int k) {
      float cyan = c / 255.0f;
      float magenta = m / 255.0f;
      float yellow = y / 255.0f;
      float key = k / 255.0f;
      int r = (int)(255 * (1 - cyan) * (1 - key));
      int g = (int)(255 * (1 - magenta) * (1 - key));
      int b = (int)(255 * (1 - yellow) * (1 - key));
      r = Math.max(0, Math.min(255, r));
      g = Math.max(0, Math.min(255, g));
      b = Math.max(0, Math.min(255, b));
      return (r << 16) | (g << 8) | b;
   }

   public static void convertCMYKToBGR(byte[] cmyk, byte[] bgr) {
      int pixCount = cmyk.length / 4;
      for (int i = 0; i < pixCount; i++) {
         int c = cmyk[i * 4] & 0xFF;
         int m = cmyk[i * 4 + 1] & 0xFF;
         int y = cmyk[i * 4 + 2] & 0xFF;
         int k = cmyk[i * 4 + 3] & 0xFF;
         int rgb = getRGB(c, m, y, k);
         bgr[i * 3] = (byte)(rgb & 0xFF);
         bgr[i * 3 + 1] = (byte)((rgb >> 8) & 0xFF);
         bgr[i * 3 + 2] = (byte)((rgb >> 16) & 0xFF);
      }
   }
}
