package rip.ysm.imagestream.webp.enc;

final class InvTrans {
   private InvTrans() {
   }

   static void eob_adjust(FullGetSetPointer eobs, GetSetPointer diff) {
      diff.savePos();

      for (int js = 0; js < 16; js++) {
         if (eobs.getRel(js) == 0 && diff.get() != 0) {
            eobs.setRel(js, (short)1);
         }

         diff.incBy(16);
      }

      diff.rewindToSaved();
   }

   static void vp8_inverse_transform_mby(MacroblockD xd) {
      GetPointer DQC;
      if (xd.mode_info_context.get().mbmi.mode != MBPredictionMode.SPLITMV) {
         if (xd.eobs.getRel(24) > 1) {
            IDCTllm.vp8_short_inv_walsh4x4(xd.block.getRel(24).dqcoeff, xd.qcoeff);
         } else {
            IDCTllm.vp8_short_inv_walsh4x4_1(xd.block.getRel(24).dqcoeff, xd.qcoeff);
         }

         eob_adjust(xd.eobs, xd.qcoeff);
         DQC = xd.dequant_y1_dc;
      } else {
         DQC = xd.dequant_y1;
      }

      IDCTBlk.vp8_dequant_idct_add_y_block(xd.qcoeff, DQC, xd.dst.y_buffer, xd.dst.y_stride, xd.eobs);
   }
}
