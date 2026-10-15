package rip.ysm.imagestream.webp.enc;

import java.util.HashMap;

final class RDOpt {
   static final int[] auto_speed_thresh = new int[]{1000, 200, 150, 130, 150, 125, 120, 115, 115, 115, 115, 115, 115, 115, 115, 115, 105};

   private RDOpt() {
   }

   static long RDCOST(int RM, int DM, int R, long D) {
      return (128L + (long)R * RM >> 8) + DM * D;
   }

   static int rd_cost_mbuv(Macroblock mb) {
      int cost = 0;
      MacroblockD x = mb.e_mbd;
      EntropyPlanes t_above = new EntropyPlanes(x.above_context.get());
      EntropyPlanes t_left = new EntropyPlanes(x.left_context);

      for (int b = 16; b < 24; b++) {
         cost += cost_coeffs(
            mb,
            x.block.getRel(b),
            PlaneType.UV,
            t_above.panes.shallowCopyWithPosInc(BlockD.vp8_block2above[b]),
            t_left.panes.shallowCopyWithPosInc(BlockD.vp8_block2left[b])
         );
      }

      return cost;
   }

   static int vp8_rdcost_mby(Macroblock mb) {
      int cost = 0;
      MacroblockD x = mb.e_mbd;
      EntropyPlanes t_above = new EntropyPlanes(mb.e_mbd.above_context.get());
      EntropyPlanes t_left = new EntropyPlanes(mb.e_mbd.left_context);

      for (int b = 0; b < 16; b++) {
         cost += cost_coeffs(
            mb,
            x.block.getRel(b),
            PlaneType.Y_NO_DC,
            t_above.panes.shallowCopyWithPosInc(BlockD.vp8_block2above[b]),
            t_left.panes.shallowCopyWithPosInc(BlockD.vp8_block2left[b])
         );
      }

      return cost
         + cost_coeffs(
            mb,
            x.block.getRel(24),
            PlaneType.Y2,
            t_above.panes.shallowCopyWithPosInc(BlockD.vp8_block2above[24]),
            t_left.panes.shallowCopyWithPosInc(BlockD.vp8_block2left[24])
         );
   }

   static int cost_coeffs(Macroblock mb, BlockD b, PlaneType type, FullGetSetPointer a, FullGetSetPointer l) {
      int c = type.start_coeff;
      int corig = c;
      int eob = b.eob.get();
      int cost = 0;
      GetPointer qcoeff_ptr = b.qcoeff;

      short pt;
      for (pt = (short)(a.get() + l.get()); c < eob; c++) {
         int v = qcoeff_ptr.getRel(W.zigzag[c]);
         TokenAlphabet t = DCTValueConstants.getTokenValue(v).token;
         cost += mb.token_costs[type.ordinal()][W.vp8CoefBands[c]][pt][t.ordinal()];
         cost += DCTValueConstants.getValueCost(v);
         pt = t.prevTokenClass;
      }

      if (c < 16) {
         cost += mb.token_costs[type.ordinal()][W.vp8CoefBands[c]][pt][TokenAlphabet.DCT_EOB_TOKEN.ordinal()];
      }

      pt = (short)(c != corig ? 1 : 0);
      a.set(l.set(pt));
      return cost;
   }

   static int vp8_block_error(GetPointer coeff, GetPointer dqcoeff) {
      int error = 0;

      for (int i = 0; i < 16; i++) {
         int this_diff = coeff.getRel(i) - dqcoeff.getRel(i);
         error += this_diff * this_diff;
      }

      return error;
   }

   static int vp8_mbuverror(Macroblock mb) {
      int error = 0;

      for (int i = 16; i < 24; i++) {
         Block be = mb.block.getRel(i);
         BlockD bd = mb.e_mbd.block.getRel(i);
         error += vp8_block_error(be.coeff, bd.dqcoeff);
      }

      return error;
   }

   static void rd_pick_intra_mbuv_mode(Macroblock x, QualityMetrics rd) {
      MBPredictionMode mode_selected = null;
      rd.error = Long.MAX_VALUE;
      MacroblockD xd = x.e_mbd;
      FullGetSetPointer vpred_ptr = xd.getFreshVPredPtr();
      FullGetSetPointer upred_ptr = xd.getFreshUPredPtr();
      GetPointer uabove = xd.dst.u_buffer.shallowCopyWithPosInc(-xd.dst.uv_stride);
      GetPointer vabove = xd.dst.v_buffer.shallowCopyWithPosInc(-xd.dst.uv_stride);
      GetPointer uleft = xd.dst.u_buffer.shallowCopyWithPosInc(-1);
      GetPointer vleft = xd.dst.v_buffer.shallowCopyWithPosInc(-1);
      ModeInfo mi = xd.mode_info_context.get();

      for (MBPredictionMode mode : MBPredictionMode.nonBlockPred) {
         mi.mbmi.uv_mode = mode;
         x.recon.vp8_build_intra_predictors_mbuv_s(xd, uabove, vabove, uleft, vleft, xd.dst.uv_stride, upred_ptr, vpred_ptr, 8);
         EncodeMB.vp8_subtract_mbuv(x.src_diff, x.src.u_buffer, x.src.v_buffer, x.src.uv_stride, upred_ptr, vpred_ptr, 8);
         EncodeMB.vp8_transform_mbuv(x);
         Quantize.vp8_quantize_mbuv(x);
         int rate_to = rd_cost_mbuv(x);
         int this_rate = rate_to + x.intra_uv_mode_cost[xd.frame_type][mi.mbmi.uv_mode.ordinal()];
         int this_distortion = vp8_mbuverror(x) / 4;
         long this_rd = RDCOST(x.rdmult, x.rddiv, this_rate, this_distortion);
         if (this_rd < rd.error) {
            rd.error = this_rd;
            rd.distortion = this_distortion;
            rd.rateComp = this_rate;
            rd.rateBase = rate_to;
            mode_selected = mode;
         }
      }

      mi.mbmi.uv_mode = mode_selected;
   }

   static int vp8_mbblock_error(Macroblock mb, int dc) {
      int error = 0;

      for (int i = 0; i < 16; i++) {
         Block be = mb.block.getRel(i);
         BlockD bd = mb.e_mbd.block.getRel(i);
         int berror = 0;

         for (int j = dc; j < 16; j++) {
            int this_diff = be.coeff.getRel(j) - bd.dqcoeff.getRel(j);
            berror += this_diff * this_diff;
         }

         error += berror;
      }

      return error;
   }

   static void macro_block_yrd(Macroblock mb, QualityMetrics best) {
      MacroblockD x = mb.e_mbd;
      Block mb_y2 = mb.block.getRel(24);
      BlockD x_y2 = x.block.getRel(24);
      FullGetSetPointer Y2DCPtr = mb_y2.src_diff.shallowCopy();
      EncodeMB.vp8_subtract_mby(mb.src_diff, mb.block.get().base_src, mb.block.get().src_stride, mb.e_mbd.predictor, 16);

      for (int bi = 0; bi < 16; bi += 2) {
         mb.short_fdct8x4.call(mb.block.getRel(bi).src_diff, mb.block.getRel(bi).coeff, 32);
         Y2DCPtr.setAndInc(mb.block.getRel(bi).coeff.get());
         Y2DCPtr.setAndInc(mb.block.getRel(bi).coeff.getRel(16));
      }

      mb.short_walsh4x4.call(mb_y2.src_diff, mb_y2.coeff, 8);

      for (int b = 0; b < 16; b++) {
         mb.quantize_b.call(mb.block.getRel(b), mb.e_mbd.block.getRel(b));
      }

      mb.quantize_b.call(mb_y2, x_y2);
      int d = vp8_mbblock_error(mb, 1) << 2;
      d += vp8_block_error(mb_y2.coeff, x_y2.dqcoeff);
      best.distortion = d >> 4;
      best.rateBase = vp8_rdcost_mby(mb);
   }

   static void rd_pick_intra16x16mby_mode(Macroblock x, QualityMetrics rd) {
      MBPredictionMode mode_selected = null;
      MacroblockD xd = x.e_mbd;
      QualityMetrics curr = new QualityMetrics();
      rd.error = Long.MAX_VALUE;
      ModeInfo mi = xd.mode_info_context.get();

      for (MBPredictionMode mode : MBPredictionMode.nonBlockPred) {
         mi.mbmi.mode = mode;
         x.recon
            .vp8_build_intra_predictors_mby_s(
               xd, xd.dst.y_buffer.shallowCopyWithPosInc(-xd.dst.y_stride), xd.dst.y_buffer.shallowCopyWithPosInc(-1), xd.dst.y_stride, xd.predictor, 16
            );
         macro_block_yrd(x, curr);
         int temp = curr.rateBase + x.mbmode_cost.get(xd.frame_type).get(mi.mbmi.mode);
         long this_rd = RDCOST(x.rdmult, x.rddiv, temp, curr.distortion);
         if (this_rd < rd.error) {
            mode_selected = mode;
            rd.error = this_rd;
            rd.rateBase = temp;
            rd.rateComp = curr.rateComp;
            rd.distortion = curr.distortion;
         }
      }

      mi.mbmi.mode = mode_selected;
   }

   static void copy_predictor(FullGetSetPointer dst, GetPointer predictor) {
      for (int i = 0; i < 13; i += 4) {
         dst.setRel(i, predictor.getRel(i));
      }
   }

   static void rd_pick_intra4x4block(
      Macroblock x, Block be, BlockD b, int[] bestmode, HashMap<Integer, Integer> bmode_costs, FullGetSetPointer a, FullGetSetPointer l, QualityMetrics best
   ) {
      best.error = Long.MAX_VALUE;
      FullGetSetPointer best_predictor = new FullGetSetPointer(64);
      FullGetSetPointer best_dqcoeff = new FullGetSetPointer(16);
      int dst_stride = x.e_mbd.dst.y_stride;
      FullGetSetPointer dst = b.getOffsetPointer(x.e_mbd.dst.y_buffer);
      FullGetSetPointer Above = dst.shallowCopyWithPosInc(-dst_stride);
      FullGetSetPointer yleft = dst.shallowCopyWithPosInc(-1);
      short top_left = Above.getRel(-1);

      for (int mode : W.bintramodes) {
         int rate = bmode_costs.get(mode);
         x.recon.vp8_intra4x4_predict(Above, yleft, dst_stride, mode, b.predictor, 16, top_left);
         EncodeMB.vp8_subtract_b(be, b);
         x.short_fdct4x4.call(be.src_diff, be.coeff, 32);
         x.quantize_b.call(be, b);
         FullGetSetPointer tempa = a.shallowCopy();
         FullGetSetPointer templ = l.shallowCopy();
         int ratey = cost_coeffs(x, b, PlaneType.Y_WITH_DC, tempa, templ);
         rate += ratey;
         int distortion = vp8_block_error(be.coeff, b.dqcoeff) >> 2;
         long this_rd = RDCOST(x.rdmult, x.rddiv, rate, distortion);
         if (this_rd < best.error) {
            best.rateBase = rate;
            best.rateComp = ratey;
            best.distortion = distortion;
            best.error = this_rd;
            bestmode[0] = mode;
            a.set(tempa.get());
            l.set(templ.get());
            copy_predictor(best_predictor, b.predictor);
            best_dqcoeff.memcopyin(0, b.dqcoeff, 0, 16);
         }
      }

      b.bmi.as_mode(bestmode[0]);
      IDCTllm.vp8_short_idct4x4llm(best_dqcoeff, best_predictor, 16, dst, dst_stride);
   }

   static void rd_pick_intra4x4mby_modes(Macroblock mb, QualityMetrics best) {
      MacroblockD xd = mb.e_mbd;
      int cost = mb.mbmode_cost.get(xd.frame_type).get(MBPredictionMode.B_PRED);
      int distortion = 0;
      int tot_rate_y = 0;
      long total_rd = 0L;
      EntropyPlanes t_above = new EntropyPlanes(mb.e_mbd.above_context.get());
      EntropyPlanes t_left = new EntropyPlanes(mb.e_mbd.left_context);
      ReconIntra.intra_prediction_down_copy(xd);
      HashMap<Integer, Integer> bmode_costs = mb.inter_bmode_costs;
      QualityMetrics rdTemp = new QualityMetrics();
      ModeInfo mi = xd.mode_info_context.get();

      for (int i = 0; i < 16; i++) {
         int mis = xd.mode_info_stride;
         int[] best_mode = new int[]{-1};
         if (mb.e_mbd.frame_type == 0) {
            int A = FindNearMV.above_block_mode(xd.mode_info_context, i, mis);
            int L = FindNearMV.left_block_mode(xd.mode_info_context, i);
            bmode_costs = mb.bmode_costs.get(A).get(L);
         }

         rd_pick_intra4x4block(
            mb,
            mb.block.getRel(i),
            xd.block.getRel(i),
            best_mode,
            bmode_costs,
            t_above.panes.shallowCopyWithPosInc(BlockD.vp8_block2above[i]),
            t_left.panes.shallowCopyWithPosInc(BlockD.vp8_block2left[i]),
            rdTemp
         );
         total_rd += rdTemp.error;
         cost += rdTemp.rateBase;
         distortion = (int)(distortion + rdTemp.distortion);
         tot_rate_y += rdTemp.rateComp;
         mi.bmi[i].as_mode(best_mode[0]);
         if (total_rd >= best.error) {
            break;
         }
      }

      if (total_rd >= best.error) {
         best.error = 2147483647L;
      } else {
         best.rateBase = cost;
         best.rateComp = tot_rate_y;
         best.distortion = distortion;
         best.error = RDCOST(mb.rdmult, mb.rddiv, cost, distortion);
      }
   }

   static long vp8_rd_pick_intra_mode(Macroblock x) {
      ModeInfo mi = x.e_mbd.mode_info_context.get();
      mi.mbmi.ref_frame = MVReferenceFrame.INTRA_FRAME;
      QualityMetrics resUV = new QualityMetrics();
      rd_pick_intra_mbuv_mode(x, resUV);
      QualityMetrics res16 = new QualityMetrics();
      rd_pick_intra16x16mby_mode(x, res16);
      QualityMetrics res4 = new QualityMetrics();
      res4.error = res16.error;
      rd_pick_intra4x4mby_modes(x, res4);
      if (res4.error < res16.error) {
         mi.mbmi.mode = MBPredictionMode.B_PRED;
         res16.rateComp = res4.rateComp;
      }

      return resUV.rateComp + res16.rateComp;
   }

   static void vp8_auto_select_speed(Compressor cpi) {
      int milliseconds_for_compress = (int)(1000000.0 / cpi.framerate);
      milliseconds_for_compress = milliseconds_for_compress * (16 - cpi.oxcf.getCpu_used()) / 16;
      if (cpi.avg_pick_mode_time >= milliseconds_for_compress || cpi.avg_encode_time - cpi.avg_pick_mode_time >= milliseconds_for_compress) {
         cpi.Speed += 4;
         if (cpi.Speed > 16) {
            cpi.Speed = 16;
         }

         cpi.avg_pick_mode_time = 0;
         cpi.avg_encode_time = 0;
      } else if (cpi.avg_pick_mode_time == 0) {
         cpi.Speed = 4;
      } else {
         if (milliseconds_for_compress * 100 < cpi.avg_encode_time * 95) {
            cpi.Speed += 2;
            cpi.avg_pick_mode_time = 0;
            cpi.avg_encode_time = 0;
            if (cpi.Speed > 16) {
               cpi.Speed = 16;
            }
         }

         if (milliseconds_for_compress * 100 > cpi.avg_encode_time * auto_speed_thresh[cpi.Speed]) {
            cpi.Speed--;
            cpi.avg_pick_mode_time = 0;
            cpi.avg_encode_time = 0;
            if (cpi.Speed < 4) {
               cpi.Speed = 4;
            }
         }
      }
   }

   static void fill_token_costs(int[][][][] c, short[][][][] p) {
      for (int i = 0; i < 4; i++) {
         for (int j = 0; j < 8; j++) {
            for (int k = 0; k < 3; k++) {
               if (k == 0 && j > (i == 0 ? 1 : 0)) {
                  TreeWriter.vp8_cost_tokens2(c[i][j][0], new GetPointer(p[i][j][k], 0));
               } else {
                  TreeWriter.vp8_cost_tokens(c[i][j][k], new GetPointer(p[i][j][k], 0), Entropy.vp8_coef_tree);
               }
            }
         }
      }
   }

   static void vp8_initialize_rd_consts(Compressor cpi, Macroblock x, int Qvalue) {
      double capped_q = Qvalue < 160 ? Qvalue : 160.0;
      double rdconst = 2.8;
      cpi.RDMULT = (int)(2.8 * (capped_q * capped_q));
      if (cpi.mb.zbin_over_quant > 0) {
         double oq_factor = 1.0 + 0.0015625 * cpi.mb.zbin_over_quant;
         double modq = (int)(capped_q * oq_factor);
         cpi.RDMULT = (int)(2.8 * (modq * modq));
      }

      cpi.mb.errorperbit = Math.max(cpi.RDMULT / 110, 1);
      OnyxIf.vp8_set_speed_features(cpi);

      for (int i = 0; i < 20; i++) {
         x.mode_test_hit_counts[i] = 0;
      }

      int q = (int)Math.pow(Qvalue, 1.25);
      if (q < 8) {
         q = 8;
      }

      if (cpi.RDMULT > 1000) {
         cpi.RDDIV = 1;
         cpi.RDMULT /= 100;

         for (int var13 = 0; var13 < 20; var13++) {
            if (cpi.sf.thresh_mult[var13] < Integer.MAX_VALUE) {
               x.rd_threshes[var13] = cpi.sf.thresh_mult[var13] * q / 100;
            } else {
               x.rd_threshes[var13] = Integer.MAX_VALUE;
            }

            cpi.rd_baseline_thresh[var13] = x.rd_threshes[var13];
         }
      } else {
         cpi.RDDIV = 100;

         for (int var14 = 0; var14 < 20; var14++) {
            if (cpi.sf.thresh_mult[var14] < Integer.MAX_VALUE / q) {
               x.rd_threshes[var14] = cpi.sf.thresh_mult[var14] * q;
            } else {
               x.rd_threshes[var14] = Integer.MAX_VALUE;
            }

            cpi.rd_baseline_thresh[var14] = x.rd_threshes[var14];
         }
      }

      FrameContext l = cpi.lfc_n;
      if (cpi.common.refresh_alt_ref_frame) {
         l = cpi.lfc_a;
      } else if (cpi.common.refresh_golden_frame) {
         l = cpi.lfc_g;
      }

      fill_token_costs(cpi.mb.token_costs, l.coef_probs);
      cpi.rd_costs.vp8_init_mode_costs(cpi);
   }
}
