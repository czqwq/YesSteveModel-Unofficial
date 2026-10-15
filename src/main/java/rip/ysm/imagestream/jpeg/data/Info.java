package rip.ysm.imagestream.jpeg.data;

public class Info {
   public int width;
   public int height;
   public JFIFHolder jfif;
   public AdobeHolder adobe;
   public int nComp;
   public int maxV;
   public int maxH;
   public int maxLineX;
   public Frame frame;
   public boolean isLossless;
   public int orientation = 1;
   public boolean useBytes;
   public boolean isProgressive;
}
