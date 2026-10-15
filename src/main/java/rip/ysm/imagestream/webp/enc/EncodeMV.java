package rip.ysm.imagestream.webp.enc;

final class EncodeMV {
   private EncodeMV() {
   }

   static int cost_mvcomponent(int v, MVContext mvc) {
      GetSetPointer p = mvc.prob.positionableOnly();
      int cost;
      if (v < 8) {
         cost = TreeWriter.vp8_cost_zero(p.getRel(0));
         p.incBy(2);
         cost += TreeWriter.vp8_treed_cost(EntropyMode.vp8_small_mvtree, p, v, 3);
         if (v == 0) {
            return cost;
         }
      } else {
         int i = 0;
         cost = TreeWriter.vp8_cost_one(p.getRel(0));
         p.incBy(9);

         do {
            cost += TreeWriter.vp8_cost_bit(p.getRel(i), v >> i & 1);
         } while (++i < 3);

         i = 9;

         do {
            cost += TreeWriter.vp8_cost_bit(p.getRel(i), v >> i & 1);
         } while (--i > 3);

         if ((v & 65520) != 0) {
            cost += TreeWriter.vp8_cost_bit(p.getRel(3), v >> 3 & 1);
         }
      }

      return cost;
   }

   static void vp8_build_component_cost_table(FullGetSetPointer[] mvcost, MVContext[] mvc, boolean[] mvc_flag) {
      int i = 1;
      if (mvc_flag[0]) {
         mvcost[0].set((short)cost_mvcomponent(0, mvc[0]));

         do {
            int cost0 = cost_mvcomponent(i, mvc[0]);
            mvcost[0].setRel(i, (short)(cost0 + TreeWriter.vp8_cost_zero(mvc[0].prob.getRel(1))));
            mvcost[0].setRel(-i, (short)(cost0 + TreeWriter.vp8_cost_one(mvc[0].prob.getRel(1))));
         } while (++i <= 1023);
      }

      i = 1;
      if (mvc_flag[1]) {
         mvcost[1].set((short)cost_mvcomponent(0, mvc[1]));

         do {
            int cost1 = cost_mvcomponent(i, mvc[1]);
            mvcost[1].setRel(i, (short)(cost1 + TreeWriter.vp8_cost_zero(mvc[1].prob.getRel(1))));
            mvcost[1].setRel(-i, (short)(cost1 + TreeWriter.vp8_cost_one(mvc[1].prob.getRel(1))));
         } while (++i <= 1023);
      }
   }
}
