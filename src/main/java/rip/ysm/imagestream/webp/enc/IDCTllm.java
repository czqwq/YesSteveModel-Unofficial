package rip.ysm.imagestream.webp.enc;

final class IDCTllm {
   private static final int cospi8sqrt2minus1 = 20091;
   private static final int sinpi8sqrt2 = 35468;

   private IDCTllm() {
   }

   static void vp8_short_idct4x4llm(GetSetPointer ip, GetSetPointer pred_ptr, int pred_stride, FullGetSetPointer dst_ptr, int dst_stride) {
      FullGetSetPointer op = vp8_short_idct4x4NoAdd(ip);

      for (int r = 0; r < 4; r++) {
         int basePred = r * pred_stride;
         int baseDst = r * dst_stride;

         for (int c = 0; c < 4; c++) {
            dst_ptr.setRel(baseDst + c, CUtils.clipPixel((short)(op.getRel(c) + pred_ptr.getRel(basePred + c))));
         }

         op.incBy(4);
      }
   }

   static FullGetSetPointer vp8_short_idct4x4NoAdd(GetSetPointer ip) {
      int inPos = ip.getPos();
      FullGetSetPointer op = new FullGetSetPointer(16);
      int shortpitch = 4;

      for (int i = 0; i < 4; i++) {
         int a1 = ip.get() + ip.getRel(8);
         int b1 = ip.get() - ip.getRel(8);
         int temp1 = ip.getRel(4) * '誌' >> 16;
         int temp2 = ip.getRel(12) + (ip.getRel(12) * 20091 >> 16);
         int c1 = temp1 - temp2;
         temp1 = ip.getRel(4) + (ip.getRel(4) * 20091 >> 16);
         temp2 = ip.getRel(12) * '誌' >> 16;
         int d1 = temp1 + temp2;
         op.set((short)(a1 + d1));
         op.setRel(12, (short)(a1 - d1));
         op.setRel(4, (short)(b1 + c1));
         op.setRel(8, (short)(b1 - c1));
         ip.inc();
         op.inc();
      }

      ip.setPos(inPos);
      op.rewind();

      for (int i = 0; i < 4; i++) {
         int a1 = op.get() + op.getRel(2);
         int b1 = op.get() - op.getRel(2);
         int temp1 = op.getRel(1) * '誌' >> 16;
         int temp2 = op.getRel(3) + (op.getRel(3) * 20091 >> 16);
         int c1 = temp1 - temp2;
         temp1 = op.getRel(1) + (op.getRel(1) * 20091 >> 16);
         temp2 = op.getRel(3) * '誌' >> 16;
         int d1 = temp1 + temp2;
         op.set((short)(a1 + d1 + 4 >> 3));
         op.setRel(3, (short)(a1 - d1 + 4 >> 3));
         op.setRel(1, (short)(b1 + c1 + 4 >> 3));
         op.setRel(2, (short)(b1 - c1 + 4 >> 3));
         op.incBy(4);
      }

      op.rewind();
      return op;
   }

   static void vp8_dc_only_idct_add(int input_dc, GetSetPointer pred_ptr, int pred_stride, FullGetSetPointer dst_ptr, int dst_stride) {
      int a1 = input_dc + 4 >> 3;

      for (int r = 0; r < 4; r++) {
         int dstBase = r * dst_stride;
         int predBase = r * pred_stride;

         for (int c = 0; c < 4; c++) {
            dst_ptr.setRel(dstBase + c, CUtils.clipPixel((short)(a1 + pred_ptr.getRel(predBase + c))));
         }
      }
   }

   static void vp8_short_inv_walsh4x4(GetSetPointer ip, FullGetSetPointer mb_dqcoeff) {
      int inPos = ip.getPos();
      FullGetSetPointer op = new FullGetSetPointer(16);

      for (int i = 0; i < 4; i++) {
         int a1 = ip.get() + ip.getRel(12);
         int b1 = ip.getRel(4) + ip.getRel(8);
         int c1 = ip.getRel(4) - ip.getRel(8);
         int d1 = ip.get() - ip.getRel(12);
         op.set((short)(a1 + b1));
         op.setRel(4, (short)(c1 + d1));
         op.setRel(8, (short)(a1 - b1));
         op.setRel(12, (short)(d1 - c1));
         ip.inc();
         op.inc();
      }

      op.rewind();
      ip.setPos(inPos);

      for (int var13 = 0; var13 < 4; var13++) {
         int a1 = op.get() + op.getRel(3);
         int b1 = op.getRel(1) + op.getRel(2);
         int c1 = op.getRel(1) - op.getRel(2);
         int d1 = op.get() - op.getRel(3);
         int a2 = a1 + b1;
         int b2 = c1 + d1;
         int c2 = a1 - b1;
         int d2 = d1 - c1;
         op.setAndInc((short)(a2 + 3 >> 3));
         op.setAndInc((short)(b2 + 3 >> 3));
         op.setAndInc((short)(c2 + 3 >> 3));
         op.setAndInc((short)(d2 + 3 >> 3));
      }

      op.rewind();

      for (int var14 = 0; var14 < 16; var14++) {
         mb_dqcoeff.setRel(var14 << 4, op.getRel(var14));
      }
   }

   static void vp8_short_inv_walsh4x4_1(GetPointer input, FullGetSetPointer mb_dqcoeff) {
      int a1 = input.get() + 3 >> 3;

      for (int i = 0; i < 16; i++) {
         mb_dqcoeff.setRel(i << 4, (short)a1);
      }
   }
}
