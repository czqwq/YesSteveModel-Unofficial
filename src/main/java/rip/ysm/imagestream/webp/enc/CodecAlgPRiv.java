package rip.ysm.imagestream.webp.enc;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.Iterator;
import java.util.List;
import rip.ysm.imagestream.internal.LogWriter;

class CodecAlgPRiv {
   final CodecPriv base = new CodecPriv();
   final CodecEncCfg cfg;
   final ExtraCFG vp8_cfg;
   final RationalLarge timestamp_ratio;
   int pts_offset;
   boolean pts_offset_initialized;
   final Config oxcf;
   final Compressor cpi;
   final FullGetSetPointer cx_data;
   final EnumSet<FrameFlags> next_frame_flag = EnumSet.noneOf(FrameFlags.class);
   List<CodecPkt> pkt_list = new ArrayList<>();
   int fixed_kf_cntr;
   final EnumSet<AlgoFlags> control_frame_flags = EnumSet.noneOf(AlgoFlags.class);

   CodecAlgPRiv(CodecEncCfg cfg, ExtraCFG vp8cfg) {
      this.cfg = cfg;
      this.vp8_cfg = vp8cfg;
      this.vp8_cfg.setPkt_list(this.pkt_list);
      this.cx_data = new FullGetSetPointer(Math.max(32768, cfg.getG_w() * cfg.getG_h() * 3 / 2 * 2));
      this.pts_offset_initialized = false;
      this.timestamp_ratio = RationalLarge.reduceLong(cfg.getG_timebase().num * 10000000L, cfg.getG_timebase().den);
      this.oxcf = new Config(cfg, this.vp8_cfg);
      this.cpi = new Compressor(this.oxcf);
      this.base.priv = this;
   }

   static CodecPkt vpx_codec_pkt_list_get(List<CodecPkt> list, Iterator<CodecPkt>[] iter) {
      CodecPkt pkt = null;
      if (iter[0] == null) {
         iter[0] = list.iterator();
      }

      if (iter[0].hasNext()) {
         pkt = iter[0].next();
      }

      return pkt;
   }

   static CodecPkt vpx_codec_get_cx_data(CodecPriv ctx, Iterator<CodecPkt>[] iter) {
      CodecPkt pkt = null;
      if (ctx != null) {
         if (iter == null) {
            LogWriter.writeLog("vp8 null error");
         } else {
            pkt = vpx_codec_pkt_list_get(ctx.priv.pkt_list, iter);
         }
      }

      if (pkt != null && pkt.kind == 0) {
         CodecPkt.FramePacket pktReal = (CodecPkt.FramePacket)pkt.packet;
         if (ctx.enc != null && ctx.enc.cx_data_dst_buf != null) {
            FullGetSetPointer dst_buf = ctx.enc.cx_data_dst_buf.shallowCopy();
            if (!pktReal.buf.equals(dst_buf) && pktReal.sz <= ctx.enc.cx_data_dst_buf.size()) {
               CodecPkt modified_pkt = ctx.enc.cx_data_pkt;
               dst_buf.memcopyin(0, ((CodecPkt.FramePacket)modified_pkt.packet).buf, 0, ((CodecPkt.FramePacket)modified_pkt.packet).buf.size());
               ((CodecPkt.FramePacket)pkt.packet).buf = dst_buf;
               pkt = pkt;
            }

            if (dst_buf == pktReal.buf) {
               ctx.enc.cx_data_dst_buf = dst_buf.shallowCopyWithPosInc(pktReal.buf.size());
            }
         }
      }

      return pkt;
   }
}
