package rip.ysm.imagestream.webp.enc;

import java.util.Arrays;
import rip.ysm.imagestream.internal.LogWriter;

final class EncodeFrame {
   private EncodeFrame() {
   }

   static void sum_intra_stats(Macroblock x) {
      ModeInfo mi = x.e_mbd.mode_info_context.get();
      MBPredictionMode m = mi.mbmi.mode;
      MBPredictionMode uvm = mi.mbmi.uv_mode;
      x.ymode_count[m.ordinal()]++;
      x.uv_mode_count[uvm.ordinal()]++;
   }

   static long vp8cx_encode_intra_macroblock(Compressor cpi, Macroblock x, FullGenArrPointer<TokenExtra> t) {
      MacroblockD xd = x.e_mbd;
      ModeInfo mi = x.e_mbd.mode_info_context.get();
      long rate;
      if (cpi.sf.RD && cpi.compressor_speed != 2) {
         rate = RDOpt.vp8_rd_pick_intra_mode(x);
      } else {
         rate = x.interPicker.vp8_pick_intra_mode(x);
      }

      if (mi.mbmi.mode == MBPredictionMode.B_PRED) {
         EncodeIntra.vp8_encode_intra4x4mby(x);
      } else {
         EncodeIntra.vp8_encode_intra16x16mby(x);
      }

      EncodeIntra.vp8_encode_intra16x16mbuv(x);
      sum_intra_stats(x);
      Tokenize.vp8_tokenize_mb(cpi, x, t);
      if (mi.mbmi.mode != MBPredictionMode.B_PRED) {
         InvTrans.vp8_inverse_transform_mby(xd);
      }

      IDCTBlk.vp8_dequant_idct_add_uv_block(
         xd.qcoeff.shallowCopyWithPosInc(256), xd.dequant_uv, xd.dst.u_buffer, xd.dst.v_buffer, xd.dst.uv_stride, xd.eobs.shallowCopyWithPosInc(16)
      );
      return rate;
   }

   static void encode_mb_row(
      Compressor cpi, CommonData cm, int mb_row, Macroblock x, MacroblockD xd, FullGenArrPointer<TokenExtra> tp, int[] segment_counts, long[] totalrate
   ) {
      int ref_fb_idx = cm.frameIdxs.get(MVReferenceFrame.LAST_FRAME);
      int dst_fb_idx = cm.new_fb_idx;
      int recon_y_stride = cm.yv12_fb[ref_fb_idx].y_stride;
      int recon_uv_stride = cm.yv12_fb[ref_fb_idx].uv_stride;
      int map_index = mb_row * cpi.common.mb_cols;
      xd.above_context = cm.above_context.shallowCopy();
      xd.up_available = mb_row != 0;
      int recon_yoffset = mb_row * recon_y_stride * 16;
      int recon_uvoffset = mb_row * recon_uv_stride * 8;
      xd.dst.y_buffer = cm.yv12_fb[dst_fb_idx].y_buffer.shallowCopyWithPosInc(recon_yoffset);
      xd.dst.u_buffer = cm.yv12_fb[dst_fb_idx].u_buffer.shallowCopyWithPosInc(recon_uvoffset);
      xd.dst.v_buffer = cm.yv12_fb[dst_fb_idx].v_buffer.shallowCopyWithPosInc(recon_uvoffset);
      cpi.tplist[mb_row].start = tp.shallowCopy();
      x.mv_row_min = (short)(-(mb_row * 16 + 16));
      x.mv_row_max = (short)((cm.mb_rows - 1 - mb_row) * 16 + 16);
      x.mb_activity_ptr = cpi.mb_activity_map.shallowCopyWithPosInc(map_index);

      for (int mb_col = 0; mb_col < cm.mb_cols; mb_col++) {
         ModeInfo mi = xd.mode_info_context.get();
         x.mv_col_min = (short)(-(mb_col * 16 + 16));
         x.mv_col_max = (short)((cm.mb_cols - 1 - mb_col) * 16 + 16);
         xd.left_available = mb_col != 0;
         x.rddiv = cpi.RDDIV;
         x.rdmult = cpi.RDMULT;
         CUtils.vp8_copy_mem16x16(x.src.y_buffer, x.src.y_stride, x.thismb, 16);
         if (xd.segmentation_enabled != 0) {
            if (cpi.segmentation_map[map_index + mb_col] <= 3) {
               mi.mbmi.segment_id = cpi.segmentation_map[map_index + mb_col];
            } else {
               mi.mbmi.segment_id = 0;
            }

            Quantize.vp8cx_mb_init_quantizer(cpi, x, true);
         } else {
            mi.mbmi.segment_id = 0;
         }

         if (cm.frame_type == 0) {
            totalrate[0] += vp8cx_encode_intra_macroblock(cpi, x, tp);
         } else {
            LogWriter.writeLog("should not be called");
         }

         cpi.tplist[mb_row].stop = tp.shallowCopy();
         x.gf_active_ptr.inc();
         x.mb_activity_ptr.inc();
         x.src.y_buffer.incBy(16);
         x.src.u_buffer.incBy(8);
         x.src.v_buffer.incBy(8);
         recon_yoffset += 16;
         recon_uvoffset += 8;
         xd.dst.y_buffer.incBy(16);
         xd.dst.u_buffer.incBy(8);
         xd.dst.v_buffer.incBy(8);
         segment_counts[mi.mbmi.segment_id]++;
         xd.mode_info_context.inc();
         xd.above_context.inc();
      }

      Extend.vp8_extend_mb_row(cm.yv12_fb[dst_fb_idx], xd.dst.y_buffer, xd.dst.u_buffer, xd.dst.v_buffer);
      xd.mode_info_context.inc();
   }

   private static void init_encode_frame_mb_context(Compressor cpi) {
      cpi.mb.e_mbd.init_encode_frame_mbd_context(cpi);
      cpi.mb.init_encode_frame_mb_context(cpi);
      CommonData cm = cpi.common;
      if (cm.frame_type == 0) {
         cm.fc.vp8_init_mbmode_probs();
      }

      cm.yv12_fb[cm.new_fb_idx].vp8_setup_intra_recon();

      for (int kk = 0; kk < cm.mb_cols; kk++) {
         cm.above_context.getRel(kk).reset();
      }
   }

   static void vp8_encode_frame(Compressor cpi) {
      Macroblock x = cpi.mb;
      CommonData cm = cpi.common;
      MacroblockD xd = x.e_mbd;
      FullGenArrPointer<TokenExtra> tp = cpi.tok.shallowCopy();
      int[] segment_counts = new int[xd.segmentation_enabled != 0 ? 4 : 1];
      long[] totalrate = new long[1];
      CUtils.vp8_zero(segment_counts);
      if (cpi.compressor_speed == 2) {
         if (cpi.oxcf.getCpu_used() < 0) {
            cpi.Speed = -cpi.oxcf.getCpu_used();
         } else {
            RDOpt.vp8_auto_select_speed(cpi);
         }
      }

      SubPixFnCollector spfncollector = cm.use_bilinear_mc_filter ? BilinearPredict.bilinear : SixtapPredict.sixtap;
      xd.subpixel_predict8x8 = spfncollector.get8x8();
      xd.subpixel_predict16x16 = spfncollector.get16x16();
      cpi.mb.skip_true_count = 0;
      cpi.tok_count = 0;
      xd.mode_info_context = cm.mi.shallowCopy();
      CUtils.vp8_zero(cpi.mb.MVcount);
      Quantize.vp8cx_frame_init_quantizer(cpi);
      RDOpt.vp8_initialize_rd_consts(cpi, x, QuantCommon.doLookup(cm, CommonData.Quant.Y1, CommonData.Comp.DC, cm.base_qindex));
      init_encode_frame_mb_context(cpi);

      for (int mb_row = 0; mb_row < cm.mb_rows; mb_row++) {
         CUtils.vp8_zero(cm.left_context.panes);
         encode_mb_row(cpi, cm, mb_row, x, xd, tp, segment_counts, totalrate);
         x.src.y_buffer.incBy(16 * x.src.y_stride - 16 * cm.mb_cols);
         x.src.u_buffer.incBy(8 * x.src.uv_stride - 8 * cm.mb_cols);
         x.src.v_buffer.incBy(8 * x.src.uv_stride - 8 * cm.mb_cols);
      }

      cpi.tok_count = cpi.tok.pointerDiff(tp);
      if (xd.segmentation_enabled != 0 && xd.update_mb_segmentation_map) {
         Arrays.fill(xd.mb_segment_tree_probs, 255);
         int tot_count = segment_counts[0] + segment_counts[1] + segment_counts[2] + segment_counts[3];
         if (tot_count != 0) {
            xd.mb_segment_tree_probs[0] = (segment_counts[0] + segment_counts[1]) * 255 / tot_count;
            tot_count = segment_counts[0] + segment_counts[1];
            if (tot_count > 0) {
               xd.mb_segment_tree_probs[1] = segment_counts[0] * 255 / tot_count;
            }

            tot_count = segment_counts[2] + segment_counts[3];
            if (tot_count > 0) {
               xd.mb_segment_tree_probs[2] = segment_counts[2] * 255 / tot_count;
            }

            for (int i = 0; i < 3; i++) {
               if (xd.mb_segment_tree_probs[i] == 0) {
                  xd.mb_segment_tree_probs[i] = 1;
               }
            }
         }
      }

      cpi.projected_frame_size = (int)(totalrate[0] >> 8);
      if (cm.frame_type == 0) {
         cpi.this_frame_percent_intra = 100;
      } else {
         ReferenceCounts rf = cpi.mb.sumReferenceCounts();
         if (rf.total != 0) {
            cpi.this_frame_percent_intra = rf.intra * 100 / rf.total;
         }
      }

      if (cm.frame_type != 0 && (cpi.oxcf.number_of_layers > 1 || !cm.refresh_alt_ref_frame && !cm.refresh_golden_frame)) {
         BitStream.vp8_convert_rfct_to_prob(cpi);
      }
   }
}
