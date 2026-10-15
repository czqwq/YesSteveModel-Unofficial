package rip.ysm.imagestream.webp.enc;

class ModeInfo {
   final MBModeInfo mbmi;
   final BModeInfo[] bmi = new BModeInfo[16];

   ModeInfo() {
      this.mbmi = new MBModeInfo();

      for (int i = 0; i < this.bmi.length; i++) {
         this.bmi[i] = new BModeInfo();
      }
   }

   static boolean hasSecondOrder(FullGenArrPointer<ModeInfo> mode_info_context) {
      return !MBPredictionMode.has_no_y_block.contains(mode_info_context.get().mbmi.mode);
   }
}
