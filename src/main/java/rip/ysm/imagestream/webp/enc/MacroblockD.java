package rip.ysm.imagestream.webp.enc;

import java.util.Arrays;

final class MacroblockD {
   static final int USHIFT = 256;
   static final int VSHIFT = 320;
   static final int Y2SHIFT = 384;
   final FullGetSetPointer predictor = new FullGetSetPointer(384);
   final FullGetSetPointer qcoeff = new FullGetSetPointer(400);
   final FullGetSetPointer dqcoeff = new FullGetSetPointer(400);
   final FullGetSetPointer eobs = new FullGetSetPointer(25);
   final FullGetSetPointer dequant_y1 = new FullGetSetPointer(16);
   final FullGetSetPointer dequant_y1_dc = new FullGetSetPointer(16);
   final FullGetSetPointer dequant_y2 = new FullGetSetPointer(16);
   final FullGetSetPointer dequant_uv = new FullGetSetPointer(16);
   final FullGenArrPointer<BlockD> block = new FullGenArrPointer<>(25);
   YV12buffer pre;
   YV12buffer dst;
   FullGenArrPointer<ModeInfo> mode_info_context;
   int mode_info_stride;
   int frame_type;
   boolean up_available;
   boolean left_available;
   FullGenArrPointer<EntropyPlanes> above_context;
   EntropyPlanes left_context;
   int segmentation_enabled;
   boolean update_mb_segmentation_map;
   boolean update_mb_segmentation_data;
   boolean mb_segement_abs_delta;
   final int[] mb_segment_tree_probs = new int[3];
   final short[][] segment_feature_data = new short[MBLvlFeatures.featureCount][4];
   boolean mode_ref_lf_delta_enabled;
   boolean mode_ref_lf_delta_update;
   final byte[] last_ref_lf_deltas = new byte[4];
   final byte[] ref_lf_deltas = new byte[4];
   final byte[] last_mode_lf_deltas = new byte[4];
   final byte[] mode_lf_deltas = new byte[4];
   public SubpixFN subpixel_predict8x8;
   public SubpixFN subpixel_predict16x16;
   final FullGetSetPointer y_buf = new FullGetSetPointer(836);

   private FullGetSetPointer getArbitraryFreshPtr(int shift) {
      return this.predictor.shallowCopyWithPosInc(shift);
   }

   public FullGetSetPointer getFreshUPredPtr() {
      return this.getArbitraryFreshPtr(256);
   }

   public FullGetSetPointer getFreshVPredPtr() {
      return this.getArbitraryFreshPtr(320);
   }

   public MacroblockD(Compressor cpi) {
      this.setup_features(cpi);
      this.vp8_setup_block_dptrs();
   }

   private void vp8_setup_block_dptrs() {
      for (int r = 0; r < 4; r++) {
         for (int c = 0; c < 4; c++) {
            this.block.setRel(r * 4 + c, new BlockD(this.predictor.shallowCopyWithPosInc(r * 4 * 16 + c * 4)));
         }
      }

      for (int var3 = 0; var3 < 2; var3++) {
         for (int c = 0; c < 2; c++) {
            this.block.setRel(16 + var3 * 2 + c, new BlockD(this.getFreshUPredPtr().shallowCopyWithPosInc(var3 * 4 * 8 + c * 4)));
         }
      }

      for (int var4 = 0; var4 < 2; var4++) {
         for (int c = 0; c < 2; c++) {
            this.block.setRel(20 + var4 * 2 + c, new BlockD(this.getFreshVPredPtr().shallowCopyWithPosInc(var4 * 4 * 8 + c * 4)));
         }
      }

      this.block.setRel(24, new BlockD(null));

      for (int var5 = 0; var5 < 25; var5++) {
         this.block.getRel(var5).qcoeff = this.qcoeff.shallowCopyWithPosInc(var5 * 16);
         this.block.getRel(var5).dqcoeff = this.dqcoeff.shallowCopyWithPosInc(var5 * 16);
         this.block.getRel(var5).eob = this.eobs.shallowCopyWithPosInc(var5);
      }
   }

   public void setup_features(Compressor cpi) {
      if (this.segmentation_enabled != 0) {
         this.update_mb_segmentation_map = true;
         this.update_mb_segmentation_data = true;
      } else {
         this.update_mb_segmentation_map = false;
         this.update_mb_segmentation_data = false;
      }

      this.mode_ref_lf_delta_enabled = false;
      this.mode_ref_lf_delta_update = false;
      Arrays.fill(this.ref_lf_deltas, (byte)0);
      Arrays.fill(this.mode_lf_deltas, (byte)0);
      Arrays.fill(this.last_ref_lf_deltas, (byte)0);
      Arrays.fill(this.last_mode_lf_deltas, (byte)0);
      this.set_default_lf_deltas(cpi);
   }

   private void set_default_lf_deltas(Compressor cpi) {
      this.mode_ref_lf_delta_enabled = true;
      this.mode_ref_lf_delta_update = true;
      Arrays.fill(this.ref_lf_deltas, (byte)0);
      Arrays.fill(this.mode_lf_deltas, (byte)0);
      this.ref_lf_deltas[MVReferenceFrame.INTRA_FRAME.ordinal()] = 2;
      this.ref_lf_deltas[MVReferenceFrame.LAST_FRAME.ordinal()] = 0;
      this.ref_lf_deltas[MVReferenceFrame.GOLDEN_FRAME.ordinal()] = -2;
      this.ref_lf_deltas[MVReferenceFrame.ALTREF_FRAME.ordinal()] = -2;
      this.mode_lf_deltas[0] = 4;
      if (cpi.oxcf.Mode == CompressMode.REALTIME) {
         this.mode_lf_deltas[1] = -12;
      } else {
         this.mode_lf_deltas[1] = -2;
      }

      this.mode_lf_deltas[2] = 2;
      this.mode_lf_deltas[3] = 4;
   }

   public void init_encode_frame_mbd_context(Compressor cpi) {
      CommonData cm = cpi.common;
      this.mode_info_context = cm.mi.shallowCopy();
      this.mode_info_stride = cm.mode_info_stride;
      this.frame_type = cm.frame_type;
      this.pre = cm.yv12_fb[cm.frameIdxs.get(MVReferenceFrame.LAST_FRAME)].shallowCopy();
      this.dst = cm.yv12_fb[cm.new_fb_idx].shallowCopy();
      ModeInfo mi = this.mode_info_context.get();
      mi.mbmi.mode = MBPredictionMode.DC_PRED;
      mi.mbmi.uv_mode = MBPredictionMode.DC_PRED;
      this.left_context = cm.left_context;
   }

   public boolean hasSecondOrder() {
      return ModeInfo.hasSecondOrder(this.mode_info_context);
   }

   public void vp8_build_block_doffsets() {
      int blockNo;
      for (blockNo = 0; blockNo < 16; blockNo++) {
         this.block.getRel(blockNo).calcBlockYOffset(blockNo, this.dst.y_stride);
      }

      while (blockNo < 20) {
         this.block.getRel(blockNo).calcBlockUVOffset(blockNo, this.dst.uv_stride);
         this.block.getRel(blockNo + 4).calcBlockUVOffset(blockNo, this.dst.uv_stride);
         blockNo++;
      }
   }
}
