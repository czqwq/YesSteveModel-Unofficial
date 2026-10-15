package rip.ysm.imagestream.webp.enc;

import java.util.HashMap;

class RDCosts {
   final FullGetSetPointer[] mvcosts = new FullGetSetPointer[2];
   final FullGetSetPointer[] mvsadcosts = new FullGetSetPointer[2];
   final HashMap<Integer, HashMap<Integer, Integer>> mbmode_cost = new HashMap<>();
   final int[][] intra_uv_mode_cost = new int[2][MBPredictionMode.count];
   final HashMap<Integer, HashMap<Integer, HashMap<Integer, Integer>>> bmode_costs = new HashMap<>();
   final HashMap<Integer, Integer> inter_bmode_costs = new HashMap<>();
   final int[][][][] token_costs = new int[4][8][3][TokenAlphabet.entropyTokenCount];
   private final HashMap<Integer, int[]> costArrayCache = new HashMap<>();
   private final int[] bpmHelper = new int[14];

   RDCosts() {
      initcostarr(this.mvcosts, 2048);
      initcostarr(this.mvsadcosts, 512);

      for (int ft : new int[]{0, 1}) {
         this.mbmode_cost.put(ft, new HashMap<>());
      }

      for (int bpm : W.validmodes) {
         HashMap<Integer, HashMap<Integer, Integer>> submap = new HashMap<>();
         int[] var6 = W.validmodes;
         int var7 = var6.length;

         for (int var8 = 0; var8 < var7; var8++) {
            Integer b2pm = var6[var8];
            submap.put(b2pm, new HashMap<>());
         }

         this.bmode_costs.put(bpm, submap);
      }
   }

   private static void initcostarr(FullGetSetPointer[] carr, int size) {
      carr[0] = new FullGetSetPointer(size);
      carr[1] = new FullGetSetPointer(size);
   }

   int[] getMBmodeCostAsArray(int t) {
      int[] ret = this.costArrayCache.computeIfAbsent(t, k -> new int[MBPredictionMode.count]);
      HashMap<Integer, Integer> data = this.mbmode_cost.get(t);

      for (MBPredictionMode mode : MBPredictionMode.validModes) {
         Integer v = data.get(mode);
         ret[mode.ordinal()] = v == null ? 0 : v;
      }

      return ret;
   }

   void setMBmodeCostAsArray(int t) {
      HashMap<Integer, Integer> data = this.mbmode_cost.get(t);
      int i = 0;
      int[] arr = this.costArrayCache.get(t);

      for (MBPredictionMode mode : MBPredictionMode.validModes) {
         data.put(mode.ordinal(), arr[i++]);
      }
   }

   private void bpmMaptoArray(HashMap<Integer, Integer> data) {
      int[] var2 = W.validmodes;
      int var3 = var2.length;

      for (int var4 = 0; var4 < var3; var4++) {
         Integer mode = var2[var4];
         Integer v = data.get(mode);
         this.bpmHelper[mode] = v == null ? 0 : v;
      }
   }

   private void arraytoBpmMap(HashMap<Integer, Integer> data) {
      int i = 0;
      int[] var3 = W.validmodes;
      int var4 = var3.length;

      for (int var5 = 0; var5 < var4; var5++) {
         Integer mode = var3[var5];
         data.put(mode, this.bpmHelper[i++]);
      }
   }

   void vp8_init_mode_costs(Compressor c) {
      GetPointer T = EntropyMode.vp8_bmode_tree;

      for (int bpO : W.bintramodes) {
         for (int bpI : W.bintramodes) {
            this.bpmMaptoArray(this.bmode_costs.get(bpO).get(bpI));
            TreeWriter.vp8_cost_tokens(this.bpmHelper, FullGetSetPointer.toPointer(W.keyFrameSubblockModeProb[bpO][bpI]), T);
            this.arraytoBpmMap(this.bmode_costs.get(bpO).get(bpI));
         }
      }

      this.bpmMaptoArray(this.inter_bmode_costs);
      TreeWriter.vp8_cost_tokens(this.bpmHelper, c.common.fc.bmode_prob, T);
      TreeWriter.vp8_cost_tokens(this.bpmHelper, c.common.fc.sub_mv_ref_prob, EntropyMode.vp8_sub_mv_ref_tree);
      this.arraytoBpmMap(this.inter_bmode_costs);
      int[] temp = this.getMBmodeCostAsArray(1);
      TreeWriter.vp8_cost_tokens(temp, c.common.fc.ymode_prob, EntropyMode.vp8_ymode_tree);
      this.setMBmodeCostAsArray(1);
      temp = this.getMBmodeCostAsArray(0);
      TreeWriter.vp8_cost_tokens(temp, EntropyMode.vp8_kf_ymode_prob, EntropyMode.vp8_kf_ymode_tree);
      this.setMBmodeCostAsArray(0);
      TreeWriter.vp8_cost_tokens(this.intra_uv_mode_cost[1], c.common.fc.uv_mode_prob, EntropyMode.vp8_uv_mode_tree);
      TreeWriter.vp8_cost_tokens(this.intra_uv_mode_cost[0], EntropyMode.vp8_kf_uv_mode_prob, EntropyMode.vp8_uv_mode_tree);
   }
}
