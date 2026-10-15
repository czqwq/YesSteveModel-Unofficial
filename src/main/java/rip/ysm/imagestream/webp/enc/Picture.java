package rip.ysm.imagestream.webp.enc;

class Picture {
   private final int width;
   private final int height;
   private final byte[][] data;

   Picture(int width, int height, byte[][] data) {
      this.width = width;
      this.height = height;
      this.data = data;
   }

   int getWidth() {
      return this.width;
   }

   int getHeight() {
      return this.height;
   }

   byte[][] getData() {
      return this.data;
   }

   int getPlaneWidth(int plane) {
      return this.width >> ColorSpace.compWidth[plane];
   }

   int getPlaneHeight(int plane) {
      return this.height >> ColorSpace.compHeight[plane];
   }
}
