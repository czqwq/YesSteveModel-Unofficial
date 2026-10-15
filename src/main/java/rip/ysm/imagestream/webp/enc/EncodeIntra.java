package rip.ysm.imagestream.webp.enc;

final class EncodeIntra {
   private EncodeIntra() {
   }

   static void vp8_encode_intra4x4block(Macroblock x, int ib) {
      BlockD b = x.e_mbd.block.getRel(ib);
      Block be = x.block.getRel(ib);
      int dst_stride = x.e_mbd.dst.y_stride;
      FullGetSetPointer dst = b.getOffsetPointer(x.e_mbd.dst.y_buffer);
      GetSetPointer Above = GetSetPointer.makePositionableAndInc(dst, -dst_stride);
      GetSetPointer yleft = GetSetPointer.makePositionableAndInc(dst, -1);
      short top_left = Above.getRel(-1);
      x.recon.vp8_intra4x4_predict(Above, yleft, dst_stride, b.bmi.as_mode(), b.predictor, 16, top_left);
      EncodeMB.vp8_subtract_b(be, b);
      x.short_fdct4x4.call(be.src_diff, be.coeff, 32);
      x.quantize_b.call(be, b);
      if (b.eob.get() > 1) {
         IDCTllm.vp8_short_idct4x4llm(b.dqcoeff, b.predictor, 16, dst, dst_stride);
      } else {
         IDCTllm.vp8_dc_only_idct_add(b.dqcoeff.get(), b.predictor, 16, dst, dst_stride);
      }
   }

   static void vp8_encode_intra4x4mby(Macroblock mb) {
      ReconIntra.intra_prediction_down_copy(mb.e_mbd);

      for (int i = 0; i < 16; i++) {
         vp8_encode_intra4x4block(mb, i);
      }
   }

   static void vp8_encode_intra16x16mby(Macroblock x) {
      Block b = x.block.get();
      MacroblockD xd = x.e_mbd;
      GetSetPointer above = GetSetPointer.makePositionableAndInc(xd.dst.y_buffer, -xd.dst.y_stride);
      GetSetPointer left = GetSetPointer.makePositionableAndInc(xd.dst.y_buffer, -1);
      x.recon.vp8_build_intra_predictors_mby_s(xd, above, left, xd.dst.y_stride, xd.dst.y_buffer, xd.dst.y_stride);
      EncodeMB.vp8_subtract_mby(x.src_diff, b.base_src, b.src_stride, xd.dst.y_buffer, xd.dst.y_stride);
      EncodeMB.vp8_transform_intra_mby(x);
      Quantize.vp8_quantize_mby(x);
      if (x.optimize) {
         EncodeMB.vp8_optimize_mby(x);
      }
   }

   static void vp8_encode_intra16x16mbuv(Macroblock x) {
      MacroblockD xd = x.e_mbd;
      GetSetPointer uab = GetSetPointer.makePositionableAndInc(xd.dst.u_buffer, -xd.dst.uv_stride);
      GetSetPointer vab = GetSetPointer.makePositionableAndInc(xd.dst.v_buffer, -xd.dst.uv_stride);
      GetSetPointer ulef = GetSetPointer.makePositionableAndInc(xd.dst.u_buffer, -1);
      GetSetPointer vlef = GetSetPointer.makePositionableAndInc(xd.dst.v_buffer, -1);
      x.recon.vp8_build_intra_predictors_mbuv_s(xd, uab, vab, ulef, vlef, xd.dst.uv_stride, xd.dst.u_buffer, xd.dst.v_buffer, xd.dst.uv_stride);
      EncodeMB.vp8_subtract_mbuv(x.src_diff, x.src.u_buffer, x.src.v_buffer, x.src.uv_stride, xd.dst.u_buffer, xd.dst.v_buffer, xd.dst.uv_stride);
      EncodeMB.vp8_transform_mbuv(x);
      Quantize.vp8_quantize_mbuv(x);
      if (x.optimize) {
         EncodeMB.vp8_optimize_mbuv(x);
      }
   }
}
