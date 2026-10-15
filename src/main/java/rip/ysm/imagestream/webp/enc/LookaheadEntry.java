package rip.ysm.imagestream.webp.enc;

import java.util.EnumSet;

class LookaheadEntry {
   YV12buffer img;
   long ts_start;
   long ts_end;
   EnumSet<FrameFlags> flags;
}
