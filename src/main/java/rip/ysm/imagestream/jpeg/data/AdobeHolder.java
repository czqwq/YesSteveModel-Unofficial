package rip.ysm.imagestream.jpeg.data;

public class AdobeHolder {
   public int version;
   public int flag0;
   public int flag1;
   public int transformCode;

   @Override
   public String toString() {
      return this.version + " " + this.flag0 + " " + this.flag1 + " " + this.transformCode;
   }
}
