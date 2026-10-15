package rip.ysm.imagestream.webp.enc;

import java.util.EnumSet;

class YV12buffer {
   static final int VP8BORDERINPIXELS = 32;
   public int y_width;
   public int y_height;
   public int y_crop_width;
   public int y_crop_height;
   public int y_stride;
   public int uv_width;
   public int uv_height;
   public int uv_crop_width;
   public int uv_crop_height;
   public int uv_stride;
   public FullGetSetPointer y_buffer;
   public FullGetSetPointer u_buffer;
   public FullGetSetPointer v_buffer;
   public FullGetSetPointer buffer_alloc;
   public int border;
   int frame_size;
   int subsampling_x;
   int subsampling_y;
   int bit_depth;
   int render_width;
   int render_height;
   int corrupted;
   public EnumSet<MVReferenceFrame> flags;

   private YV12buffer() {
   }

   YV12buffer shallowCopy() {
      YV12buffer sh = new YV12buffer();
      sh.y_width = this.y_width;
      sh.y_height = this.y_height;
      sh.y_crop_width = this.y_crop_width;
      sh.y_crop_height = this.y_crop_height;
      sh.y_stride = this.y_stride;
      sh.uv_width = this.uv_width;
      sh.uv_height = this.uv_height;
      sh.uv_crop_width = this.uv_crop_width;
      sh.uv_crop_height = this.uv_crop_height;
      sh.uv_stride = this.uv_stride;
      sh.y_buffer = this.y_buffer.shallowCopy();
      sh.u_buffer = this.u_buffer.shallowCopy();
      sh.v_buffer = this.v_buffer.shallowCopy();
      sh.buffer_alloc = this.buffer_alloc.shallowCopy();
      sh.border = this.border;
      sh.frame_size = this.frame_size;
      sh.subsampling_x = this.subsampling_x;
      sh.subsampling_y = this.subsampling_y;
      sh.bit_depth = this.bit_depth;
      sh.render_width = this.render_width;
      sh.render_height = this.render_height;
      sh.corrupted = this.corrupted;
      sh.flags = this.flags == null ? EnumSet.noneOf(MVReferenceFrame.class) : EnumSet.copyOf(this.flags);
      return sh;
   }

   private static void extend_plane(
      FullGetSetPointer src, int src_stride, int width, int height, int extend_top, int extend_left, int extend_bottom, int extend_right
   ) {
      for (int i = 0; i < height; i++) {
         int strideshift = i * src_stride;
         src.memset(strideshift - extend_left, src.getRel(strideshift), extend_left);
         src.memset(strideshift + width, src.getRel(strideshift + width - 1), extend_right);
      }

      int linesize = extend_left + extend_right + width;

      for (int i = 0; i < extend_top; i++) {
         src.memcopyin(src_stride * (i - extend_top) - extend_left, src, -extend_left, linesize);
      }

      int bottomrowstart = src_stride * (height - 1) - extend_left;

      for (int i = 0; i < extend_bottom; i++) {
         src.memcopyin(src_stride * (i + height) - extend_left, src, bottomrowstart, linesize);
      }
   }

   public void extend_frame_borders() {
      int uv_border = this.border / 2;
      extend_plane(
         this.y_buffer,
         this.y_stride,
         this.y_crop_width,
         this.y_crop_height,
         this.border,
         this.border,
         this.border + this.y_height - this.y_crop_height,
         this.border + this.y_width - this.y_crop_width
      );
      extend_plane(
         this.u_buffer,
         this.uv_stride,
         this.uv_crop_width,
         this.uv_crop_height,
         uv_border,
         uv_border,
         uv_border + this.uv_height - this.uv_crop_height,
         uv_border + this.uv_width - this.uv_crop_width
      );
      extend_plane(
         this.v_buffer,
         this.uv_stride,
         this.uv_crop_width,
         this.uv_crop_height,
         uv_border,
         uv_border,
         uv_border + this.uv_height - this.uv_crop_height,
         uv_border + this.uv_width - this.uv_crop_width
      );
   }

   private static void leftcolhelper(FullGetSetPointer target, int st, int h) {
      for (int i = 0; i < h; i++) {
         target.setRel(st * i - 1, (short)129);
      }
   }

   private static void toplinehelper(FullGetSetPointer target, int stride, int width) {
      target.memset(-1 - stride, (short)127, width + 5);
   }

   private void vp8_setup_intra_recon_left_col() {
      leftcolhelper(this.y_buffer, this.y_stride, this.y_height);
      leftcolhelper(this.u_buffer, this.uv_stride, this.uv_height);
      leftcolhelper(this.v_buffer, this.uv_stride, this.uv_height);
   }

   private void vp8_setup_intra_recon_top_line() {
      toplinehelper(this.y_buffer, this.y_stride, this.y_width);
      toplinehelper(this.u_buffer, this.uv_stride, this.uv_width);
      toplinehelper(this.v_buffer, this.uv_stride, this.uv_width);
   }

   public void vp8_setup_intra_recon() {
      this.vp8_setup_intra_recon_top_line();
      this.vp8_setup_intra_recon_left_col();
   }

   private static void copyBufPart(FullGetSetPointer src_ptr, FullGetSetPointer dst_ptr, int width, int height, int src_stride, int dst_stride) {
      CUtils.genericCopy(src_ptr, src_stride, dst_ptr, dst_stride, height, width);
   }

   static void copyY(YV12buffer src_ybc, YV12buffer dst_ybc) {
      copyBufPart(src_ybc.y_buffer, dst_ybc.y_buffer, src_ybc.y_width, src_ybc.y_height, src_ybc.y_stride, dst_ybc.y_stride);
   }

   public YV12buffer(Picture img) {
      byte[][] pic_raw_data = img.getData();
      FullGetSetPointer img_data = new FullGetSetPointer(pic_raw_data[0].length + pic_raw_data[1].length + pic_raw_data[2].length);

      for (byte[] planeData : pic_raw_data) {
         for (byte pix : planeData) {
            img_data.setAndInc((short)(pix + 128));
         }
      }

      img_data.rewind();
      int y_w = img.getWidth();
      int y_h = img.getHeight();
      int uv_w = img.getPlaneWidth(1);
      int uv_h = img.getPlaneHeight(1);
      this.y_buffer = img_data;
      this.u_buffer = img_data.shallowCopyWithPosInc(pic_raw_data[0].length);
      this.v_buffer = this.u_buffer.shallowCopyWithPosInc(pic_raw_data[1].length);
      this.y_crop_width = y_w;
      this.y_crop_height = y_h;
      this.y_width = y_w;
      this.y_height = y_h;
      this.uv_crop_width = uv_w;
      this.uv_crop_height = uv_h;
      this.uv_width = uv_w;
      this.uv_height = uv_h;
      this.y_stride = img.getWidth();
      this.uv_stride = img.getPlaneWidth(1);
      this.border = 0;
   }

   public YV12buffer(int width, int height) {
      this(width, height, 32);
   }

   public YV12buffer(int width, int height, int border) {
      this.y_width = width + 15 & -16;
      this.y_height = height + 15 & -16;
      this.y_stride = this.y_width + 2 * border + 31 & -32;
      this.y_crop_width = width;
      this.y_crop_height = height;
      this.uv_width = this.y_width >> 1;
      this.uv_height = this.y_height >> 1;
      this.uv_stride = this.y_stride >> 1;
      this.uv_crop_width = (width + 1) / 2;
      this.uv_crop_height = (height + 1) / 2;
      this.border = border;
      int yplane_size = (this.y_height + 2 * border) * this.y_stride;
      int uvplane_size = (this.uv_height + border) * this.uv_stride;
      this.frame_size = yplane_size + 2 * uvplane_size;
      this.buffer_alloc = new FullGetSetPointer(this.frame_size);
      this.y_buffer = this.buffer_alloc.shallowCopyWithPosInc(border * this.y_stride + border);
      this.u_buffer = this.buffer_alloc.shallowCopyWithPosInc(yplane_size + border / 2 * this.uv_stride + border / 2);
      this.v_buffer = this.buffer_alloc.shallowCopyWithPosInc(yplane_size + uvplane_size + border / 2 * this.uv_stride + border / 2);
      this.corrupted = 0;
   }
}
