package rip.ysm.imagestream.webp.enc;

import java.util.EnumMap;
import java.util.EnumSet;

class CommonData {
   static final int NUM_YV12_BUFFERS = 4;
   final EnumMap<Quant, FullGetSetPointer[]> dequant = new EnumMap<>(Quant.class);
   int Width;
   int Height;
   static final Scaling horiz_scale = Scaling.NORMAL;
   static final Scaling vert_scale = Scaling.NORMAL;
   YV12buffer frame_to_show;
   final YV12buffer[] yv12_fb = new YV12buffer[4];
   final int[] fb_idx_ref_cnt = new int[4];
   final EnumMap<MVReferenceFrame, Integer> frameIdxs = new EnumMap<>(MVReferenceFrame.class);
   int new_fb_idx;
   int frame_type;
   boolean show_frame;
   EnumSet<FrameFlags> frame_flags;
   int MBs;
   int mb_rows;
   int mb_cols;
   int mode_info_stride;
   final boolean mb_no_coeff_skip;
   boolean no_lpf;
   boolean use_bilinear_mc_filter;
   boolean full_pixel;
   short base_qindex;
   final EnumMap<Quant, EnumMap<Comp, Short>> delta_q = new EnumMap<>(Quant.class);
   FullGenArrPointer<ModeInfo> mip;
   FullGenArrPointer<ModeInfo> mi;
   int filter_type;
   final LoopFilterInfoN lf_info;
   short filter_level;
   int last_sharpness_level;
   int sharpness_level;
   boolean refresh_last_frame;
   boolean refresh_golden_frame;
   boolean refresh_alt_ref_frame;
   int copy_buffer_to_gf;
   int copy_buffer_to_arf;
   boolean refresh_entropy_probs;
   final EnumMap<MVReferenceFrame, Boolean> ref_frame_sign_bias = new EnumMap<>(MVReferenceFrame.class);
   FullGenArrPointer<EntropyPlanes> above_context;
   final EntropyPlanes left_context = new EntropyPlanes();
   FrameContext lfc;
   FrameContext fc = new FrameContext();
   int current_video_frame;
   private byte version;
   int multi_token_partition;

   CommonData() {
      this.refresh_golden_frame = false;
      this.refresh_last_frame = true;
      this.refresh_entropy_probs = true;
      this.mb_no_coeff_skip = true;
      this.no_lpf = false;
      this.filter_type = 0;
      this.use_bilinear_mc_filter = false;
      this.full_pixel = false;
      this.multi_token_partition = 0;
      this.copy_buffer_to_gf = 0;
      this.copy_buffer_to_arf = 0;
      this.lf_info = new LoopFilterInfoN(this);

      for (MVReferenceFrame rf : MVReferenceFrame.validFrames) {
         this.ref_frame_sign_bias.put(rf, false);
         this.frameIdxs.put(rf, 0);
      }

      for (Quant q : Quant.values()) {
         FullGetSetPointer[] temp = new FullGetSetPointer[128];

         for (int i = 0; i < temp.length; i++) {
            temp[i] = new FullGetSetPointer(2);
         }

         this.dequant.put(q, temp);
         EnumMap<Comp, Short> tempMap = new EnumMap<>(Comp.class);

         for (Comp c : Comp.values()) {
            tempMap.put(c, (short)0);
         }

         this.delta_q.put(q, tempMap);
      }
   }

   byte getVersion() {
      return this.version;
   }

   void setVersion(byte version) {
      this.version = version;
      this.vp8_setup_version();
   }

   void vp8_setup_version() {
      switch (this.version) {
         case 1:
            this.no_lpf = false;
            this.filter_type = 1;
            this.use_bilinear_mc_filter = true;
            this.full_pixel = false;
            break;
         case 2:
            this.no_lpf = true;
            this.filter_type = 0;
            this.use_bilinear_mc_filter = true;
            this.full_pixel = false;
            break;
         case 3:
            this.no_lpf = true;
            this.filter_type = 1;
            this.use_bilinear_mc_filter = true;
            this.full_pixel = true;
            break;
         default:
            this.no_lpf = false;
            this.filter_type = 0;
            this.use_bilinear_mc_filter = false;
            this.full_pixel = false;
      }
   }

   void vp8_alloc_frame_buffers(int width, int height) {
      this.vp8_de_alloc_frame_buffers();
      if ((width & 15) != 0) {
         width += 16 - (width & 15);
      }

      if ((height & 15) != 0) {
         height += 16 - (height & 15);
      }

      for (int i = 0; i < 4; i++) {
         this.fb_idx_ref_cnt[i] = 0;
         this.yv12_fb[i] = new YV12buffer(width, height);
         this.yv12_fb[i].flags = EnumSet.noneOf(MVReferenceFrame.class);
      }

      this.new_fb_idx = 0;
      this.frameIdxs.clear();
      this.frameIdxs.put(MVReferenceFrame.LAST_FRAME, 1);
      this.frameIdxs.put(MVReferenceFrame.GOLDEN_FRAME, 2);
      this.frameIdxs.put(MVReferenceFrame.ALTREF_FRAME, 3);
      this.fb_idx_ref_cnt[0] = 1;
      this.fb_idx_ref_cnt[1] = 1;
      this.fb_idx_ref_cnt[2] = 1;
      this.fb_idx_ref_cnt[3] = 1;
      this.mb_rows = height >> 4;
      this.mb_cols = width >> 4;
      this.MBs = this.mb_rows * this.mb_cols;
      this.mode_info_stride = this.mb_cols + 1;
      this.mip = new FullGenArrPointer<>((this.mb_cols + 1) * (this.mb_rows + 1));

      for (int var4 = 0; var4 < this.mip.size(); var4++) {
         this.mip.setRel(var4, new ModeInfo());
      }

      this.mi = this.mip.shallowCopyWithPosInc(this.mode_info_stride + 1);
      this.above_context = new FullGenArrPointer<>(this.mb_cols);

      for (int var5 = 0; var5 < this.mb_cols; var5++) {
         this.above_context.setRel(var5, new EntropyPlanes());
      }
   }

   void vp8_de_alloc_frame_buffers() {
      this.above_context = null;
      this.mip = null;
   }

   static enum Comp {
      DC(0),
      AC(1);

      final int baseIndex;

      private Comp(final int base) {
         this.baseIndex = base;
      }
   }

   static enum Quant {
      Y1,
      Y2,
      UV;
   }
}
