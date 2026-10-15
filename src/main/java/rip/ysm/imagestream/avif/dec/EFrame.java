package rip.ysm.imagestream.avif.dec;

class EFrame {
   Plane[] planes = new Plane[3];

   EFrame(int y_crop_height, int y_crop_width) {
      int y_width = E.roundUp8(y_crop_width);
      int y_height = E.roundUp8(y_crop_height);
      int uv_crop_width = E.round2(y_crop_width, 1);
      int uv_crop_height = E.round2(y_crop_height, 1);
      int uv_width = y_width / 2;
      int uv_height = y_height / 2;
      this.planes[0] = new Plane(E.Array2dInt.zeroed(y_height, y_width), y_crop_width, y_crop_height);
      this.planes[1] = new Plane(E.Array2dInt.zeroed(uv_height, uv_width), uv_crop_width, uv_crop_height);
      this.planes[2] = new Plane(E.Array2dInt.zeroed(uv_height, uv_width), uv_crop_width, uv_crop_height);
   }

   Plane plane(int idx) {
      return this.planes[idx];
   }

   Plane y() {
      return this.planes[0];
   }

   Plane u() {
      return this.planes[1];
   }

   Plane v() {
      return this.planes[2];
   }

   static class Plane {
      final E.Array2dInt pixels;
      final int crop_width;
      final int crop_height;

      Plane(E.Array2dInt pixels, int crop_width, int crop_height) {
         this.pixels = pixels;
         this.crop_width = crop_width;
         this.crop_height = crop_height;
      }

      int width() {
         return this.pixels.cols;
      }

      int height() {
         return this.pixels.rows;
      }

      void fill_padding() {
         int width = this.width();
         int height = this.height();

         for (int row = 0; row < height; row++) {
            int rightmost_pixel = this.pixels.data[row][this.crop_width - 1];

            for (int i = this.crop_width; i < width; i++) {
               this.pixels.data[row][i] = rightmost_pixel;
            }
         }

         for (int row = this.crop_height; row < height; row++) {
            for (int col = 0; col < width; col++) {
               this.pixels.data[row][col] = this.pixels.data[this.crop_height - 1][col];
            }
         }
      }
   }
}
