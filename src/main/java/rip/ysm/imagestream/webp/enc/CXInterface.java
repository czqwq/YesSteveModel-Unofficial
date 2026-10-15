package rip.ysm.imagestream.webp.enc;

import java.util.ArrayList;
import java.util.EnumSet;
import rip.ysm.imagestream.internal.LogWriter;

final class CXInterface {
   static final int VPX_DL_REALTIME = 1;

   private CXInterface() {
   }

   static void validate_img(CodecAlgPRiv ctx, Picture img) {
      if (img.getWidth() != ctx.cfg.getG_w() || img.getHeight() != ctx.cfg.getG_h()) {
         LogWriter.writeLog("image error");
      }
   }

   static void pick_quickcompress_mode(CodecAlgPRiv ctx, long duration, long deadline) {
      CompressMode new_qc = CompressMode.BESTQUALITY;
      if (deadline != 0L) {
         long duration_us = duration * ctx.timestamp_ratio.getNum() / (ctx.timestamp_ratio.getDen() * 10L);
         new_qc = deadline > duration_us ? CompressMode.GOODQUALITY : CompressMode.REALTIME;
      }

      if (deadline == 1L) {
         new_qc = CompressMode.REALTIME;
      }

      if (ctx.oxcf.Mode != new_qc) {
         ctx.oxcf.Mode = new_qc;
         ctx.cpi.vp8_change_config(ctx.oxcf);
      }
   }

   static void set_reference_and_update(CodecAlgPRiv ctx, EnumSet<AlgoFlags> flags) {
      if (flags.contains(AlgoFlags.NO_UPD_GF) && flags.contains(AlgoFlags.FORCE_GF)
         || flags.contains(AlgoFlags.NO_UPD_ARF) && flags.contains(AlgoFlags.FORCE_ARF)) {
         LogWriter.writeLog("flags error");
      }

      boolean update = false;
      EnumSet<MVReferenceFrame> referenceFrames = EnumSet.allOf(MVReferenceFrame.class);
      if (flags.contains(AlgoFlags.NO_REF_LAST)) {
         update = true;
         referenceFrames.remove(MVReferenceFrame.LAST_FRAME);
      }

      if (flags.contains(AlgoFlags.NO_REF_GF)) {
         update = true;
         referenceFrames.remove(MVReferenceFrame.GOLDEN_FRAME);
      }

      if (flags.contains(AlgoFlags.NO_REF_ARF)) {
         update = true;
         referenceFrames.remove(MVReferenceFrame.ALTREF_FRAME);
      }

      if (update) {
         OnyxIf.vp8_use_as_reference(ctx.cpi, referenceFrames);
      }

      update = false;
      referenceFrames = EnumSet.allOf(MVReferenceFrame.class);
      if (flags.contains(AlgoFlags.NO_UPD_LAST)) {
         update = true;
         referenceFrames.remove(MVReferenceFrame.LAST_FRAME);
      }

      if (flags.contains(AlgoFlags.NO_UPD_GF)) {
         update = true;
         referenceFrames.remove(MVReferenceFrame.GOLDEN_FRAME);
      }

      if (flags.contains(AlgoFlags.NO_UPD_ARF)) {
         update = true;
         referenceFrames.remove(MVReferenceFrame.ALTREF_FRAME);
      }

      if (update) {
         OnyxIf.vp8_update_reference(ctx.cpi, referenceFrames);
      }

      if (flags.contains(AlgoFlags.NO_UPD_ENTROPY)) {
         OnyxIf.vp8_update_entropy(ctx.cpi);
      }
   }

   static void vp8e_encode(CodecAlgPRiv ctx, Picture img, long pts_val, EnumSet<AlgoFlags> flags) {
      if (ctx.cfg.getRc_target_bitrate() != 0) {
         if (img != null) {
            validate_img(ctx, img);
         }

         if (!ctx.pts_offset_initialized) {
            ctx.pts_offset = (int)pts_val;
            ctx.pts_offset_initialized = true;
         }

         pts_val -= ctx.pts_offset;
         pick_quickcompress_mode(ctx, 1L, 1L);
         ctx.pkt_list = new ArrayList<>();
         if (flags.isEmpty()) {
            flags = EnumSet.copyOf(ctx.control_frame_flags);
         }

         ctx.control_frame_flags.clear();
         set_reference_and_update(ctx, flags);
         if (ctx.cfg.getKf_mode() == CodecEncCfg.vpx_kf_mode.VPX_KF_AUTO
            && ctx.cfg.getKf_min_dist() == ctx.cfg.getKf_max_dist()
            && ++ctx.fixed_kf_cntr > ctx.cfg.getKf_min_dist()) {
            flags.add(AlgoFlags.FORCE_KF);
            ctx.fixed_kf_cntr = 1;
         }

         if (ctx.cpi != null) {
            OnyxIf.TimeStampRange dst_ts = new OnyxIf.TimeStampRange();
            ctx.cpi.b_calculate_psnr = ctx.base.init_flags.contains(InitFlags.USE_PSNR);
            ctx.cpi.output_partition = ctx.base.init_flags.contains(InitFlags.USE_OUTPUT_PARTITION);
            EnumSet<FrameFlags> lib_flags = flags.contains(AlgoFlags.FORCE_KF) ? EnumSet.of(FrameFlags.Key) : EnumSet.noneOf(FrameFlags.class);
            double tsratio = ctx.timestamp_ratio.scalar();
            dst_ts.time_stamp = (long)(pts_val * tsratio);
            dst_ts.time_end = (long)((pts_val + 1L) * tsratio);
            if (img != null) {
               YV12buffer sd = new YV12buffer(img);
               EnumSet<FrameFlags> passedFlags = EnumSet.copyOf(ctx.next_frame_flag);
               passedFlags.addAll(lib_flags);
               OnyxIf.vp8_receive_raw_frame(ctx.cpi, passedFlags, sd, dst_ts.time_stamp, dst_ts.time_end);
               ctx.next_frame_flag.clear();
            }

            FullGetSetPointer cx_data = ctx.cx_data.shallowCopy();
            int cx_data_sz = ctx.cx_data.size() - 1;
            lib_flags.clear();

            while (cx_data_sz >= ctx.cx_data.size() / 2) {
               int size = OnyxIf.vp8_get_compressed_data(ctx.cpi, lib_flags, cx_data, dst_ts, img == null);
               if (size == -1) {
                  break;
               }

               if (size != 0) {
                  CodecPkt pkt = new CodecPkt();
                  Compressor cpi = ctx.cpi;
                  long round = ctx.timestamp_ratio.getNum() / 2L;
                  if (round > 0L) {
                     round--;
                  }

                  pkt.kind = 0;
                  CodecPkt.FramePacket fp = new CodecPkt.FramePacket();
                  pkt.packet = fp;
                  fp.flags = EnumSet.noneOf(GeneralFrameFlags.class);
                  if (lib_flags.contains(FrameFlags.Key)) {
                     fp.flags.add(GeneralFrameFlags.FRAME_IS_KEY);
                  }

                  if (!cpi.common.show_frame) {
                     fp.flags.add(GeneralFrameFlags.FRAME_IS_INVISIBLE);
                  }

                  if (cpi.droppable) {
                     fp.flags.add(GeneralFrameFlags.FRAME_IS_DROPPABLE);
                  }

                  if (cpi.output_partition) {
                     int num_partitions = (1 << cpi.common.multi_token_partition) + 1;
                     fp.flags.add(GeneralFrameFlags.FRAME_IS_FRAGMENT);

                     for (int i = 0; i < num_partitions; i++) {
                        fp.buf = cx_data.shallowCopy();
                        cx_data.incBy(cpi.partition_sz[i]);
                        cx_data_sz -= cpi.partition_sz[i];
                        fp.sz = cpi.partition_sz[i];
                        if (i == num_partitions - 1) {
                           fp.flags.remove(GeneralFrameFlags.FRAME_IS_FRAGMENT);
                        }

                        ctx.pkt_list.add(pkt);
                     }
                  } else {
                     fp.buf = cx_data.shallowCopy();
                     fp.sz = size;
                     ctx.pkt_list.add(pkt);
                     cx_data.incBy(size);
                     cx_data_sz -= size;
                  }
               }
            }
         }
      }
   }
}
