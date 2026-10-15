package rip.ysm.imagestream.webp.enc;

class ReconIntra {
   final FullGetSetPointer aboveRow = new FullGetSetPointer(12);
   final FullGetSetPointer yLeftCol = new FullGetSetPointer(16);
   final FullGetSetPointer uLeftCol = new FullGetSetPointer(8);
   final FullGetSetPointer vLeftCol = new FullGetSetPointer(8);

   void vp8_build_intra_predictors_mby_s(MacroblockD x, GetPointer yAbove, GetPointer yLeft, int left_stride, FullGetSetPointer yPred, int y_stride) {
      MBPredictionMode mode = x.mode_info_context.get().mbmi.mode;

      for (int i = 0; i < 16; i++) {
         this.yLeftCol.setRel(i, yLeft.getRel(i * left_stride));
      }

      IntraPredFN fn;
      if (mode == MBPredictionMode.DC_PRED) {
         fn = AllIntraPred.dc_pred[x.left_available ? 1 : 0][x.up_available ? 1 : 0][0];
      } else {
         fn = AllIntraPred.pred[mode.ordinal()][0];
      }

      fn.call(yPred, y_stride, yAbove, this.yLeftCol);
   }

   void vp8_build_intra_predictors_mbuv_s(
      MacroblockD x,
      GetPointer uabove_row,
      GetPointer vabove_row,
      GetPointer uleft,
      GetPointer vleft,
      int left_stride,
      FullGetSetPointer upred,
      FullGetSetPointer vpred,
      int pred_stride
   ) {
      MBPredictionMode uvmode = x.mode_info_context.get().mbmi.uv_mode;

      for (int i = 0; i < 8; i++) {
         this.uLeftCol.setRel(i, uleft.getRel(i * left_stride));
         this.vLeftCol.setRel(i, vleft.getRel(i * left_stride));
      }

      IntraPredFN fn;
      if (uvmode == MBPredictionMode.DC_PRED) {
         fn = AllIntraPred.dc_pred[x.left_available ? 1 : 0][x.up_available ? 1 : 0][1];
      } else {
         fn = AllIntraPred.pred[uvmode.ordinal()][1];
      }

      fn.call(upred, pred_stride, uabove_row, this.uLeftCol);
      fn.call(vpred, pred_stride, vabove_row, this.vLeftCol);
   }

   void vp8_intra4x4_predict(GetPointer above, GetPointer yleft, int left_stride, int b_mode, FullGetSetPointer dst, int dst_stride, short top_left) {
      this.aboveRow.setPos(4);
      this.yLeftCol.rewind();
      this.yLeftCol.setAndInc(yleft.get());
      this.yLeftCol.setAndInc(yleft.getRel(left_stride));
      this.yLeftCol.setAndInc(yleft.getRel(left_stride << 1));
      this.yLeftCol.setAndInc(yleft.getRel(3 * left_stride));
      this.yLeftCol.rewind();
      this.aboveRow.memcopyin(0, above, 0, 8);
      this.aboveRow.setRel(-1, top_left);
      AllIntraPred.bpred[b_mode].call(dst, dst_stride, this.aboveRow, this.yLeftCol);
   }

   static void intra_prediction_down_copy(MacroblockD xd) {
      int srcLoc = -xd.dst.y_stride + 16;

      for (int i = 3; i < 12; i += 4) {
         xd.dst.y_buffer.memcopyin(i * xd.dst.y_stride + 16, xd.dst.y_buffer, srcLoc, 4);
      }
   }
}
