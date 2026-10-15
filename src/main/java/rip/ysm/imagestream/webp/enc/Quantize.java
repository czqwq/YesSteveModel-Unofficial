package rip.ysm.imagestream.webp.enc;

import java.util.EnumMap;

final class Quantize {
   static final Quant fastQuant = Quantize::vp8_fast_quantize_b;
   static final Quant regularQuant = Quantize::vp8_regular_quantize_b;
   static final int qrounding_factor = 48;
   static final EnumMap<CommonData.Quant, int[]> qzbin_factors = new EnumMap<>(CommonData.Quant.class);

   private Quantize() {
   }

   static void vp8_quantize_mby(Macroblock x) {
      quantizeblockRange(x, 0, 16);
      if (x.e_mbd.hasSecondOrder()) {
         quantizeblockRange(x, 24, 25);
      }
   }

   static void vp8_quantize_mbuv(Macroblock x) {
      quantizeblockRange(x, 16, 24);
   }

   private static void quantizeblockRange(Macroblock x, int st, int stop) {
      for (int i = st; i < stop; i++) {
         x.quantize_b.call(x.block.getRel(i), x.e_mbd.block.getRel(i));
      }
   }

   static void vp8cx_mb_init_quantizer(Compressor cpi, Macroblock x, boolean ok_to_skip) {
      MacroblockD xd = x.e_mbd;
      ModeInfo mi = x.e_mbd.mode_info_context.get();
      short QIndex;
      if (xd.segmentation_enabled != 0) {
         if (xd.mb_segement_abs_delta) {
            QIndex = xd.segment_feature_data[MBLvlFeatures.ALT_Q.ordinal()][mi.mbmi.segment_id];
         } else {
            QIndex = (short)(cpi.common.base_qindex + xd.segment_feature_data[MBLvlFeatures.ALT_Q.ordinal()][mi.mbmi.segment_id]);
            QIndex = CUtils.clamp(QIndex, (short)0, (short)127);
         }
      } else {
         QIndex = cpi.common.base_qindex;
      }

      if (!ok_to_skip || QIndex != x.q_index) {
         xd.dequant_y1_dc.set((short)1);
         xd.dequant_y1.set(cpi.common.dequant.get(CommonData.Quant.Y1)[QIndex].get());
         xd.dequant_y2.set(cpi.common.dequant.get(CommonData.Quant.Y2)[QIndex].get());
         xd.dequant_uv.set(cpi.common.dequant.get(CommonData.Quant.UV)[QIndex].get());

         for (int i = 1; i < 16; i++) {
            xd.dequant_y1_dc.setRel(i, xd.dequant_y1.setRel(i, cpi.common.dequant.get(CommonData.Quant.Y1)[QIndex].getRel(1)));
            xd.dequant_y2.setRel(i, cpi.common.dequant.get(CommonData.Quant.Y2)[QIndex].getRel(1));
            xd.dequant_uv.setRel(i, cpi.common.dequant.get(CommonData.Quant.UV)[QIndex].getRel(1));
         }

         for (int var7 = 0; var7 < 16; var7++) {
            x.e_mbd.block.getRel(var7).dequant = xd.dequant_y1;
         }

         for (int var8 = 16; var8 < 24; var8++) {
            x.e_mbd.block.getRel(var8).dequant = xd.dequant_uv;
         }

         x.e_mbd.block.getRel(24).dequant = xd.dequant_y2;
         vp8_update_zbin_extra(cpi, x);

         int var9;
         for (var9 = 0; var9 < 16; var9++) {
            cpi.q.get(CommonData.Quant.Y1).shallowCopyTo(x.block.getRel(var9), QIndex);
         }

         while (var9 < 24) {
            cpi.q.get(CommonData.Quant.UV).shallowCopyTo(x.block.getRel(var9), QIndex);
            var9++;
         }

         cpi.q.get(CommonData.Quant.Y2).shallowCopyTo(x.block.getRel(var9), QIndex);
         x.q_index = QIndex;
         x.last_zbin_over_quant = x.zbin_over_quant;
         x.last_zbin_mode_boost = x.zbin_mode_boost;
         x.last_act_zbin_adj = x.act_zbin_adj;
      } else if (x.last_zbin_over_quant != x.zbin_over_quant || x.last_zbin_mode_boost != x.zbin_mode_boost || x.last_act_zbin_adj != x.act_zbin_adj) {
         vp8_update_zbin_extra(cpi, x);
         x.last_zbin_over_quant = x.zbin_over_quant;
         x.last_zbin_mode_boost = x.zbin_mode_boost;
         x.last_act_zbin_adj = x.act_zbin_adj;
      }
   }

   static void vp8_update_zbin_extra(Compressor cpi, Macroblock x) {
      int QIndex = x.q_index;
      int zbin_extra = cpi.common.dequant.get(CommonData.Quant.Y1)[QIndex].getRel(1) * (x.zbin_over_quant + x.zbin_mode_boost + x.act_zbin_adj) >> 7;

      int i;
      for (i = 0; i < 16; i++) {
         x.block.getRel(i).zbin_extra = zbin_extra;
      }

      for (int var5 = cpi.common.dequant.get(CommonData.Quant.UV)[QIndex].getRel(1) * (x.zbin_over_quant + x.zbin_mode_boost + x.act_zbin_adj) >> 7;
         i < 24;
         i++
      ) {
         x.block.getRel(i).zbin_extra = var5;
      }

      zbin_extra = cpi.common.dequant.get(CommonData.Quant.Y2)[QIndex].getRel(1) * (x.zbin_over_quant / 2 + x.zbin_mode_boost + x.act_zbin_adj) >> 7;
      x.block.getRel(i).zbin_extra = zbin_extra;
   }

   static void vp8_fast_quantize_b(Block b, BlockD d) {
      GetPointer coeff_ptr = b.coeff;
      GetPointer round_ptr = b.round;
      GetPointer quant_ptr = b.quant_fast;
      FullGetSetPointer qcoeff_ptr = d.qcoeff;
      FullGetSetPointer dqcoeff_ptr = d.dqcoeff;
      GetPointer dequant_ptr = d.dequant;
      int eob = -1;

      for (int i = 0; i < 16; i++) {
         int rc = W.zigzag[i];
         int z = coeff_ptr.getRel(rc);
         int sz = z >> 31;
         int x = (z ^ sz) - sz;
         int y = (x + round_ptr.getRel(rc)) * quant_ptr.getRel(rc) >> 16;
         x = (y ^ sz) - sz;
         qcoeff_ptr.setRel(rc, (short)x);
         dqcoeff_ptr.setRel(rc, (short)(x * dequant_ptr.getRel(rc)));
         if (y != 0) {
            eob = i;
         }
      }

      d.eob.set((short)(eob + 1));
   }

   static void vp8_regular_quantize_b(Block b, BlockD d) {
      GetSetPointer zbin_boost_ptr = b.zrun_zbin_boost.positionableOnly();
      GetPointer coeff_ptr = b.coeff;
      GetPointer zbin_ptr = b.zbin;
      GetPointer round_ptr = b.round;
      GetPointer quant_ptr = b.quant_fast;
      FullGetSetPointer qcoeff_ptr = d.qcoeff;
      FullGetSetPointer dqcoeff_ptr = d.dqcoeff;
      GetPointer dequant_ptr = d.dequant;
      GetPointer quant_shift_ptr = b.quant_shift;
      int zbin_oq_value = b.zbin_extra;
      qcoeff_ptr.memset(0, (short)0, 16);
      dqcoeff_ptr.memset(0, (short)0, 16);
      int eob = -1;

      for (int i = 0; i < 16; i++) {
         int rc = W.zigzag[i];
         int z = coeff_ptr.getRel(rc);
         int zbin = zbin_ptr.getRel(rc) + zbin_boost_ptr.get() + zbin_oq_value;
         zbin_boost_ptr.inc();
         int sz = z >> 31;
         int x = (z ^ sz) - sz;
         if (x >= zbin) {
            x += round_ptr.getRel(rc);
            int y = ((x * quant_ptr.getRel(rc) >> 16) + x) * quant_shift_ptr.getRel(rc) >> 16;
            x = (y ^ sz) - sz;
            qcoeff_ptr.setRel(rc, (short)x);
            dqcoeff_ptr.setRel(rc, (short)(x * dequant_ptr.getRel(rc)));
            if (y != 0) {
               eob = i;
               zbin_boost_ptr.rewindToSaved();
            }
         }
      }

      d.eob.set((short)(eob + 1));
   }

   static void vp8cx_frame_init_quantizer(Compressor cpi) {
      cpi.mb.zbin_mode_boost = 0;
      vp8cx_mb_init_quantizer(cpi, cpi.mb, false);
   }

   static void invert_quant(boolean improved_quant, FullGetSetPointer quant, FullGetSetPointer shift, int d) {
      if (improved_quant) {
         int l = 32 - Integer.numberOfLeadingZeros(d);
         int m = 1 + (1 << 16 + l) / d;
         quant.set((short)(m - 65536 & 65535));
         shift.set((short)l);
         shift.set((short)(1 << 16 - shift.get()));
      } else {
         quant.set((short)(65536 / d));
         shift.set((short)0);
      }
   }

   static void vp8cx_init_quantizer(Compressor cpi) {
      short[] zbin_boost = new short[]{0, 0, 8, 10, 12, 14, 16, 20, 24, 28, 32, 36, 40, 44, 44, 44};

      for (short Q = 0; Q < 128; Q++) {
         for (CommonData.Quant qenum : CommonData.Quant.values()) {
            QuantDetails q = cpi.q.get(qenum);

            for (CommonData.Comp comp : CommonData.Comp.values()) {
               short quant_val = QuantCommon.lookup.get(qenum).get(comp).call(Q, cpi.common.delta_q.get(qenum).get(comp));
               q.quant_fast[Q].setRel(comp.baseIndex, (short)(65536 / quant_val));
               invert_quant(
                  cpi.sf.improved_quant, q.quant[Q].shallowCopyWithPosInc(comp.baseIndex), q.quant_shift[Q].shallowCopyWithPosInc(comp.baseIndex), quant_val
               );
               q.zbin[Q].setRel(comp.baseIndex, (short)(qzbin_factors.get(qenum)[Q] * quant_val + 64 >> 7));
               q.round[Q].setRel(comp.baseIndex, (short)(48 * quant_val >> 7));
               cpi.common.dequant.get(qenum)[Q].setRel(comp.baseIndex, quant_val);
               q.zrun_zbin_boost[Q].setRel(comp.baseIndex, (short)(quant_val * zbin_boost[comp.baseIndex] >> 7));
            }

            q.quant_fast[Q].memset(2, q.quant_fast[Q].getRel(1), 14);
            q.quant[Q].memset(2, q.quant[Q].getRel(1), 14);
            q.quant_shift[Q].memset(2, q.quant_shift[Q].getRel(1), 14);
            q.zbin[Q].memset(2, q.zbin[Q].getRel(1), 14);
            q.round[Q].memset(2, q.round[Q].getRel(1), 14);

            for (int j = 2; j < q.zrun_zbin_boost[Q].size(); j++) {
               q.zrun_zbin_boost[Q].setRel(j, (short)(cpi.common.dequant.get(qenum)[Q].getRel(1) * zbin_boost[j] >> 7));
            }
         }
      }
   }

   static void vp8_set_quantizer(Compressor cpi, short Q) {
      CommonData cm = cpi.common;
      MacroblockD mbd = cpi.mb.e_mbd;
      boolean update = false;
      cm.base_qindex = Q;
      cm.delta_q.get(CommonData.Quant.Y1).put(CommonData.Comp.DC, (short)0);
      cm.delta_q.get(CommonData.Quant.Y2).put(CommonData.Comp.AC, (short)0);
      short new_delta_q;
      if (Q < 4) {
         new_delta_q = (short)(4 - Q);
      } else {
         new_delta_q = 0;
      }

      update |= cm.delta_q.get(CommonData.Quant.Y2).get(CommonData.Comp.DC) != new_delta_q;
      cm.delta_q.get(CommonData.Quant.Y2).put(CommonData.Comp.DC, new_delta_q);
      short new_uv_delta_q = 0;
      if (cpi.oxcf.screen_content_mode != 0 && Q > 40) {
         new_uv_delta_q = (short)(-0.15 * Q);
         if (new_uv_delta_q < -15) {
            new_uv_delta_q = -15;
         }
      }

      update |= cm.delta_q.get(CommonData.Quant.UV).get(CommonData.Comp.DC) != new_uv_delta_q;
      cm.delta_q.get(CommonData.Quant.UV).put(CommonData.Comp.DC, new_uv_delta_q);
      cm.delta_q.get(CommonData.Quant.UV).put(CommonData.Comp.AC, new_uv_delta_q);
      mbd.segment_feature_data[MBLvlFeatures.ALT_Q.ordinal()][0] = cpi.segment_feature_data[MBLvlFeatures.ALT_Q.ordinal()][0];
      mbd.segment_feature_data[MBLvlFeatures.ALT_Q.ordinal()][1] = cpi.segment_feature_data[MBLvlFeatures.ALT_Q.ordinal()][1];
      mbd.segment_feature_data[MBLvlFeatures.ALT_Q.ordinal()][2] = cpi.segment_feature_data[MBLvlFeatures.ALT_Q.ordinal()][2];
      mbd.segment_feature_data[MBLvlFeatures.ALT_Q.ordinal()][3] = cpi.segment_feature_data[MBLvlFeatures.ALT_Q.ordinal()][3];
      if (update) {
         vp8cx_init_quantizer(cpi);
      }
   }

   static {
      int[] factors = new int[129];

      for (int i = 0; i < 129; i++) {
         factors[i] = i < 48 ? 84 : 80;
      }

      qzbin_factors.put(CommonData.Quant.Y1, factors);
      qzbin_factors.put(CommonData.Quant.Y2, factors);
      qzbin_factors.put(CommonData.Quant.UV, factors);
   }

   interface Quant {
      void call(Block var1, BlockD var2);
   }
}
