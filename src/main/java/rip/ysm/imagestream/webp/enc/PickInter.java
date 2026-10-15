package rip.ysm.imagestream.webp.enc;

import java.util.EnumMap;
import java.util.HashMap;

class PickInter {
   static final int[] nearsaddx_proto = new int[]{0, 1, 2, 3, 4, 5, 6, 7};
   final MV[] best_ref_mv_sb = new MV[]{new MV(), new MV()};
   final MV[][] mode_mv_sb = new MV[2][MBPredictionMode.count];
   final MV best_ref_mv = new MV();
   final MV mvp = new MV();
   final EnumMap<MBPredictionMode, int[]> pred_error = new EnumMap<>(MBPredictionMode.class);
   final int[] nearsad = new int[nearsaddx_proto.length];

   PickInter() {
      for (int i = 0; i < this.mode_mv_sb.length; i++) {
         for (int j = 0; j < this.mode_mv_sb[i].length; j++) {
            this.mode_mv_sb[i][j] = new MV();
         }
      }

      for (MBPredictionMode m : MBPredictionMode.nonBlockPred) {
         this.pred_error.put(m, new int[1]);
      }
   }

   void reset() {
      for (MV mv : this.best_ref_mv_sb) {
         mv.setZero();
      }

      for (MV[] mvs : this.mode_mv_sb) {
         for (MV mv : mvs) {
            mv.setZero();
         }
      }

      this.best_ref_mv.setZero();
      this.mvp.setZero();
      CUtils.vp8_zero(this.nearsad);
      CUtils.vp8_copy(nearsaddx_proto, this.nearsad);

      for (MBPredictionMode m : MBPredictionMode.nonBlockPred) {
         this.pred_error.get(m)[0] = 0;
      }
   }

   void pick_intra_mbuv_mode(Macroblock mb) {
      MacroblockD x = mb.e_mbd;
      FullGetSetPointer usrc_ptr = mb.block.getRel(16).getSrcPtr();
      FullGetSetPointer vsrc_ptr = mb.block.getRel(20).getSrcPtr();
      int uvsrc_stride = mb.block.getRel(16).src_stride;
      int utop_left = x.dst.u_buffer.getRel(-x.dst.uv_stride - 1);
      int vtop_left = x.dst.v_buffer.getRel(-x.dst.uv_stride - 1);
      this.reset();
      int expected_udc;
      int expected_vdc;
      if (!x.up_available && !x.left_available) {
         expected_udc = 128;
         expected_vdc = 128;
      } else {
         int shift = 2;
         int uaverage = 0;
         int vaverage = 0;
         if (x.up_available) {
            int i = 0;

            for (int relI = -x.dst.uv_stride; i < 8; relI++) {
               uaverage += x.dst.u_buffer.getRel(relI);
               vaverage += x.dst.v_buffer.getRel(relI);
               i++;
            }

            shift++;
         }

         if (x.left_available) {
            for (int i = 0; i < 8; i++) {
               uaverage += x.dst.u_buffer.getRel(i * x.dst.uv_stride - 1);
               vaverage += x.dst.v_buffer.getRel(i * x.dst.uv_stride - 1);
            }

            shift++;
         }

         expected_udc = CUtils.roundPowerOfTwo(uaverage, shift);
         expected_vdc = CUtils.roundPowerOfTwo(vaverage, shift);
      }

      for (int i = 0; i < 8; i++) {
         int j = 0;

         for (int relJ = -x.dst.uv_stride; j < 8; relJ++) {
            short uab = x.dst.u_buffer.getRel(relJ);
            short vab = x.dst.v_buffer.getRel(relJ);
            short ule = x.dst.u_buffer.getRel(i * x.dst.uv_stride - 1);
            short vle = x.dst.v_buffer.getRel(i * x.dst.uv_stride - 1);
            short predu = CUtils.clipPixel((short)(ule + uab - utop_left));
            short predv = CUtils.clipPixel((short)(vle + vab - vtop_left));
            short u_p = usrc_ptr.getRel(j);
            short v_p = vsrc_ptr.getRel(j);
            short diffU = (short)(u_p - expected_udc);
            short diffV = (short)(v_p - expected_vdc);
            this.pred_error.get(MBPredictionMode.DC_PRED)[0] += diffU * diffU + diffV * diffV;
            diffU = (short)(u_p - uab);
            diffV = (short)(v_p - vab);
            this.pred_error.get(MBPredictionMode.V_PRED)[0] += diffU * diffU + diffV * diffV;
            diffU = (short)(u_p - ule);
            diffV = (short)(v_p - vle);
            this.pred_error.get(MBPredictionMode.H_PRED)[0] += diffU * diffU + diffV * diffV;
            diffU = (short)(u_p - predu);
            diffV = (short)(v_p - predv);
            this.pred_error.get(MBPredictionMode.TM_PRED)[0] += diffU * diffU + diffV * diffV;
            j++;
         }

         usrc_ptr.incBy(uvsrc_stride);
         vsrc_ptr.incBy(uvsrc_stride);
         if (i == 3) {
            Block ublk = mb.block.getRel(18);
            Block vblk = mb.block.getRel(22);
            usrc_ptr.setPos(ublk.base_src.getPos() + ublk.src);
            vsrc_ptr.setPos(vblk.base_src.getPos() + vblk.src);
         }
      }

      int best_error = Integer.MAX_VALUE;
      MBPredictionMode best_mode = null;

      for (MBPredictionMode pm : MBPredictionMode.nonBlockPred) {
         int pe = this.pred_error.get(pm)[0];
         if (best_error > pe) {
            best_error = pe;
            best_mode = pm;
         }
      }

      mb.e_mbd.mode_info_context.get().mbmi.uv_mode = best_mode;
   }

   static int get_prediction_error(Block be, BlockD b) {
      return Variance.vpx_get4x4sse_cs(be.getSrcPtr(), be.src_stride, b.predictor);
   }

   static void pick_intra4x4block(Macroblock x, int[] bestmode, int ib, HashMap<Integer, Integer> mode_costs, QualityMetrics best) {
      BlockD b = x.e_mbd.block.getRel(ib);
      Block be = x.block.getRel(ib);
      int dst_stride = x.e_mbd.dst.y_stride;
      best.error = Long.MAX_VALUE;
      FullGetSetPointer Above = b.getOffsetPointer(x.e_mbd.dst.y_buffer).shallowCopyWithPosInc(-dst_stride);
      FullGetSetPointer yleft = b.getOffsetPointer(x.e_mbd.dst.y_buffer).shallowCopyWithPosInc(-1);
      short top_left = Above.getRel(-1);

      for (int mode : W.basicbmodes) {
         int rate = mode_costs.get(mode);
         x.recon.vp8_intra4x4_predict(Above, yleft, dst_stride, mode, b.predictor, 16, top_left);
         long distortion = get_prediction_error(be, b);
         long this_rd = RDOpt.RDCOST(x.rdmult, x.rddiv, rate, distortion);
         if (this_rd < best.error) {
            best.rateBase = rate;
            best.distortion = distortion;
            best.error = this_rd;
            bestmode[0] = mode;
         }
      }

      b.bmi.as_mode(bestmode[0]);
      EncodeIntra.vp8_encode_intra4x4block(x, ib);
   }

   static void pick_intra4x4mby_modes(Macroblock mb, QualityMetrics best) {
      MacroblockD xd = mb.e_mbd;
      int cost = mb.mbmode_cost.get(xd.frame_type).get(MBPredictionMode.B_PRED.ordinal());
      long distortion = 0L;
      ReconIntra.intra_prediction_down_copy(xd);
      HashMap<Integer, Integer> bmode_costs = mb.inter_bmode_costs;
      QualityMetrics curr = new QualityMetrics();

      int i;
      for (i = 0; i < 16; i++) {
         FullGenArrPointer<ModeInfo> mic = xd.mode_info_context;
         int mis = xd.mode_info_stride;
         int[] best_mode = new int[]{-1};
         if (mb.e_mbd.frame_type == 0) {
            int A = FindNearMV.above_block_mode(mic, i, mis);
            int L = FindNearMV.left_block_mode(mic, i);
            bmode_costs = mb.bmode_costs.get(A).get(L);
         }

         pick_intra4x4block(mb, best_mode, i, bmode_costs, curr);
         cost += curr.rateBase;
         distortion += curr.distortion;
         mic.get().bmi[i].as_mode(best_mode[0]);
         if (distortion > best.distortion) {
            break;
         }
      }

      best.rateBase = cost;
      if (i == 16) {
         best.distortion = distortion;
         best.error = RDOpt.RDCOST(mb.rdmult, mb.rddiv, cost, distortion);
      } else {
         best.distortion = 2147483647L;
         best.error = 2147483647L;
      }
   }

   long vp8_pick_intra_mode(Macroblock x) {
      long error16x16 = Long.MAX_VALUE;
      QualityMetrics curr = new QualityMetrics();
      curr.distortion = Long.MAX_VALUE;
      long best_rate = 0L;
      MBPredictionMode best_mode = MBPredictionMode.DC_PRED;
      Block b = x.block.get();
      MacroblockD xd = x.e_mbd;
      VarianceResults varRes = new VarianceResults();
      MBModeInfo mbmi = xd.mode_info_context.get().mbmi;
      mbmi.ref_frame = MVReferenceFrame.INTRA_FRAME;
      this.pick_intra_mbuv_mode(x);
      GetPointer yabove = xd.dst.y_buffer.shallowCopyWithPosInc(-xd.dst.y_stride);
      GetPointer yleft = xd.dst.y_buffer.shallowCopyWithPosInc(-1);

      for (MBPredictionMode mode : MBPredictionMode.nonBlockPred) {
         mbmi.mode = mode;
         x.recon.vp8_build_intra_predictors_mby_s(xd, yabove, yleft, xd.dst.y_stride, xd.predictor, 16);
         Variance.variance(b.base_src, b.src_stride, xd.predictor, 16, varRes, 16, 16);
         curr.rateBase = x.mbmode_cost.get(xd.frame_type).get(mode.ordinal());
         long this_rd = RDOpt.RDCOST(x.rdmult, x.rddiv, curr.rateBase, varRes.variance);
         if (error16x16 > this_rd) {
            error16x16 = this_rd;
            best_mode = mode;
            curr.distortion = varRes.sse;
            best_rate = curr.rateBase;
         }
      }

      mbmi.mode = best_mode;
      pick_intra4x4mby_modes(x, curr);
      if (curr.error < error16x16) {
         mbmi.mode = MBPredictionMode.B_PRED;
         best_rate = curr.rateBase;
      }

      return best_rate;
   }
}
