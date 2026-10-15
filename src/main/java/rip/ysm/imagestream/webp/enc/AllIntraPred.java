package rip.ysm.imagestream.webp.enc;

final class AllIntraPred {
   static final int SIZE_16 = 0;
   static final int SIZE_8 = 1;
   static final int NUM_SIZES = 2;
   static final IntraPredFN[][] pred = new IntraPredFN[4][2];
   static final IntraPredFN[][][] dc_pred = new IntraPredFN[2][2][2];
   static final IntraPredFN[] bpred = new IntraPredFN[14];

   private AllIntraPred() {
   }

   static {
      pred[MBPredictionMode.V_PRED.ordinal()][0] = new VPredictor(16);
      pred[MBPredictionMode.H_PRED.ordinal()][0] = new HPredictor(16);
      pred[MBPredictionMode.TM_PRED.ordinal()][0] = new TMPredictor(16);
      pred[MBPredictionMode.V_PRED.ordinal()][1] = new VPredictor(8);
      pred[MBPredictionMode.H_PRED.ordinal()][1] = new HPredictor(8);
      pred[MBPredictionMode.TM_PRED.ordinal()][1] = new TMPredictor(8);
      dc_pred[0][0][0] = new DC128Predictor(16);
      dc_pred[0][1][0] = new DCTopPredictor(16);
      dc_pred[1][0][0] = new DCLeftPredictor(16);
      dc_pred[1][1][0] = new DCPredictor(16);
      dc_pred[0][0][1] = new DC128Predictor(8);
      dc_pred[0][1][1] = new DCTopPredictor(8);
      dc_pred[1][0][1] = new DCLeftPredictor(8);
      dc_pred[1][1][1] = new DCPredictor(8);
      bpred[0] = new DCPredictor(4);
      bpred[1] = new TMPredictor(4);
      bpred[2] = new VEPredictor4x4();
      bpred[3] = new HEPredictor4x4();
      bpred[4] = new D45EPredictor4x4();
      bpred[5] = new D135Predictor4x4();
      bpred[6] = new D117Predictor4x4();
      bpred[7] = new D63EPredictor4x4();
      bpred[8] = new D153Predictor4x4();
      bpred[9] = new D207Predictor4x4();
   }
}
