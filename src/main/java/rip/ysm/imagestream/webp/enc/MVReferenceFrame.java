package rip.ysm.imagestream.webp.enc;

import java.util.EnumSet;

enum MVReferenceFrame {
   INTRA_FRAME,
   LAST_FRAME,
   GOLDEN_FRAME,
   ALTREF_FRAME;

   static final EnumSet<MVReferenceFrame> validFrames = EnumSet.allOf(MVReferenceFrame.class);
   static final EnumSet<MVReferenceFrame> interFrames = EnumSet.of(LAST_FRAME, GOLDEN_FRAME, ALTREF_FRAME);
   static final int count = validFrames.size();
}
