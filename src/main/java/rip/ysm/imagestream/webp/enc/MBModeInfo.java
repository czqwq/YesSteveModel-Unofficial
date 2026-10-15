package rip.ysm.imagestream.webp.enc;

class MBModeInfo {
   MBPredictionMode mode = MBPredictionMode.DC_PRED;
   MBPredictionMode uv_mode = MBPredictionMode.DC_PRED;
   MVReferenceFrame ref_frame = MVReferenceFrame.INTRA_FRAME;
   boolean mb_skip_coeff;
   int segment_id;
}
