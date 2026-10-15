package rip.ysm.imagestream.avif.dec;

import java.awt.image.BufferedImage;

class Yuv {
   private double cooffCrToR;
   private double cooffCrToG;
   private double cooffCbToG;
   private double cooffCbToB;
   int bitdepth_ = 8;
   boolean is_monochrome_ = false;
   int y_width_ = 0;
   int uv_width_ = 0;
   int y_height_ = 0;
   int uv_height_ = 0;
   final int[] left_border_ = new int[3];
   final int[] right_border_ = new int[3];
   final int[] top_border_ = new int[3];
   final int[] bottom_border_ = new int[3];
   final int[] stride_ = new int[3];
   final int[][] buffer_ = new int[3][];
   int subsampling_x_ = 0;
   int subsampling_y_ = 0;

   int stride(int plane) {
      return this.stride_[plane];
   }

   int[] data(int plane) {
      return this.buffer_[plane];
   }

   int height(int plane) {
      return plane == 0 ? this.y_height_ : this.uv_height_;
   }

   int alignment() {
      return 16;
   }

   int leftBorder(int plane) {
      return this.left_border_[plane];
   }

   int rightBorder(int plane) {
      return this.right_border_[plane];
   }

   int topBorder(int plane) {
      return this.top_border_[plane];
   }

   int bottomBorder(int plane) {
      return this.bottom_border_[plane];
   }

   boolean realloc(
      int bitDepth,
      boolean isMonochrome,
      int width,
      int height,
      int subsamplingX,
      int subsamplingY,
      int leftBorder,
      int rightBorder,
      int topBorder,
      int bottomBorder
   ) {
      int plane_align = 16;
      int uv_width = isMonochrome ? 0 : D.SubsampledValue(width, subsamplingX);
      int uv_height = isMonochrome ? 0 : D.SubsampledValue(height, subsamplingY);
      int uv_left_border = isMonochrome ? 0 : leftBorder >> subsamplingX;
      int uv_right_border = isMonochrome ? 0 : rightBorder >> subsamplingX;
      int uv_top_border = isMonochrome ? 0 : topBorder >> subsamplingY;
      int uv_bottom_border = isMonochrome ? 0 : bottomBorder >> subsamplingY;
      int y_stride = width + leftBorder + rightBorder;
      y_stride = D.Align(y_stride, 16);
      int y_plane_size = (height + topBorder + bottomBorder) * y_stride + 15;
      int uv_stride = uv_width + uv_left_border + uv_right_border;
      uv_stride = D.Align(uv_stride, 16);
      int uv_plane_size = isMonochrome ? 0 : (uv_height + uv_top_border + uv_bottom_border) * uv_stride + 15;
      this.stride_[0] = y_stride;
      this.stride_[1] = this.stride_[2] = uv_stride;
      this.buffer_[0] = new int[y_plane_size + topBorder * y_stride + leftBorder];
      this.buffer_[1] = new int[uv_plane_size + uv_top_border * uv_stride + uv_left_border];
      this.buffer_[2] = new int[uv_plane_size + uv_top_border * uv_stride + uv_left_border];
      this.y_width_ = width;
      this.y_height_ = height;
      this.left_border_[0] = leftBorder;
      this.right_border_[0] = rightBorder;
      this.top_border_[0] = topBorder;
      this.bottom_border_[0] = bottomBorder;
      this.uv_width_ = uv_width;
      this.uv_height_ = uv_height;
      this.left_border_[1] = this.left_border_[2] = uv_left_border;
      this.right_border_[1] = this.right_border_[2] = uv_right_border;
      this.top_border_[1] = this.top_border_[2] = uv_top_border;
      this.bottom_border_[1] = this.bottom_border_[2] = uv_bottom_border;
      this.subsampling_x_ = subsamplingX;
      this.subsampling_y_ = subsamplingY;
      this.bitdepth_ = bitDepth;
      this.is_monochrome_ = isMonochrome;
      return true;
   }

   static void doFastBT601(BufferedImage image, D.ObuSequenceHeader sequence_header, D.RefCountedBuffer current_frame) {
      int[] dataY = current_frame.buffer().data(0);
      int[] dataU = current_frame.buffer().data(1);
      int[] dataV = current_frame.buffer().data(2);
      int stride0 = current_frame.buffer().stride(0);
      int stride1 = current_frame.buffer().stride(1);
      int stride2 = current_frame.buffer().stride(2);
      int profile = sequence_header.profile;
      int p = 0;
      int c = 0;
      if (profile == 0) {
         for (int h = 0; h < image.getHeight(); h++) {
            int cStart = (h >> 1) * stride1;
            int indexY = h * stride0;

            for (int w = 0; w < image.getWidth(); w++) {
               c = cStart + (w >> 1);
               int y = ((dataY[indexY++] & 0xFF) << 8) + 128;
               int u = (dataU[c] & 0xFF) - 128;
               int v = (dataV[c] & 0xFF) - 128;
               int r = y + 359 * v >> 8;
               int g = y - 88 * u - 183 * v >> 8;
               int b = y + 454 * u >> 8;
               r = Math.max(0, Math.min(255, r));
               g = Math.max(0, Math.min(255, g));
               b = Math.max(0, Math.min(255, b));
               int val = r << 16 | g << 8 | b;
               image.setRGB(w, h, val);
            }
         }
      } else if (profile == 1) {
         if (sequence_header.color_config.color_primary == 1 && sequence_header.color_config.transfer_characteristics == 13) {
            for (int h = 0; h < image.getHeight(); h++) {
               int index = h * stride0;

               for (int w = 0; w < image.getWidth(); w++) {
                  int g = dataY[index] & 0xFF;
                  int b = dataU[index] & 0xFF;
                  int r = dataV[index] & 0xFF;
                  int val = r << 16 | g << 8 | b;
                  index++;
                  image.setRGB(w, h, val);
               }
            }
         } else {
            for (int h = 0; h < image.getHeight(); h++) {
               int index = h * stride0;

               for (int w = 0; w < image.getWidth(); w++) {
                  int y = ((dataY[index] & 0xFF) << 8) + 128;
                  int u = (dataU[index] & 0xFF) - 128;
                  int v = (dataV[index] & 0xFF) - 128;
                  int r = y + 359 * v >> 8;
                  int g = y - 88 * u - 183 * v >> 8;
                  int b = y + 454 * u >> 8;
                  r = Math.max(0, Math.min(255, r));
                  g = Math.max(0, Math.min(255, g));
                  b = Math.max(0, Math.min(255, b));
                  int val = r << 16 | g << 8 | b;
                  index++;
                  image.setRGB(w, h, val);
               }
            }
         }
      } else {
         for (int h = 0; h < image.getHeight(); h++) {
            int cStart = h * stride1;
            int indexY = h * stride0;

            for (int w = 0; w < image.getWidth(); w++) {
               c = cStart + (w >> 1);
               int y = ((dataY[indexY++] & 0xFF) << 8) + 128;
               int u = (dataU[c] & 0xFF) - 128;
               int v = (dataV[c] & 0xFF) - 128;
               int r = y + 359 * v >> 8;
               int g = y - 88 * u - 183 * v >> 8;
               int b = y + 454 * u >> 8;
               r = Math.max(0, Math.min(255, r));
               g = Math.max(0, Math.min(255, g));
               b = Math.max(0, Math.min(255, b));
               int val = r << 16 | g << 8 | b;
               image.setRGB(w, h, val);
            }
         }
      }
   }

   static void doFullBT601(BufferedImage image, D.ObuSequenceHeader sequence_header, D.RefCountedBuffer current_frame) {
      double a = 0.299;
      double b = 0.587;
      double c = 0.114;
      double d = 1.772;
      double e = 1.402;
      int[] dataY = current_frame.buffer().data(0);
      int[] dataU = current_frame.buffer().data(1);
      int[] dataV = current_frame.buffer().data(2);
      int stride0 = current_frame.buffer().stride(0);
      int stride1 = current_frame.buffer().stride(1);
      int stride2 = current_frame.buffer().stride(1);
      int xx = 0;
      if (dataU.length != 0 && !sequence_header.color_config.is_monochrome) {
         if (stride0 == stride1) {
            for (int h = 0; h < image.getHeight(); h++) {
               int index = h * stride0;

               for (int w = 0; w < image.getWidth(); w++) {
                  int Y = dataY[index] & 0xFF;
                  int Cb = (dataU[index] & 0xFF) - 128;
                  int Cr = (dataV[index] & 0xFF) - 128;
                  int R = (int)(Y + e * Cr);
                  int G = (int)(Y - a * e / b * Cr - c * d / b * Cb);
                  int B = (int)(Y + d * Cb);
                  R = Math.max(0, Math.min(255, R));
                  G = Math.max(0, Math.min(255, G));
                  B = Math.max(0, Math.min(255, B));
                  int val = R << 16 | G << 8 | B;
                  index++;
                  image.setRGB(w, h, val);
               }
            }
         } else {
            int indexUV = 0;
            int l = 0;

            for (int h = 0; h < image.getHeight(); h++) {
               int cStart = (h >> 1) * stride1;
               int indexY = h * stride0;

               for (int w = 0; w < image.getWidth(); w++) {
                  indexUV = cStart + (w >> 1);
                  int Y = dataY[indexY++] & 0xFF;
                  int Cb = (dataU[indexUV] & 0xFF) - 128;
                  int Cr = (dataV[indexUV] & 0xFF) - 128;
                  int R = (int)(Y + e * Cr);
                  int G = (int)(Y - a * e / b * Cr - c * d / b * Cb);
                  int B = (int)(Y + d * Cb);
                  R = Math.max(0, Math.min(255, R));
                  G = Math.max(0, Math.min(255, G));
                  B = Math.max(0, Math.min(255, B));
                  int val = R << 16 | G << 8 | B;
                  image.setRGB(w, h, val);
               }
            }
         }
      }
   }

   static void doFullBT709(BufferedImage image, D.ObuSequenceHeader sequence_header, D.RefCountedBuffer current_frame) {
      double a = 0.2126;
      double b = 0.7152;
      double c = 0.0722;
      double d = 1.8556;
      double e = 1.578;
      int[] dataY = current_frame.yuv_buffer_.data(0);
      int[] dataU = current_frame.yuv_buffer_.data(1);
      int[] dataV = current_frame.yuv_buffer_.data(2);
      int stride0 = current_frame.yuv_buffer_.stride(0);
      int stride1 = current_frame.yuv_buffer_.stride(1);
      int stride2 = current_frame.yuv_buffer_.stride(1);
      int xx = 0;
      if (dataU.length != 0 && !sequence_header.color_config.is_monochrome) {
         if (stride0 == stride1) {
            for (int h = 0; h < image.getHeight(); h++) {
               int index = h * stride0;

               for (int w = 0; w < image.getWidth(); w++) {
                  int Y = dataY[index] & 0xFF;
                  int Cb = (dataU[index] & 0xFF) - 128;
                  int Cr = (dataV[index] & 0xFF) - 128;
                  int R = (int)(Y + e * Cr);
                  int G = (int)(Y - a * e / b * Cr - c * d / b * Cb);
                  int B = (int)(Y + d * Cb);
                  R = Math.max(0, Math.min(255, R));
                  G = Math.max(0, Math.min(255, G));
                  B = Math.max(0, Math.min(255, B));
                  int val = R << 16 | G << 8 | B;
                  index++;
                  image.setRGB(w, h, val);
               }
            }
         } else {
            int indexUV = 0;
            int l = 0;

            for (int h = 0; h < image.getHeight(); h++) {
               int cStart = (h >> 1) * stride1;
               int indexY = h * stride0;

               for (int w = 0; w < image.getWidth(); w++) {
                  indexUV = cStart + (w >> 1);
                  int Y = dataY[indexY++] & 0xFF;
                  int Cb = (dataU[indexUV] & 0xFF) - 128;
                  int Cr = (dataV[indexUV] & 0xFF) - 128;
                  int R = (int)(Y + e * Cr);
                  int G = (int)(Y - a * e / b * Cr - c * d / b * Cb);
                  int B = (int)(Y + d * Cb);
                  R = Math.max(0, Math.min(255, R));
                  G = Math.max(0, Math.min(255, G));
                  B = Math.max(0, Math.min(255, B));
                  int val = R << 16 | G << 8 | B;
                  image.setRGB(w, h, val);
               }
            }
         }
      }
   }

   private void YCrCb2RGB(double Y, double Cb, double Cr, double[] outRef) {
      outRef[0] = Y + this.cooffCrToR * Cr;
      outRef[1] = Y - this.cooffCrToG * Cr - this.cooffCbToG * Cb;
      outRef[2] = Y + this.cooffCbToB * Cb;
   }

   private void defineCoefficients(int matrix_coeffs, int colour_primaries) {
      double kr;
      double kb;
      switch (matrix_coeffs) {
         case 0:
         case 2:
         case 3:
         case 8:
         case 11:
         case 14:
         default:
            kr = 0.299;
            kb = 0.114;
            break;
         case 1:
            kr = 0.2126;
            kb = 0.0722;
            break;
         case 4:
            kr = 0.3;
            kb = 0.11;
            break;
         case 5:
         case 6:
            kr = 0.299;
            kb = 0.114;
            break;
         case 7:
            kr = 0.212;
            kb = 0.087;
            break;
         case 9:
         case 10:
            kr = 0.2627;
            kb = 0.0593;
            break;
         case 12:
         case 13:
            Primary p = Primary.getSpecified(colour_primaries & 0xFF);
            double zR = 1.0 - (p.xR + p.yR);
            double zG = 1.0 - (p.xG + p.yG);
            double zB = 1.0 - (p.xB + p.yB);
            double zW = 1.0 - (p.xW + p.yW);
            double tmpGmB_BmG = p.yG * zB - p.yB * zG;
            double tmpRmG_GmR = p.yR * zG - p.yG * zR;
            double denom = p.yW * (p.xR * tmpGmB_BmG + p.xG * (p.yB * zR - p.yR * zB) + p.xB * tmpRmG_GmR);

            try {
               kr = p.yR * (p.xW * tmpGmB_BmG + p.yW * (p.xB * zG - p.xG * zB) + zW * (p.xG * p.yB - p.xB * p.yG)) / denom;
               kb = p.yB * (p.xW * tmpRmG_GmR + p.yW * (p.xG * zR - p.xR * zG) + zW * (p.xR * p.yG - p.xG * p.yR)) / denom;
            } catch (Exception var23) {
               kr = 0.299;
               kb = 0.114;
            }
      }

      double kg = 1.0 - kr - kb;
      this.cooffCrToR = 2.0 * (1.0 - kr);
      this.cooffCrToG = 2.0 * kr * (1.0 - kr) / kg;
      this.cooffCbToG = 2.0 * kb * (1.0 - kb) / kg;
      this.cooffCbToB = 2.0 * (1.0 - kb);
   }

   void doColorConvert(BufferedImage image, D.ObuSequenceHeader sequenceHeader, D.RefCountedBuffer currentFrame) {
      this.defineCoefficients(sequenceHeader.color_config.matrix_coefficients, sequenceHeader.color_config.color_primary);
      int[] dataY = currentFrame.buffer().data(0);
      int[] dataU = currentFrame.buffer().data(1);
      int[] dataV = currentFrame.buffer().data(2);
      int stride0 = currentFrame.buffer().stride(0);
      int stride1 = currentFrame.buffer().stride(1);
      int stride2 = currentFrame.buffer().stride(1);
      double tvRangeCoeffLuma = 1.1643835616438356;
      double tvRangeCoeffChroma = 1.1383928571428572;
      double BitKoefY = 1.0;
      double BitKoefC = 1.0;
      double[] rgb = new double[3];
      if (stride0 == stride1) {
         for (int h = 0; h < image.getHeight(); h++) {
            int index = h * stride0;

            for (int w = 0; w < image.getWidth(); w++) {
               double Y = dataY[index] & 0xFF;
               double Cb = (dataU[index] & 0xFF) - 128;
               double Cr = (dataV[index] & 0xFF) - 128;
               this.YCrCb2RGB(Y * BitKoefY, Cb * BitKoefC, Cr * BitKoefC, rgb);
               int ir = (int)Math.max(0.0, Math.min(255.0, rgb[0]));
               int ig = (int)Math.max(0.0, Math.min(255.0, rgb[1]));
               int ib = (int)Math.max(0.0, Math.min(255.0, rgb[2]));
               image.setRGB(w, h, ir << 16 | ig << 8 | ib);
               index++;
            }
         }
      } else {
         int indexUV = 0;
         int l = 0;

         for (int h = 0; h < image.getHeight(); h++) {
            int cStart = (h >> 1) * stride1;
            int indexY = h * stride0;

            for (int w = 0; w < image.getWidth(); w++) {
               indexUV = cStart + (w >> 1);
               double Y = dataY[indexY++] & 0xFF;
               double Cb = (dataU[indexUV] & 0xFF) - 128;
               double Cr = (dataV[indexUV] & 0xFF) - 128;
               this.YCrCb2RGB(Y * BitKoefY, Cb * BitKoefC, Cr * BitKoefC, rgb);
               int ir = (int)Math.max(0.0, Math.min(255.0, rgb[0]));
               int ig = (int)Math.max(0.0, Math.min(255.0, rgb[1]));
               int ib = (int)Math.max(0.0, Math.min(255.0, rgb[2]));
               image.setRGB(w, h, ir << 16 | ig << 8 | ib);
            }
         }
      }
   }

   private static class Primary {
      final double xG;
      final double yG;
      final double xB;
      final double yB;
      final double xR;
      final double yR;
      final double xW;
      final double yW;

      Primary(double xG, double yG, double xB, double yB, double xR, double yR, double xW, double yW) {
         this.xG = xG;
         this.yG = yG;
         this.xB = xB;
         this.yB = yB;
         this.xR = xR;
         this.yR = yR;
         this.xW = xW;
         this.yW = yW;
      }

      static Primary getSpecified(int colourPrimary) {
         switch (colourPrimary) {
            case 1:
               return new Primary(0.3, 0.6, 0.15, 0.06, 0.64, 0.33, 0.3127, 0.329);
            case 2:
            case 3:
            case 13:
            case 14:
            case 15:
            case 16:
            case 17:
            case 18:
            case 19:
            case 20:
            case 21:
            default:
               System.out.println("default called");
               return new Primary(1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0);
            case 4:
               return new Primary(0.21, 0.71, 0.14, 0.08, 0.67, 0.33, 0.31, 0.316);
            case 5:
               return new Primary(0.29, 0.6, 0.15, 0.06, 0.64, 0.33, 0.3127, 0.329);
            case 6:
            case 7:
               return new Primary(0.31, 0.595, 0.155, 0.07, 0.63, 0.34, 0.3127, 0.329);
            case 8:
               return new Primary(0.243, 0.692, 0.145, 0.049, 0.681, 0.319, 0.31, 0.316);
            case 9:
               return new Primary(0.17, 0.797, 0.131, 0.046, 0.708, 0.292, 0.3127, 0.329);
            case 10:
               return new Primary(0.0, 1.0, 0.0, 0.0, 1.0, 0.0, 0.333333, 0.33333);
            case 11:
               return new Primary(0.265, 0.69, 0.15, 0.06, 0.68, 0.32, 0.314, 0.351);
            case 12:
               return new Primary(0.265, 0.69, 0.15, 0.06, 0.68, 0.32, 0.3127, 0.329);
            case 22:
               return new Primary(0.295, 0.605, 0.155, 0.077, 0.63, 0.34, 0.3127, 0.329);
         }
      }
   }
}
