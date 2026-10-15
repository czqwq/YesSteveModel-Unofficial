package rip.ysm.imagestream.webp.enc;

final class PickLpf {
   private PickLpf() {
   }

   private static void yv12_copy_partial_frame(YV12buffer src_ybc, YV12buffer dst_ybc) {
      int yheight = src_ybc.y_height;
      int ystride = src_ybc.y_stride;
      int linestocopy = (yheight >> 4) / 8;
      linestocopy = linestocopy != 0 ? linestocopy << 4 : 16;
      linestocopy += 4;
      int yoffset = ystride * ((yheight >> 5) * 16 - 4);
      dst_ybc.y_buffer.memcopyin(yoffset, src_ybc.y_buffer, yoffset, ystride * linestocopy);
   }

   private static short get_min_filter_level(Compressor cpi, short base_qindex) {
      short min_filter_level;
      if (cpi.source_alt_ref_active && cpi.common.refresh_golden_frame && !cpi.common.refresh_alt_ref_frame) {
         min_filter_level = 0;
      } else if (base_qindex <= 6) {
         min_filter_level = 0;
      } else if (base_qindex <= 16) {
         min_filter_level = 1;
      } else {
         min_filter_level = (short)(base_qindex / 8);
      }

      return min_filter_level;
   }

   static long calc_partial_ssl_err(YV12buffer source, YV12buffer dest) {
      int srcoffset = source.y_stride * (dest.y_height >> 5) * 16;
      int dstoffset = dest.y_stride * (dest.y_height >> 5) * 16;
      int linestocopy = (source.y_height >> 4) / 8;
      linestocopy = linestocopy != 0 ? linestocopy << 4 : 16;
      FullGetSetPointer src = source.y_buffer.shallowCopyWithPosInc(srcoffset);
      FullGetSetPointer dst = dest.y_buffer.shallowCopyWithPosInc(dstoffset);
      return OnyxIf.vp8_calc_ss_err(src, source.y_stride, dst, dest.y_stride, linestocopy, source.y_width);
   }

   static void vp8cx_pick_filter_level_fast(YV12buffer sd, Compressor cpi) {
      CommonData cm = cpi.common;
      short min_filter_level = get_min_filter_level(cpi, cm.base_qindex);
      short max_filter_level = 63;
      YV12buffer saved_frame = cm.frame_to_show;
      cm.frame_to_show = cpi.pick_lf_lvl_frame;
      cm.sharpness_level = cm.frame_type == 0 ? 0 : cpi.oxcf.Sharpness;
      if (cm.sharpness_level != cm.last_sharpness_level) {
         cm.lf_info.vp8_loop_filter_update_sharpness(cm.sharpness_level);
         cm.last_sharpness_level = cm.sharpness_level;
      }

      if (cm.filter_level < min_filter_level) {
         cm.filter_level = min_filter_level;
      } else if (cm.filter_level > 63) {
         cm.filter_level = 63;
      }

      short filt_val = cm.filter_level;
      short best_filt_val = filt_val;
      yv12_copy_partial_frame(saved_frame, cm.frame_to_show);
      LoopFilter.vp8_loop_filter_partial_frame(cm, cpi.mb.e_mbd, filt_val);
      long best_err = calc_partial_ssl_err(sd, cm.frame_to_show);

      for (filt_val = (short)(filt_val - (1 + (filt_val > 10 ? 1 : 0)));
         filt_val >= min_filter_level;
         filt_val = (short)(filt_val - (1 + (filt_val > 10 ? 1 : 0)))
      ) {
         yv12_copy_partial_frame(saved_frame, cm.frame_to_show);
         LoopFilter.vp8_loop_filter_partial_frame(cm, cpi.mb.e_mbd, filt_val);
         long filt_err = calc_partial_ssl_err(sd, cm.frame_to_show);
         if (filt_err >= best_err) {
            break;
         }

         best_err = filt_err;
         best_filt_val = filt_val;
      }

      filt_val = (short)(cm.filter_level + 1 + (filt_val > 10 ? 1 : 0));
      if (best_filt_val == cm.filter_level) {
         for (long var12 = best_err - (best_err >> 10); filt_val < 63; filt_val = (short)(filt_val + 1 + (filt_val > 10 ? 1 : 0))) {
            yv12_copy_partial_frame(saved_frame, cm.frame_to_show);
            LoopFilter.vp8_loop_filter_partial_frame(cm, cpi.mb.e_mbd, filt_val);
            long filt_err = calc_partial_ssl_err(sd, cm.frame_to_show);
            if (filt_err >= var12) {
               break;
            }

            var12 = filt_err - (filt_err >> 10);
            best_filt_val = filt_val;
         }
      }

      cm.filter_level = best_filt_val;
      if (cm.filter_level < min_filter_level) {
         cm.filter_level = min_filter_level;
      }

      if (cm.filter_level > 63) {
         cm.filter_level = 63;
      }

      cm.frame_to_show = saved_frame;
   }

   static void vp8cx_set_alt_lf_level(Compressor cpi) {
      CUtils.vp8_copy(cpi.segment_feature_data[MBLvlFeatures.ALT_LF.ordinal()], cpi.mb.e_mbd.segment_feature_data[MBLvlFeatures.ALT_LF.ordinal()]);
   }

   static void vp8cx_pick_filter_level(YV12buffer sd, Compressor cpi) {
      CommonData cm = cpi.common;
      short min_filter_level = get_min_filter_level(cpi, cm.base_qindex);
      short max_filter_level = 63;
      int filt_direction = 0;
      long[] ss_err = new long[64];
      YV12buffer saved_frame = cm.frame_to_show;
      cm.frame_to_show = cpi.pick_lf_lvl_frame;
      if (cm.frame_type == 0) {
         cm.sharpness_level = 0;
      } else {
         cm.sharpness_level = cpi.oxcf.Sharpness;
      }

      short filt_mid = cm.filter_level;
      if (filt_mid < min_filter_level) {
         filt_mid = min_filter_level;
      } else if (filt_mid > 63) {
         filt_mid = 63;
      }

      short filter_step = (short)(filt_mid < 16 ? 4 : filt_mid / 4);
      YV12buffer.copyY(saved_frame, cm.frame_to_show);
      vp8cx_set_alt_lf_level(cpi);
      LoopFilter.vp8_loop_filter_frame_yonly(cm, cpi.mb.e_mbd, filt_mid);
      long best_err = OnyxIf.vp8_calc_ss_err(sd, cm.frame_to_show);
      ss_err[filt_mid] = best_err;
      short filt_best = filt_mid;

      while (filter_step > 0) {
         long Bias = (best_err >> 15 - filt_mid / 8) * filter_step;
         short filt_high = (short)(filt_mid + filter_step > 63 ? 63 : filt_mid + filter_step);
         short filt_low = (short)(filt_mid - filter_step < min_filter_level ? min_filter_level : filt_mid - filter_step);
         if (filt_direction <= 0 && filt_low != filt_mid) {
            long filt_err;
            if (ss_err[filt_low] == 0L) {
               YV12buffer.copyY(saved_frame, cm.frame_to_show);
               vp8cx_set_alt_lf_level(cpi);
               LoopFilter.vp8_loop_filter_frame_yonly(cm, cpi.mb.e_mbd, filt_low);
               filt_err = OnyxIf.vp8_calc_ss_err(sd, cm.frame_to_show);
               ss_err[filt_low] = filt_err;
            } else {
               filt_err = ss_err[filt_low];
            }

            if (filt_err - Bias < best_err) {
               if (filt_err < best_err) {
                  best_err = filt_err;
               }

               filt_best = filt_low;
            }
         }

         if (filt_direction >= 0 && filt_high != filt_mid) {
            long filt_errx;
            if (ss_err[filt_high] == 0L) {
               YV12buffer.copyY(saved_frame, cm.frame_to_show);
               vp8cx_set_alt_lf_level(cpi);
               LoopFilter.vp8_loop_filter_frame_yonly(cm, cpi.mb.e_mbd, filt_high);
               filt_errx = OnyxIf.vp8_calc_ss_err(sd, cm.frame_to_show);
               ss_err[filt_high] = filt_errx;
            } else {
               filt_errx = ss_err[filt_high];
            }

            if (filt_errx < best_err - Bias) {
               best_err = filt_errx;
               filt_best = filt_high;
            }
         }

         if (filt_best == filt_mid) {
            filter_step = (short)(filter_step / 2);
            filt_direction = 0;
         } else {
            filt_direction = filt_best < filt_mid ? -1 : 1;
            filt_mid = filt_best;
         }
      }

      cm.filter_level = filt_best;
      cm.frame_to_show = saved_frame;
   }
}
