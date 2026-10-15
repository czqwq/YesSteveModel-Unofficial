package rip.ysm.imagestream.webp.enc;

import java.util.EnumSet;

final class DCTValueConstants {
   private static final int[] dct_value_cost = new int[4096];
   private static final TokenValue[] dct_value_tokens = new TokenValue[4096];

   private DCTValueConstants() {
   }

   static int getValueCost(int v) {
      return dct_value_cost[2048 + v];
   }

   static TokenValue getTokenValue(int v) {
      return dct_value_tokens[2048 + v];
   }

   static {
      int i = -2048;
      int sign = 1;

      do {
         if (i == 0) {
            sign = 0;
         }

         TokenAlphabet selected = null;
         int eb = sign;
         int a = sign != 0 ? -i : i;
         if (a <= 4) {
            for (TokenAlphabet ta : EnumSet.range(TokenAlphabet.ZERO_TOKEN, TokenAlphabet.FOUR_TOKEN)) {
               if (a == 0) {
                  selected = ta;
               }

               a--;
            }
         } else {
            for (TokenAlphabet ta : EnumSet.range(TokenAlphabet.DCT_VAL_CATEGORY1, TokenAlphabet.DCT_VAL_CATEGORY6)) {
               if (ta.base_val > a) {
                  break;
               }

               selected = ta;
            }

            if (selected != null) {
               eb = sign | a - selected.base_val << 1;
            }
         }

         dct_value_tokens[2048 + i] = new TokenValue(selected, eb);
         a = 0;
         if (selected != null && selected.base_val != 0) {
            if (selected.len != 0) {
               a += TreeWriter.vp8_treed_cost_short(selected.tree, selected.prob, eb >> 1, selected.len);
            }

            a += TreeWriter.vp8_cost_bit(128, eb & 1);
            dct_value_cost[2048 + i] = a;
         }
      } while (++i < 2048);
   }
}
