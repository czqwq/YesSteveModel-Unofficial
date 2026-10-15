package rip.ysm.imagestream.webp.enc;

final class TemporalFilter {
   private static final int THRESH_LOW = 10000;
   private static final int THRESH_HIGH = 20000;

   private TemporalFilter() {
   }

   static void vp8_temporal_filter_predictors_mb(
      MacroblockD x,
      FullGetSetPointer y_mb_ptr,
      FullGetSetPointer u_mb_ptr,
      FullGetSetPointer v_mb_ptr,
      int stride,
      int mv_row,
      int mv_col,
      FullGetSetPointer pred
   ) {
      FullGetSetPointer yptr = y_mb_ptr.shallowCopyWithPosInc((mv_row >> 3) * stride + (mv_col >> 3));
      if (((mv_row | mv_col) & 7) != 0) {
         x.subpixel_predict16x16.call(yptr, stride, mv_col & 7, mv_row & 7, pred, 16);
      } else {
         CUtils.vp8_copy_mem16x16(yptr, stride, pred, 16);
      }

      mv_row >>= 1;
      mv_col >>= 1;
      stride = stride + 1 >> 1;
      int offset = (mv_row >> 3) * stride + (mv_col >> 3);
      FullGetSetPointer uptr = u_mb_ptr.shallowCopyWithPosInc(offset);
      FullGetSetPointer vptr = v_mb_ptr.shallowCopyWithPosInc(offset);
      if (((mv_row | mv_col) & 7) != 0) {
         x.subpixel_predict8x8.call(uptr, stride, mv_col & 7, mv_row & 7, pred.shallowCopyWithPosInc(256), 8);
         x.subpixel_predict8x8.call(vptr, stride, mv_col & 7, mv_row & 7, pred.shallowCopyWithPosInc(320), 8);
      } else {
         CUtils.vp8_copy_mem8x8(uptr, stride, pred.shallowCopyWithPosInc(256));
         CUtils.vp8_copy_mem8x8(vptr, stride, pred.shallowCopyWithPosInc(320));
      }
   }

   static void vp8_temporal_filter_apply(
      FullGetSetPointer frame1,
      int stride,
      FullGetSetPointer frame2,
      int block_size,
      int strength,
      int filter_weight,
      FullGetSetPointer accumulator,
      FullGetSetPointer count
   ) {
      int byt = 0;
      frame2 = frame2.shallowCopy();
      int rounding = strength > 0 ? 1 << strength - 1 : 0;
      int i = 0;

      for (int k = 0; i < block_size; i++) {
         for (int j = 0; j < block_size; k++) {
            int src_byte = frame1.getRel(byt);
            int pixel_value = frame2.getAndInc();
            int modifier = src_byte - pixel_value;
            modifier *= modifier;
            modifier *= 3;
            modifier += rounding;
            modifier >>= strength;
            if (modifier > 16) {
               modifier = 16;
            }

            modifier = 16 - modifier;
            modifier *= filter_weight;
            count.setRel(k, (short)(count.getRel(k) + modifier));
            accumulator.setRel(k, (short)(accumulator.getRel(k) + modifier * pixel_value));
            byt++;
            j++;
         }

         byt += stride - block_size;
      }
   }

   static long vp8_temporal_filter_find_matching_mb(Compressor cpi, YV12buffer arf_frame, YV12buffer frame_ptr, int mb_offset) {
      Macroblock x = cpi.mb;
      Block b = x.block.get();
      BlockD d = x.e_mbd.block.get();
      MV best_ref_mv1 = new MV();
      FullGetSetPointer base_src = b.base_src;
      int src = b.src;
      int src_stride = b.src_stride;
      FullGetSetPointer base_pre = x.e_mbd.pre.y_buffer.shallowCopy();
      int pre = d.getOffset();
      int pre_stride = x.e_mbd.pre.y_stride;
      b.base_src = arf_frame.y_buffer.shallowCopy();
      b.src_stride = arf_frame.y_stride;
      b.src = mb_offset;
      x.e_mbd.pre.y_buffer = frame_ptr.y_buffer.shallowCopy();
      x.e_mbd.pre.y_stride = frame_ptr.y_stride;
      d.setOffset(mb_offset);
      VarianceResults res = new VarianceResults();
      long bestsme = cpi.find_fractional_mv_step.call(x, b, d, d.bmi.mv, best_ref_mv1, x.errorperbit, cpi.fn_ptr.get(4), null, res);
      b.base_src = base_src;
      b.src = src;
      b.src_stride = src_stride;
      x.e_mbd.pre.y_buffer = base_pre;
      d.setOffset(pre);
      x.e_mbd.pre.y_stride = pre_stride;
      return bestsme;
   }

   static void vp8_temporal_filter_iterate(Compressor cpi, int frame_count, int alt_ref_index, int strength) {
      int mb_cols = cpi.common.mb_cols;
      int mb_rows = cpi.common.mb_rows;
      int mb_y_offset = 0;
      int mb_uv_offset = 0;
      FullGetSetPointer accumulator = new FullGetSetPointer(384);
      FullGetSetPointer count = new FullGetSetPointer(384);
      MacroblockD mbd = cpi.mb.e_mbd;
      YV12buffer f = cpi.frames[alt_ref_index];
      FullGetSetPointer predictor = new FullGetSetPointer(384);
      FullGetSetPointer y_buffer = mbd.pre.y_buffer.shallowCopy();
      FullGetSetPointer u_buffer = mbd.pre.u_buffer.shallowCopy();
      FullGetSetPointer v_buffer = mbd.pre.v_buffer.shallowCopy();

      for (int mb_row = 0; mb_row < mb_rows; mb_row++) {
         cpi.mb.mv_row_min = (short)(-(mb_row * 16 + 11));
         cpi.mb.mv_row_max = (short)((cpi.common.mb_rows - 1 - mb_row) * 16 + 11);

         for (int mb_col = 0; mb_col < mb_cols; mb_col++) {
            CUtils.vp8_zero(accumulator);
            CUtils.vp8_zero(count);
            cpi.mb.mv_col_min = (short)(-(mb_col * 16 + 11));
            cpi.mb.mv_col_max = (short)((cpi.common.mb_cols - 1 - mb_col) * 16 + 11);

            for (int frame = 0; frame < frame_count; frame++) {
               if (cpi.frames[frame] != null) {
                  mbd.block.get().bmi.mv.setZero();
                  int filter_weight;
                  if (frame == alt_ref_index) {
                     filter_weight = 2;
                  } else {
                     long err = vp8_temporal_filter_find_matching_mb(cpi, cpi.frames[alt_ref_index], cpi.frames[frame], mb_y_offset);
                     filter_weight = err < 10000L ? 2 : (err < 20000L ? 1 : 0);
                  }

                  if (filter_weight != 0) {
                     vp8_temporal_filter_predictors_mb(
                        mbd,
                        cpi.frames[frame].y_buffer.shallowCopyWithPosInc(mb_y_offset),
                        cpi.frames[frame].u_buffer.shallowCopyWithPosInc(mb_uv_offset),
                        cpi.frames[frame].v_buffer.shallowCopyWithPosInc(mb_uv_offset),
                        cpi.frames[frame].y_stride,
                        mbd.block.get().bmi.mv.row,
                        mbd.block.get().bmi.mv.col,
                        predictor
                     );
                     vp8_temporal_filter_apply(
                        f.y_buffer.shallowCopyWithPosInc(mb_y_offset), f.y_stride, predictor, 16, strength, filter_weight, accumulator, count
                     );
                     vp8_temporal_filter_apply(
                        f.u_buffer.shallowCopyWithPosInc(mb_uv_offset),
                        f.uv_stride,
                        predictor.shallowCopyWithPosInc(256),
                        8,
                        strength,
                        filter_weight,
                        accumulator.shallowCopyWithPosInc(256),
                        count.shallowCopyWithPosInc(256)
                     );
                     vp8_temporal_filter_apply(
                        f.v_buffer.shallowCopyWithPosInc(mb_uv_offset),
                        f.uv_stride,
                        predictor.shallowCopyWithPosInc(320),
                        8,
                        strength,
                        filter_weight,
                        accumulator.shallowCopyWithPosInc(320),
                        count.shallowCopyWithPosInc(320)
                     );
                  }
               }
            }

            FullGetSetPointer dst1 = cpi.alt_ref_buffer.y_buffer;
            int stride = cpi.alt_ref_buffer.y_stride;
            int byt = mb_y_offset;
            int i = 0;

            for (int k = 0; i < 16; i++) {
               for (int j = 0; j < 16; k++) {
                  int pval = accumulator.getRel(k) + (count.getRel(k) >> 1);
                  pval *= cpi.fixed_divide[count.getRel(k)];
                  pval >>= 19;
                  dst1.setRel(byt, (short)pval);
                  byt++;
                  j++;
               }

               byt += stride - 16;
            }

            dst1 = cpi.alt_ref_buffer.u_buffer;
            FullGetSetPointer dst2 = cpi.alt_ref_buffer.v_buffer;
            stride = cpi.alt_ref_buffer.uv_stride;
            byt = mb_uv_offset;
            i = 0;

            for (int var32 = 256; i < 8; i++) {
               for (int j = 0; j < 8; var32++) {
                  int m = var32 + 64;
                  int pval = accumulator.getRel(var32) + (count.getRel(var32) >> 1);
                  pval *= cpi.fixed_divide[count.getRel(var32)];
                  pval >>= 19;
                  dst1.setRel(byt, (short)pval);
                  pval = accumulator.getRel(m) + (count.getRel(m) >> 1);
                  pval *= cpi.fixed_divide[count.getRel(m)];
                  pval >>= 19;
                  dst2.setRel(byt, (short)pval);
                  byt++;
                  j++;
               }

               byt += stride - 8;
            }

            mb_y_offset += 16;
            mb_uv_offset += 8;
         }

         mb_y_offset += 16 * (f.y_stride - mb_cols);
         mb_uv_offset += 8 * (f.uv_stride - mb_cols);
      }

      mbd.pre.y_buffer = y_buffer;
      mbd.pre.u_buffer = u_buffer;
      mbd.pre.v_buffer = v_buffer;
   }

   static void vp8_temporal_filter_prepare(Compressor cpi, int distance) {
      int frames_to_blur_backward = 0;
      int frames_to_blur_forward = 0;
      int strength = cpi.oxcf.arnr_strength;
      int blur_type = cpi.oxcf.arnr_type;
      int max_frames = 0;
      int num_frames_forward = cpi.lookahead.vp8_lookahead_depth() - (distance + 1);

      int frames_to_blur = switch (blur_type) {
         case 1 -> {
            frames_to_blur_backward = distance;
            if (distance >= 0) {
               frames_to_blur_backward = -1;
            }

            yield frames_to_blur_backward + 1;
         }
         case 2 -> {
            frames_to_blur_forward = num_frames_forward;
            if (num_frames_forward >= 0) {
               frames_to_blur_forward = -1;
            }

            yield frames_to_blur_forward + 1;
         }
         default -> {
            frames_to_blur_forward = num_frames_forward;
            frames_to_blur_backward = distance;
            if (num_frames_forward > distance) {
               frames_to_blur_forward = distance;
            }

            if (distance > frames_to_blur_forward) {
               frames_to_blur_backward = frames_to_blur_forward;
            }

            if (frames_to_blur_forward > 0) {
               frames_to_blur_forward = 0;
            }

            if (frames_to_blur_backward > 0) {
               frames_to_blur_backward = 0;
            }

            yield frames_to_blur_backward + frames_to_blur_forward + 1;
         }
      };
      int start_frame = distance + frames_to_blur_forward;

      for (int frame = 0; frame < frames_to_blur; frame++) {
         int which_buffer = start_frame - frame;
         LookaheadEntry buf = cpi.lookahead.vp8_lookahead_peek(which_buffer, 1);
         cpi.frames[frames_to_blur - 1 - frame] = buf.img;
      }

      vp8_temporal_filter_iterate(cpi, frames_to_blur, frames_to_blur_backward, strength);
   }
}
