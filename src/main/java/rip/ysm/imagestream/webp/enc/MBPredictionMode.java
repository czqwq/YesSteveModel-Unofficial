package rip.ysm.imagestream.webp.enc;

import java.util.EnumSet;

public enum MBPredictionMode {
   DC_PRED,
   V_PRED,
   H_PRED,
   TM_PRED,
   B_PRED,
   NEARESTMV,
   NEARMV,
   ZEROMV,
   NEWMV,
   SPLITMV;

   static final EnumSet<MBPredictionMode> has_no_y_block = EnumSet.of(B_PRED, SPLITMV);
   static final EnumSet<MBPredictionMode> validModes = EnumSet.range(DC_PRED, SPLITMV);
   static final EnumSet<MBPredictionMode> nonBlockPred = EnumSet.range(DC_PRED, TM_PRED);
   static final int count = validModes.size();
}
