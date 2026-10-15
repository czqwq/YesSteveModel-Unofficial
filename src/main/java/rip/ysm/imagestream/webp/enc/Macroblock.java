package rip.ysm.imagestream.webp.enc;

import java.util.Arrays;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashMap;
import rip.ysm.imagestream.internal.DCT;

class Macroblock {
   static final int MAX_MODES = 20;
   static final int MAX_ERROR_BINS = 1024;
   final FullGetSetPointer src_diff = new FullGetSetPointer(400);
   final FullGetSetPointer coeff = new FullGetSetPointer(400);
   final FullGetSetPointer thismb = new FullGetSetPointer(256);
   FullGetSetPointer thismb_ptr;
   final FullGenArrPointer<Block> block = new FullGenArrPointer<>(25);
   YV12buffer src;
   final MacroblockD e_mbd;
   final int[] ref_frame_cost = new int[MVReferenceFrame.count];
   final FullGenArrPointer<SearchSite> ss = new FullGenArrPointer<>(65);
   int ss_count;
   int errorperbit;
   int rddiv;
   int rdmult;
   FullGetSetPointer mb_activity_ptr;
   int act_zbin_adj;
   int last_act_zbin_adj;
   final FullGetSetPointer[] mvcost = new FullGetSetPointer[2];
   final FullGetSetPointer[] mvsadcost = new FullGetSetPointer[2];
   final HashMap<Integer, HashMap<Integer, Integer>> mbmode_cost;
   final int[][] intra_uv_mode_cost;
   final HashMap<Integer, HashMap<Integer, HashMap<Integer, Integer>>> bmode_costs;
   final HashMap<Integer, Integer> inter_bmode_costs;
   final int[][][][] token_costs;
   short mv_col_min;
   short mv_col_max;
   short mv_row_min;
   short mv_row_max;
   FullGetSetPointer gf_active_ptr;
   boolean optimize;
   int q_index;
   int skip_true_count;
   final int[][][][] coef_counts = new int[4][8][3][TokenAlphabet.entropyTokenCount];
   final int[][] MVcount = new int[2][2047];
   final int[] ymode_count = new int[BlockD.VP8_YMODES];
   final int[] uv_mode_count = new int[BlockD.VP8_UV_MODES];
   long prediction_error;
   long intra_error;
   final EnumMap<MVReferenceFrame, Integer> count_mb_ref_frame_usage = new EnumMap<>(MVReferenceFrame.class);
   final int[] rd_thresh_mult = new int[20];
   final int[] rd_threshes = new int[20];
   final int[] mode_test_hit_counts = new int[20];
   int zbin_mode_boost;
   int last_zbin_mode_boost;
   int last_zbin_over_quant;
   int zbin_over_quant;
   final int[] error_bins = new int[1024];
   DCT.TRANSFORM short_fdct4x4;
   DCT.TRANSFORM short_fdct8x4;
   DCT.TRANSFORM short_walsh4x4;
   Quantize.Quant quantize_b;
   final ReconIntra recon = new ReconIntra();
   final PickInter interPicker = new PickInter();

   Macroblock(Compressor cpi) {
      this.mvcost[0] = cpi.rd_costs.mvcosts[0].shallowCopyWithPosInc(1024);
      this.mvcost[1] = cpi.rd_costs.mvcosts[1].shallowCopyWithPosInc(1024);
      this.mvsadcost[0] = cpi.rd_costs.mvsadcosts[0].shallowCopyWithPosInc(256);
      this.mvsadcost[1] = cpi.rd_costs.mvsadcosts[1].shallowCopyWithPosInc(256);
      cal_mvsadcosts(this.mvsadcost);
      this.mbmode_cost = cpi.rd_costs.mbmode_cost;
      this.intra_uv_mode_cost = cpi.rd_costs.intra_uv_mode_cost;
      this.bmode_costs = cpi.rd_costs.bmode_costs;
      this.inter_bmode_costs = cpi.rd_costs.inter_bmode_costs;
      this.token_costs = cpi.rd_costs.token_costs;
      this.error_bins[0] = cpi.common.MBs;
      this.vp8_setup_block_ptrs();
      this.e_mbd = new MacroblockD(cpi);
      this.changeFNs(cpi);
      Arrays.fill(this.rd_thresh_mult, 128);
      this.initRefFrameCounts();
   }

   static void cal_mvsadcosts(FullGetSetPointer[] mvsadcost) {
      int i = 1;
      mvsadcost[0].set((short)300);
      mvsadcost[1].set((short)300);

      do {
         double z = 256.0 * (2.0 * (OnyxIf.log2f(8 * i) + 0.6));
         mvsadcost[0].setRel(i, (short)z);
         mvsadcost[1].setRel(i, (short)z);
         mvsadcost[0].setRel(-i, (short)z);
         mvsadcost[1].setRel(-i, (short)z);
      } while (++i <= 255);
   }

   private void initRefFrameCounts() {
      for (MVReferenceFrame rf : MVReferenceFrame.validFrames) {
         this.count_mb_ref_frame_usage.put(rf, 0);
      }
   }

   private void vp8_setup_block_ptrs() {
      for (int r = 0; r < 4; r++) {
         for (int c = 0; c < 4; c++) {
            this.block.setRel(r * 4 + c, new Block(this.src_diff.shallowCopyWithPosInc(r * 4 * 16 + c * 4)));
         }
      }

      for (int var4 = 0; var4 < 2; var4++) {
         for (int c = 0; c < 2; c++) {
            this.block.setRel(16 + var4 * 2 + c, new Block(this.src_diff.shallowCopyWithPosInc(256 + var4 * 4 * 8 + c * 4)));
         }
      }

      for (int var5 = 0; var5 < 2; var5++) {
         for (int c = 0; c < 2; c++) {
            this.block.setRel(20 + var5 * 2 + c, new Block(this.src_diff.shallowCopyWithPosInc(320 + var5 * 4 * 8 + c * 4)));
         }
      }

      this.block.setRel(24, new Block(this.src_diff.shallowCopyWithPosInc(384)));

      for (int i = 0; i < 25; i++) {
         this.block.getRel(i).coeff = this.coeff.shallowCopyWithPosInc(i * 16);
      }
   }

   private void prepInitMotionComp() {
      this.ss_count = 1;
      this.ss.set(new SearchSite(0, 0, 0));
   }

   private void init_addBasicSearchSites(int stride, int Len) {
      this.ss.incBy(this.ss_count);
      this.ss.setAndInc(new SearchSite(-Len, 0, -Len * stride));
      this.ss.setAndInc(new SearchSite(Len, 0, Len * stride));
      this.ss.setAndInc(new SearchSite(0, -Len, -Len));
      this.ss.setAndInc(new SearchSite(0, Len, Len));
      this.ss_count += 4;
      this.ss.rewind();
   }

   private void vp8_init3smotion_compensation(int stride) {
      this.prepInitMotionComp();

      for (int Len = 128; Len > 0; Len >>= 1) {
         this.init_addBasicSearchSites(stride, Len);
         this.ss.incBy(this.ss_count);
         this.ss.setAndInc(new SearchSite(-Len, -Len, -Len * stride - Len));
         this.ss.setAndInc(new SearchSite(-Len, Len, -Len * stride + Len));
         this.ss.setAndInc(new SearchSite(Len, -Len, Len * stride - Len));
         this.ss.setAndInc(new SearchSite(Len, Len, Len * stride + Len));
         this.ss.rewind();
         this.ss_count += 4;
      }
   }

   private void vp8_init_dsmotion_compensation(int stride) {
      this.prepInitMotionComp();

      for (int Len = 128; Len > 0; Len >>= 1) {
         this.init_addBasicSearchSites(stride, Len);
      }
   }

   void changeFNs(Compressor cpi) {
      CommonData cm = cpi.common;
      if (cpi.sf.search_method == SearchMethods.NSTEP) {
         this.vp8_init3smotion_compensation(cm.yv12_fb[cm.frameIdxs.get(MVReferenceFrame.LAST_FRAME)].y_stride);
      } else if (cpi.sf.search_method == SearchMethods.DIAMOND) {
         this.vp8_init_dsmotion_compensation(cm.yv12_fb[cm.frameIdxs.get(MVReferenceFrame.LAST_FRAME)].y_stride);
      }

      this.short_fdct8x4 = DCT.vp8_short_fdct8x4;
      this.short_fdct4x4 = DCT.vp8_short_fdct4x4;
      this.short_walsh4x4 = DCT.vp8_short_walsh4x4;
      if (cpi.sf.improved_quant) {
         this.quantize_b = Quantize.regularQuant;
      } else {
         this.quantize_b = Quantize.fastQuant;
      }

      this.optimize = cpi.sf.optimize_coefficients;
   }

   ReferenceCounts sumReferenceCounts() {
      int inter = 0;

      for (MVReferenceFrame rf : EnumSet.range(MVReferenceFrame.LAST_FRAME, MVReferenceFrame.ALTREF_FRAME)) {
         inter += this.count_mb_ref_frame_usage.get(rf);
      }

      return new ReferenceCounts(this.count_mb_ref_frame_usage.get(MVReferenceFrame.INTRA_FRAME), inter);
   }

   void init_encode_frame_mb_context(Compressor cpi) {
      this.gf_active_ptr = cpi.gf_active_flags.shallowCopy();
      this.mb_activity_ptr = cpi.mb_activity_map.shallowCopy();
      this.act_zbin_adj = 0;
      this.src = cpi.sourceYV12.shallowCopy();
      this.vp8_build_block_offsets();
      if (cpi.ref_frame_flags.contains(MVReferenceFrame.LAST_FRAME) && cpi.ref_frame_flags.size() == 1) {
         BitStream.vp8_calc_ref_frame_costs(this.ref_frame_cost, cpi.prob_intra_coded, 255, 128);
      } else if (cpi.oxcf.number_of_layers > 1 && cpi.ref_frame_flags.contains(MVReferenceFrame.GOLDEN_FRAME) && cpi.ref_frame_flags.size() == 1) {
         BitStream.vp8_calc_ref_frame_costs(this.ref_frame_cost, cpi.prob_intra_coded, 1, 255);
      } else if (cpi.oxcf.number_of_layers > 1 && cpi.ref_frame_flags.contains(MVReferenceFrame.ALTREF_FRAME) && cpi.ref_frame_flags.size() == 1) {
         BitStream.vp8_calc_ref_frame_costs(this.ref_frame_cost, cpi.prob_intra_coded, 1, 1);
      } else {
         BitStream.vp8_calc_ref_frame_costs(this.ref_frame_cost, cpi.prob_intra_coded, cpi.prob_last_coded, cpi.prob_gf_coded);
      }

      CUtils.vp8_zero(this.coef_counts);
      CUtils.vp8_zero(this.ymode_count);
      CUtils.vp8_zero(this.uv_mode_count);
      this.prediction_error = 0L;
      this.intra_error = 0L;
      this.initRefFrameCounts();
   }

   void vp8_build_block_offsets() {
      this.e_mbd.vp8_build_block_doffsets();
      this.thismb_ptr = this.thismb.shallowCopy();

      for (int br = 0; br < 4; br++) {
         for (int bc = 0; bc < 4; bc++) {
            Block this_block = this.block.getAndInc();
            this_block.base_src = this.thismb_ptr;
            this_block.src_stride = 16;
            this_block.src = 4 * br * 16 + 4 * bc;
         }
      }

      for (int var4 = 0; var4 < 2; var4++) {
         for (int bc = 0; bc < 2; bc++) {
            Block this_block = this.block.getAndInc();
            this_block.base_src = this.src.u_buffer;
            this_block.src_stride = this.src.uv_stride;
            this_block.src = 4 * var4 * this_block.src_stride + 4 * bc;
         }
      }

      for (int var5 = 0; var5 < 2; var5++) {
         for (int bc = 0; bc < 2; bc++) {
            Block this_block = this.block.getAndInc();
            this_block.base_src = this.src.v_buffer;
            this_block.src_stride = this.src.uv_stride;
            this_block.src = 4 * var5 * this_block.src_stride + 4 * bc;
         }
      }

      this.block.rewind();
   }
}
