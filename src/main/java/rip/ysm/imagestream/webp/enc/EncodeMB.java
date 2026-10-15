package rip.ysm.imagestream.webp.enc;

final class EncodeMB {
   private EncodeMB() {
   }

   static void vpx_subtract_block(
      int rows, int cols, FullGetSetPointer diff_ptr, int diff_stride, GetPointer src_ptr, int src_stride, GetPointer pred_ptr, int pred_stride
   ) {
      for (int r = 0; r < rows; r++) {
         int c = 0;
         int diff = r * diff_stride;
         int src = r * src_stride;

         for (int pred = r * pred_stride; c < cols; pred++) {
            diff_ptr.setRel(diff, (short)(src_ptr.getRel(src) - pred_ptr.getRel(pred)));
            c++;
            diff++;
            src++;
         }
      }
   }

   static void vp8_subtract_b(Block be, BlockD bd) {
      vpx_subtract_block(4, 4, be.src_diff, 16, be.getSrcPtr(), be.src_stride, bd.predictor, 16);
   }

   static void vp8_subtract_mby(FullGetSetPointer diff, GetPointer src, int src_stride, GetPointer pred, int pred_stride) {
      vpx_subtract_block(16, 16, diff, 16, src, src_stride, pred, pred_stride);
   }

   static void build_dcblock(Macroblock x) {
      int i = 0;

      for (int j = 384; i < 16; j++) {
         x.src_diff.setAbs(j, x.coeff.getRel(i << 4));
         i++;
      }
   }

   static void vp8_transform_intra_mby(Macroblock x) {
      for (int i = 0; i < 16; i += 2) {
         x.short_fdct8x4.call(x.block.getRel(i).src_diff, x.block.getRel(i).coeff, 32);
      }

      build_dcblock(x);
      x.short_walsh4x4.call(x.block.getRel(24).src_diff, x.block.getRel(24).coeff, 8);
   }

   static int RDCOST(int RM, int DM, int R, int D) {
      return (128 + R * RM >> 8) + DM * D;
   }

   static int RDTRUNC(int RM, int R) {
      return 128 + R * RM & 0xFF;
   }

   static void optimize_b(Macroblock mb, int ib, PlaneType type, FullGetSetPointer a, FullGetSetPointer l) {
      TokenState[][] tokens = new TokenState[17][2];
      int sz = 0;
      int err_mult = type.rd_mult;
      Block b = mb.block.getRel(ib);
      BlockD d = mb.e_mbd.block.getRel(ib);
      GetPointer dequant_ptr = d.dequant.readOnly();
      GetPointer coeff_ptr = b.coeff;
      FullGetSetPointer qcoeff_ptr = d.qcoeff.shallowCopy();
      FullGetSetPointer dqcoeff_ptr = d.dqcoeff.shallowCopy();
      int i0 = type.start_coeff;
      int eob = d.eob.get();
      int rdmult = mb.rdmult * err_mult;
      if (mb.e_mbd.mode_info_context.get().mbmi.ref_frame == MVReferenceFrame.INTRA_FRAME) {
         rdmult = rdmult * 9 >> 4;
      }

      int rddiv = mb.rddiv;
      int[] best_mask = new int[]{0, 0};
      tokens[eob][0] = new TokenState(0, 0, 16, TokenAlphabet.DCT_EOB_TOKEN, 0);
      tokens[eob][1] = new TokenState(tokens[eob][0]);
      int next = eob;
      int i = eob;

      while (i-- > i0) {
         int rc = W.zigzag[i];
         int x = qcoeff_ptr.getRel(rc);
         if (x != 0) {
            boolean shortcut = false;
            int error0 = tokens[next][0].error;
            int error1 = tokens[next][1].error;
            int rate0 = tokens[next][0].rate;
            int rate1 = tokens[next][1].rate;
            TokenAlphabet t0 = DCTValueConstants.getTokenValue(x).token;
            if (next < 16) {
               int band = W.vp8CoefBands[i + 1];
               rate0 += mb.token_costs[type.ordinal()][band][t0.prevTokenClass][tokens[next][0].token.ordinal()];
               rate1 += mb.token_costs[type.ordinal()][band][t0.prevTokenClass][tokens[next][1].token.ordinal()];
            }

            int rd_cost0 = RDCOST(rdmult, rddiv, rate0, error0);
            int rd_cost1 = RDCOST(rdmult, rddiv, rate1, error1);
            if (rd_cost0 == rd_cost1) {
               rd_cost0 = RDTRUNC(rdmult, rate0);
               rd_cost1 = RDTRUNC(rdmult, rate1);
            }

            int best = rd_cost1 < rd_cost0 ? 1 : 0;
            int base_bits = DCTValueConstants.getValueCost(x);
            int dx = dqcoeff_ptr.getRel(rc) - coeff_ptr.getRel(rc);
            int d2 = dx * dx;
            tokens[i][0] = new TokenState(base_bits + (best > 0 ? rate1 : rate0), d2 + (best > 0 ? error1 : error0), next, t0, x);
            best_mask[0] |= best << i;
            rate0 = tokens[next][0].rate;
            rate1 = tokens[next][1].rate;
            if (Math.abs(x) * dequant_ptr.getRel(rc) > Math.abs(coeff_ptr.getRel(rc))
               && Math.abs(x) * dequant_ptr.getRel(rc) < Math.abs(coeff_ptr.getRel(rc)) + dequant_ptr.getRel(rc)) {
               sz = x < 0 ? -1 : 0;
               x -= 2 * sz + 1;
               shortcut = true;
            }

            TokenAlphabet t1;
            if (x == 0) {
               t0 = tokens[next][0].token == TokenAlphabet.DCT_EOB_TOKEN ? TokenAlphabet.DCT_EOB_TOKEN : TokenAlphabet.ZERO_TOKEN;
               t1 = tokens[next][1].token == TokenAlphabet.DCT_EOB_TOKEN ? TokenAlphabet.DCT_EOB_TOKEN : TokenAlphabet.ZERO_TOKEN;
            } else {
               t0 = t1 = DCTValueConstants.getTokenValue(x).token;
            }

            if (next < 16) {
               int band = W.vp8CoefBands[i + 1];
               if (t0 != TokenAlphabet.DCT_EOB_TOKEN) {
                  rate0 += mb.token_costs[type.ordinal()][band][t0.prevTokenClass][tokens[next][0].token.ordinal()];
               }

               if (t1 != TokenAlphabet.DCT_EOB_TOKEN) {
                  rate1 += mb.token_costs[type.ordinal()][band][t1.prevTokenClass][tokens[next][1].token.ordinal()];
               }
            }

            rd_cost0 = RDCOST(rdmult, rddiv, rate0, error0);
            rd_cost1 = RDCOST(rdmult, rddiv, rate1, error1);
            if (rd_cost0 == rd_cost1) {
               rd_cost0 = RDTRUNC(rdmult, rate0);
               rd_cost1 = RDTRUNC(rdmult, rate1);
            }

            best = rd_cost1 < rd_cost0 ? 1 : 0;
            base_bits = DCTValueConstants.getValueCost(x);
            if (shortcut) {
               dx -= dequant_ptr.getRel(rc) + sz ^ sz;
               d2 = dx * dx;
            }

            tokens[i][1] = new TokenState(base_bits + (best > 0 ? rate1 : rate0), d2 + (best > 0 ? error1 : error0), next, best > 0 ? t1 : t0, x);
            best_mask[1] |= best << i;
            next = i;
         } else {
            nonZero(mb, type, tokens, next, i);
         }
      }

      optimizeBsecond(mb, type, a, l, tokens, next, rdmult, i, d, dequant_ptr, qcoeff_ptr, dqcoeff_ptr, i0, eob, rddiv, best_mask);
   }

   private static void nonZero(Macroblock mb, PlaneType type, TokenState[][] tokens, int next, int i) {
      int band = W.vp8CoefBands[i + 1];
      TokenAlphabet t0 = tokens[next][0].token;
      TokenAlphabet t1 = tokens[next][1].token;
      if (t0 != TokenAlphabet.DCT_EOB_TOKEN) {
         tokens[next][0].rate = tokens[next][0].rate + mb.token_costs[type.ordinal()][band][0][t0.ordinal()];
         tokens[next][0].token = TokenAlphabet.ZERO_TOKEN;
      }

      if (t1 != TokenAlphabet.DCT_EOB_TOKEN) {
         tokens[next][1].rate = tokens[next][1].rate + mb.token_costs[type.ordinal()][band][0][t1.ordinal()];
         tokens[next][1].token = TokenAlphabet.ZERO_TOKEN;
      }
   }

   private static void optimizeBsecond(
      Macroblock mb,
      PlaneType type,
      FullGetSetPointer a,
      FullGetSetPointer l,
      TokenState[][] tokens,
      int next,
      int rdmult,
      int i,
      BlockD d,
      GetPointer dequant_ptr,
      FullGetSetPointer qcoeff_ptr,
      FullGetSetPointer dqcoeff_ptr,
      int i0,
      int eob,
      int rddiv,
      int[] best_mask
   ) {
      int band = W.vp8CoefBands[i + 1];
      int pt = a.get() + l.get();
      int rate0 = tokens[next][0].rate;
      int rate1 = tokens[next][1].rate;
      int error0 = tokens[next][0].error;
      int error1 = tokens[next][1].error;
      rate0 += mb.token_costs[type.ordinal()][band][pt][tokens[next][0].token.ordinal()];
      rate1 += mb.token_costs[type.ordinal()][band][pt][tokens[next][1].token.ordinal()];
      int rd_cost0 = RDCOST(rdmult, rddiv, rate0, error0);
      int rd_cost1 = RDCOST(rdmult, rddiv, rate1, error1);
      if (rd_cost0 == rd_cost1) {
         rd_cost0 = RDTRUNC(rdmult, rate0);
         rd_cost1 = RDTRUNC(rdmult, rate1);
      }

      int best = rd_cost1 < rd_cost0 ? 1 : 0;
      int final_eob = i0 - 1;
      i = next;

      while (i < eob) {
         int x = tokens[i][best].qc;
         if (x != 0) {
            final_eob = i;
         }

         int rc = W.zigzag[i];
         qcoeff_ptr.setRel(rc, (short)x);
         dqcoeff_ptr.setRel(rc, (short)(x * dequant_ptr.getRel(rc)));
         next = tokens[i][best].next;
         best = best_mask[best] >> i & 1;
         i = next;
      }

      final_eob++;
      a.set(l.set((short)(final_eob != type.start_coeff ? 1 : 0)));
      d.eob.set((short)final_eob);
   }

   public static void check_reset_2nd_coeffs(MacroblockD x, PlaneType type, FullGetSetPointer a, FullGetSetPointer l) {
      int sum = 0;
      BlockD bd = x.block.getRel(24);
      if (bd.dequant.get() < 35 || bd.dequant.get() < 35) {
         for (int i = 0; i < bd.eob.get(); i++) {
            int coef = bd.dqcoeff.getRel(W.zigzag[i]);
            sum += coef >= 0 ? coef : -coef;
            if (sum >= 35) {
               return;
            }
         }

         for (int var8 = 0; var8 < bd.eob.get(); var8++) {
            int rc = W.zigzag[var8];
            bd.qcoeff.setRel(rc, (short)0);
            bd.dqcoeff.setRel(rc, (short)0);
         }

         bd.eob.set((short)0);
         a.set(l.set((short)(bd.eob.get() != type.start_coeff ? 1 : 0)));
      }
   }

   static void vp8_optimize_mby(Macroblock x) {
      if (x.e_mbd.above_context != null && x.e_mbd.left_context != null) {
         EntropyPlanes t_above = new EntropyPlanes(x.e_mbd.above_context.get());
         EntropyPlanes t_left = new EntropyPlanes(x.e_mbd.left_context);
         FullGetSetPointer ta = t_above.panes;
         FullGetSetPointer tl = t_left.panes;
         boolean has_2nd_order = x.e_mbd.hasSecondOrder();
         PlaneType type = has_2nd_order ? PlaneType.Y_NO_DC : PlaneType.Y_WITH_DC;

         for (int b = 0; b < 16; b++) {
            ta.setPos(BlockD.vp8_block2above[b]);
            tl.setPos(BlockD.vp8_block2left[b]);
            optimize_b(x, b, type, ta, tl);
         }

         if (has_2nd_order) {
            int var8 = 24;
            ta.setPos(BlockD.vp8_block2above[var8]);
            tl.setPos(BlockD.vp8_block2left[var8]);
            optimize_b(x, var8, PlaneType.Y2, ta, tl);
            check_reset_2nd_coeffs(x.e_mbd, PlaneType.Y2, ta, tl);
         }
      }
   }

   static void vp8_subtract_mbuv(FullGetSetPointer diff, GetPointer usrc, GetPointer vsrc, int src_stride, GetPointer upred, GetPointer vpred, int pred_stride) {
      FullGetSetPointer udiff = diff.shallowCopyWithPosInc(256);
      FullGetSetPointer vdiff = diff.shallowCopyWithPosInc(320);
      vpx_subtract_block(8, 8, udiff, 8, usrc, src_stride, upred, pred_stride);
      vpx_subtract_block(8, 8, vdiff, 8, vsrc, src_stride, vpred, pred_stride);
   }

   static void vp8_transform_mbuv(Macroblock x) {
      for (int i = 16; i < 24; i += 2) {
         x.short_fdct8x4.call(x.block.getRel(i).src_diff, x.block.getRel(i).coeff, 16);
      }
   }

   static void vp8_optimize_mbuv(Macroblock x) {
      if (x.e_mbd.above_context.get() != null && x.e_mbd.left_context != null) {
         EntropyPlanes t_above = new EntropyPlanes(x.e_mbd.above_context.get());
         EntropyPlanes t_left = new EntropyPlanes(x.e_mbd.left_context);
         FullGetSetPointer ta = t_above.panes;
         FullGetSetPointer tl = t_left.panes;

         for (int b = 16; b < 24; b++) {
            ta.setPos(BlockD.vp8_block2above[b]);
            tl.setPos(BlockD.vp8_block2left[b]);
            optimize_b(x, b, PlaneType.UV, ta, tl);
         }
      }
   }
}
