package rip.ysm.imagestream.webp.enc;

final class TreeWriter {
   private TreeWriter() {
   }

   static int vp8_cost_zero(int p) {
      return BitEncoder.vp8_prob_cost[p];
   }

   static int vp8_cost_one(int p) {
      return vp8_cost_zero(vp8_complement(p));
   }

   static int vp8_cost_bit(int x, int b) {
      return vp8_cost_zero(b != 0 ? vp8_complement(x) : x);
   }

   static int vp8_cost_branch(int[] ct, int p) {
      return (int)((long)ct[0] * vp8_cost_zero(p) + (long)ct[1] * vp8_cost_one(p) >> 8);
   }

   static void vp8_write_literal(BitEncoder bc, int data, int bits) {
      bc.vp8_encode_value(data, bits);
   }

   static void vp8_treed_write(BitEncoder w, GetPointer t, GetPointer p, int v, int n) {
      int i = 0;

      do {
         int b = v >> --n & 1;
         w.vp8_encode_bool(b == 1, p.getRel(i >> 1));
         i = t.getRel(i + b);
      } while (n != 0);
   }

   static void vp8_write_token(BitEncoder w, GetPointer t, GetPointer p, Token x) {
      vp8_treed_write(w, t, p, x.value, x.len);
   }

   static int vp8_treed_cost(GetPointer t, GetPointer p, int v, int n) {
      int c = 0;
      int i = 0;

      do {
         int b = v >> --n & 1;
         c += vp8_cost_bit(p.getRel(i >> 1), b);
         i = t.getRel(i + b);
      } while (n != 0);

      return c;
   }

   static int vp8_treed_cost_short(short[] t, short[] p, int v, int n) {
      int c = 0;
      int i = 0;

      do {
         int b = v >> --n & 1;
         c += vp8_cost_bit(p[i >> 1], b);
         i = t[i + b];
      } while (n != 0);

      return c;
   }

   private static void cost(int[] C, GetPointer T, GetPointer P, int i, int c) {
      int p = P.getRel(i >> 1);

      do {
         int j = T.getRel(i);
         int d = c + vp8_cost_bit(p, i & 1);
         if (j <= 0) {
            C[-j] = d;
         } else {
            cost(C, T, P, j, d);
         }
      } while ((++i & 1) != 0);
   }

   static void vp8_cost_tokens(int[] c, GetPointer p, GetPointer t) {
      cost(c, t, p, 0, 0);
   }

   static void vp8_cost_tokens2(int[] c, GetPointer p) {
      cost(c, Entropy.vp8_coef_tree, p, 2, 0);
   }

   private static int vp8_complement(int p) {
      return 255 - p;
   }
}
