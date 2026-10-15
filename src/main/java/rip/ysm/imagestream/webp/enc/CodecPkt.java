package rip.ysm.imagestream.webp.enc;

import java.util.EnumSet;

class CodecPkt {
   APacket packet;
   int kind;

   interface APacket {
   }

   static class FramePacket implements APacket {
      FullGetSetPointer buf;
      int sz;
      EnumSet<GeneralFrameFlags> flags;
   }

   static class PSNRPacket implements APacket {
      final int[] samples = new int[4];
      final long[] sse = new long[4];
      final double[] psnr = new double[4];
   }
}
